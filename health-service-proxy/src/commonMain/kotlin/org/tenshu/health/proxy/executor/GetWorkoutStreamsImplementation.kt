package org.tenshu.health.proxy.executor

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.health.GetWorkoutStreams
import org.tenshu.core.health.Workout

/**
 * Resolves [Workout.Streams] on demand from the external [FileDatabase],
 * keyed by `/.health/streams/{workoutId}` — the health twin of `GetDataImplementation` (which reads a
 * file's bytes by its path). Registered in [healthServiceProxyImplementationFactory].
 *
 * `null` when the blob is not present locally (a summary-only workout, or bytes not yet fetched from
 * the relay) — the caller treats that as "no route", exactly as `File.Data` returning null means
 * "no local bytes".
 */
internal class GetWorkoutStreamsImplementation(
    private val fileDatabase: FileDatabase,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val workout = caller as? Composite
            ?: (params?.get(GetWorkoutStreams.Params.WORKOUT) as? Composite)
            ?: error("Parameter ${GetWorkoutStreams.Params.WORKOUT} is required")

        val workoutId: String = when (workout) {
            is Workout -> workout.getField<TString>(Workout.Fields.WORKOUT_ID).get()
            else -> (workout.getOptionalField(Workout.Fields.WORKOUT_ID) as? TString)?.get()
                ?: return null as T
        }

        val bytes = fileDatabase.readBytes(org.tenshu.health.proxy.workoutStreamsPath(workoutId)) ?: return null as T
        return bytes as T
    }

}
