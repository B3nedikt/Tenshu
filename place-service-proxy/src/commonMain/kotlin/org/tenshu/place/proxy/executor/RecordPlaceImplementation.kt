package org.tenshu.place.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.place.Place
import org.tenshu.core.place.RecordPlace

internal class RecordPlaceImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val place: Place = params.read(RecordPlace.Params.PLACE)

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound
        // SyncBlock) and the generic `RelationMaintainer` (adds to
        // `PlaceService.Relations.PLACES`). Never `Database.saveData` /
        // `addDataToArray`, which bypass `SyncObserver` and never propagate.
        executionContext.saveData(place)

        return null as T
    }
}
