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
 * Phase 5 (docs/proposals/core-background-processing.md §3.3): the schedule
 * shape of a [ScheduleItem]. Per-invocation runtime data, **not** trait params
 * (the §3.2 revision's whole point).
 *
 * `*Ms` durations are stored as [TInteger] (64-bit; the codebase backs Long
 * millis with `TInteger`, see `SyncScheduler`'s `toTInteger`). They are
 * optional: a `ONE_SHOT` with no delay carries none.
 */
interface ScheduleSpec : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** [KIND_ONE_SHOT] or [KIND_PERIODIC]. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** PERIODIC only — floor on the tick interval. */
    val minPeriodMs: TInteger?
        get() = getOptionalField(Fields.MIN_PERIOD_MS)

    /** PERIODIC only — window the OS may shift within. */
    val flexPeriodMs: TInteger?
        get() = getOptionalField(Fields.FLEX_PERIOD_MS)

    /** ONE_SHOT and PERIODIC — minimum wait before the first run. */
    val initialDelayMs: TInteger?
        get() = getOptionalField(Fields.INITIAL_DELAY_MS)

    companion object {
        const val ID = "tenshu/sync/schedule/scheduleSpec"

        const val KIND_ONE_SHOT = "ONE_SHOT"
        const val KIND_PERIODIC = "PERIODIC"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.KIND)
                int(Fields.MIN_PERIOD_MS, isOptional = true)
                int(Fields.FLEX_PERIOD_MS, isOptional = true)
                int(Fields.INITIAL_DELAY_MS, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            kind: String,
            minPeriodMs: Long? = null,
            flexPeriodMs: Long? = null,
            initialDelayMs: Long? = null
        ): ScheduleSpec = context.create(
            type = ID,
            fields = listOf(
                kind.toTString(context),
                minPeriodMs?.toTInteger(context),
                flexPeriodMs?.toTInteger(context),
                initialDelayMs?.toTInteger(context)
            )
        )
    }

    object Fields {
        const val KIND = "$ID/kind"
        const val MIN_PERIOD_MS = "$ID/minPeriodMs"
        const val FLEX_PERIOD_MS = "$ID/flexPeriodMs"
        const val INITIAL_DELAY_MS = "$ID/initialDelayMs"
    }
}
