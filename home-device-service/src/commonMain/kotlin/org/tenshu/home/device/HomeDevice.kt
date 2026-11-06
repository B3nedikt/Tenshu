package org.tenshu.home.device

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Inventory row for one smart-home device bridged by a hub
 * (`home-device-service.md` HD-D1): identity + configuration, one row per
 * device, keyed by the **bridge-scoped stable device id** ([deviceId] =
 * `"<bridgeId>:<bridge-local-id>"`, e.g. `"simulated:light-1"`).
 *
 * Authorship is split by field:
 *  - hub-authored on join / re-announce: [bridgeId], [kind], [manufacturer],
 *    [model], [capabilityIds], [joinedAtEpochMs] (the hub-side publisher is
 *    change-gated on exactly these, and preserves the user fields on
 *    re-publish);
 *  - user-editable from any realm device via `UpdateHomeDevice`: [name] and
 *    [room] (a plain string per `home-and-mia-apps.md` D5 — a `Room` entity
 *    waits for real need).
 *
 * Concurrent edits resolve at the sync layer's row-level LWW; the two authors
 * stay out of each other's way because the hub only re-publishes when a
 * hub-authored field changed (device re-announce), never on state chatter —
 * live state rides the separate [DeviceState] row.
 *
 * [capabilityIds] is a single space-separated string, NOT a native list
 * field: native list fields on keyed syncable rows read empty over the chain
 * (the known keyed-row list gap — the SyncPolicy `TConstant` work hit it);
 * a flat string is honest, wire-cheap, and greppable. [extras] is the
 * `genericData` escape hatch (preference-service precedent) for per-kind
 * identity extras — HD-1 writes an empty `TString`; HD-2's zigbee2mqtt bridge
 * can put richer typed payloads there without a schema change.
 */
interface HomeDevice : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Bridge-scoped stable device id — the row key ([DataIdProviderSchema]). */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** Which bridge this device came from (e.g. [BRIDGE_SIMULATED], `"zigbee2mqtt"`). */
    val bridgeId: TString
        get() = getField(Fields.BRIDGE_ID)

    /** Device kind — one of the `KIND_*` constants; unknown kinds render generically. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** Manufacturer as reported by the bridge; `""` = unknown. */
    val manufacturer: TString
        get() = getField(Fields.MANUFACTURER)

    /** Model as reported by the bridge; `""` = unknown. */
    val model: TString
        get() = getField(Fields.MODEL)

    /** Space-separated capability ids (`CAP_*` constants) — see class KDoc. */
    val capabilityIds: TString
        get() = getField(Fields.CAPABILITY_IDS)

    /** User-facing name. Hub seeds it on join; user-editable (`UpdateHomeDevice`). */
    val name: TString
        get() = getField(Fields.NAME)

    /** Room assignment (free string, D5); `""` = unassigned. User-editable. */
    val room: TString
        get() = getField(Fields.ROOM)

    /** Epoch ms (hub clock) at which the hub first published this device. */
    val joinedAtEpochMs: TInteger
        get() = getField(Fields.JOINED_AT_EPOCH_MS)

    /** Polymorphic per-kind extras — the `genericData` escape hatch (class KDoc). */
    val extras: Data
        get() = getField(Fields.EXTRAS)

    /** [capabilityIds] split into individual capability ids. */
    fun capabilities(): List<String> =
        capabilityIds.get().split(' ').filter { it.isNotBlank() }

    companion object {
        const val ID = "tenshu/home/device/homeDevice"

        const val BRIDGE_SIMULATED = "simulated"

        /**
         * HD-2's Zigbee bridge (a zigbee2mqtt sidecar). ⚠ Note the bridge
         * publishes **itself** as one inventory row whose [deviceId] IS this
         * bridge id — a deliberate exception to `"<bridgeId>:<local-id>"`,
         * because that row *is* the bridge: it advertises
         * [CAP_PERMIT_JOIN], it exists in a network that has never paired
         * anything, and it is the `permitJoin` command's target.
         */
        const val BRIDGE_ZIGBEE2MQTT = "zigbee2mqtt"

        const val KIND_LIGHT = "light"
        const val KIND_PLUG = "plug"
        const val KIND_SENSOR = "sensor"
        const val KIND_THERMOSTAT = "thermostat"
        const val KIND_SPEAKER = "speaker"
        const val KIND_CAMERA = "camera"

        /** A radio bridge itself (HD-2) — the row you open a pairing window on. */
        const val KIND_BRIDGE = "bridge"

        const val KIND_OTHER = "other"

        const val CAP_POWER = "power"
        const val CAP_LEVEL = "level"
        const val CAP_TEMPERATURE = "temperature"
        const val CAP_HUMIDITY = "humidity"
        const val CAP_BATTERY = "battery"
        const val CAP_COLOR_TEMP = "colorTemp"

        /**
         * This row can open a time-bounded pairing window
         * ([DeviceCommand.VERB_PERMIT_JOIN]). Advertised by bridge rows only;
         * it is how Home discovers that a pairable bridge exists without
         * hard-coding any bridge id in the UI.
         */
        const val CAP_PERMIT_JOIN = "permitJoin"

        /** Keys the row by [Fields.DEVICE_ID] — one inventory row per device. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.BRIDGE_ID)
                string(Fields.KIND)
                string(Fields.MANUFACTURER)
                string(Fields.MODEL)
                string(Fields.CAPABILITY_IDS)
                string(Fields.NAME)
                string(Fields.ROOM)
                int(Fields.JOINED_AT_EPOCH_MS)
                genericData(Fields.EXTRAS)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            bridgeId: String,
            kind: String,
            manufacturer: String = "",
            model: String = "",
            capabilityIds: String = "",
            name: String,
            room: String = "",
            joinedAtEpochMs: Long,
            extras: Data? = null,
        ): HomeDevice = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                bridgeId.toTString(context),
                kind.toTString(context),
                manufacturer.toTString(context),
                model.toTString(context),
                capabilityIds.toTString(context),
                name.toTString(context),
                room.toTString(context),
                joinedAtEpochMs.toTInteger(context),
                extras ?: "".toTString(context),
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val BRIDGE_ID = "$ID/bridgeId"
        const val KIND = "$ID/kind"
        const val MANUFACTURER = "$ID/manufacturer"
        const val MODEL = "$ID/model"
        const val CAPABILITY_IDS = "$ID/capabilityIds"
        const val NAME = "$ID/name"
        const val ROOM = "$ID/room"
        const val JOINED_AT_EPOCH_MS = "$ID/joinedAtEpochMs"
        const val EXTRAS = "$ID/extras"
    }
}
