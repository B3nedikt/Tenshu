package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.number.TInteger

internal class ListStreamEndImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        val streamId = (params!![EndStream.Params.STREAM_ID] as TInteger).get()

        executionContext.removeDatabaseListener(streamId)

        return null as T
    }
}
