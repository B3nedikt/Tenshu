package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun InferenceService.petGroups(
    limit: Int,
): TResult<TList<GroupedFace>> =
    findOperation<PetGroups>(PetGroups.ID)
        .petGroups(limit)
        .asTaskInteractor<TList<GroupedFace>>()
        .execute()

/**
 * Pets' groups (`photos-pets.md` PT-3b): [FaceGroups]' contract over `pets.log`, under `PET_RECOGNITION`.
 *
 * ⚠ The same return shape, [GroupedFace] — a "face" here is one cat or dog's box — and the same refusal,
 * [FaceGroupsUnavailable], so Photos reads both halves with one code path. The groups are PT-D11's 0.85
 * best guess: split-first scaffolding, never identity.
 */
interface PetGroups : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun petGroups(limit: Int): RequestObject

    companion object {
        const val ID = "tenshu/inference/petGroups"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                int(id = Params.LIMIT)
            },
            returnType = listType(GroupedFace.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val LIMIT = "$ID/limit"
    }
}
