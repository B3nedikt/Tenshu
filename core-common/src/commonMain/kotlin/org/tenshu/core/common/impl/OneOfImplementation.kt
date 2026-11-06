package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.OneOf
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.oneOfLogic

internal class OneOfImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        checkNotNull(caller)

        val candidates = params
            ?.get(OneOf.Params.CANDIDATES) as? TList<*>
            ?: error("Param ${OneOf.Params.CANDIDATES} not provided")

        return oneOfLogic(caller as Data, candidates.toList()) as T
    }
}
