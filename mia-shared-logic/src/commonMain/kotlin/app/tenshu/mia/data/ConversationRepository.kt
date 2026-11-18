package app.tenshu.mia.data

import app.tenshu.mia.model.Conversation
import app.tenshu.mia.model.MiaMessage
import kotlinx.coroutines.flow.Flow

/**
 * Mia's conversation surface (docs/proposals/home-and-mia-apps.md §4). M3 binds
 * [MockConversationRepository]; the real backend is the Voice App Service, replaced behind
 * this interface — the same seam every Tenshu app v1 used.
 */
interface ConversationRepository {

    /** The current thread, oldest-first. */
    fun messages(): Flow<List<MiaMessage>>

    /** True while Mia is composing a reply — drives the typing indicator. */
    fun replying(): Flow<Boolean>

    /** Past conversations, newest-first (the History tab). */
    fun history(): Flow<List<Conversation>>

    /** Sends the user's text; the reply arrives asynchronously on [messages]. */
    suspend fun send(text: String)

    /**
     * Runs a pending [MessageBody.Confirmation] (MQ-D7). ⚠ The only path by which the
     * conversation surface changes anything — `send` never does.
     */
    suspend fun confirm(token: String) = Unit

    /**
     * A chip on the [MessageBody.Clarification] holding [token] was tapped (`mia-query-layer.md`
     * §25): [label] is what the user is shown to have said, [reply] what is sent.
     *
     * ⚠⚠ [token] names the chip's OWN question. A chip from an older question must not answer a
     * newer one, so an implementation ignores a token that is no longer the open question.
     * ⚠ Defaulted to sending the label, which every chip label parses as, so a repository with no
     * clarifications needs nothing.
     */
    suspend fun answer(token: String, label: String, reply: String) = send(label)

    /**
     * An alternative on the [MessageBody.Confirmation] holding [token] was tapped (§25, CL-D6). The
     * card is replaced by a new one; nothing is written. ⚠ Defaulted to sending the label.
     */
    suspend fun alternative(token: String, label: String, reply: String) = send(label)

    /**
     * Adds a line from Mia that answers something other than an utterance — today, a tap that
     * could not be honoured (`control-cross-app-open.md` TT-1c).
     *
     * ⚠⚠ It goes through the repository rather than straight into UI state, and that is not
     * ceremony: [messages] is the single source the chat renders, so a view model appending to its
     * own copy would have the line **erased by the next emission** — silently, and only sometimes,
     * which is the worst shape of all.
     *
     * ⚠ Defaulted so the mock and any other implementor keep compiling; a repository with no
     * conversation to add to is entitled to ignore it.
     */
    suspend fun note(text: String) = Unit
}
