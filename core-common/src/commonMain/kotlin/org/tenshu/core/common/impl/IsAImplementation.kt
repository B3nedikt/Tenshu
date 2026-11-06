package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.IsA
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.isALogic

internal class IsAImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        checkNotNull(caller)

        val typeId = params.read<TString>(IsA.Params.TYPE_ID).get()

        return isALogic(
            receiver = caller as Data,
            typeId = typeId,
            schemaProvider = executionContext.context.schemaProvider(),
        ) as T
    }
}
