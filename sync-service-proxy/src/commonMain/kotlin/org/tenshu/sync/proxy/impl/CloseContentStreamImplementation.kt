package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.sync.CloseContentStream

/**
 * D2b streaming (`drive-streaming-preview.md` §3.5): closes a content-stream session and
 * releases its decrypted-chunk buffers. Idempotent — an unknown/already-closed handle is a
 * clean no-op. Non-suspend (pure in-memory registry removal).
 */
internal class CloseContentStreamImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val handle: TString = params.read(CloseContentStream.Params.HANDLE)
        executionContext.context.onDemandResolution()?.closeContentStream(handle.get())
        return null as T
    }
}
