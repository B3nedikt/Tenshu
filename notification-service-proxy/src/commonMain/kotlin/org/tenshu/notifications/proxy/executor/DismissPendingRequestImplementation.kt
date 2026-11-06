package org.tenshu.notifications.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.notifications.DismissPendingRequest
import org.tenshu.core.notifications.DismissedRequest

internal class DismissPendingRequestImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val requestId: String = params.read<TString>(DismissPendingRequest.Params.REQUEST_ID).get()

        // `DismissedRequest` is `Syncable(Eager)` and keyed by `requestId`, so the
        // single `saveData` fires `SyncObserver` (outbound block → other devices get
        // the "seen" marker) and the generic `RelationMaintainer` (local relation
        // membership → the in-process feed sees it). Re-dismissing the same id
        // overwrites the same entity (keyed), staying idempotent.
        executionContext.saveData(
            DismissedRequest.create(
                context = executionContext.context,
                requestId = requestId,
            )
        )

        return null as T
    }
}
