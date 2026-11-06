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

/**
 * The faces this device currently groups together, largest group first (IN-6, F3).
 *
 * **Why an op, and not the chain DSL.** For the reason [PhotosLike] gives: grouping is a pass over
 * every stored template computing cosines, which is not `equalTo`, `contains` or `inRange`, and no
 * predicate would turn it into one. It is also not a *listing* — there are no stored group rows to
 * list. Every group returned here is computed during this call and then discarded.
 *
 * ⚠⚠ **The templates never leave the proxy**, exactly as `photosLike` keeps vectors off the IPC
 * wire. A caller receives paths and an ephemeral group id; it holds no biometric data, needs no
 * model, and cannot reconstruct one. This is what makes it safe for the app process to render
 * People at all — see `photos-people-and-pets.md` §3.1 on the three custodies.
 *
 * ⚠⚠ **Throws [FaceGroupsUnavailable] rather than returning an empty list** when the capability is
 * off, nothing can detect a face, no pass has run, or this build has no calibrated threshold. An
 * empty People grid is a *plausible-looking* screen, which is what makes the empty-success mistake
 * more expensive here than anywhere else it has been made.
 *
 * ⚠ It is a **scan**, like `photosLike`: every stored template, in-process, per call. A caller
 * should read it once per screen, not per recomposition.
 *
 * @param limit the maximum number of **groups** — not faces — returned, largest first. `0` or less
 *   returns nothing rather than everything. All of a returned group's faces are always included, so
 *   a caller never sees a partial person.
 */
suspend fun InferenceService.faceGroups(
    limit: Int,
): TResult<TList<GroupedFace>> =
    findOperation<FaceGroups>(FaceGroups.ID)
        .faceGroups(limit)
        .asTaskInteractor<TList<GroupedFace>>()
        .execute()

interface FaceGroups : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun faceGroups(limit: Int): RequestObject

    companion object {
        const val ID = "tenshu/inference/faceGroups"

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
