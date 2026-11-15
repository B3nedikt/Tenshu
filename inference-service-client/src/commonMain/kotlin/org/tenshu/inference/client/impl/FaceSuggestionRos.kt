package org.tenshu.inference.client.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.core.toTString
import org.tenshu.inference.FaceSuggestionRejections
import org.tenshu.inference.RejectFaceSuggestion
import org.tenshu.inference.RejectedFace

internal class RejectFaceSuggestionRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RejectFaceSuggestion {

    override fun rejectFaceSuggestion(name: String, filePaths: List<String>, boxesPerMille: List<Int>): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RejectFaceSuggestion.Params.NAME to name.toTString(context),
                    RejectFaceSuggestion.Params.FILE_PATHS to
                        context.dataFactory().createList<Data>(filePaths.map { it.toTString(context) }),
                    RejectFaceSuggestion.Params.BOXES_PER_MILLE to
                        context.dataFactory().createList<Data>(boxesPerMille.map { it.toLong().toTInteger(context) }),
                ),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject<TInteger>(
            type = schema.returnType,
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}

internal class FaceSuggestionRejectionsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), FaceSuggestionRejections {

    override fun faceSuggestionRejections(): RequestObject {
        val nextChain = requestChain.addArray(OperationDto(params = emptyMap(), schema = schema))
        return context.roProvider().createRequestObject<TList<RejectedFace>>(
            type = listType(RejectedFace.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
