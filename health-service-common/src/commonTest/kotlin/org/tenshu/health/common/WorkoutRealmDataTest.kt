package org.tenshu.health.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.Start
import org.tenshu.core.health.Workout
import org.tenshu.core.trait.RealmData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Workout` opts into `RealmData` (`docs/proposals/core-realm-data.md` §4 / §8) so it surfaces
 * as its own card in Control's **Data** section and is resettable there
 * (`docs/proposals/control-data-reset.md` R1). Two things have to hold for that, and both are
 * easy to break silently: the marker must be *declared* (enumeration reads declared, not
 * inherited, traits) and `RealmData.CREATED_AT` must resolve — without the binding the
 * `createdBefore` narrowing is rejected at the op.
 *
 * The last test pins the reverse walk `DeleteRealmData` depends on: a type whose rows live in
 * no service-data array cannot be reached by the generic delete at all (`File` is exactly that
 * case), so "Workout has one" is a precondition of the Data-section action, not a detail.
 */
class WorkoutRealmDataTest {

    private val registry =
        createCoreTestRegistry().apply { registerConfig(healthServiceCommonModule) }

    @Test
    fun realmDataTypeIds_containsWorkout() {
        assertTrue(
            Workout.ID in registry.realmDataTypeIds(),
            "Workout must declare the RealmData marker: ${registry.realmDataTypeIds()}",
        )
    }

    @Test
    fun resolveCreatedAt_bindsWorkoutStart() {
        assertEquals(
            Workout.Start.id,
            registry.resolveValueRequirement(Workout.ID, RealmData.CREATED_AT)?.id,
            "Workout.Start must fulfil RealmData.CREATED_AT — a workout is dated by when its " +
                "session started, and that is the field the read layer already orders by",
        )
    }

    @Test
    fun workoutRowsAreReachableThroughAServiceDataArray() {
        val bindings = registry.serviceDataArraysHolding(Workout.ID)

        assertEquals(1, bindings.size, "exactly one array holds workouts")
        assertEquals(Workout.ID, bindings.single().elementTypeId)
        assertEquals(
            HealthService.Relations.WORKOUTS,
            bindings.single().ref.get(),
            "the walk must land on HealthService.workouts",
        )
    }
}
