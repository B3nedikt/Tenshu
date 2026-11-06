package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.health.Workout
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordWorkoutUnitTest {

    @Test
    fun recordWorkoutRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val workout = Workout.create(
            context = context,
            workoutId = "workout-1",
            activityType = "Running",
            source = "Gpx",
            start = UnixInstantDto(1_700_000_000_000L),
            end = UnixInstantDto(1_700_003_600_000L),
            durationSec = 3_600,
            title = "Morning run",
            distanceM = 10_000,
            activeEnergyKj = 2_500,
            avgHr = 148,
        )

        val request = byteArrayOf(
            *idToUuid(HealthService.ID),
            *idToUuid(RecordWorkout.ID),
            *testProxy.serializer.serialize(workout),
        )

        val resultBytes = testProxy.handleRequest(request)
        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = resultBytes,
        )

        // Read back the workouts relation stream.
        val flowId = 1L
        val workoutsRequest = byteArrayOf(
            *idToUuid(HealthService.ID),
            *idToUuid(HealthService.Relations.WORKOUTS),
            *idToUuid(Stream.ID),
            *flowId.toByteArray(),
        )
        testProxy.handleRequest(workoutsRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val workouts = (valueUpdates.first().payload as TList<Workout>).toList()
        assertEquals(1, workouts.size)

        val received = workouts.first()
        assertEquals("workout-1", received.workoutId.get())
        assertEquals("Running", received.activityType.get())
        assertEquals(3_600L, received.durationSec.get())
        assertEquals(10_000L, received.distanceM.get())
        assertEquals(148L, received.avgHr.get())
    }
}
