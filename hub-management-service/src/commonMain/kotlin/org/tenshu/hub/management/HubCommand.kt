package org.tenshu.hub.management

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
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
 * An idempotent management command for a hub, authored by a managing realm
 * device and delivered to the hub via ordinary sync (there is no cross-device
 * operation routing — see [HubStatus]'s KDoc for the §8 deviation rationale).
 *
 * The hub executes a command **at most once**: consumed [commandId]s are
 * persisted durably on the hub, and commands older than a sanity window
 * (15 min) are ignored — so a hub restored from a sync backlog does not
 * replay a restart storm. Execution results are written back onto the hub's
 * [HubStatus] row ([HubStatus.lastCommandId] / [HubStatus.lastCommandOutcome]).
 *
 * Rows are keyed by the caller-supplied [commandId] (a fresh UUID per
 * gesture), so re-syncing the same row is intrinsically idempotent — it is
 * the *same* command, not a new one.
 */
interface HubCommand : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Unique id of this command — the row key; consumed-set member on the hub. */
    val commandId: TString
        get() = getField(Fields.COMMAND_ID)

    /** Realm device id of the hub this command addresses. */
    val targetDeviceId: TString
        get() = getField(Fields.TARGET_DEVICE_ID)

    /** [KIND_RESTART] | [KIND_RUN_BACKUP_NOW]. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** For [KIND_RESTART]: [MODE_PROXY] | [MODE_SYSTEM]. `""` otherwise. */
    val mode: TString
        get() = getField(Fields.MODE)

    /** Epoch ms (issuer clock) — the sanity-window anchor. */
    val issuedAtEpochMs: TInteger
        get() = getField(Fields.ISSUED_AT_EPOCH_MS)

    /** Realm device id of the issuing device (audit; `""` if unknown). */
    val issuedByDeviceId: TString
        get() = getField(Fields.ISSUED_BY_DEVICE_ID)

    companion object {
        const val ID = "tenshu/hub/management/command"

        /** Restart the hub. [MODE_PROXY] restarts the proxy process (systemd re-spawns it). */
        const val KIND_RESTART = "restart"

        /** Run one keep-everything backup pass now (H3 `runBackupPassNow`). */
        const val KIND_RUN_BACKUP_NOW = "runBackupNow"

        const val MODE_PROXY = "proxy"

        /** Reboot the node. v1-unimplemented — the hub reports [HubStatus.OUTCOME_UNSUPPORTED]. */
        const val MODE_SYSTEM = "system"

        /** Keys the row by [Fields.COMMAND_ID]. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.COMMAND_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.COMMAND_ID)
                string(Fields.TARGET_DEVICE_ID)
                string(Fields.KIND)
                string(Fields.MODE)
                int(Fields.ISSUED_AT_EPOCH_MS)
                string(Fields.ISSUED_BY_DEVICE_ID)
            }
        )

        fun create(
            context: TContext,
            commandId: String,
            targetDeviceId: String,
            kind: String,
            mode: String = "",
            issuedAtEpochMs: Long,
            issuedByDeviceId: String = "",
        ): HubCommand = context.create(
            type = ID,
            fields = listOf(
                commandId.toTString(context),
                targetDeviceId.toTString(context),
                kind.toTString(context),
                mode.toTString(context),
                issuedAtEpochMs.toTInteger(context),
                issuedByDeviceId.toTString(context),
            )
        )
    }

    object Fields {
        const val COMMAND_ID = "$ID/commandId"
        const val TARGET_DEVICE_ID = "$ID/targetDeviceId"
        const val KIND = "$ID/kind"
        const val MODE = "$ID/mode"
        const val ISSUED_AT_EPOCH_MS = "$ID/issuedAtEpochMs"
        const val ISSUED_BY_DEVICE_ID = "$ID/issuedByDeviceId"
    }
}
