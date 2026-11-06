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
import org.tenshu.core.entity.DataId
import org.tenshu.sync.ContentStreamHandle
import org.tenshu.sync.OpenContentStream

/**
 * D2b streaming (`drive-streaming-preview.md` §3.5): opens a seekable content-stream session
 * for the OnDemand byte property `(entityType, entityKey, propertyId)` and returns a
 * [ContentStreamHandle]. Delegates to [org.tenshu.core.common.proxy.OnDemandResolution.openContentStream]
 * — the same realm-routed fetch the reassemble path uses, but the manifest is captured into a
 * session instead of materialised to disk. A typed failure when the file isn't streamable yet
 * (unsynced / locked).
 */
internal class OpenContentStreamImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val entityType: TString = params.read(OpenContentStream.Params.ENTITY_TYPE)
        val entityKey: TString = params.read(OpenContentStream.Params.ENTITY_KEY)
        val propertyId: TString = params.read(OpenContentStream.Params.PROPERTY_ID)

        val entityId = DataId.StringDataId.create(
            context = executionContext.context,
            type = entityType.get(),
            keyValue = entityKey.get(),
        )

        val resolver = executionContext.context.onDemandResolution()
            ?: throw TenshuException(
                GenericError.create(executionContext.context, "OnDemand resolution unavailable.")
            )

        val opening = resolver.openContentStream(executionContext, entityId, propertyId.get())
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Content stream unavailable — file not synced yet or realm locked.",
                )
            )

        return ContentStreamHandle.create(
            context = executionContext.context,
            handle = opening.handle,
            totalSize = opening.totalSize,
        ) as T
    }
}
