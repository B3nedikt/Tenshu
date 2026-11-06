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
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The photos most like [text], best first, capped at [limit] (IN-4b, `inference-service.md`
 * IN-D12) — semantic search, and **the thing the chain DSL cannot express**.
 *
 * **Why an op.** The DSL has `equalTo`, `contains` and `inRange`. Nearest-neighbour over a vector
 * is none of them, and no predicate we could add would turn a *ranked* similarity into a filter: a
 * filter answers yes/no per row, while this has to score every row and keep the best few. IN-D12
 * called this out precisely because MQ-4 was written assuming an index would slot in behind the
 * existing chain, and it will not. `photosCapturedBetween` is the shipped precedent for the shape.
 *
 * ⚠⚠ **The query is text, and the vectors never leave the proxy.** Embedding the utterance
 * client-side and sending a 768-float array would put on the IPC wire exactly the thing IN-D5
 * keeps off the sync wire, and it would require every client to hold the model. The proxy embeds,
 * ranks against its own store, and returns paths and scores — so a caller needs no model, no
 * vectors and no knowledge that either exists.
 *
 * ⚠⚠ **Throws [SemanticSearchUnavailable] rather than returning an empty list** when the capability
 * is off, no model is installed, the backend has no text embedding, or nothing is indexed yet.
 * "Nothing matched" and "this cannot run here" are different answers and only one of them is
 * actionable — see that error for why an empty success is the more expensive mistake.
 *
 * ⚠ It is a **scan**: one dot product per indexed photo, in-process, per call. That is the honest
 * cost of adding no index, no backfill and no invalidation, and IN-D12 sizes it — ~40 MB of vectors
 * for 20 000 photos, fine to hold, not fine to re-read per keystroke. A caller that searches as the
 * user types owes debouncing; an approximate index behind this signature would be invisible to it.
 *
 * @param text the utterance, embedded by the proxy's own backend
 * @param limit maximum results; `0` or less returns nothing rather than everything
 */
suspend fun InferenceService.photosLike(
    text: String,
    limit: Int,
): TResult<TList<SimilarPhoto>> =
    findOperation<PhotosLike>(PhotosLike.ID)
        .photosLike(text, limit)
        .asTaskInteractor<TList<SimilarPhoto>>()
        .execute()

interface PhotosLike : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun photosLike(text: String, limit: Int): RequestObject

    companion object {
        const val ID = "tenshu/inference/photosLike"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                string(id = Params.TEXT)
                int(id = Params.LIMIT)
            },
            returnType = listType(SimilarPhoto.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val TEXT = "$ID/text"
        const val LIMIT = "$ID/limit"
    }
}
