package org.tenshu.health.proxy

import org.tenshu.core.entity.getDataIdHash
import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.DeleteWorkout
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.health.Workout
import org.tenshu.core.toTString
import org.tenshu.health.test.createHealthProxy
import org.tenshu.sync.common.TombstoneEmitter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠ The tombstone assertions are the point of this file. A delete that removes the row and
 * emits nothing passes any store-only test while being **wrong on a synced realm** — the next
 * pull resurrects the row. `DeleteRoutineImplementation` and `DeleteEventImplementation` both
 * shipped with exactly that gap, so "the row is gone locally" is demonstrably not enough evidence.
 */
class DeleteWorkoutUnitTest {

    private class RecordingTombstoneEmitter : TombstoneEmitter {
        /** ⚠ A fake with no realm: it names locally and therefore does NOT cover the wire (EI-1). */
        override fun emit(
            targetEntityId: org.tenshu.core.entity.DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String? = emit(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
        )

        val emitted = mutableListOf<Pair<String, String>>()
        override fun emit(
            targetEntityIdHash: String,
            targetEntityType: String,
            revokedDeviceId: String?
        ): String {
            emitted += targetEntityIdHash to targetEntityType
            return "block-${++emitCount}"
        }

        private var emitCount = 0
    }

    private fun workout(context: org.tenshu.core.core.TContext, id: String) = Workout.create(
        context = context,
        workoutId = id,
        activityType = "Running",
        source = "Recorded",
        start = UnixInstantDto(1_800_000_000_000L),
        end = UnixInstantDto(1_800_003_600_000L),
        durationSec = 3600,
    )

    @Test
    fun deleteWorkoutRemovesTheRowAndEmitsATombstone() = runTest {
        val emitter = RecordingTombstoneEmitter()
        val testProxy = createHealthProxy(this, tombstoneEmitter = emitter)
        val context = testProxy.executionContext.context

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(RecordWorkout.ID),
                *testProxy.serializer.serialize(workout(context, "w-1")),
            )
        )

        val resultBytes = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(DeleteWorkout.ID),
                *testProxy.serializer.serialize("w-1".toTString(context)),
            )
        )
        assertContentEquals(byteArrayOf(*idToUuid(TResult.Success.ID)), resultBytes)

        assertEquals(emptyList(), readWorkoutIds(testProxy, flowId = 1L))

        assertEquals(1, emitter.emitted.size, "exactly one tombstone per delete operation")
        assertEquals(Workout.ID, emitter.emitted.single().second)
        assertTrue(emitter.emitted.single().first.isNotEmpty())
    }

    @Test
    fun deleteWorkoutLeavesOtherWorkoutsAlone() = runTest {
        val emitter = RecordingTombstoneEmitter()
        val testProxy = createHealthProxy(this, tombstoneEmitter = emitter)
        val context = testProxy.executionContext.context

        listOf("w-1", "w-2").forEach { id ->
            testProxy.handleRequest(
                byteArrayOf(
                    *idToUuid(HealthService.ID),
                    *idToUuid(RecordWorkout.ID),
                    *testProxy.serializer.serialize(workout(context, id)),
                )
            )
        }

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(DeleteWorkout.ID),
                *testProxy.serializer.serialize("w-1".toTString(context)),
            )
        )

        // The survivor is still in the relation — deleteData drops only the keyed row, and
        // RelationMaintainer removes only that id from HealthService.workouts.
        assertEquals(listOf("w-2"), readWorkoutIds(testProxy, flowId = 2L))
        assertEquals(1, emitter.emitted.size)
    }

    @Test
    fun deletingAnAbsentWorkoutIsANoOpThatStillSucceeds() = runTest {
        val emitter = RecordingTombstoneEmitter()
        val testProxy = createHealthProxy(this, tombstoneEmitter = emitter)
        val context = testProxy.executionContext.context

        val resultBytes = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(DeleteWorkout.ID),
                *testProxy.serializer.serialize("never-existed".toTString(context)),
            )
        )

        // Idempotent by key: a replayed delete (or one racing a peer's) must not fail the caller.
        assertContentEquals(byteArrayOf(*idToUuid(TResult.Success.ID)), resultBytes)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readWorkoutIds(
        testProxy: org.tenshu.core.common.TestProxy,
        flowId: Long,
    ): List<String> {
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.WORKOUTS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )
        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        if (valueUpdates.isEmpty()) return emptyList()
        return (valueUpdates.last().payload as TList<Workout>).toList()
            .map { it.workoutId.get() }
    }
}
