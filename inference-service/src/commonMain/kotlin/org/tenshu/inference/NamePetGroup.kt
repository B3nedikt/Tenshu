package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun InferenceService.namePetGroup(
    filePaths: List<String>,
    name: String,
): TResult<TInteger> =
    findOperation<NamePetGroup>(NamePetGroup.ID)
        .namePetGroup(filePaths, name)
        .asTaskInteractor<TInteger>()
        .execute()

/**
 * Names pets (`photos-pets.md` PT-D1): one `MediaLabel` per photograph under `PET_RECOGNITION`,
 * `ModelProvenance.User` — [NameFaceGroup]'s contract with the other capability.
 *
 * ⚠⚠ A realm-writing op (threat model A40's note): the name syncs; the templates never do.
 */
interface NamePetGroup : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun namePetGroup(filePaths: List<String>, name: String): RequestObject

    companion object {
        const val ID = "tenshu/inference/namePetGroup"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                list(id = Params.FILE_PATHS, of = TString.Schema)
                string(id = Params.NAME)
            },
            returnType = TInteger.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FILE_PATHS = "$ID/filePaths"
        const val NAME = "$ID/name"
    }
}
