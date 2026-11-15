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

/**
 * Every "this face is not X" recorded on this device ([RejectFaceSuggestion]) — what the app needs
 * to stop asking *"Is this X?"* again (IN-6 F6c, name suggestions).
 *
 * ⚠⚠ Refused with [FaceGroupsUnavailable] exactly where `faceGroups` is refused (no consent, no
 * usable store), never answered empty: an empty success would read as "nothing was ever rejected"
 * and bring every answered question back. The app shows no suggestion when this fails.
 */
suspend fun InferenceService.faceSuggestionRejections(): TResult<TList<RejectedFace>> =
    findOperation<FaceSuggestionRejections>(FaceSuggestionRejections.ID)
        .faceSuggestionRejections()
        .asTaskInteractor<TList<RejectedFace>>()
        .execute()

interface FaceSuggestionRejections : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun faceSuggestionRejections(): RequestObject

    companion object {
        const val ID = "tenshu/inference/faceSuggestionRejections"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {},
            returnType = listType(RejectedFace.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
