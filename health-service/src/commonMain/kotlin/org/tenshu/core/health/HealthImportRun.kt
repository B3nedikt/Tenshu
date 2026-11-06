package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * **One import that did something** — the durable record the Data sources surface reads
 * ("165 items · 3 Aug", "Last import: 01.08.2026"), and the history list behind each source
 * (`docs/proposals/fit-data-sources-restructure.md` §2.2).
 *
 * The durable counterpart of `ImportStatus`, which is a process-scoped `MutableStateFlow` in
 * `FitProjectionRepository` and so dies on restart (§0).
 *
 * ⚠⚠ **Only a run that did something is recorded (D7).** `HealthImportBootstrap` fires on every
 * foreground with a 60 s debounce; a row per foreground would turn a synced type into a heartbeat
 * log broadcast to every device. A refresh that imported nothing writes nothing. **Failures are
 * recorded** — a failure is information, and it is what the detail screen explains.
 *
 * ⚠ **Runs are capped per (kind, device) and only the originating device trims (D8)** —
 * `FileModification`'s `RETENTION_CAP` shape, where the trim runs only for
 * `origin == WriteOrigin.USER` so peers converge via the propagated tombstone instead of each
 * re-trimming on ingest and doubling tombstone traffic.
 *
 * Keyed by [key] (`"$deviceId:$startedAt"`) via [HealthImportRunDataIdProvider], so re-recording
 * the same run folds rather than duplicating.
 *
 * ⚠ **Two imports that start in the same millisecond are therefore the same row**, and the second
 * folds onto the first. In life this is unreachable — `importLock` serialises imports and a real
 * one takes far longer than a millisecond — but it is reachable with **empty** archives back to
 * back, so a test that records several runs must drive the repository's injectable `clock`.
 *
 * `Syncable(Eager)` — a per-run record under a unique key has nothing to semantically merge.
 *
 * Unlike [HealthSource], [kind] here **does** carry [KIND_TAKEOUT]: an import from a file is a
 * genuine run, it is simply not a standing connection (D11), so the batch-import surface reads
 * these rows and no source row exists for it.
 */
interface HealthImportRun : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable identity + relation key: `"$deviceId:$startedAt"`. */
    val key: TString
        get() = getField(Fields.KEY)

    /** The device that ran the import. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** [HealthSource.KIND_HEALTH_CONNECT] / [HealthSource.KIND_APPLE_HEALTH] / [KIND_TAKEOUT]. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** Epoch millis the import started. Part of the key. */
    val startedAt: TInteger
        get() = getField(Fields.STARTED_AT)

    /** Epoch millis the import finished — success or failure. */
    val finishedAt: TInteger
        get() = getField(Fields.FINISHED_AT)

    /** [WINDOW_RECENT] / [WINDOW_FULL_HISTORY] / [WINDOW_FILE]. */
    val window: TString
        get() = getField(Fields.WINDOW)

    /** [STATUS_SUCCEEDED] / [STATUS_FAILED] / [STATUS_PERMISSIONS_MISSING] / [STATUS_NO_DATA]. */
    val status: TString
        get() = getField(Fields.STATUS)

    /** Everything imported — workouts, samples, sleep, nutrition, cycle days, records. */
    val itemCount: TInteger
        get() = getField(Fields.ITEM_COUNT)

    /** Workouts alone, the figure users actually recognise in the history list. */
    val workoutCount: TInteger
        get() = getField(Fields.WORKOUT_COUNT)

    /**
     * Archive size in bytes — **file imports only (D6)**.
     *
     * ⚠ Health Connect and HealthKit hand us objects, not bytes; there is no honest megabyte
     * figure for them, and the detail screen must not invent one.
     */
    val byteCount: TInteger?
        get() = getOptionalField(Fields.BYTE_COUNT)

    /** The picked archive's display name — file imports only. Stays inside the realm. */
    val fileName: TString?
        get() = getOptionalField(Fields.FILE_NAME)

    /** ⚠ Typed error code only — redaction by construction, never a path or a message (A19). */
    val error: TString?
        get() = getOptionalField(Fields.ERROR)

    companion object {
        const val ID = "tenshu/health/importRun"

        /** A Google Takeout / activity-file archive. ⚠ A run kind, never a [HealthSource] kind. */
        const val KIND_TAKEOUT = "TAKEOUT"

        /**
         * Per-(kind, device) retention cap — keep at most this many runs, dropping the oldest.
         * Matches `FileModification.RETENTION_CAP`, whose observer shape this copies.
         *
         * ⚠ Enforced **only on the originating device** (`origin == WriteOrigin.USER`); peers
         * converge via the propagated tombstone rather than re-trimming on ingest, which would
         * double the tombstone traffic.
         */
        const val RETENTION_CAP = 50

        const val WINDOW_RECENT = "RECENT"
        const val WINDOW_FULL_HISTORY = "FULL_HISTORY"
        const val WINDOW_FILE = "FILE"

        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_PERMISSIONS_MISSING = "PERMISSIONS_MISSING"
        const val STATUS_NO_DATA = "NO_DATA"

        /** The stable relation/identity key for one run. */
        fun keyOf(deviceId: String, startedAt: Long): String = "$deviceId:$startedAt"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(HealthImportRunDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.KEY)
                string(Fields.DEVICE_ID)
                string(Fields.KIND)
                int(Fields.STARTED_AT)
                int(Fields.FINISHED_AT)
                string(Fields.WINDOW)
                string(Fields.STATUS)
                int(Fields.ITEM_COUNT)
                int(Fields.WORKOUT_COUNT)
                int(Fields.BYTE_COUNT, isOptional = true)
                string(Fields.FILE_NAME, isOptional = true)
                string(Fields.ERROR, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            kind: String,
            startedAt: Long,
            finishedAt: Long,
            window: String,
            status: String,
            itemCount: Long,
            workoutCount: Long,
            byteCount: Long? = null,
            fileName: String? = null,
            error: String? = null,
        ): HealthImportRun = context.create(
            type = ID,
            fields = listOf(
                keyOf(deviceId, startedAt).toTString(context),
                deviceId.toTString(context),
                kind.toTString(context),
                startedAt.toTInteger(context),
                finishedAt.toTInteger(context),
                window.toTString(context),
                status.toTString(context),
                itemCount.toTInteger(context),
                workoutCount.toTInteger(context),
                byteCount?.toTInteger(context),
                fileName?.toTString(context),
                error?.toTString(context),
            )
        )
    }

    object Fields {
        const val KEY = "$ID/key"
        const val DEVICE_ID = "$ID/deviceId"
        const val KIND = "$ID/kind"
        const val STARTED_AT = "$ID/startedAt"
        const val FINISHED_AT = "$ID/finishedAt"
        const val WINDOW = "$ID/window"
        const val STATUS = "$ID/status"
        const val ITEM_COUNT = "$ID/itemCount"
        const val WORKOUT_COUNT = "$ID/workoutCount"
        const val BYTE_COUNT = "$ID/byteCount"
        const val FILE_NAME = "$ID/fileName"
        const val ERROR = "$ID/error"
    }
}

/**
 * Content-addresses a [HealthImportRun] by its [HealthImportRun.key], so re-recording the same
 * run folds rather than duplicating.
 */
interface HealthImportRunDataIdProvider : CompositeDataIdProvider<HealthImportRun> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/importRun/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = HealthImportRun.Fields.KEY
        )
    }
}
