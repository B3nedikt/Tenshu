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
import org.tenshu.inference.PetGroups
import org.tenshu.inference.GroupedFace

/** Pets PT-3b: the face RO's twin (`photos-pets.md` §3). */
internal class PetGroupsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PetGroups {

    override fun petGroups(limit: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    PetGroups.Params.LIMIT to limit.toLong().toTInteger(context),
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
