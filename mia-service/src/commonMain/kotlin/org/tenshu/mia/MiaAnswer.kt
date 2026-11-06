package org.tenshu.mia

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string

/**
 * What [AskMia] returns (`mia-query-layer.md` MQ-D1): **citations, never prose**.
 *
 * An assistant that can only show you your own data cannot hallucinate — every hit in
 * [hits] is a row that exists and that the user can tap through to the owning app. That
 * is a sharper claim than "we run the model locally", and it holds with no model at all.
 *
 * ⚠ **[verdict] carries the distinction a keyword router most often gets wrong.**
 * "I did not understand you" and "I understood you and there is nothing" are different
 * answers, and collapsing them is exactly MQ-D4's *answering a different question
 * confidently* failure. [VERDICT_NO_MATCH] is a first-class outcome, not an error.
 *
 * ⚠ Never stored, never synced — a return shape only.
 */
interface MiaAnswer : Composite {

    override val schema: CompositeSchema get() = Schema

    /** [VERDICT_ANSWERED] | [VERDICT_NO_MATCH] | [VERDICT_UNAVAILABLE]. */
    val verdict: TString
        get() = getField(Fields.VERDICT)

    /**
     * What Mia actually did, in one sentence the user can check — *"Searched Drive for
     * photos taken 1 Jun–31 Aug 2025"*.
     *
     * ⭐ MQ-D3's explainability half, and it is load-bearing rather than decorative:
     * when retrieval is wrong, a wrong *plan* is legible where a wrong embedding is not.
     * It also names the resolved date range, which is how a hemisphere-dependent word
     * like "summer" stays correctable instead of silently wrong (see `TimeGrammar`).
     */
    val explanation: TString
        get() = getField(Fields.EXPLANATION)

    /** The rows Mia is citing; empty when [verdict] is not [VERDICT_ANSWERED]. */
    val hits: TList<MiaHit>
        get() = getField(Fields.HITS)

    /**
     * The action awaiting a tap, present exactly when [verdict] is [VERDICT_CONFIRM]
     * (MQ-D7). Absent for every read.
     */
    val action: MiaAction?
        get() = getOptionalField(Fields.ACTION)

    companion object {
        const val ID = "tenshu/mia/answer"

        /** The plan ran. [hits] may still be empty — that means "nothing matched". */
        const val VERDICT_ANSWERED = "answered"

        /**
         * Mia is not going to answer, and says why.
         *
         * Two shapes, and the second is easy to miss:
         * - **No intent matched the utterance** — Mia lists what it can do rather than
         *   answering a question it was not asked (MQ-D4).
         * - **An intent matched but a slot did not resolve** — "I couldn't find a device
         *   called X", "I don't know a place called X", "I don't know which calendar to use".
         *
         * ⚠⚠ **The second shape is why this is not interchangeable with [VERDICT_ANSWERED]
         * plus an empty [hits].** Nothing was searched, so claiming an absence would assert
         * something never established — and the chat surface *renders* the difference by
         * appending "nothing matched" to an empty [VERDICT_ANSWERED]. Returning a refusal as
         * [VERDICT_ANSWERED] therefore produces a sentence that says both (§14.1).
         */
        const val VERDICT_NO_MATCH = "noMatch"

        /**
         * Mia understood, resolved exactly one thing to do, and is **waiting for a tap**
         * (MQ-D7). Nothing has happened yet. [action] carries what will.
         */
        const val VERDICT_CONFIRM = "confirm"

        /**
         * The plan could not run — the validator refused the chain (MQ-D10), or
         * execution failed. Distinct from an empty answer on purpose: "I could not
         * look" must never render as "there is nothing".
         */
        const val VERDICT_UNAVAILABLE = "unavailable"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.VERDICT)
                string(Fields.EXPLANATION)
                list(Fields.HITS, of = MiaHit.Schema)
                composite(Fields.ACTION, MiaAction.Schema, isOptional = true)
            },
        )

        fun create(
            context: TContext,
            verdict: String,
            explanation: String,
            hits: List<MiaHit> = emptyList(),
            action: MiaAction? = null,
        ): MiaAnswer = context.create(
            type = ID,
            fields = listOfNotNull<Any>(
                verdict,
                explanation,
                hits as List<Data>,
                action,
            ),
        )
    }

    object Fields {
        const val VERDICT = "$ID/verdict"
        const val EXPLANATION = "$ID/explanation"
        const val HITS = "$ID/hits"
        const val ACTION = "$ID/action"
    }
}
