package org.tenshu.core.health

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * Read-scaling option B (`docs/proposals/fit-workout-streams-content-plane.md`).
 *
 * The on-demand resolver for [Workout.Streams] once it moves to the content plane — the health-service
 * twin of `File.getData()` / `GetData`. When `Workout.Streams` is a `dynamicProperty(Syncable(OnDemand))`
 * instead of an eager stored field, projecting `relations(Workout.Streams)` dispatches this op, which
 * reads the dense route/HR blob from the external `FileDatabase` (keyed by `/.health/streams/{workoutId}`)
 * rather than from the entity — so the blob never sits in the entity cache and the bulk Takeout import
 * stops OOMing Control.
 *
 * Mirrors `org.tenshu.core.file.GetData` verbatim in shape; only the receiver + key differ.
 */
fun Workout.getStreams(): TByteArray =
    findOperation<GetWorkoutStreams>(GetWorkoutStreams.ID).getStreams(this)

interface GetWorkoutStreams : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getStreams(workout: Workout): TByteArray

    companion object {
        const val ID = "tenshu/health/getWorkoutStreams"

        val Schema = operation(
            id = ID,
            receiver = Workout.Schema.toType(),
            params = {
                composite(id = Params.WORKOUT, type = Workout.Schema)
            },
            traits = listOf(TerminalOperation.Schema),
        )
    }

    object Params {
        const val WORKOUT = "$ID/workout"
    }
}
