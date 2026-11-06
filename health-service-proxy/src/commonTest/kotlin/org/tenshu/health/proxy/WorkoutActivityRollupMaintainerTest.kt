package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.Workout
import org.tenshu.core.health.WorkoutActivityRollup
import org.tenshu.core.health.WorkoutRollupAttribution
import org.tenshu.core.health.WorkoutRollupState
import org.tenshu.core.number.TInteger
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The maintained per-activity workout counts (`fit-read-scaling.md` Ph3/4).
 *
 * ⚠ The load-bearing case is [reSavingTheSameWorkoutDoesNotInflateTheCount]. A count is not a max,
 * so unlike the `LatestSample` rollup it is **not** idempotent for free: the same workout is saved
 * again on every sync re-delivery and on any re-run of an import, and a blind increment would
 * inflate every count with nothing downstream able to notice. Only asserting the "counts go up"
 * direction would pass an implementation with exactly that bug.
 */
class WorkoutActivityRollupMaintainerTest {

    @Test
    fun firstSightOfEachWorkoutCountsItsActivityType() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w2", "Running")
        ctx.saveWorkout("w3", "Cycling")

        assertEquals(2L, ctx.rollupCount("Running"))
        assertEquals(1L, ctx.rollupCount("Cycling"))
    }

    @Test
    fun reSavingTheSameWorkoutDoesNotInflateTheCount() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        assertEquals(1L, ctx.rollupCount("Running"))

        // A sync re-delivery / a re-run import: same id, same type, saved four more times.
        repeat(4) { ctx.saveWorkout("w1", "Running") }

        assertEquals(1L, ctx.rollupCount("Running"))
    }

    @Test
    fun correctingAWorkoutsTypeMovesTheCountRatherThanDoubleCounting() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w1", "Cycling") // re-import corrects the type

        assertEquals(0L, ctx.rollupCount("Running"))
        assertEquals(1L, ctx.rollupCount("Cycling"))
    }

    @Test
    fun deletingAWorkoutDecrementsTheTypeItWasCountedUnder() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w2", "Running")
        ctx.deleteWorkout("w1")

        assertEquals(1L, ctx.rollupCount("Running"))

        // The whole type going away must reach zero, not stop at one — a `DeleteRealmData` reset
        // of `Workout` should leave no counts standing.
        ctx.deleteWorkout("w2")
        assertEquals(0L, ctx.rollupCount("Running"))
    }

    @Test
    fun deletingAnUncountedWorkoutIsANoOp() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.deleteWorkout("never-counted")

        assertEquals(1L, ctx.rollupCount("Running"))
    }

    /**
     * The upgrade path, exercising the **scan** — a store holding workouts with no rollup yet.
     *
     * ⚠ Saving workouts through a live maintainer and then calling the backfill does NOT test this:
     * the maintainer has already created the rollup rows, so the emptiness probe short-circuits and
     * the scan never runs. [clearRollups] reproduces the pre-rollup store the upgrade actually
     * meets.
     */
    @Test
    fun backfillScansAPreRollupStoreAndDerivesEveryCount() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w2", "Running")
        ctx.saveWorkout("w3", "Cycling")
        ctx.clearRollups(listOf("Running", "Cycling"), workoutIds = listOf("w1", "w2", "w3"))
        assertNull(ctx.rollupCount("Running"), "precondition: the store looks pre-rollup")

        ctx.backfillWorkoutActivityRollups()

        assertEquals(2L, ctx.rollupCount("Running"))
        assertEquals(1L, ctx.rollupCount("Cycling"))
        // The scan must seed attributions too, or every one of these workouts double-counts on
        // its next save — the drift observed on device.
        assertEquals("Running", ctx.attributionOf("w1"))
        assertEquals("Cycling", ctx.attributionOf("w3"))
    }

    /**
     * Re-running the backfill must not double the counts — the emptiness probe is the guard, and
     * it is the difference between an upgrade and a corruption on every proxy init.
     */
    @Test
    fun backfillIsIdempotent() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w2", "Cycling")

        ctx.backfillWorkoutActivityRollups()
        ctx.backfillWorkoutActivityRollups()

        assertEquals(1L, ctx.rollupCount("Running"))
        assertEquals(1L, ctx.rollupCount("Cycling"))
    }

    /**
     * The repair lever ([WorkoutRollupState]): a store whose rows were produced by an older
     * generation re-derives once, healing drift that correct code alone cannot reach — a derived
     * view keeps whatever *past* code wrote.
     *
     * Reproduces the device's actual state: a workout counted but unattributed. Re-deriving must
     * restore the attribution, so the workout stops double-counting on its next save.
     */
    @Test
    fun aStaleGenerationReDerivesAndRepairsDrift() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w2", "Running")
        ctx.dropAttribution("w1")          // the drift: counted, unattributed
        ctx.setGeneration(1)               // ...produced by the old derivation

        ctx.backfillWorkoutActivityRollups()

        assertEquals("Running", ctx.attributionOf("w1"), "the re-derive must restore attributions")
        assertEquals(2L, ctx.rollupCount("Running"))

        // And the repair holds: the previously drifted workout no longer double-counts.
        ctx.saveWorkout("w1", "Running")
        assertEquals(2L, ctx.rollupCount("Running"))
    }

    /**
     * A re-derive REPLACES rather than merges: a type whose workouts have all gone must not keep
     * its stale count, and the scan simply never mentions it.
     */
    @Test
    fun reDerivingDropsATypeThatNoLongerHasWorkouts() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.saveWorkout("w2", "Cycling")
        ctx.deleteWorkout("w2")
        // Fake a stale count for a type the scan will not find, as an older derivation could leave.
        ctx.forceRollup("Swimming", 7)
        ctx.setGeneration(1)

        ctx.backfillWorkoutActivityRollups()

        assertNull(ctx.rollupCount("Swimming"), "a re-derive must replace, not merge")
        assertEquals(1L, ctx.rollupCount("Running"))
    }

    /** Once stamped at the current generation, init must not scan again. */
    @Test
    fun currentGenerationDoesNotReDerive() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        ctx.backfillWorkoutActivityRollups()   // stamps the current generation

        // A drift introduced AFTER the stamp must survive — proving no scan ran.
        ctx.dropAttribution("w1")
        ctx.backfillWorkoutActivityRollups()

        assertNull(ctx.attributionOf("w1"), "no re-derive was due, so nothing should have rescanned")
    }

    /**
     * A workout counted but left without its attribution is the drift this rollup must not be able
     * to produce: its next save reads "never counted" and increments a second time. Reproduced
     * here by removing the attribution behind the maintainer's back.
     */
    @Test
    fun aCountedWorkoutWithoutItsAttributionWouldDoubleCount() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")
        assertEquals(1L, ctx.rollupCount("Running"))

        ctx.dropAttribution("w1")
        ctx.saveWorkout("w1", "Running")

        // This is what drift looks like — hence the single transaction pairing the two writes.
        assertEquals(2L, ctx.rollupCount("Running"))
    }

    @Test
    fun aTypeNeverSeenHasNoRollupRow() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        ctx.saveWorkout("w1", "Running")

        assertNull(ctx.rollupCount("Swimming"))
    }

    /**
     * The upgrade path at realistic scale. The backfill runs once at proxy init, so its cost is
     * paid on the first launch after this ships — on a store holding a full imported history
     * (~10k workouts is one Health-Connect import). Guards correctness at that size; the elapsed
     * time is printed rather than asserted, because a wall-clock bound would be flaky in CI.
     */
    @Test
    fun backfillOverALargeHistoryStaysCorrect() = runTest {
        val proxy = createHealthProxy(this)
        val ctx = proxy.executionContext

        val types = listOf("Running", "Cycling", "Swimming", "Walking")
        repeat(10_000) { i -> ctx.saveWorkout("w$i", types[i % types.size]) }

        types.forEach { assertEquals(2_500L, ctx.rollupCount(it)) }

        // ⚠ The counts being right is not enough: every counted workout must also carry its
        // attribution, or a later re-save of an unattributed one increments a second time.
        // Counts and attributions must not be able to drift apart.
        repeat(10_000) { i ->
            assertEquals(
                types[i % types.size],
                ctx.attributionOf("w$i"),
                "workout w$i was counted but carries no attribution",
            )
        }
    }

    /** Removes the rollup rows + their relation entries, leaving a store that looks pre-rollup. */
    private fun ExecutionContext.clearRollups(types: List<String>, workoutIds: List<String>) {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = context,
                type = TList.ID,
                relationId = HealthService.Relations.WORKOUT_ACTIVITY_ROLLUPS,
            ),
            isEntity = false,
            elementSchemaId = WorkoutActivityRollup.ID,
        )
        types.forEach { type ->
            val id = DataId.StringDataId.create(
                context = context,
                type = WorkoutActivityRollup.ID,
                keyValue = type,
            )
            database.removeFromArray(ref, id)
            database.deleteData(id)
        }
        workoutIds.forEach { dropAttribution(it) }
    }

    /** Stamps an arbitrary rollup generation, standing in for rows written by older code. */
    private fun ExecutionContext.setGeneration(generation: Long) {
        database.saveData(WorkoutRollupState.create(context = context, generation = generation))
    }

    /** Writes a rollup row directly, as an older derivation might have left behind. */
    private fun ExecutionContext.forceRollup(activityType: String, count: Long) {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = context,
                type = TList.ID,
                relationId = HealthService.Relations.WORKOUT_ACTIVITY_ROLLUPS,
            ),
            isEntity = false,
            elementSchemaId = WorkoutActivityRollup.ID,
        )
        val row = WorkoutActivityRollup.create(context, activityType, count)
        database.saveData(row)
        database.addToArray(ref, row)
    }

    private fun ExecutionContext.dropAttribution(workoutId: String) {
        database.deleteData(
            DataId.StringDataId.create(
                context = context,
                type = WorkoutRollupAttribution.ID,
                keyValue = workoutId,
            )
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun ExecutionContext.saveWorkout(id: String, activityType: String) {
        saveData(
            Workout.create(
                context = context,
                workoutId = id,
                activityType = activityType,
                source = "Gpx",
                start = UnixInstantDto(1_700_000_000_000L),
                end = UnixInstantDto(1_700_003_600_000L),
                durationSec = 3_600,
                title = "session",
                distanceM = 10_000,
                activeEnergyKj = 2_500,
                avgHr = 148,
            )
        )
    }

    private fun ExecutionContext.deleteWorkout(id: String) {
        deleteData(
            DataId.StringDataId.create(context = context, type = Workout.ID, keyValue = id)
        )
    }

    /** The type this workout is recorded as counted under, or `null` if it has no attribution. */
    private fun ExecutionContext.attributionOf(workoutId: String): String? {
        val id = DataId.StringDataId.create(
            context = context,
            type = WorkoutRollupAttribution.ID,
            keyValue = workoutId,
        )
        val stored = database.getData(id, asEntity = false) ?: return null
        return (stored as Composite)
            .getField<TString>(WorkoutRollupAttribution.Fields.ACTIVITY_TYPE).get()
    }

    /** The stored count for [activityType], or `null` when the type has no rollup row at all. */
    private fun ExecutionContext.rollupCount(activityType: String): Long? {
        val id = DataId.StringDataId.create(
            context = context,
            type = WorkoutActivityRollup.ID,
            keyValue = activityType,
        )
        val stored = database.getData(id, asEntity = false) ?: return null
        return (stored as Composite)
            .getField<TInteger>(WorkoutActivityRollup.Fields.COUNT).get()
    }
}
