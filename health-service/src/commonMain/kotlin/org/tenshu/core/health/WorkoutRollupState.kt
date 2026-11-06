package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * One row recording **which generation of the derivation** produced the current
 * [WorkoutActivityRollup] rows.
 *
 * A derived view is only as correct as the code that derived it, and a device keeps whatever its
 * *past* code wrote — the rollup is computed once per store and then maintained incrementally, so a
 * bug fixed in the maintainer does not repair the rows already on disk. That gap is not
 * hypothetical: the first derivation left 625 of 10 517 workouts counted but unattributed (a
 * committed count whose attribution write was lost to a process kill; see
 * `WorkoutActivityRollupMaintainer`), and no amount of correct code afterwards would have healed
 * them.
 *
 * So the rebuild lever is a stamp, not a hope: bump [WorkoutActivityRollup.ROLLUP_GENERATION] and
 * every device re-derives once, on its next proxy init. The cost is one thin scan of the workout
 * relation — the same scan the initial derivation pays.
 *
 * Locally derived and never synced, like everything else in this rollup: each device stamps its own
 * generation because each device derives its own rows.
 */
interface WorkoutRollupState : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Content-address key — a constant, so this type has exactly one row. */
    val stateKey: TString
        get() = getField(Fields.STATE_KEY)

    /** The [WorkoutActivityRollup.ROLLUP_GENERATION] that derived the current rows. */
    val generation: TInteger
        get() = getField(Fields.GENERATION)

    companion object {
        const val ID = "tenshu/health/workoutRollupState"

        /** The single row's key — this is a per-store singleton. */
        const val SINGLETON_KEY = "workout-rollup-state"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // No Syncable trait — locally derived (see class KDoc).
            traits = listOf(WorkoutRollupStateDataIdProvider.Schema),
            fields = {
                string(Fields.STATE_KEY)
                int(Fields.GENERATION)
            }
        )

        fun create(
            context: TContext,
            generation: Long,
        ): WorkoutRollupState = context.create(
            type = ID,
            fields = listOf(SINGLETON_KEY, generation.toTInteger(context)),
        )
    }

    object Fields {
        const val STATE_KEY = "$ID/stateKey"
        const val GENERATION = "$ID/generation"
    }
}

/** Content-addresses the single [WorkoutRollupState] row by its constant key. */
interface WorkoutRollupStateDataIdProvider : CompositeDataIdProvider<WorkoutRollupState> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/workoutRollupState/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = WorkoutRollupState.Fields.STATE_KEY
        )
    }
}
