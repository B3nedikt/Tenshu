package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTByteArray
import org.tenshu.sync.ReadContentRange

/**
 * D2b streaming (`drive-streaming-preview.md` §3.5): reads a plaintext byte range from an open
 * stream session, decrypting only the chunk(s) the range touches. A typed failure when the
 * handle is unknown/closed or a covering chunk fails its per-chunk integrity gate (tamper /
 * missing blob) — callers never see partial/forged bytes. Bytes at/after EOF come back empty.
 */
internal class ReadContentRangeImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val handle: TString = params.read(ReadContentRange.Params.HANDLE)
        val offset: TInteger = params.read(ReadContentRange.Params.OFFSET)
        val length: TInteger = params.read(ReadContentRange.Params.LENGTH)

        val resolver = executionContext.context.onDemandResolution()
            ?: throw TenshuException(
                GenericError.create(executionContext.context, "OnDemand resolution unavailable.")
            )

        val bytes = resolver.readContentRange(handle.get(), offset.get(), length.get().toInt())
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Content range read failed — stream closed or chunk integrity gate.",
                )
            )

        return bytes.toTByteArray(executionContext.context) as T
    }
}
