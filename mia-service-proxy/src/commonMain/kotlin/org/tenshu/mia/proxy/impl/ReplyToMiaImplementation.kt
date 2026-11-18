package org.tenshu.mia.proxy.impl

import kotlinx.datetime.TimeZone
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.ReplyToMia
import org.tenshu.mia.plan.MiaFrames
import org.tenshu.mia.plan.QueryPlanner
import org.tenshu.mia.proxy.MiaActionRegistry
import org.tenshu.mia.proxy.readMiaGrants

/**
 * A reply to a clarification (`mia-query-layer.md` §25, CL-D5).
 *
 * ⚠⚠ **The token is the whole capability.** It names one param of one plan this proxy asked
 * about; the reply can fill that param and nothing else, because the title, the target and the
 * verb never travel with it. An unknown, answered or expired token gets the honest "ask me
 * again", never a guess at what the reply meant.
 *
 * ⭐ Everything after the param is filled is [AskMiaImplementation]'s, unchanged: the completed
 * command (or the next question) goes through the same `answerPlan` an `askMia` would, so the
 * write's preconditions, its resolved defaults and MQ-D7's card are the same code. A reply that
 * is not an answer goes through `answerUtterance`, so it is planned exactly as a new question.
 *
 * ⚠ The grants are read again at reply time: a source revoked between the question and the answer
 * is refused by `answerPlan`, not by a decision made five minutes earlier.
 */
internal class ReplyToMiaImplementation(
    private val actions: MiaActionRegistry,
    private val ask: AskMiaImplementation,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val token = (params?.get(ReplyToMia.Params.TOKEN) as? TString)?.get().orEmpty()
        val utterance = (params?.get(ReplyToMia.Params.UTTERANCE) as? TString)?.get().orEmpty()
        val nowMs = (params?.get(ReplyToMia.Params.NOW_EPOCH_MS) as? TInteger)?.get() ?: 0L
        val zoneId = (params?.get(ReplyToMia.Params.TIME_ZONE_ID) as? TString)?.get().orEmpty()

        // An open question, or a card's alternative (CL-D6), which lives under the card's token.
        val clarify = actions.takeClarification(token) ?: actions.takeAlternative(token)
            ?: return MiaAnswer.create(
                context = executionContext.context,
                verdict = MiaAnswer.VERDICT_NO_MATCH,
                explanation = "That question is no longer open — ask me again.",
            ) as T

        // ⚠⚠ A card's alternative (CL-D6) is a reply against the CARD's token: the card it came from
        // is spent here, so it can never be confirmed beside the card this reply mints.
        actions.take(token)

        val granted = executionContext.readMiaGrants()

        // ⭐ CL-3: a which-one question is answered from its candidates, never from the realm.
        if (MiaFrames.of(clarify.op).param(clarify.param).slot == MiaFrames.Slot.ENTITY_REFERENCE) {
            return when (val pick = QueryPlanner.answerCandidate(clarify, utterance)) {
                null -> ask.answerUtterance(executionContext, implementationFactory, utterance, nowMs, zoneId)
                is QueryPlanner.Pick.Fewer -> ask.answerPlan(executionContext, implementationFactory, pick.clarify, granted, zoneId)
                is QueryPlanner.Pick.One -> ask.confirmCandidate(executionContext, clarify, pick.choice, granted)
            } as T
        }

        val zone = runCatching { TimeZone.of(zoneId) }.getOrDefault(TimeZone.UTC)
        val answered = QueryPlanner.answerClarification(clarify, utterance, nowMs, zone)
            ?: return ask.answerUtterance(executionContext, implementationFactory, utterance, nowMs, zoneId) as T

        return ask.answerPlan(executionContext, implementationFactory, answered, granted, zoneId) as T
    }
}
