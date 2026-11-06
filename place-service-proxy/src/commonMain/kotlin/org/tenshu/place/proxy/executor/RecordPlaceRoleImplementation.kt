package org.tenshu.place.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.RecordPlaceRole

internal class RecordPlaceRoleImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val role: PlaceRole = params.read(RecordPlaceRole.Params.ROLE)

        // `ExecutionContext.saveData` — fires the SyncObserver (outbound SyncBlock) and the generic
        // RelationMaintainer (adds to `PlaceService.Relations.ROLES`). Never `Database.saveData`,
        // which bypasses both, leaving the row local and invisible to the roles stream.
        executionContext.saveData(role)

        return null as T
    }
}
