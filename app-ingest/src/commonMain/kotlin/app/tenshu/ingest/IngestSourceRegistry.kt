package app.tenshu.ingest

import kotlinx.coroutines.CancellationException
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.setSourceEnabled
import org.tenshu.ingest.client.ingestService
import kotlinx.coroutines.flow.first

/**
 * One thing this device is ingesting — a camera bucket, a watched folder — as an app shows it.
 *
 * ⚠ A projection of an `IngestSource` row, not the row: the registry is a **synced, device-scoped**
 * record written by whichever app owns the drain (Drive today), and a reader should not have to
 * know that to render a list.
 */
data class IngestSourceInfo(
    val sourceId: String,
    val appId: String,
    val displayName: String,
    val kind: String,
    val enabled: Boolean,
    val status: String,
    val pendingCount: Long,
    val lastRunAtMs: Long,
    val lastError: String,
) {
    val isGallery: Boolean get() = kind == IngestSource.KIND_GALLERY
}

/**
 * Read, and switch off, what this device backs up (`photos-app.md` PH-4, PH-D3).
 *
 * ## ⚠⚠ What the switch actually does, and what it does not
 *
 * `IngestSource.enabled` is **Control's central kill switch**, read by the drain before every
 * source's pass (`SyncedFolderController`: *"honour Control's central kill switch for this source
 * before the shared gate"*). So turning a source off here really does stop it, in whichever app
 * owns the drain — this is not a cosmetic toggle over someone else's setting.
 *
 * ⚠ It is **not** the master switch. The owning app's own toggle remains the primary gate and the
 * registry read is deliberately **fail-open** (an absent row, or a read error, means enabled), so
 * switching a source *on* here cannot force backups to run while Drive's own backup switch is off.
 * Any UI over this must say so rather than implying symmetry — an "on" that silently does nothing
 * is the failure this whole doc keeps circling.
 *
 * ⚠ A source appears here only once the drain has **registered** it, and `SyncedFolderController`
 * is the only caller of [IngestRegistry.registerSource]. An app that does not own a drain (Photos)
 * can manage sources but cannot create one — PH-4b gave Photos a folder *picker* over the same
 * device-scoped `BackupPreferences` rows Drive's drain enumerates (`photos-app.md` §19.4), not a
 * drain of its own. So the honest empty state here is still "nothing is being backed up from this
 * device yet", not a picker that writes a row nothing reads.
 */
interface IngestSources {

    /** Every source registered for **this device**, or `null` when the read failed. */
    suspend fun sources(): List<IngestSourceInfo>?

    /** Flips the kill switch for one source on this device. `false` when the write did not land. */
    suspend fun setEnabled(sourceId: String, enabled: Boolean): Boolean
}

/**
 * The real [IngestSources], over Control's `:ingest-service`.
 *
 * ⚠ Consumers depend on the interface, not on this: a screen's tests must not need a proxy to
 * prove that "could not read" renders differently from "nothing is backed up", which is the
 * distinction most worth testing here.
 */
class IngestSourceRegistry(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : IngestSources {

    /**
     * Every source registered for **this device**, name-ordered.
     *
     * ⚠ Filtered by device id rather than showing the realm's whole registry: the rows sync, so a
     * phone's camera bucket is visible on the desktop — and listing it there under a "what this
     * device backs up" heading would be a lie. The cross-device view is Control's job.
     */
    override suspend fun sources(): List<IngestSourceInfo>? = try {
        val deviceId = identity.awaitOrNull() ?: return null
        val rows = client.ingestService.sources.flow().first().getOrNull() ?: return null
        rows.toList()
            .filterIsInstance<IngestSource>()
            .filter { it.deviceId.get() == deviceId }
            .map { row ->
                IngestSourceInfo(
                    sourceId = row.sourceId.get(),
                    appId = row.appId.get(),
                    displayName = row.displayName.get(),
                    kind = row.sourceKind.get(),
                    enabled = row.enabled.get(),
                    status = row.lastStatus?.get().orEmpty(),
                    pendingCount = row.pendingCount?.get() ?: 0L,
                    lastRunAtMs = row.lastRunAt?.get() ?: 0L,
                    lastError = row.lastError?.get().orEmpty(),
                )
            }
            .sortedBy { it.displayName.lowercase() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // ⚠ `null`, not an empty list: "could not read" and "nothing is being backed up" are
        // different answers, and only one of them should reach a user as an empty screen.
        null
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean): Boolean = try {
        val deviceId = identity.awaitOrNull() ?: return false
        client.ingestService.setSourceEnabled(sourceId, deviceId, enabled) is TResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
