package org.tenshu.inference

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string

/**
 * One result of a semantic search: a file, and how close it was (IN-4b, IN-D12).
 *
 * ⚠⚠ **Not a stored row.** `MediaLabel` is `Syncable` and durable; this is the *return shape* of an
 * operation and is never saved, because a similarity is a fact about a query rather than about a
 * photo. Storing it would create rows that go stale the moment the model or the question changes.
 *
 * ⚠ [scorePerMille] follows `MediaLabel.confidencePerMille` — the DSL's numeric field is an integer
 * — but the range is **−1000…1000**, not 0…1000: a cosine is signed, and clamping negatives to zero
 * would erase the difference between "unrelated" and "the opposite of what you asked for".
 *
 * ⚠ The path is `:file-service`'s and stays opaque here, exactly as `MediaLabel.filePath` does.
 * `:inference-service` returning a `File` composite would couple the two services in the direction
 * IN-D12 spent its argument avoiding.
 */
interface SimilarPhoto : Composite {

    override val schema: CompositeSchema get() = Schema

    val filePath: TString
        get() = getField(Fields.FILE_PATH)

    /** Cosine × 1000, signed. See the class note. */
    val scorePerMille: TInteger
        get() = getField(Fields.SCORE_PER_MILLE)

    companion object {
        const val ID = "tenshu/inference/similarPhoto"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.FILE_PATH)
                int(Fields.SCORE_PER_MILLE)
            },
        )

        fun create(
            context: TContext,
            filePath: String,
            scorePerMille: Int,
        ): SimilarPhoto = context.create(
            type = ID,
            fields = listOf(
                filePath,
                // ⚠ A LONG on the wire; an Int compiles and fails at save time (IN-3a found this).
                scorePerMille.toLong(),
            ),
        )
    }

    object Fields {
        const val FILE_PATH = "$ID/filePath"
        const val SCORE_PER_MILLE = "$ID/scorePerMille"
    }
}
