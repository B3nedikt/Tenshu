package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation

internal class GetAllImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sequence = executionContext.resolveList(caller)
            ?: throw TenshuException(
                InvalidCallerError.create(
                    context = executionContext.context,
                    message = "Caller is not a list: $caller"
                )
            )

        return sequence.toList().toTList(executionContext.context) as T
    }
}
