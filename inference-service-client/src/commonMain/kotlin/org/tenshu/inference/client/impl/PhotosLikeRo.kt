package org.tenshu.inference.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.core.toTString
import org.tenshu.inference.PhotosLike
import org.tenshu.inference.SimilarPhoto

internal class PhotosLikeRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PhotosLike {

    override fun photosLike(text: String, limit: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    PhotosLike.Params.TEXT to text.toTString(context),
                    PhotosLike.Params.LIMIT to limit.toLong().toTInteger(context),
                ),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject<TList<SimilarPhoto>>(
            type = listType(SimilarPhoto.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
