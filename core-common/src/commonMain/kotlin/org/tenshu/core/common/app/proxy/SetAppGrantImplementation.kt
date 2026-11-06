package org.tenshu.core.common.app.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.SetAppGrant
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read

/**
 * Upserts the [AppGrant] row for `(appId, serviceId, operationId)` with the
 * user's tri-state decision (`tenshu-rei.md` §3.3). Keyed on the derived
 * `compositeKey`, so a save overwrites any prior decision for the same
 * capability. `AppGrant` is `Syncable`, so the decision replicates realm-wide.
 */
internal class SetAppGrantImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val appId: String = params.read<TString>(SetAppGrant.Params.APP_ID).get()
        val serviceId: String = params.read<TString>(SetAppGrant.Params.SERVICE_ID).get()
        val operationId: String = params.read<TString>(SetAppGrant.Params.OPERATION_ID).get()
        val grant: String = params.read<TString>(SetAppGrant.Params.GRANT).get()

        executionContext.saveData(
            AppGrant.create(
                context = executionContext.context,
                appId = appId,
                serviceId = serviceId,
                operationId = operationId,
                grant = grant,
            )
        )

        return null as T
    }
}
