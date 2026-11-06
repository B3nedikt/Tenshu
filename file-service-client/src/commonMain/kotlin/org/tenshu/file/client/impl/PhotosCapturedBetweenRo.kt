package org.tenshu.file.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.system.PhotosCapturedBetween
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType

internal class PhotosCapturedBetweenRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PhotosCapturedBetween {

    override fun photosCapturedBetween(startMs: Long, endMs: Long, limit: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    PhotosCapturedBetween.Params.START_MS to startMs.toTInteger(context),
                    PhotosCapturedBetween.Params.END_MS to endMs.toTInteger(context),
                    PhotosCapturedBetween.Params.LIMIT to limit.toLong().toTInteger(context),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TList<CapturedPhoto>>(
            type = listType(CapturedPhoto.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
