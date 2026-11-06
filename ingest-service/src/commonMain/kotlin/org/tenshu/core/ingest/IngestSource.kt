package org.tenshu.core.ingest

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A background-ingest source registered with Control's generic ingest engine
 * (`docs/proposals/drive-synced-folders-phase2.md` §3). One row per
 * (`sourceId`, `deviceId`) — sources are **device-scoped** (a `MediaStore`
 * bucket / SAF tree / desktop path is a device-local identity) but the row is
 * **`Syncable(Eager)`**, so another of the user's devices can read this device's
 * source list + status for the cross-device management view (§3.1 — same free
 * win as the offline-keeper selection).
 *
 * Keyed by [key] (`"sourceId@deviceId"`) via [IngestSourceDataIdProvider], so a
 * register/status/enable write is an idempotent upsert against the stable key —
 * the relation maintainer (`initIngestService`) remove-then-adds by that key.
 *
 * Phase-2a carried the descriptor + enabled-state + live status, and both slices
 * that built on it have since landed: the read-gate is live (`SyncedFolderController`
 * checks `IngestRegistry.isEnabled` before every source's pass) and Control's
 * desktop `FolderWatcher` walks [watchedPaths] for real. So a write here is load
 * bearing on both platforms, not just a descriptor nobody reads.
 */
interface IngestSource : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable identity + relation key: `"$sourceId@$deviceId"`. */
    val key: TString
        get() = getField(Fields.KEY)

    /** Stable per (app, source kind), e.g. `"drive.gallery"`. Shared across devices. */
    val sourceId: TString
        get() = getField(Fields.SOURCE_ID)

    /** Owning app, e.g. `"app.tenshu.drive"`. */
    val appId: TString
        get() = getField(Fields.APP_ID)

    /** The device this source instance lives on (sources are device-scoped, §3.2). */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** Human label, localised by the app at register time, e.g. "Photos & Videos". */
    val displayName: TString
        get() = getField(Fields.DISPLAY_NAME)

    /** [KIND_GALLERY] / [KIND_DOCUMENT_TREE] / [KIND_LOCATION] / [KIND_VITALS] … */
    val sourceKind: TString
        get() = getField(Fields.SOURCE_KIND)

    /** The central kill switch (§5). Defaults true at register time. */
    val enabled: TBoolean
        get() = getField(Fields.ENABLED)

    /**
     * Desktop only: the directories Control's in-process `FolderWatcher` walks
     * (§4.2), newline-separated. Empty on mobile, where the app holds the
     * bucket selection and enumerates with its own gallery permission.
     */
    val watchedPaths: TString?
        get() = getOptionalField(Fields.WATCHED_PATHS)

    val lastRunAt: TInteger?
        get() = getOptionalField(Fields.LAST_RUN_AT)

    /** [STATUS_IDLE] / [STATUS_RUNNING] / [STATUS_UP_TO_DATE] / [STATUS_PAUSED_POLICY] / [STATUS_OUT_OF_SPACE] / [STATUS_ERROR]. */
    val lastStatus: TString?
        get() = getOptionalField(Fields.LAST_STATUS)

    val pendingCount: TInteger?
        get() = getOptionalField(Fields.PENDING_COUNT)

    /** Typed-error code only — redaction-by-construction, no path/content (A19). */
    val lastError: TString?
        get() = getOptionalField(Fields.LAST_ERROR)

    companion object {
        const val ID = "tenshu/ingest/source"

        const val KIND_GALLERY = "GALLERY"
        const val KIND_DOCUMENT_TREE = "DOCUMENT_TREE"
        const val KIND_LOCATION = "LOCATION"
        const val KIND_VITALS = "VITALS"

        const val STATUS_IDLE = "IDLE"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_UP_TO_DATE = "UP_TO_DATE"
        const val STATUS_PAUSED_POLICY = "PAUSED_POLICY"
        const val STATUS_OUT_OF_SPACE = "OUT_OF_SPACE"
        const val STATUS_ERROR = "ERROR"

        /** The stable relation/identity key for a source on a device. */
        fun keyOf(sourceId: String, deviceId: String): String = "$sourceId@$deviceId"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // Eager push on save + default LastWriterWins, mirroring `Notification`
            // / `Device`: a source row is an individual descriptor keyed by a
            // unique (source, device) key, so there is nothing for a semantic
            // merge to do — receivers either have the row or they don't.
            traits = listOf(IngestSourceDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.KEY)
                string(Fields.SOURCE_ID)
                string(Fields.APP_ID)
                string(Fields.DEVICE_ID)
                string(Fields.DISPLAY_NAME)
                string(Fields.SOURCE_KIND)
                boolean(Fields.ENABLED)
                string(Fields.WATCHED_PATHS, isOptional = true)
                int(Fields.LAST_RUN_AT, isOptional = true)
                string(Fields.LAST_STATUS, isOptional = true)
                int(Fields.PENDING_COUNT, isOptional = true)
                string(Fields.LAST_ERROR, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            sourceId: String,
            appId: String,
            deviceId: String,
            displayName: String,
            sourceKind: String,
            enabled: Boolean = true,
            watchedPaths: String? = null,
            lastRunAt: Long? = null,
            lastStatus: String? = STATUS_IDLE,
            pendingCount: Long? = null,
            lastError: String? = null,
        ): IngestSource = context.create(
            type = ID,
            fields = listOf(
                keyOf(sourceId, deviceId).toTString(context),
                sourceId.toTString(context),
                appId.toTString(context),
                deviceId.toTString(context),
                displayName.toTString(context),
                sourceKind.toTString(context),
                enabled.toTBoolean(context),
                watchedPaths?.toTString(context),
                lastRunAt?.toTInteger(context),
                lastStatus?.toTString(context),
                pendingCount?.toTInteger(context),
                lastError?.toTString(context),
            )
        )
    }

    object Fields {
        const val KEY = "$ID/key"
        const val SOURCE_ID = "$ID/sourceId"
        const val APP_ID = "$ID/appId"
        const val DEVICE_ID = "$ID/deviceId"
        const val DISPLAY_NAME = "$ID/displayName"
        const val SOURCE_KIND = "$ID/sourceKind"
        const val ENABLED = "$ID/enabled"
        const val WATCHED_PATHS = "$ID/watchedPaths"
        const val LAST_RUN_AT = "$ID/lastRunAt"
        const val LAST_STATUS = "$ID/lastStatus"
        const val PENDING_COUNT = "$ID/pendingCount"
        const val LAST_ERROR = "$ID/lastError"
    }
}

interface IngestSourceDataIdProvider : CompositeDataIdProvider<IngestSource> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/ingest/source/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = IngestSource.Fields.KEY
        )
    }
}
