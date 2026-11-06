package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.toTBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **single day of menstruation** — one logged bleeding day behind the Fit app's Cycle Browse
 * category (`docs/proposals/fit-app.md` §12 B3b). Import-only in v1 (Health Connect
 * `MenstruationFlowRecord`/`MenstruationPeriodRecord`; HealthKit `menstrualFlow`); Fit doesn't
 * author cycle data itself yet.
 *
 * **Special-category data (GDPR Art. 9), on the sealed plane like every health field** — and the
 * Cycle card is **data-driven** (shown only when such days exist), never gated on a profile sex
 * field, so it stays inclusive (matching how Apple / Health Connect surface cycle tracking).
 *
 * Keyed by [date] (ISO-8601 `YYYY-MM-DD`) via [MenstruationDayDataIdProvider], so a re-import of the
 * same day folds. [flow] is the level string (`Light` / `Medium` / `Heavy` / `Unspecified`); the
 * projector maps it to the UI enum. [periodStart] marks the first day of a period (Health Connect's
 * period-record start / HealthKit's cycle-start metadata), the anchor the "cycle day N" teaser counts
 * from. `Syncable(Eager)`; `RealmData` deferred (as the sibling health entities also skip it).
 */
interface MenstruationDay : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The calendar day this bleeding entry is for — ISO-8601 `YYYY-MM-DD`. The primary key. */
    val date: TString
        get() = getField(Fields.DATE)

    /** The flow level — `Light` / `Medium` / `Heavy` / `Unspecified`. */
    val flow: TString
        get() = getField(Fields.FLOW)

    /** Whether this day is the start of a period (the cycle-day anchor). */
    val periodStart: TBoolean
        get() = getField(Fields.PERIOD_START)

    companion object {
        const val ID = "tenshu/health/menstruationDay"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(MenstruationDayDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DATE)
                string(Fields.FLOW)
                boolean(Fields.PERIOD_START)
            }
        )

        fun create(
            context: TContext,
            date: String,
            flow: String,
            periodStart: Boolean = false,
        ): MenstruationDay = context.create(
            type = ID,
            fields = listOf(
                date,
                flow,
                periodStart.toTBoolean(context),
            )
        )
    }

    object Fields {
        const val DATE = "$ID/date"
        const val FLOW = "$ID/flow"
        const val PERIOD_START = "$ID/periodStart"
    }
}

val Entity<out MenstruationDay>.date: TString
    get() = getFieldValue(MenstruationDay.Fields.DATE)

/**
 * Content-addresses a [MenstruationDay] by its [MenstruationDay.date], so re-importing the same day
 * folds to a single entity rather than duplicating.
 */
interface MenstruationDayDataIdProvider : CompositeDataIdProvider<MenstruationDay> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/menstruationDay/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = MenstruationDay.Fields.DATE
        )
    }
}
