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
 * The **maintained per-activity workout rollup** (`fit-read-scaling.md` Phases 3/4, the
 * [LatestSample] pattern): one row per `activityType` holding how many workouts of that type
 * exist — so a "what have you done" surface reads ~20 rows instead of the whole workout history.
 *
 * **Why this exists.** Timeline's Activities tab (and its Home feed, transitively) grouped *every*
 * workout by type just to count them. At ~10.5k workouts — one full Health-Connect history import —
 * that read stopped completing at all: the scan degraded quadratically (2 000 rows ≈ 338 s; 10.5k
 * never finished), and because Home `combine`s the workout read with four others, the one unfinished
 * read blanked the entire screen. A count is the canonical cross-row aggregate: precompute it.
 *
 * **Locally derived, never synced.** Deliberately NOT `Syncable`, exactly like [LatestSample]:
 * the maintainer is a `DataObserver` on the save path, which carries both this device's writes and
 * rows arriving via sync, so every device derives an identical rollup. Syncing it would only add
 * convergence noise to data every peer can compute.
 *
 * **Exact under re-saves.** A count cannot be a blind increment — the same workout is saved again
 * on every sync re-delivery and on a re-run of an import, and [LatestSample]'s idempotence comes
 * free only because "newest wins" is a max. Exactness here rides [WorkoutRollupAttribution]: one
 * tiny row per workout recording which type it was last counted under, so the maintainer can tell
 * a first sight (increment) from a re-save (no-op) from a changed type (decrement + increment) —
 * and a delete decrements the right bucket. See `WorkoutActivityRollupMaintainer`.
 */
interface WorkoutActivityRollup : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The activity type this row counts — the content-address key (`Running`, `Cycling`, …). */
    val activityType: TString
        get() = getField(Fields.ACTIVITY_TYPE)

    /** How many workouts of [activityType] this device knows about. Never negative. */
    val count: TInteger
        get() = getField(Fields.COUNT)

    companion object {
        const val ID = "tenshu/health/workoutActivityRollup"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // No Syncable trait — locally derived (see class KDoc).
            traits = listOf(WorkoutActivityRollupDataIdProvider.Schema),
            fields = {
                string(Fields.ACTIVITY_TYPE)
                int(Fields.COUNT)
            }
        )

        /**
         * Which generation of the derivation the maintainer should produce. **Bump this whenever
         * the way these rows are derived changes** — every device then re-derives once, on its
         * next proxy init, instead of keeping whatever its past code wrote (see
         * [WorkoutRollupState]). Cost of a bump is one thin scan of the workout relation per
         * device.
         *
         * - `1` — the original derivation. Left counted-but-unattributed workouts behind when a
         *   process kill dropped the attribution write (625 of 10 517 on the device it was first
         *   run against); those rows could double-count on a re-save.
         * - `2` — count and attribution commit in one transaction, so the two cannot drift.
         */
        const val ROLLUP_GENERATION = 2L

        fun create(
            context: TContext,
            activityType: String,
            count: Long,
        ): WorkoutActivityRollup = context.create(
            type = ID,
            fields = listOf(activityType, count.toTInteger(context)),
        )
    }

    object Fields {
        const val ACTIVITY_TYPE = "$ID/activityType"
        const val COUNT = "$ID/count"
    }
}

/** Content-addresses a [WorkoutActivityRollup] by its activity type — one row per type. */
interface WorkoutActivityRollupDataIdProvider : CompositeDataIdProvider<WorkoutActivityRollup> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/workoutActivityRollup/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = WorkoutActivityRollup.Fields.ACTIVITY_TYPE
        )
    }
}
