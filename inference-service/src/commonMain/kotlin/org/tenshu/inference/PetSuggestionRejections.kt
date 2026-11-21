package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun InferenceService.petSuggestionRejections(): TResult<TList<RejectedFace>> =
    findOperation<PetSuggestionRejections>(PetSuggestionRejections.ID)
        .petSuggestionRejections()
        .asTaskInteractor<TList<RejectedFace>>()
        .execute()

/** Every pet suggestion rejected on this device — [FaceSuggestionRejections]' contract over `pets.log`. */
interface PetSuggestionRejections : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun petSuggestionRejections(): RequestObject

    companion object {
        const val ID = "tenshu/inference/petSuggestionRejections"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {},
            returnType = listType(RejectedFace.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
