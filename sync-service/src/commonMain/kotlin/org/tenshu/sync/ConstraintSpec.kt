package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Phase 5 (docs/proposals/core-background-processing.md §3.3): the constraint
 * vocabulary of a [ScheduleItem]. The cross-platform core set (§5.6); per-
 * platform extensions live next to the platform scheduler binding (Phase 6+).
 *
 * Modelled as `string` fields carrying a fixed vocabulary rather than a
 * `TConstant` hierarchy per value: schema-first and wire-serialisable via the
 * `string(...)` DSL, far fewer types/DTOs, and the in-process drain loop
 * treats constraints best-effort (§5.6) so the extra type-safety would buy
 * nothing until `PlatformScheduler` (Phase 6).
 */
interface ConstraintSpec : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val network: TString
        get() = getField(Fields.NETWORK)

    val power: TString
        get() = getField(Fields.POWER)

    val idle: TString
        get() = getField(Fields.IDLE)

    val storage: TString
        get() = getField(Fields.STORAGE)

    val battery: TString
        get() = getField(Fields.BATTERY)

    /**
     * Monthly cellular-data ceiling in MiB; `null` = no cap. Resolved from
     * `ControlPreferences.MaxMonthlyDataMb` at schedule time (§3.3 / §6.4).
     * Best-effort: only the Android `PlatformScheduler` (Phase 6) enforces it;
     * other platforms ignore it in v1.
     */
    val maxDataMb: TInteger?
        get() = getOptionalField(Fields.MAX_DATA_MB)

    companion object {
        const val ID = "tenshu/sync/schedule/constraintSpec"

        const val NETWORK_ANY = "ANY"
        const val NETWORK_UNMETERED = "UNMETERED"
        const val NETWORK_NONE = "NONE"
        const val POWER_ANY = "ANY"
        const val POWER_CHARGING = "CHARGING"
        const val IDLE_ANY = "ANY"
        const val IDLE_DEVICE_IDLE = "DEVICE_IDLE"
        const val STORAGE_ANY = "ANY"
        const val STORAGE_NOT_LOW = "NOT_LOW"
        const val BATTERY_ANY = "ANY"
        const val BATTERY_NOT_LOW = "NOT_LOW"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.NETWORK)
                string(Fields.POWER)
                string(Fields.IDLE)
                string(Fields.STORAGE)
                string(Fields.BATTERY)
                int(Fields.MAX_DATA_MB, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            network: String = NETWORK_ANY,
            power: String = POWER_ANY,
            idle: String = IDLE_ANY,
            storage: String = STORAGE_ANY,
            battery: String = BATTERY_ANY,
            maxDataMb: Long? = null
        ): ConstraintSpec = context.create(
            type = ID,
            fields = listOf(
                network.toTString(context),
                power.toTString(context),
                idle.toTString(context),
                storage.toTString(context),
                battery.toTString(context),
                maxDataMb?.toTInteger(context)
            )
        )
    }

    object Fields {
        const val NETWORK = "$ID/network"
        const val POWER = "$ID/power"
        const val IDLE = "$ID/idle"
        const val STORAGE = "$ID/storage"
        const val BATTERY = "$ID/battery"
        const val MAX_DATA_MB = "$ID/maxDataMb"
    }
}
