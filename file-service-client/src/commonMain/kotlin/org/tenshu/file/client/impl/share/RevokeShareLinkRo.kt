package org.tenshu.file.client.impl.share

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.share.RevokeShareLink
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class RevokeShareLinkRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RevokeShareLink {

    override fun revokeShareLink(shareId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RevokeShareLink.Params.SHARE_ID to shareId.toTString(context),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
