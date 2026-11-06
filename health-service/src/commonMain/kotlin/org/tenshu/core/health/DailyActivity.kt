package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **per-day activity aggregate** — the daily steps / distance / energy / active-minutes
 * roll-up (`docs/proposals/timeline-services.md` §4; `fit-app.md` §5 `DailyAggregate`). The
 * store behind the Fit app's Home today-strip + trend and the Steps category exemplar.
 *
 * Distinct from [Workout] (one *session*): a `DailyActivity` is the **whole-day** total a step
 * counter / Health-Connect daily aggregate produces, so it carries `steps` — which no `Workout`
 * does — and its distance/energy already fold in that day's workouts. The Fit projector prefers
 * a stored `DailyActivity` over its workout-derived fallback (§6), so a day with a real step
 * count reads truthfully rather than `0`.
 *
 * Keyed by [date] (ISO-8601 `YYYY-MM-DD`) via [DailyActivityDataIdProvider], so a re-import of
 * the same day — including concurrently on two devices — folds to one row. `Syncable(Eager)`;
 * `RealmData` deferred (as [Workout] also skips it) — opt in when it should surface in Control's
 * Data section.
 */
interface DailyActivity : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The calendar day this aggregate is for — ISO-8601 `YYYY-MM-DD`. The primary key. */
    val date: TString
        get() = getField(Fields.DATE)

    /** Step count for the day; `0` when unknown / no source. */
    val steps: TInteger
        get() = getField(Fields.STEPS)

    /** Total distance in metres for the day; `0` when unknown. */
    val distanceM: TInteger
        get() = getField(Fields.DISTANCE_M)

    /** Active energy in kilojoules for the day; `0` when unknown. */
    val activeEnergyKj: TInteger
        get() = getField(Fields.ACTIVE_ENERGY_KJ)

    /** WHO moderate + vigorous minutes for the day, computed on-device; `0` when unknown. */
    val activeMinutes: TInteger
        get() = getField(Fields.ACTIVE_MINUTES)

    companion object {
        const val ID = "tenshu/health/dailyActivity"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(DailyActivityDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DATE)
                int(Fields.STEPS)
                int(Fields.DISTANCE_M)
                int(Fields.ACTIVE_ENERGY_KJ)
                int(Fields.ACTIVE_MINUTES)
            }
        )

        fun create(
            context: TContext,
            date: String,
            steps: Long = 0,
            distanceM: Long = 0,
            activeEnergyKj: Long = 0,
            activeMinutes: Long = 0,
        ): DailyActivity = context.create(
            type = ID,
            fields = listOf(
                date,
                steps.toTInteger(context),
                distanceM.toTInteger(context),
                activeEnergyKj.toTInteger(context),
                activeMinutes.toTInteger(context),
            )
        )
    }

    object Fields {
        const val DATE = "$ID/date"
        const val STEPS = "$ID/steps"
        const val DISTANCE_M = "$ID/distanceM"
        const val ACTIVE_ENERGY_KJ = "$ID/activeEnergyKj"
        const val ACTIVE_MINUTES = "$ID/activeMinutes"
    }
}

val Entity<out DailyActivity>.date: TString
    get() = getFieldValue(DailyActivity.Fields.DATE)

/**
 * Content-addresses a [DailyActivity] by its [DailyActivity.date], so re-importing the same
 * day — including concurrently on two devices — folds to a single entity rather than duplicating.
 */
interface DailyActivityDataIdProvider : CompositeDataIdProvider<DailyActivity> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/dailyActivity/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = DailyActivity.Fields.DATE
        )
    }
}
