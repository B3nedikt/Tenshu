package org.tenshu.ingest.client.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.ingest.PickSecurityScopedFolder
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class PickSecurityScopedFolderRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PickSecurityScopedFolder {
    override fun pickSecurityScopedFolder(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(params = emptyMap<String, Data>(), schema = schema),
        )
        return context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
