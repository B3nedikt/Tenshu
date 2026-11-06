package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger

internal class ListStreamImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        operationContext as? ArrayOperationContext
            ?: error("Missing ArrayOperationContext")

        val streamId = (params.read<TInteger>(Stream.Params.STREAM_ID)).get()
        // Optional param: absent (older clients / plain streams) ⇒ false.
        val signalOnly = (params?.get(Stream.Params.SIGNAL_ONLY) as? TBoolean)?.get() ?: false

        val arrayDatabaseReference = operationContext.arrayDatabaseReference

        val initialData = executionContext.setDatabaseListener(
            streamId = streamId,
            reference = arrayDatabaseReference,
            executionStack = operationContext.executionStack,
            signalOnly = signalOnly,
        )

        return initialData as T
    }
}
