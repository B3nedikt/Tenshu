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
import org.tenshu.inference.PetSuggestionRejections
import org.tenshu.inference.RejectPetSuggestion
import org.tenshu.inference.RejectedFace

/** Pets PT-3b: the face RO's twin (`photos-pets.md` §3). */
internal class RejectPetSuggestionRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RejectPetSuggestion {

    override fun rejectPetSuggestion(name: String, filePaths: List<String>, boxesPerMille: List<Int>): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RejectPetSuggestion.Params.NAME to name.toTString(context),
                    RejectPetSuggestion.Params.FILE_PATHS to
                        context.dataFactory().createList<Data>(filePaths.map { it.toTString(context) }),
                    RejectPetSuggestion.Params.BOXES_PER_MILLE to
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

/** Pets PT-3b: the face RO's twin (`photos-pets.md` §3). */
internal class PetSuggestionRejectionsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PetSuggestionRejections {

    override fun petSuggestionRejections(): RequestObject {
        val nextChain = requestChain.addArray(OperationDto(params = emptyMap(), schema = schema))
        return context.roProvider().createRequestObject<TList<RejectedFace>>(
            type = listType(RejectedFace.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
