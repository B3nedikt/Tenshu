package org.tenshu.routine.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.routine.DeleteRoutine
import org.tenshu.routine.Routine
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Deletes one [Routine] row **and tombstones it** (ADR 0006).
 *
 * ⚠⚠ The tombstone is ours to emit: `SyncObserver.onDataDeleted` is a deliberate no-op, so a delete
 * that emits none stays on this device, and the next write of the routine from anywhere else brings
 * it back (`RoutineDeleteSyncSystemTest`). A `null` [tombstoneEmitter] deletes locally only — the
 * posture of a sync-less test proxy, never production.
 *
 * ⚠ Deliberately leaves the routine's `RoutineRun` history alone: the Activity
 * tab should still be able to say what a since-deleted routine did, and run rows
 * age out on their own retention.
 */
internal class DeleteRoutineImplementation(
    private val tombstoneEmitter: TombstoneEmitter?,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val routineId: TString = params.read(DeleteRoutine.Params.ROUTINE_ID)

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Routine.ID,
            keyValue = routineId.get(),
        )
        executionContext.deleteData(dataId)
        // ⚠ The id form: since EI-1 only the emitter can derive the relay-visible name.
        tombstoneEmitter?.emit(targetEntityId = dataId, targetEntityType = Routine.ID)

        return null as T
    }
}
