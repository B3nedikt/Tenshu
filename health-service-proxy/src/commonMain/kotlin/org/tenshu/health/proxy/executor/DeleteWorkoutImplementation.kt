package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.health.DeleteWorkout
import org.tenshu.core.health.Workout
import org.tenshu.health.proxy.workoutStreamsPath
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Deletes one [Workout] by key: the row, its content-plane streams blob, and a tombstone.
 *
 * ⚠⚠ The tombstone is **not** optional and is **not** emitted for us.
 * `SyncObserver.onDataDeleted` is a deliberate no-op — *"Tombstones are emitted explicitly by
 * operation implementations via `emit`"* — so a delete op that skips it deletes locally only
 * and the next pull resurrects the row. `DeleteFileImplementation` is the precedent this follows
 * (`RoutineDeleteSyncSystemTest` pins the same contract for routines).
 *
 * Relation membership needs no hand-written `removeFromArray`: `HealthService.workouts` is a
 * `maintained = true` service relation, so `RelationMaintainer.onDataDeleted` drops the id from
 * the array inside `deleteData`'s transaction. (`DeleteFile` removes by hand only because
 * `Folder.FILES` is an entity-field array, not a service relation.)
 *
 * ⚠ The `WorkoutActivityRollup` maintainer already handles deletion correctly
 * (`HealthProxyInitialization`), so the rollups need no change here. That is *not* true of
 * `LatestSampleMaintainer.onDataDeleted`, which is an intentional no-op justified by samples
 * having no delete surface — relevant if a `deleteHealthSample` ever follows this.
 */
internal class DeleteWorkoutImplementation(
    private val fileDatabase: FileDatabase,
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val workoutId: TString = params.read(DeleteWorkout.Params.WORKOUT_ID)

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Workout.ID,
            keyValue = workoutId.get(),
        )

        // 1. The row (+ relation membership, inside deleteData's transaction).
        executionContext.deleteData(dataId)

        // 2. The content-plane blob. `Workout.Streams` lives in the external byte store, not on
        // the entity, so deleting the row alone would orphan it. `deleteFile` is a no-op when
        // there is nothing there (a summary workout never had a blob).
        fileDatabase.deleteFile(workoutStreamsPath(workoutId.get()))

        // 3. The tombstone — see the ⚠⚠ in the class KDoc. Receivers short-circuit any merge for
        // this entity at HLC ≤ tombstone.hlc.
        // ⚠⚠ The id form: only the emitter can derive the relay-visible name
        // (`HMAC(K_entity, identity)`). Named locally, this tombstone matches nothing on any peer
        // and the workout stays on every other device — silently, since both sides still sync.
        tombstoneEmitter?.emit(
            targetEntityId = dataId,
            targetEntityType = Workout.ID,
        )

        return null as T
    }
}
