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
 * Answers a [MiaClarification] (`mia-query-layer.md` §25, CL-D5).
 *
 * ⚠⚠ **Its own op, not an optional `askMia` parameter.** An ordinary question is never read
 * against an open clarification; only a reply that names the clarification's token is. That keeps
 * "the user answered Mia" and "the user asked something new" on different paths. (An optional op
 * parameter would also be a reshape of `askMia`.)
 *
 * The [utterance] is read **only as the param the token names**. A reply that does not parse as
 * that param is treated as a new question and planned as `askMia` would plan it, so a user who
 * changes their mind is answered rather than asked again.
 *
 * ⚠ A reply never writes. The answer is an ordinary [MiaAnswer]: a confirmation of the now
 * complete command, another clarification, or the answer to a new question. MQ-D7's confirmation
 * stays the only path to a row.
 */
suspend fun MiaService.replyToMia(
    token: String,
    utterance: String,
    nowEpochMs: Long,
    timeZoneId: String,
): TResult<MiaAnswer> =
    findOperation<ReplyToMia>(ReplyToMia.ID)
        .replyToMia(token, utterance, nowEpochMs, timeZoneId)
        .asTaskInteractor<MiaAnswer>()
        .execute()

interface ReplyToMia : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun replyToMia(token: String, utterance: String, nowEpochMs: Long, timeZoneId: String): RequestObject

    companion object {
        const val ID = "tenshu/mia/service/replyToMia"

        val Schema = operation(
            id = ID,
            receiver = MiaService.Schema.toType(),
            params = {
                string(id = Params.TOKEN)
                string(id = Params.UTTERANCE)
                int(id = Params.NOW_EPOCH_MS)
                string(id = Params.TIME_ZONE_ID)
            },
            returnType = MiaAnswer.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val TOKEN = "$ID/token"
        const val UTTERANCE = "$ID/utterance"
        const val NOW_EPOCH_MS = "$ID/nowEpochMs"
        const val TIME_ZONE_ID = "$ID/timeZoneId"
    }
}
