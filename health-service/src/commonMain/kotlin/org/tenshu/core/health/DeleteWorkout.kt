package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Deletes one [Workout] row by its [Workout.workoutId], plus its content-plane
 * `Workout.Streams` blob, and emits a tombstone (ADR 0006) so the deletion converges on
 * every paired device.
 *
 * Until this op existed there was **no way to delete a workout at all**: `:health-service`
 * was nine upserts and a blob read, and the only deletion path was the whole-type realm-wide
 * `DeleteRealmData` — whose `createdBefore` narrowing cannot even work here, because
 * `Workout.Start` is a field alias that `Entity.getProperty` cannot read
 * (`HealthServiceCommonModule` §"Workout.Start"). See
 * `docs/proposals/fit-automatic-activity-detection.md` §5.2.
 *
 * ⚠ Deletion is per-row and by key. There is deliberately no list variant: a batch parameter
 * is permanent wire surface bought against a cost nobody has measured, and the callers today
 * (a user tapping Delete, a supersession pass with nothing waiting on it) do not need one.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.deleteWorkout(workoutId: String): TResult<Data?> =
    findOperation<DeleteWorkout>(DeleteWorkout.ID)
        .deleteWorkout(workoutId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteWorkout : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteWorkout(workoutId: String): RequestObject

    companion object {
        const val ID = "tenshu/health/service/deleteWorkout"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                string(id = Params.WORKOUT_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val WORKOUT_ID = "$ID/workoutId"
    }
}
