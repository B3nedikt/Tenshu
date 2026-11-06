package org.tenshu.hub.management

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
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
 * The hub's self-published status snapshot — one row per hub device, keyed by
 * [deviceId], authored and periodically refreshed **by the hub itself** (on
 * startup, after each backup pass, after executing a [HubCommand], and on a
 * coarse heartbeat).
 *
 * ### Design deviation from the hub proposal's §8 stub
 *
 * The §8 stub shaped `getHubStatus` as an on-demand operation returning a live
 * snapshot ("deliberately not Syncable"). That shape presumes cross-device
 * *operation routing*, which does not exist on this platform: an operation
 * executes on the caller's own proxy, and the only cross-DEVICE channel is
 * synced rows. So v1 inverts it, platform-native: the status is a
 * `Syncable(Eager)` row the hub authors, and `getHubStatus()` on a managing
 * device is a **local read** of the freshest hub-authored snapshot. The
 * [freshAsOf] timestamp keeps the snapshot semantics honest — the value is
 * "as of the hub's last write", not a live probe.
 *
 * Command outcomes ride this same row ([lastCommandId] / [lastCommandOutcome])
 * rather than a second ack type — the status row is already the hub→realm
 * reporting channel, and an ack type would double every sync write.
 */
interface HubStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The hub's realm device id — the row key ([DataIdProviderSchema]). */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** Human-readable node name shown in the managing UI. */
    val name: TString
        get() = getField(Fields.NAME)

    /** Running hub build / version string. */
    val version: TString
        get() = getField(Fields.VERSION)

    /**
     * Epoch ms at which the hub process last started. Uptime derives
     * client-side (`now - startedAtEpochMs`) — deliberately not a ticking
     * uptime field, which would force a sync write per refresh interval.
     */
    val startedAtEpochMs: TInteger
        get() = getField(Fields.STARTED_AT_EPOCH_MS)

    /** Files held in the hub's durable content replica (H3). */
    val replicaFileCount: TInteger
        get() = getField(Fields.REPLICA_FILE_COUNT)

    /** Total bytes held in the hub's durable content replica (H3). */
    val replicaByteCount: TInteger
        get() = getField(Fields.REPLICA_BYTE_COUNT)

    /**
     * Free bytes on the filesystem holding the hub's data dir (and thus the
     * H3 content replica). `0` together with a zero [diskTotalBytes] means
     * "unknown" — consumers must not render a zero-capacity disk as real
     * (Home's storage tile falls back to its mock/empty state instead).
     */
    val diskFreeBytes: TInteger
        get() = getField(Fields.DISK_FREE_BYTES)

    /** Total bytes of that filesystem; `0` = unknown (see [diskFreeBytes]). */
    val diskTotalBytes: TInteger
        get() = getField(Fields.DISK_TOTAL_BYTES)

    /** Epoch ms of the last completed backup pass; `0` = none this process. */
    val lastBackupPassAtEpochMs: TInteger
        get() = getField(Fields.LAST_BACKUP_PASS_AT_EPOCH_MS)

    /** Realm-bound? (Trivially `true` for a synced row, but cheap and honest.) */
    val paired: TBoolean
        get() = getField(Fields.PAIRED)

    /** Epoch ms (hub clock) at which the hub authored this snapshot. */
    val freshAsOf: TInteger
        get() = getField(Fields.FRESH_AS_OF)

    /** [HubCommand.commandId] of the last command the hub consumed; `""` = none. */
    val lastCommandId: TString
        get() = getField(Fields.LAST_COMMAND_ID)

    /**
     * Outcome of the last consumed command: [OUTCOME_OK], [OUTCOME_FAILED],
     * [OUTCOME_UNSUPPORTED], [OUTCOME_RESTARTING]; `""` = none.
     */
    val lastCommandOutcome: TString
        get() = getField(Fields.LAST_COMMAND_OUTCOME)

    companion object {
        const val ID = "tenshu/hub/management/status"

        const val OUTCOME_OK = "ok"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_UNSUPPORTED = "unsupported"

        /** Restart accepted; published *before* the process exits. */
        const val OUTCOME_RESTARTING = "restarting"

        /** Keys the row by [Fields.DEVICE_ID] — one status row per hub device. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.NAME)
                string(Fields.VERSION)
                int(Fields.STARTED_AT_EPOCH_MS)
                int(Fields.REPLICA_FILE_COUNT)
                int(Fields.REPLICA_BYTE_COUNT)
                // Disk figures added for Home's storage tile ("n GB free / total").
                // ⚠ Positional composite: inserting these fields reshaped the row →
                // TENSHU_STORAGE_FORMAT_VERSION v12 (HR #9) landed in the same PR.
                int(Fields.DISK_FREE_BYTES)
                int(Fields.DISK_TOTAL_BYTES)
                int(Fields.LAST_BACKUP_PASS_AT_EPOCH_MS)
                boolean(Fields.PAIRED)
                int(Fields.FRESH_AS_OF)
                string(Fields.LAST_COMMAND_ID)
                string(Fields.LAST_COMMAND_OUTCOME)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            name: String,
            version: String,
            startedAtEpochMs: Long,
            replicaFileCount: Long,
            replicaByteCount: Long,
            diskFreeBytes: Long,
            diskTotalBytes: Long,
            lastBackupPassAtEpochMs: Long,
            paired: Boolean,
            freshAsOf: Long,
            lastCommandId: String = "",
            lastCommandOutcome: String = "",
        ): HubStatus = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                name.toTString(context),
                version.toTString(context),
                startedAtEpochMs.toTInteger(context),
                replicaFileCount.toTInteger(context),
                replicaByteCount.toTInteger(context),
                diskFreeBytes.toTInteger(context),
                diskTotalBytes.toTInteger(context),
                lastBackupPassAtEpochMs.toTInteger(context),
                paired.toTBoolean(context),
                freshAsOf.toTInteger(context),
                lastCommandId.toTString(context),
                lastCommandOutcome.toTString(context),
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val NAME = "$ID/name"
        const val VERSION = "$ID/version"
        const val STARTED_AT_EPOCH_MS = "$ID/startedAtEpochMs"
        const val REPLICA_FILE_COUNT = "$ID/replicaFileCount"
        const val REPLICA_BYTE_COUNT = "$ID/replicaByteCount"
        const val DISK_FREE_BYTES = "$ID/diskFreeBytes"
        const val DISK_TOTAL_BYTES = "$ID/diskTotalBytes"
        const val LAST_BACKUP_PASS_AT_EPOCH_MS = "$ID/lastBackupPassAtEpochMs"
        const val PAIRED = "$ID/paired"
        const val FRESH_AS_OF = "$ID/freshAsOf"
        const val LAST_COMMAND_ID = "$ID/lastCommandId"
        const val LAST_COMMAND_OUTCOME = "$ID/lastCommandOutcome"
    }
}
