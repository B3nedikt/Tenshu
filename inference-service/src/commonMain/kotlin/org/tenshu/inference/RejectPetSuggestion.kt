package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun InferenceService.rejectPetSuggestion(
    name: String,
    filePaths: List<String>,
    boxesPerMille: List<Int>,
): TResult<TInteger> =
    findOperation<RejectPetSuggestion>(RejectPetSuggestion.ID)
        .rejectPetSuggestion(name, filePaths, boxesPerMille)
        .asTaskInteractor<TInteger>()
        .execute()

/** "This is not Mochi" (`photos-pets.md` PT-5), held beside the pet templates — [RejectFaceSuggestion]'s contract. */
interface RejectPetSuggestion : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun rejectPetSuggestion(name: String, filePaths: List<String>, boxesPerMille: List<Int>): RequestObject

    companion object {
        const val ID = "tenshu/inference/rejectPetSuggestion"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                string(id = Params.NAME)
                list(id = Params.FILE_PATHS, of = TString.Schema)
                list(id = Params.BOXES_PER_MILLE, of = TInteger.Schema)
            },
            returnType = TInteger.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val NAME = "$ID/name"
        const val FILE_PATHS = "$ID/filePaths"
        const val BOXES_PER_MILLE = "$ID/boxesPerMille"
    }
}
