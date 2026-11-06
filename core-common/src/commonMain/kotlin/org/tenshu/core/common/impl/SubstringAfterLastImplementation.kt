package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.SubstringAfterLast
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.substringAfterLastLogic

internal class SubstringAfterLastImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        checkNotNull(caller)

        val separator = params
            ?.get(SubstringAfterLast.Params.SEPARATOR)
            ?: error("Param ${SubstringAfterLast.Params.SEPARATOR} not provided")

        return substringAfterLastLogic(caller as Data, separator) as T
    }
}
