package org.tenshu.mia

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * One turn: an utterance in, a grounded [MiaAnswer] out
 * (`docs/proposals/mia-query-layer.md` MQ-1).
 *
 * Everything between the two happens on the proxy — the utterance becomes a typed
 * `QueryPlan`, the plan **lowers to an ordinary chain-DSL callstack**, the callstack
 * passes MQ-D10's validator, and only then does anything execute. Mia gets no query
 * language of its own: it emits the same `List<Data>` callstack every app's read path
 * already uses, so it inherits ordered-window fusion, `@defer` and projection pushdown
 * for free, and a future model that replaces the *planner* meets the same validator
 * unchanged.
 *
 * ⚠⚠ **[nowEpochMs] and [timeZoneId] come from the caller, and that is deliberate.**
 * "Tomorrow" and "last summer" are answered against the clock and calendar of the device
 * the person is *holding*, not of whatever machine happens to host the proxy — which may
 * be a hub in another timezone, or a desktop left running abroad. This is
 * `routines-engine.md` RE-D3's rule one layer up: the routine carries the authoring
 * device's zone because using the executing device's is the most likely way to build a
 * subtly broken version. It also keeps the planner **pure** — utterance plus clock in,
 * plan out — which is what makes hundreds of grammar cases assertable with no IO.
 *
 * ⚠ v1 speaks **English only** (MQ-D4: an intent grammar is per-language). There is
 * deliberately no `languageTag` param: nothing would read it today, and a second locale
 * is a new op id under the `RenewTaskLease` rule rather than a param nobody consulted.
 *
 * @param utterance what the user typed, verbatim
 * @param nowEpochMs the caller's wall clock, epoch millis
 * @param timeZoneId the caller's IANA zone, e.g. `Europe/Berlin`; an unknown id resolves
 *   relative windows in UTC rather than failing the turn
 */
suspend fun MiaService.askMia(
    utterance: String,
    nowEpochMs: Long,
    timeZoneId: String,
): TResult<MiaAnswer> =
    findOperation<AskMia>(AskMia.ID)
        .askMia(utterance, nowEpochMs, timeZoneId)
        .asTaskInteractor<MiaAnswer>()
        .execute()

interface AskMia : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun askMia(utterance: String, nowEpochMs: Long, timeZoneId: String): RequestObject

    companion object {
        const val ID = "tenshu/mia/service/askMia"

        val Schema = operation(
            id = ID,
            receiver = MiaService.Schema.toType(),
            params = {
                string(id = Params.UTTERANCE)
                int(id = Params.NOW_EPOCH_MS)
                string(id = Params.TIME_ZONE_ID)
            },
            returnType = MiaAnswer.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val UTTERANCE = "$ID/utterance"
        const val NOW_EPOCH_MS = "$ID/nowEpochMs"
        const val TIME_ZONE_ID = "$ID/timeZoneId"
    }
}
