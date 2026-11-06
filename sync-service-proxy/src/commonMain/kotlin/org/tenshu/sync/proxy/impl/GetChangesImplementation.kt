package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger
import org.tenshu.sync.GetChanges
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.SyncRelay

internal class GetChangesImplementation(
    private val relay: SyncRelay
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sinceTimestamp: TInteger = params.read(GetChanges.Params.SINCE_TIMESTAMP)
        val tip: SignedHlcTip = params.read(GetChanges.Params.TIP)

        val result = relay.getChanges(sinceTimestamp.get(), tip)
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }
}
