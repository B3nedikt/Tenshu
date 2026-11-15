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

/**
 * Remember that these faces are NOT [name] (IN-6 F6c, name suggestions) — the "No" to
 * *"Is this Anna?"*.
 *
 * ⚠⚠ **Writes nothing to the realm.** The record is device-local and has the face templates'
 * custody ([RejectedFace]; threat model A40 *Name suggestions*); `photos-people-and-pets.md` §6a
 * F6c.9 D2 is why a rejection does not sync.
 *
 * ⚠ Faces travel as parallel lists — `filePaths[i]` with the four per-mille box edges at
 * `boxesPerMille[4i..4i+3]` (left, top, right, bottom) — rather than a list of composites, so the
 * parameter is made of the same primitives every older peer already decodes. A length mismatch is
 * a caller mistake and writes nothing.
 *
 * @return how many faces were recorded; `0` when nothing was written
 */
suspend fun InferenceService.rejectFaceSuggestion(
    name: String,
    filePaths: List<String>,
    boxesPerMille: List<Int>,
): TResult<TInteger> =
    findOperation<RejectFaceSuggestion>(RejectFaceSuggestion.ID)
        .rejectFaceSuggestion(name, filePaths, boxesPerMille)
        .asTaskInteractor<TInteger>()
        .execute()

interface RejectFaceSuggestion : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun rejectFaceSuggestion(name: String, filePaths: List<String>, boxesPerMille: List<Int>): RequestObject

    companion object {
        const val ID = "tenshu/inference/rejectFaceSuggestion"

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
