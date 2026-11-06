package org.tenshu.inference

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * One thing [ClassifyIntent] may choose: the caller's stable [intentId] and the [description] a
 * model is shown (MQ-5).
 *
 * ⚠ **One composite rather than two parallel lists.** `(ids, descriptions)` as separate params
 * compiles, serialises and silently misaligns the moment either list is filtered — and the failure
 * would be a model shown one intent's prose while its answer is matched against another's id.
 *
 * ⚠ Never stored. Like `SimilarPhoto`, this is an operation's shape, not a row: the candidate set
 * is assembled per question from what the caller is willing to allow *right now* (which grants are
 * on, whether writes are in scope at all), so persisting it would preserve a decision that is
 * meant to be re-taken every time.
 */
interface IntentCandidate : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The caller's own id. A returned choice is matched against exactly this string. */
    val intentId: TString
        get() = getField(Fields.INTENT_ID)

    /** What the intent means, in prose — the only part a model reads. */
    val description: TString
        get() = getField(Fields.DESCRIPTION)

    companion object {
        const val ID = "tenshu/inference/intentCandidate"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.INTENT_ID)
                string(Fields.DESCRIPTION)
            },
        )

        fun create(
            context: TContext,
            intentId: String,
            description: String,
        ): IntentCandidate = context.create(
            type = ID,
            fields = listOf(intentId, description),
        )
    }

    object Fields {
        const val INTENT_ID = "$ID/intentId"
        const val DESCRIPTION = "$ID/description"
    }
}

/**
 * What [ClassifyIntent] chose — always one of the caller's own [IntentCandidate.intentId]s.
 *
 * ⚠⚠ A **list of at most one** of these is the op's return type, so "the model had no opinion" is
 * an empty list rather than a null field or a sentinel id. The caller's fallback is then the
 * ordinary empty-result path it already has, not a special case.
 */
interface IntentChoice : Composite {

    override val schema: CompositeSchema get() = Schema

    val intentId: TString
        get() = getField(Fields.INTENT_ID)

    companion object {
        const val ID = "tenshu/inference/intentChoice"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.INTENT_ID)
            },
        )

        fun create(context: TContext, intentId: String): IntentChoice = context.create(
            type = ID,
            fields = listOf(intentId),
        )
    }

    object Fields {
        const val INTENT_ID = "$ID/intentId"
    }
}
