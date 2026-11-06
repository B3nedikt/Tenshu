package org.tenshu.home.device

import org.tenshu.core.Composite
import org.tenshu.core.Data
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
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Live-state snapshot for one bridged device (`home-device-service.md`
 * HD-D1): **hub-authored only**, keyed by [deviceId] (same key space as
 * [HomeDevice]), change-gated + rate-limited by the hub-side publisher so a
 * chatty sensor cannot flood the realm with sync blocks.
 *
 * The common typed fields are deliberately **generous now** — optional-field
 * *additions* later are peer-breaking (positional composites), so anything a
 * plausible v1/v2 device reports got a slot up front. Fields a device does
 * not have carry a sentinel ("not applicable"), documented per field; the
 * long tail rides [extras] (`genericData`, the preference-service precedent).
 *
 * Command outcomes ride this row ([lastCommandId] / [lastCommandOutcome])
 * rather than a separate ack type — exactly the `HubStatus` pattern: the
 * state row is already the hub→realm reporting channel for the device.
 *
 * Telemetry *history* is explicitly out of v1 — this row is "latest is realm
 * state, history is elsewhere" (the Fit `LatestSample` precedent).
 */
interface DeviceState : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The [HomeDevice.deviceId] this state belongs to — the row key. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** Reachability as seen by the bridge (radio-level, not sync-level). */
    val online: TBoolean
        get() = getField(Fields.ONLINE)

    /** [POWER_ON] | [POWER_OFF] | [POWER_NOT_APPLICABLE] (no power capability). */
    val power: TInteger
        get() = getField(Fields.POWER)

    /** Dim/position level 0–100; [NO_PERCENT] = no level capability. */
    val levelPercent: TInteger
        get() = getField(Fields.LEVEL_PERCENT)

    /** Tenths of a degree Celsius; [NO_TEMPERATURE_DECI_C] = none reported. */
    val temperatureDeciC: TInteger
        get() = getField(Fields.TEMPERATURE_DECI_C)

    /** Relative humidity 0–100; [NO_PERCENT] = none reported. */
    val humidityPercent: TInteger
        get() = getField(Fields.HUMIDITY_PERCENT)

    /** Battery 0–100; [NO_PERCENT] = mains-powered / not reported. */
    val batteryPercent: TInteger
        get() = getField(Fields.BATTERY_PERCENT)

    /** Colour temperature in mireds; [NO_VALUE] = no colour-temp capability. */
    val colorTempMireds: TInteger
        get() = getField(Fields.COLOR_TEMP_MIREDS)

    /** Instantaneous power draw in tenths of a watt; [NO_VALUE] = not metered. */
    val powerDrawDeciW: TInteger
        get() = getField(Fields.POWER_DRAW_DECI_W)

    /** [DeviceCommand.commandId] of the last command the hub consumed for this device; `""` = none. */
    val lastCommandId: TString
        get() = getField(Fields.LAST_COMMAND_ID)

    /** [OUTCOME_OK] | [OUTCOME_FAILED] | [OUTCOME_UNSUPPORTED]; `""` = none. */
    val lastCommandOutcome: TString
        get() = getField(Fields.LAST_COMMAND_OUTCOME)

    /** Epoch ms (hub clock) at which the hub authored this snapshot. */
    val freshAsOf: TInteger
        get() = getField(Fields.FRESH_AS_OF)

    /** Polymorphic per-kind extras — the `genericData` escape hatch (class KDoc). */
    val extras: Data
        get() = getField(Fields.EXTRAS)

    /** [power] as a nullable Boolean (`null` = no power capability). */
    fun powerOnOrNull(): Boolean? = when (power.get()) {
        POWER_ON -> true
        POWER_OFF -> false
        else -> null
    }

    companion object {
        const val ID = "tenshu/home/device/state"

        const val POWER_NOT_APPLICABLE = -1L
        const val POWER_OFF = 0L
        const val POWER_ON = 1L

        /** Sentinel for absent percent-valued fields (level, humidity, battery). */
        const val NO_PERCENT = -1L

        /** Sentinel for absent non-negative scalar fields (mireds, deci-watts). */
        const val NO_VALUE = -1L

        /**
         * Sentinel for an absent temperature. NOT `-1` — temperatures are
         * legitimately negative — and NOT `Long.MIN_VALUE` (hostile to
         * arithmetic on a mis-read); no terrestrial reading gets near it.
         */
        const val NO_TEMPERATURE_DECI_C = -1_000_000L

        const val OUTCOME_OK = "ok"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_UNSUPPORTED = "unsupported"

        /** Keys the row by [Fields.DEVICE_ID] — one state row per device. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                boolean(Fields.ONLINE)
                int(Fields.POWER)
                int(Fields.LEVEL_PERCENT)
                int(Fields.TEMPERATURE_DECI_C)
                int(Fields.HUMIDITY_PERCENT)
                int(Fields.BATTERY_PERCENT)
                int(Fields.COLOR_TEMP_MIREDS)
                int(Fields.POWER_DRAW_DECI_W)
                string(Fields.LAST_COMMAND_ID)
                string(Fields.LAST_COMMAND_OUTCOME)
                int(Fields.FRESH_AS_OF)
                genericData(Fields.EXTRAS)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            online: Boolean,
            power: Long = POWER_NOT_APPLICABLE,
            levelPercent: Long = NO_PERCENT,
            temperatureDeciC: Long = NO_TEMPERATURE_DECI_C,
            humidityPercent: Long = NO_PERCENT,
            batteryPercent: Long = NO_PERCENT,
            colorTempMireds: Long = NO_VALUE,
            powerDrawDeciW: Long = NO_VALUE,
            lastCommandId: String = "",
            lastCommandOutcome: String = "",
            freshAsOf: Long,
            extras: Data? = null,
        ): DeviceState = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                online.toTBoolean(context),
                power.toTInteger(context),
                levelPercent.toTInteger(context),
                temperatureDeciC.toTInteger(context),
                humidityPercent.toTInteger(context),
                batteryPercent.toTInteger(context),
                colorTempMireds.toTInteger(context),
                powerDrawDeciW.toTInteger(context),
                lastCommandId.toTString(context),
                lastCommandOutcome.toTString(context),
                freshAsOf.toTInteger(context),
                extras ?: "".toTString(context),
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val ONLINE = "$ID/online"
        const val POWER = "$ID/power"
        const val LEVEL_PERCENT = "$ID/levelPercent"
        const val TEMPERATURE_DECI_C = "$ID/temperatureDeciC"
        const val HUMIDITY_PERCENT = "$ID/humidityPercent"
        const val BATTERY_PERCENT = "$ID/batteryPercent"
        const val COLOR_TEMP_MIREDS = "$ID/colorTempMireds"
        const val POWER_DRAW_DECI_W = "$ID/powerDrawDeciW"
        const val LAST_COMMAND_ID = "$ID/lastCommandId"
        const val LAST_COMMAND_OUTCOME = "$ID/lastCommandOutcome"
        const val FRESH_AS_OF = "$ID/freshAsOf"
        const val EXTRAS = "$ID/extras"
    }
}
