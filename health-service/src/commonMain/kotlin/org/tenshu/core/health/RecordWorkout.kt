package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records (upserts) a [Workout]. Idempotent by [Workout.workoutId] (the
 * `WorkoutDataIdProvider` folds re-imports to one row). Persists via
 * `ExecutionContext.saveData` on the proxy side → the workout syncs eagerly to every paired
 * device.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.recordWorkout(
    workout: Workout,
    streams: TByteArray? = null,
): TResult<Data?> =
    findOperation<RecordWorkout>(RecordWorkout.ID)
        .recordWorkout(workout, streams)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordWorkout : Operation {

    override val schema: OperationSchema get() = Schema

    /**
     * [streams] is the optional dense route + HR blob (`WorkoutStreamsCodec`-encoded), stored on
     * the workout's `Workout.Streams` property. `null` for a summary-only workout (bare GPX /
     * Health-Connect header). Optional param → additive on the op wire.
     */
    fun recordWorkout(workout: Workout, streams: TByteArray? = null): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordWorkout"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.WORKOUT, type = Workout.Schema)
                byteArray(id = Params.STREAMS, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val WORKOUT = "$ID/workout"
        const val STREAMS = "$ID/streams"
    }
}
