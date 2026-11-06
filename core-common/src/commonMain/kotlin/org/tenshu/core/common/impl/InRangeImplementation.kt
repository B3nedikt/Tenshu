package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.InRange
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.inRangeLogic

internal class InRangeImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        checkNotNull(caller)

        val start = params
            ?.get(InRange.Params.START_INCLUSIVE)
            ?: error("Param ${InRange.Params.START_INCLUSIVE} not provided")

        val end = params[InRange.Params.END_EXCLUSIVE]
            ?: error("Param ${InRange.Params.END_EXCLUSIVE} not provided")

        return inRangeLogic(caller as Data, start, end) as T
    }
}
