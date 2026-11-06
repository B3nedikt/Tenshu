package org.tenshu.file.client.impl.share

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

/**
 * Client-side RequestObject builder for [ShareLink]. Mirrors `CreateFolderRo`
 * for the string param and `GetPreviewRo` for the numeric params.
 *
 * Result type is `ShareLinkResult.Schema` rather than `Data.Schema` so the
 * UI layer projects fields off the typed return without an unchecked cast.
 */
internal class ShareLinkRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ShareLink {

    override fun shareLink(
        path: String,
        expiresAtMillis: Long,
        maxOpens: Long,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ShareLink.Params.PATH to FilePath.create(context, path),
                    ShareLink.Params.EXPIRES_AT_MILLIS to expiresAtMillis.toTInteger(context),
                    ShareLink.Params.MAX_OPENS to maxOpens.toTInteger(context),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<ShareLinkResult>(
            type = ShareLinkResult.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
