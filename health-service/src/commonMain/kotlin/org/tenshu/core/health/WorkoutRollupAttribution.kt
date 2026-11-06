package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * The bookkeeping half of [WorkoutActivityRollup]: which activity type a given workout was last
 * *counted* under. One row per workout, content-addressed by `workoutId`.
 *
 * **Why a row rather than a smarter observer.** `DataObserver.onDataSaved` hands over the row as it
 * now is, with no access to what it replaced, and by then the new row is already stored — so the
 * maintainer cannot otherwise distinguish:
 * - a workout seen for the first time (**increment** its type),
 * - the same workout saved again by a sync re-delivery or a re-run import (**no-op** — the naive
 *   increment that would silently inflate every count),
 * - a workout whose type changed on re-import (**decrement** the old, **increment** the new).
 *
 * Reading this row is one O(1) `getData` by id, which is why the maintainer stays O(1) per save
 * rather than rescanning; `deleteData` on the workout decrements the recorded bucket and drops the
 * attribution, so a `DeleteRealmData` reset of `Workout` leaves the rollup at zero instead of
 * advertising counts for data that is gone.
 *
 * Locally derived and never synced, for the same reason as [WorkoutActivityRollup]: it is a
 * function of the workout rows each device already holds. The cost is one small row per workout
 * (two string fields) — deliberate, and proportionate to the `Workout` row it shadows.
 */
interface WorkoutRollupAttribution : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The workout this attribution is for — the content-address key. */
    val workoutId: TString
        get() = getField(Fields.WORKOUT_ID)

    /** The activity type this workout is currently counted under. */
    val activityType: TString
        get() = getField(Fields.ACTIVITY_TYPE)

    companion object {
        const val ID = "tenshu/health/workoutRollupAttribution"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // No Syncable trait — locally derived (see class KDoc).
            traits = listOf(WorkoutRollupAttributionDataIdProvider.Schema),
            fields = {
                string(Fields.WORKOUT_ID)
                string(Fields.ACTIVITY_TYPE)
            }
        )

        fun create(
            context: TContext,
            workoutId: String,
            activityType: String,
        ): WorkoutRollupAttribution = context.create(
            type = ID,
            fields = listOf(workoutId, activityType),
        )
    }

    object Fields {
        const val WORKOUT_ID = "$ID/workoutId"
        const val ACTIVITY_TYPE = "$ID/activityType"
    }
}

/** Content-addresses a [WorkoutRollupAttribution] by its workout id — one row per workout. */
interface WorkoutRollupAttributionDataIdProvider :
    CompositeDataIdProvider<WorkoutRollupAttribution> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/workoutRollupAttribution/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = WorkoutRollupAttribution.Fields.WORKOUT_ID
        )
    }
}
