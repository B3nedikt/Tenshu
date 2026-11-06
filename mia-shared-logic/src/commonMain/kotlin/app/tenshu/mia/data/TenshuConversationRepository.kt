package app.tenshu.mia.data

import app.tenshu.mia.model.Conversation
import app.tenshu.mia.model.Hit
import app.tenshu.mia.model.MessageBody
import app.tenshu.mia.model.MiaMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.get
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaService
import org.tenshu.mia.askMia
import org.tenshu.mia.confirmMiaAction
import kotlin.time.Clock

/**
 * The real assistant (`mia-query-layer.md` MQ-1): every turn goes to the proxy-side query
 * layer and comes back as citations of rows that exist.
 *
 * ⚠⚠ **There is deliberately no fall-back to [MockConversationRepository] when the proxy
 * is unreachable.** A mock that invents plausible answers is worse than an error, because
 * the user cannot tell the difference — Timeline shipped exactly that shape and had to be
 * given a `NotConnectedTimelineRepository` to undo it. When Control cannot be reached,
 * Mia says so.
 *
 * ⚠ **History is in-memory for the process, and is not persisted.** MQ-D8 makes
 * transcripts device-local by default *and* requires a delete affordance in the same slice
 * that makes them durable — what someone asks is routinely more revealing than what they
 * are shown. Keeping this session-scoped honours the first half now and leaves the second
 * as one coherent later slice, rather than shipping a history nobody can clear.
 *
 * @param couldNotLook what a turn says when the proxy could not be asked at all. ⚠⚠ The default
 *   names Control because, over Binder or a socket, that is the cause the user can act on. It is
 *   **false where the proxy is this process** — iOS route 1 has no Control channel to lose — so
 *   that runtime passes its own sentence (`MiaLocalRuntime.COULD_NOT_LOOK`) rather than telling
 *   someone to go and open an app that is not involved.
 */
class TenshuConversationRepository(
    private val client: TenshuClient,
    private val couldNotLook: String = CANNOT_REACH_CONTROL,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timeZoneId: () -> String = { TimeZone.currentSystemDefault().id },
) : ConversationRepository {

    private val messagesFlow = MutableStateFlow<List<MiaMessage>>(emptyList())
    private val replyingFlow = MutableStateFlow(false)
    private val historyFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private var nextId = 1

    override fun messages(): Flow<List<MiaMessage>> = messagesFlow

    override fun replying(): Flow<Boolean> = replyingFlow

    override fun history(): Flow<List<Conversation>> = historyFlow

    override suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        append(fromUser = true, body = MessageBody.Text(trimmed))
        replyingFlow.value = true
        try {
            append(fromUser = false, body = ask(trimmed))
        } finally {
            replyingFlow.value = false
            recordHistory()
        }
    }

    private suspend fun ask(utterance: String): MessageBody {
        val service = runCatching { client.getService<MiaService>(MiaService.ID) }
            .getOrElse { return MessageBody.Text(couldNotLook) }

        val result = runCatchingCancellable {
            service.askMia(utterance, now(), timeZoneId())
        }.getOrElse { return MessageBody.Text(couldNotLook) }

        val answer = (result as? TResult.Success<*>)?.value as? MiaAnswer
            ?: return MessageBody.Text(couldNotLook)

        return bodyOf(answer)
    }

    override suspend fun confirm(token: String) {
        val service = runCatching { client.getService<MiaService>(MiaService.ID) }.getOrNull()
            ?: return
        replyingFlow.value = true
        try {
            val result = runCatchingCancellable { service.confirmMiaAction(token) }.getOrNull()
            val answer = (result as? TResult.Success<*>)?.value as? MiaAnswer
            // ⚠ Mark the confirmation spent BEFORE appending the acknowledgement, so the
            // button cannot be pressed again while the reply is in flight.
            markConfirmed(token)
            append(
                fromUser = false,
                body = answer?.let { MessageBody.Text(it.explanation.get()) }
                    ?: MessageBody.Text(couldNotLook),
            )
        } finally {
            replyingFlow.value = false
            recordHistory()
        }
    }

    private fun markConfirmed(token: String) {
        messagesFlow.update { messages ->
            messages.map { message ->
                val body = message.body
                if (body is MessageBody.Confirmation && body.token == token) {
                    message.copy(body = body.copy(done = true))
                } else {
                    message
                }
            }
        }
    }

    private fun bodyOf(answer: MiaAnswer): MessageBody {
        val hits = answer.hits.toList()
            .filterIsInstance<MiaHit>()
            .map {
                Hit(
                    target = it.target,
                    title = it.title.get(),
                    subtitle = it.subtitle.get(),
                    atMs = it.at.get(),
                )
            }

        answer.action?.let { action ->
            return MessageBody.Confirmation(
                title = action.title.get(),
                detail = action.detail.get(),
                token = action.token.get(),
            )
        }

        return when (answer.verdict.get()) {
            // ⚠ Three verdicts, three sentences. Collapsing "I did not understand" into
            // "nothing found" tells the user their question was fine when it was not, and
            // collapsing "I could not look" into it is worse: it asserts an absence we
            // never established.
            //
            // ⚠⚠ **The suffix below is why a refusal must never arrive as `ANSWERED`.** This
            // reads the verdict and nothing else — it cannot tell a description of a search
            // ("Searched your files for …") from a refusal to run one ("I don't know a place
            // called …"), so an `ANSWERED` carrying the second produced *"I don't know a place
            // called “paris” … — nothing matched."* on a real device (§14.1). Fixed on the
            // producing side, in `AskMiaImplementation.placeJoin`; recorded here because this
            // is the line that makes the verdict load-bearing.
            MiaAnswer.VERDICT_ANSWERED ->
                if (hits.isEmpty()) {
                    MessageBody.Text("${answer.explanation.get()} — nothing matched.")
                } else {
                    MessageBody.Citations(caption = answer.explanation.get(), hits = hits)
                }

            else -> MessageBody.Text(answer.explanation.get())
        }
    }

    override suspend fun note(text: String) {
        append(fromUser = false, body = MessageBody.Text(text))
        recordHistory()
    }

    private fun append(fromUser: Boolean, body: MessageBody) {
        messagesFlow.update {
            it + MiaMessage(
                id = "message-${nextId++}",
                at = kotlin.time.Instant.fromEpochMilliseconds(now()),
                fromUser = fromUser,
                body = body,
            )
        }
    }

    /** One conversation per session, kept current as the thread grows. */
    private fun recordHistory() {
        val messages = messagesFlow.value
        val opening = messages.firstOrNull { it.fromUser }?.body as? MessageBody.Text ?: return
        historyFlow.value = listOf(
            Conversation(
                id = "session",
                startedAt = messages.first().at,
                title = opening.text,
                messages = messages,
            )
        )
    }

    companion object {
        /**
         * ⚠ Names the cause the user can act on. "Mia is unavailable" would be true of a
         * bug too; the overwhelmingly common case is that Control is not running or this
         * device is not paired.
         */
        const val CANNOT_REACH_CONTROL =
            "I can't reach Control right now, so I can't look anything up."
    }
}
