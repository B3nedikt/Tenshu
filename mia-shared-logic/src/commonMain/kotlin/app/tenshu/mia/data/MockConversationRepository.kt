package app.tenshu.mia.data

import app.tenshu.mia.model.Conversation
import app.tenshu.mia.model.MessageBody
import app.tenshu.mia.model.MiaMessage
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The keyword-routed mock assistant (docs/proposals/home-and-mia-apps.md §4, M3): replies
 * are canned but the *shapes* are real — a photo query yields a [MessageBody.PhotoGrid],
 * a place query a [MessageBody.PlaceCard], a device command a [MessageBody.DeviceAck] —
 * so the chat surface renders every rich-result cell the realm-data assistant will need.
 *
 * [replyDelay] simulates thinking (drives the typing indicator); tests run under a virtual
 * clock, so the default costs nothing there.
 */
class MockConversationRepository(
    private val replyDelay: Duration = 600.milliseconds,
) : ConversationRepository {

    private val messagesFlow = MutableStateFlow<List<MiaMessage>>(emptyList())
    private val replyingFlow = MutableStateFlow(false)
    private val historyFlow = MutableStateFlow(seededHistory(Clock.System.now()))
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
            delay(replyDelay)
            append(fromUser = false, body = replyFor(trimmed))
        } finally {
            replyingFlow.value = false
        }
    }

    private fun append(fromUser: Boolean, body: MessageBody) {
        messagesFlow.update {
            it + MiaMessage(
                id = "message-${nextId++}",
                at = Clock.System.now(),
                fromUser = fromUser,
                body = body,
            )
        }
    }

    /**
     * Keyword routing, deliberately transparent: this is a demo script, not NLU. The real
     * dispatcher is the Voice App Service.
     */
    private fun replyFor(text: String): MessageBody {
        val lower = text.lowercase()
        return when {
            listOf("photo", "picture", "image").any { it in lower } -> MessageBody.PhotoGrid(
                caption = "I found these in your Drive:",
                photoLabels = listOf("Beach sunset", "Mountain trail", "Birthday cake", "Lake view"),
            )
            listOf("where", "place", "cafe", "café", "restaurant").any { it in lower } -> MessageBody.PlaceCard(
                name = "Café Miran",
                detail = "Visited twice in June · from your Timeline",
            )
            listOf("turn on", "turn off", "switch", "dim").any { it in lower } -> MessageBody.DeviceAck(
                text = "Done — I asked Home to take care of that.",
            )
            else -> MessageBody.Text(
                "I can find photos from your Drive, places from your Timeline, or control your home — try asking for a photo.",
            )
        }
    }

    private companion object {
        /** Two past sessions so History reads naturally on first launch. */
        fun seededHistory(now: kotlin.time.Instant): List<Conversation> = listOf(
            Conversation(
                id = "conversation-1",
                startedAt = now - 26.hours,
                title = "Show me photos from the lake",
                messages = listOf(
                    MiaMessage(
                        id = "history-1-1",
                        at = now - 26.hours,
                        fromUser = true,
                        body = MessageBody.Text("Show me photos from the lake"),
                    ),
                    MiaMessage(
                        id = "history-1-2",
                        at = now - 26.hours + 2.milliseconds,
                        fromUser = false,
                        body = MessageBody.PhotoGrid(
                            caption = "From your Drive:",
                            photoLabels = listOf("Lake view", "Pier at dawn"),
                        ),
                    ),
                ),
            ),
            Conversation(
                id = "conversation-2",
                startedAt = now - 3.days,
                title = "Where was that ramen place?",
                messages = listOf(
                    MiaMessage(
                        id = "history-2-1",
                        at = now - 3.days,
                        fromUser = true,
                        body = MessageBody.Text("Where was that ramen place?"),
                    ),
                    MiaMessage(
                        id = "history-2-2",
                        at = now - 3.days + 2.milliseconds,
                        fromUser = false,
                        body = MessageBody.PlaceCard(
                            name = "Menya Kaiko",
                            detail = "Visited in April · from your Timeline",
                        ),
                    ),
                ),
            ),
        )
    }
}
