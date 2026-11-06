package app.tenshu.fit.data

import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import org.tenshu.core.common.util.getPlatformDeviceName
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.getOrNull
import org.tenshu.core.core.TContext
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.recordImportRun
import org.tenshu.core.health.registerHealthSource
import org.tenshu.core.health.setSourceAutoImport
import org.tenshu.health.client.healthService

/**
 * Writes the Data sources registry (`docs/proposals/fit-data-sources-restructure.md`) — the
 * durable half of what an import did. `ImportStatus` is process-scoped live, in-flight progress;
 * this keeps the facts, across restarts and devices.
 *
 * ⚠ **Every method is best-effort and never throws.** Bookkeeping about an import must not be
 * able to fail the import it is describing: a user who imported 4 000 workouts and lost them to
 * a failed audit-log write would be worse off than one with no audit log.
 */
internal class HealthImportRunRecorder(
    private val client: TenshuClient,
    private val context: TContext,
    private val resolveDeviceId: suspend () -> String?,
    private val deviceLabel: () -> String = { getPlatformDeviceName() },
    /**
     * ADR 0025 §8.6 write boundary: runs before [registerSource]'s and [record]'s write and throws
     * to refuse it. ⚠ Called OUTSIDE their `runCatchingCancellable`, so a refusal reaches the
     * pipeline instead of being logged away as a failed write. ⚠ Required, never defaulted.
     */
    private val beforeEachWrite: suspend () -> Unit,
) {

    /**
     * Declares that this device can import from [kind]. Idempotent, and the op **preserves** an
     * existing row's `autoImport` and run figures — this runs on every launch where the importer
     * reports itself available, so a re-register that reset the flag would silently re-enable a
     * source the user had turned off.
     */
    suspend fun registerSource(kind: String) {
        val deviceId = deviceId() ?: return
        beforeEachWrite()
        runCatchingCancellable {
            client.healthService.registerHealthSource(
                kind = kind,
                deviceId = deviceId,
                deviceLabel = deviceLabel(),
            )
        }.onFailure { fitLog("importRun: registerSource($kind) failed", it) }
    }

    /** Whether this device may import from [kind] automatically. Absent row ⇒ yes (the default). */
    suspend fun autoImportEnabled(kind: String): Boolean {
        val deviceId = deviceId() ?: return true
        val key = HealthSource.keyOf(kind, deviceId)
        // A full read, not a projection: the registry holds one row per platform store per
        // device — three rows on a heavily-used realm — so a `select` would buy nothing and
        // cost the Entity-vs-composite unwrapping at the call site.
        return runCatchingCancellable {
            client.healthService.sources.getAll()
                .getOrNull()
                ?.toList()
                ?.firstOrNull { it.key.get() == key }
                ?.autoImport?.get()
                ?: true
        }.getOrElse {
            // ⚠ Fail OPEN, not closed. A read failure must not silently stop importing —
            // the user would see a Fit that quietly stopped updating and no reason why.
            fitLog("importRun: autoImportEnabled($kind) read failed — assuming enabled", it)
            true
        }
    }

    suspend fun setAutoImport(kind: String, deviceId: String, enabled: Boolean) {
        runCatchingCancellable {
            client.healthService.setSourceAutoImport(kind, deviceId, enabled)
        }.onFailure { fitLog("importRun: setAutoImport($kind) failed", it) }
    }

    /**
     * Records one finished import, and folds its figures onto the owning [HealthSource].
     *
     * ⚠⚠ **The no-op rule (D7).** `HealthImportBootstrap` fires on *every foreground*, so most
     * calls here describe a refresh that found nothing. Writing a row for each would turn a
     * `Syncable(Eager)` relation into a heartbeat broadcast. A run is recorded
     * only when it is *information*:
     *
     * | case | recorded | why |
     * |---|---|---|
     * | imported ≥ 1 item | ✅ | it did something |
     * | failed / permissions missing | ✅ | a failure is information |
     * | window ≠ RECENT and found nothing | ✅ | the user deliberately asked — "we looked, and there was nothing" is an answer |
     * | window = RECENT and found nothing | ❌ | the automatic case, and the only silent one |
     *
     * `RECENT` is the only window an automatic pass ever uses; `FULL_HISTORY` and `FILE` are
     * always a deliberate act, which is what makes this rule a one-liner rather than a
     * `userInitiated` flag threaded through five layers.
     */
    suspend fun record(
        kind: String,
        window: String,
        startedAt: Long,
        finishedAt: Long,
        status: String,
        itemCount: Long = 0L,
        workoutCount: Long = 0L,
        byteCount: Long? = null,
        fileName: String? = null,
        error: String? = null,
    ) {
        if (!worthRecording(window, status, itemCount)) {
            fitLog("importRun: nothing to record — an empty $window refresh of $kind")
            return
        }
        val deviceId = deviceId() ?: return
        beforeEachWrite()
        runCatchingCancellable {
            client.healthService.recordImportRun(
                HealthImportRun.create(
                    context = context,
                    deviceId = deviceId,
                    kind = kind,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    window = window,
                    status = status,
                    itemCount = itemCount,
                    workoutCount = workoutCount,
                    byteCount = byteCount,
                    fileName = fileName,
                    error = error,
                )
            )
        }.onFailure { fitLog("importRun: record($kind/$status) failed", it) }
    }

    /**
     * ⚠ Never substitutes a default. A blank device id under a borrowed key would write this
     * device's state into *another* device's row — and the row syncs, so the damage lands
     * everywhere. No id means identity is still coming up; skip this write and catch the next.
     */
    private suspend fun deviceId(): String? =
        runCatchingCancellable { resolveDeviceId() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: run {
                fitLog("importRun: no device id yet — skipping the write rather than guessing")
                null
            }

    internal companion object {

        /**
         * ⚠ `STATUS_NO_DATA` on a `RECENT` window is the **only** silent case, and it has to be
         * matched exactly — "anything that is not SUCCEEDED is worth recording" reads plausibly
         * and gets it backwards, because NO_DATA on RECENT *is* the empty automatic refresh
         * this rule exists to suppress.
         *
         * `PERMISSIONS_MISSING` is always recorded even on RECENT: an automatic pass cannot
         * produce it (Android's `autoRefreshReady` is `hasAllPermissions()`, and HealthKit never
         * reports read denial at all), so in practice it only ever describes something the user
         * asked for — and the retention cap bounds it even if that stops being true.
         */
        fun worthRecording(window: String, status: String, itemCount: Long): Boolean =
            itemCount > 0L ||
                window != HealthImportRun.WINDOW_RECENT ||
                status == HealthImportRun.STATUS_FAILED ||
                status == HealthImportRun.STATUS_PERMISSIONS_MISSING

        /** The registry's kind for a platform importer, or `null` where there is no connection. */
        fun kindOf(source: ImportSource): String? = when (source) {
            ImportSource.HealthConnect -> HealthSource.KIND_HEALTH_CONNECT
            ImportSource.AppleHealth -> HealthSource.KIND_APPLE_HEALTH
            // ⚠ Not connections (D11): a file import is an act, and StartFresh imports nothing.
            ImportSource.ImportFile, ImportSource.StartFresh -> null
        }

        fun windowOf(window: ImportWindow): String = when (window) {
            ImportWindow.Recent -> HealthImportRun.WINDOW_RECENT
            ImportWindow.FullHistory -> HealthImportRun.WINDOW_FULL_HISTORY
        }
    }
}

/**
 * A **typed code**, never a message — redaction by construction (A19).
 *
 * ⚠⚠ `Throwable.message` is the natural thing to store and the wrong one: on this path it
 * routinely carries the picked archive's filename or a content-provider URI, and
 * `HealthImportRun` is a **synced** row. The class name says as much as a support answer needs
 * ("it was an IO failure, not a permission one") and cannot leak a path.
 */
internal fun Throwable.errorCode(): String = this::class.simpleName ?: "Unknown"
