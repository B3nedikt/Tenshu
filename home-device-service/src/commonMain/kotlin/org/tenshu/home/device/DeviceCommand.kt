package org.tenshu.home.device

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
 * An idempotent command for one bridged device — the H4 `HubCommand` pattern
 * verbatim (`home-device-service.md` HD-D1): authored by a managing realm
 * device (via `IssueDeviceCommand`), delivered by ordinary sync, consumed
 * **at most once** by the hub whose bridge owns [targetDeviceId] (durable
 * consumed-journal written *before* execution).
 *
 * ⚠ The staleness window is [STALE_WINDOW_MS] = **2 minutes** — much tighter
 * than hub management's 15 (HD-D7): a command replayed from a stale sync
 * backlog causes *physical actuation*, and a light toggle must never fire
 * hours after the gesture. The window + journal are load-bearing and pinned
 * by tests; see `docs/sync/threat-model.md` §"Smart-home actuation".
 *
 * [verb] + [argValue] carry the capability action. [argValue] is a single
 * canonical **string** encoding (e.g. `"on"`, `"42"`) rather than a
 * `genericData` slot: the hub parses per-verb anyway, new verbs are additive
 * with no schema change, and a string argument is exactly the shape the
 * zigbee2mqtt contract speaks (HD-2). Outcome rides the [DeviceState] row
 * ([DeviceState.lastCommandId] / [DeviceState.lastCommandOutcome]) — no ack
 * type.
 *
 * Rows are keyed by the caller-supplied [commandId] (a fresh UUID per
 * gesture), so re-syncing the same row is intrinsically idempotent — it is
 * the *same* command, not a new one.
 */
interface DeviceCommand : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Unique id of this command — the row key; consumed-set member on the hub. */
    val commandId: TString
        get() = getField(Fields.COMMAND_ID)

    /** The [HomeDevice.deviceId] this command addresses. */
    val targetDeviceId: TString
        get() = getField(Fields.TARGET_DEVICE_ID)

    /** Capability verb — [VERB_SET_POWER] | [VERB_TOGGLE] | [VERB_SET_LEVEL] | future. */
    val verb: TString
        get() = getField(Fields.VERB)

    /** Canonical string argument for [verb]; `""` when the verb takes none. */
    val argValue: TString
        get() = getField(Fields.ARG_VALUE)

    /** Epoch ms (issuer clock) — the staleness-window anchor. */
    val issuedAtEpochMs: TInteger
        get() = getField(Fields.ISSUED_AT_EPOCH_MS)

    /** Realm device id of the issuing device (audit; `""` if unknown). */
    val issuedByDeviceId: TString
        get() = getField(Fields.ISSUED_BY_DEVICE_ID)

    companion object {
        const val ID = "tenshu/home/device/command"

        /**
         * HD-D7 staleness window (2 min). Part of the service contract, not a
         * hub tuning knob: every consumer of the command channel relies on
         * "older than this never actuates".
         */
        const val STALE_WINDOW_MS = 2L * 60 * 1000

        /** Set on/off explicitly; arg = [ARG_ON] | [ARG_OFF]. */
        const val VERB_SET_POWER = "setPower"

        /** Flip the current power state; no arg. */
        const val VERB_TOGGLE = "toggle"

        /** Set the dim/position level; arg = `"0"`..`"100"`. Implies power-on at level > 0. */
        const val VERB_SET_LEVEL = "setLevel"

        /**
         * Open a bridge's pairing window (HD-2, HD-D7's admission gate).
         * ⚠ Addressed to a **bridge row** ([HomeDevice.CAP_PERMIT_JOIN]),
         * not to a device; arg = seconds (`"0"` closes it).
         *
         * Riding the ordinary command channel is the point: the durable
         * consumed journal makes it exactly-once and the 2-minute
         * [STALE_WINDOW_MS] makes a backlog-replayed "open the network"
         * inert — a permit-join that arrives late must never quietly open the
         * radio to the neighbourhood.
         */
        const val VERB_PERMIT_JOIN = "permitJoin"

        /**
         * Hard ceiling on a pairing window, enforced hub-side regardless of
         * what was asked (5 min — long enough for a stubborn bulb's
         * reset-and-pair dance, short enough that a forgotten window closes
         * itself). The network is never open by default.
         */
        const val PERMIT_JOIN_MAX_SECONDS = 300

        /** What "Add device" asks for when it does not say (1 min). */
        const val PERMIT_JOIN_DEFAULT_SECONDS = 60

        const val ARG_ON = "on"
        const val ARG_OFF = "off"

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
                string(Fields.VERB)
                string(Fields.ARG_VALUE)
                int(Fields.ISSUED_AT_EPOCH_MS)
                string(Fields.ISSUED_BY_DEVICE_ID)
            }
        )

        fun create(
            context: TContext,
            commandId: String,
            targetDeviceId: String,
            verb: String,
            argValue: String = "",
            issuedAtEpochMs: Long,
            issuedByDeviceId: String = "",
        ): DeviceCommand = context.create(
            type = ID,
            fields = listOf(
                commandId.toTString(context),
                targetDeviceId.toTString(context),
                verb.toTString(context),
                argValue.toTString(context),
                issuedAtEpochMs.toTInteger(context),
                issuedByDeviceId.toTString(context),
            )
        )
    }

    object Fields {
        const val COMMAND_ID = "$ID/commandId"
        const val TARGET_DEVICE_ID = "$ID/targetDeviceId"
        const val VERB = "$ID/verb"
        const val ARG_VALUE = "$ID/argValue"
        const val ISSUED_AT_EPOCH_MS = "$ID/issuedAtEpochMs"
        const val ISSUED_BY_DEVICE_ID = "$ID/issuedByDeviceId"
    }
}
