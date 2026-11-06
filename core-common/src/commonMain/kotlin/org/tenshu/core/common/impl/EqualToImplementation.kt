package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.equalToLogic

internal class EqualToImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        checkNotNull(caller)

        val other = params
            ?.get(EqualTo.Params.OTHER)
            ?: error("Param ${EqualTo.Params.OTHER} not provided")

        return equalToLogic(caller as Data, other) as T
    }
}
