package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Which of [candidates] an utterance is asking for — **the model-as-planner seam** (MQ-5,
 * `mia-query-layer.md` §16).
 *
 * ⭐⭐ **A closed choice is the whole design.** MQ-5's risk is not that a model answers badly; it is
 * that a model answers *expressively* — free text parsed into a plan, or worse a generated chain.
 * §5 says "constrained decoding into the callstack composite shape, never free-text parsed into
 * one", and this is the cheapest complete form of that rule: the caller supplies the candidates,
 * the op returns one of them or **nothing**, and a value outside the set is discarded before it
 * reaches a planner. A model literally cannot express an intent Mia does not already implement.
 *
 * ⚠⚠ **Returns an empty list rather than throwing, unlike [PhotosLike].** The two look alike and
 * the difference is deliberate: semantic search has *no* fallback, so "the capability is off" had
 * to be an error the user could act on. Intent classification has a perfect fallback — the
 * deterministic grammar that has answered every question on this track since MQ-1 — so an
 * unavailable model costs the user nothing and must not produce a sentence about models. The
 * caller falls back and the user sees a plan either way (MQ-D9: every intent keeps working with
 * this off).
 *
 * ⚠ The utterance is the only user content that crosses this call, and it goes nowhere: the
 * backend is in-process on the proxy under IN-D1's binding. A remote binding would make the prompt
 * an egress and owes the `threat-model.md` A5-family amendment `inference-service.md` §4 names.
 *
 * @param utterance the user's own words, unmodified — a planner's job is to read what was typed
 * @param candidates what may be chosen at all; anything outside this set is dropped
 */
suspend fun InferenceService.classifyIntent(
    utterance: String,
    candidates: List<IntentCandidate>,
): TResult<TList<IntentChoice>> =
    findOperation<ClassifyIntent>(ClassifyIntent.ID)
        .classifyIntent(utterance, candidates)
        .asTaskInteractor<TList<IntentChoice>>()
        .execute()

interface ClassifyIntent : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun classifyIntent(utterance: String, candidates: List<IntentCandidate>): RequestObject

    companion object {
        const val ID = "tenshu/inference/classifyIntent"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                string(id = Params.UTTERANCE)
                list(id = Params.CANDIDATES, of = IntentCandidate.Schema)
            },
            // ⚠ A list of at most one, rather than an optional composite: the wire and the DSL
            // both handle "a list that came back empty" everywhere already, and it keeps "no
            // answer" from needing a second shape.
            returnType = listType(IntentChoice.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val UTTERANCE = "$ID/utterance"
        const val CANDIDATES = "$ID/candidates"
    }
}
