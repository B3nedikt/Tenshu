package app.tenshu.mia.data

import app.tenshu.mia.model.Conversation
import app.tenshu.mia.model.MessageBody
import app.tenshu.mia.model.MiaMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * Mia with no runtime behind it: every question gets the same true sentence, and nothing else is
 * ever shown.
 *
 * ⚠⚠ **It answers, rather than staying silent, and it invents nothing.** A chat that swallows a
 * message reads as a hang; one that replies with [MockConversationRepository]'s canned photos
 * and places reads as the user's own data. The only honest reply available without a runtime is
 * that there is none — the Mia member of the `NotConnected*Repository` family
 * (`NotConnectedTimelineRepository` is the one that had to be written after the fact).
 *
 * ⚠ In practice a shipping iOS build never lets a user type into this: an unprovisioned app
 * shows the set-up screen instead of the chat (ADR 0022 §Decision 6). It is the default so that
 * a view model built ahead of the runtime is wrong in the safe direction.
 *
 * ⚠ No history, by the same rule: a past conversation is exactly the kind of row a mock seeds.
 */
class NotConnectedConversationRepository(
    private val now: () -> kotlin.time.Instant = { Clock.System.now() },
) : ConversationRepository {

    private val messagesFlow = MutableStateFlow<List<MiaMessage>>(emptyList())
    private var nextId = 1

    override fun messages(): Flow<List<MiaMessage>> = messagesFlow

    override fun replying(): Flow<Boolean> = flowOf(false)

    override fun history(): Flow<List<Conversation>> = flowOf(emptyList())

    override suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        append(fromUser = true, text = trimmed)
        append(fromUser = false, text = NOT_CONNECTED)
    }

    private fun append(fromUser: Boolean, text: String) {
        messagesFlow.update {
            it + MiaMessage(
                id = "message-${nextId++}",
                at = now(),
                fromUser = fromUser,
                body = MessageBody.Text(text),
            )
        }
    }

    companion object {
        /**
         * ⚠ Names Control, and here that is right: this repository exists only where Control has
         * not provisioned the app, which is the one thing the user can go and change.
         */
        const val NOT_CONNECTED: String =
            "I'm not set up on this device yet, so I can't look anything up. Set up Tenshu " +
                "Control, then ask me again."
    }
}
