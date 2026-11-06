package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import org.tenshu.health.proxy.executor.GetWorkoutStreamsImplementation
import org.tenshu.health.proxy.workoutStreamsPath
import org.tenshu.health.proxy.executor.RecordWorkoutImplementation
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

// Content-plane streams coverage (read-scaling option B): the blob must land in the
// external FileDatabase — never on the Workout entity — and resolve back through
// the GetWorkoutStreams on-demand resolver.
class RecordWorkoutStreamsUnitTest {

    private fun workout(context: org.tenshu.core.core.TContext) = Workout.create(
        context = context,
        workoutId = "workout-streams-1",
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

    @Test
    fun streamsLandInTheFileDatabaseAndResolveBack() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context
        val blob = byteArrayOf(1, 2, 3, 4, 5, -1, 0, 42)

        val impl = RecordWorkoutImplementation(testProxy.fileDatabase)
        impl.execute<Data?>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(
                RecordWorkout.Params.WORKOUT to workout(context),
                RecordWorkout.Params.STREAMS to TByteArrayDto(blob),
            ),
        )

        // The blob lives in the external store, keyed by workoutId…
        assertContentEquals(
            blob,
            testProxy.fileDatabase
                .readBytes(workoutStreamsPath("workout-streams-1"))
                ?.get(),
        )

        // …and resolves back through the on-demand resolver op.
        val resolved = GetWorkoutStreamsImplementation(testProxy.fileDatabase)
            .execute<TByteArray?>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = workout(context),
                params = null,
            )
        assertContentEquals(blob, resolved?.get())

        // The entity row itself carries no streams payload (the point of option B):
        // the saved Workout resolves a null Streams without the blob present…
        val saved = testProxy.executionContext.database.getData(
            org.tenshu.core.entity.DataId.StringDataId.create(
                context = context,
                type = Workout.ID,
                keyValue = "workout-streams-1",
            ),
        )
        checkNotNull(saved)
    }

    @Test
    fun summaryOnlyWorkoutResolvesNullStreams() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        RecordWorkoutImplementation(testProxy.fileDatabase).execute<Data?>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RecordWorkout.Params.WORKOUT to workout(context)),
        )

        val resolved = GetWorkoutStreamsImplementation(testProxy.fileDatabase)
            .execute<TByteArray?>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = workout(context),
                params = null,
            )
        assertNull(resolved)
    }
}
