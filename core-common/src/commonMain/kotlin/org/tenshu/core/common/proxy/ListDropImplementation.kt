package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger

internal class ListDropImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        // Phase 2 of `docs/proposals/core-error-channel.md`: dispatcher enforces
        // presence + type of `Drop.Params.N` (declared `int(Params.N)` — non-optional).
        val n = params.read<TInteger>(Drop.Params.N).get().toInt()

        val sequence = executionContext.resolveList(caller)
            ?: throw TenshuException(
                InvalidCallerError.create(
                    context = executionContext.context,
                    message = "Caller is not an array. Caller: $caller"
                )
            )

        val list = sequence.drop(n).toList()

        return list.toTList(executionContext.context) as T
    }
}
