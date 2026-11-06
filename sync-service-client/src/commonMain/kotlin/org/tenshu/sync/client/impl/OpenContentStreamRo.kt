package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.OpenContentStream

internal class OpenContentStreamRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), OpenContentStream {

    override val schema: OperationSchema
        get() = OpenContentStream.Schema

    override fun openContentStream(
        entityType: String,
        entityKey: String,
        propertyId: String,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    OpenContentStream.Params.ENTITY_TYPE to entityType.toTString(context),
                    OpenContentStream.Params.ENTITY_KEY to entityKey.toTString(context),
                    OpenContentStream.Params.PROPERTY_ID to propertyId.toTString(context),
                ),
                schema = schema
            )
        )
        // Erase the returned request object to `Data` (the convention every other
        // terminal-op RO follows, e.g. GetSyncStatusRo): the concrete
        // ContentStreamHandle is materialised from the wire response by its DTO/
        // composite factory (syncServiceCommonModule) at execute() time. Passing the
        // concrete type here instead would require a client-side request-object
        // factory for ContentStreamHandle, and without one the call crashes.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
