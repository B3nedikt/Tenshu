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
import org.tenshu.inference.FaceGroups
import org.tenshu.inference.GroupedFace

internal class FaceGroupsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), FaceGroups {

    override fun faceGroups(limit: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    FaceGroups.Params.LIMIT to limit.toLong().toTInteger(context),
                ),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject<TList<GroupedFace>>(
            type = listType(GroupedFace.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
