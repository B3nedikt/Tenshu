package app.tenshu.mia.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.VoiceInput
import app.tenshu.mia.model.Hit
import app.tenshu.mia.model.MiaMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.common.app.OpenTargetResult
import org.tenshu.core.common.app.TargetOpener

data class ChatUiState(
    val messages: List<MiaMessage> = emptyList(),
    val input: String = "",
    /** Mia is composing — the typing indicator. */
    val replying: Boolean = false,
    /** AV-1: the recogniser is listening — the mic shows it and the composer is read-only. */
    val listening: Boolean = false,
) {
    val canSend: Boolean get() = input.isNotBlank() && !replying
}

/**
 * The chat surface's view model (docs/proposals/home-and-mia-apps.md §4, M3) — used by the
 * Mia app's Chat tab AND by Home's embedded "Ask Mia" destination (same VM, same repo
 * binding, one conversation).
 */
class ChatViewModel(
    private val repository: ConversationRepository,
    /**
     * AV-1 — null where the platform cannot recognise speech **on-device** (IN-D11), which is what
     * hides the mic rather than offering one that would send audio away.
     */
    private val voice: VoiceInput? = null,
    /**
     * Cross-app open (`control-cross-app-open.md` TT-1b/TT-1c) — null on a platform with no
     * client stack, where a citation stays inert rather than failing.
     */
    private val opener: TargetOpener? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.messages().collect { v -> _uiState.update { it.copy(messages = v) } }
        }
        viewModelScope.launch {
            repository.replying().collect { v -> _uiState.update { it.copy(replying = v) } }
        }
        voice?.let { input ->
            viewModelScope.launch {
                input.listening.collect { v -> _uiState.update { it.copy(listening = v) } }
            }
            // ⚠ Partials land in the composer as they arrive: a recogniser that appears to do
            // nothing for four seconds reads as broken.
            viewModelScope.launch {
                input.partial.collect { v -> if (v.isNotEmpty()) _uiState.update { it.copy(input = v) } }
            }
            // ⚠⚠ The final transcript is PUT IN THE COMPOSER, never sent. A misheard word is the
            // failure voice actually has, and MQ-D7's confirmation only covers the write intents —
            // a misheard search would answer the wrong question with nothing to notice.
            viewModelScope.launch {
                input.transcripts.collect { text -> _uiState.update { it.copy(input = text) } }
            }
        }
    }

    /** Whether this build can listen at all — the mic's whole gate. */
    val canListen: Boolean get() = voice != null

    /**
     * Mic tapped: start listening, or stop if already.
     *
     * ⚠ Ignored while Mia is replying — a recogniser started under a running answer would race the
     * composer's own state, and the user has nothing to say yet anyway.
     */
    fun toggleListening() {
        val input = voice ?: return
        if (_uiState.value.replying) return
        if (_uiState.value.listening) input.stop() else input.start()
    }

    override fun onCleared() {
        // ⚠ A recogniser holds the microphone. Leaving it open when the screen goes away is the
        // kind of thing a user finds out about from their OS's recording indicator.
        voice?.stop()
        super.onCleared()
    }

    fun setInput(text: String) = _uiState.update { it.copy(input = text) }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.replying) return
        // ⚠ Sending ends a dictation: the mic must not stay open behind an answer.
        if (_uiState.value.listening) voice?.stop()
        _uiState.update { it.copy(input = "") }
        viewModelScope.launch { repository.send(text) }
    }

    /**
     * Runs a pending confirmation (MQ-D7). ⚠ The only path from this surface that changes
     * anything — [send] resolves and shows, it never actuates.
     */
    fun confirm(token: String) {
        if (_uiState.value.replying) return
        viewModelScope.launch { repository.confirm(token) }
    }

    /**
     * A citation was tapped — ask Control to show the row in whichever app owns it
     * (`control-cross-app-open.md` TT-1c). ⭐ **The first tap-through in the platform.**
     *
     * ⚠⚠ **Every outcome says something.** §14's device pass found that a refusal rendered as an
     * empty result reads as "nothing matched", and a tap is worse that way: nothing visibly
     * happens at all, so the user cannot tell a refusal from a broken button (CO-D7). The five
     * reasons therefore become five sentences, and the two successes say nothing — because the
     * app appearing IS the feedback.
     */
    fun openHit(hit: Hit) {
        val opener = this.opener ?: return
        viewModelScope.launch {
            when (val result = opener.open(hit.target)) {
                // ⚠ Silent on purpose: the handler coming to the front is the acknowledgement, and
                // a toast under an app that just opened is noise.
                OpenTargetResult.Delivered, OpenTargetResult.LaunchRequested -> Unit
                is OpenTargetResult.Refused -> repository.note(sentenceFor(result))
                OpenTargetResult.Unreachable -> repository.note(CANNOT_REACH_CONTROL)
            }
        }
    }

    /**
     * ⚠⚠ **The reason, not the message.** `OpenTargetUnavailable.message` is a developer string
     * written proxy-side ("app.tenshu.calendar is not installed on this device") — it names an
     * `appId` the user has never seen and cannot act on. The stable token is what the caller
     * localises, which is exactly what CO-D9 says the token is for.
     */
    private fun sentenceFor(refusal: OpenTargetResult.Refused): String = when (refusal.reason) {
        OpenTargetUnavailable.Reasons.NO_HANDLER ->
            "Nothing on this device opens that yet."
        // ⚠ Names no app: the handler is Control's resolution, and telling the user to install
        // "Calendar" would be a routing decision leaking out of the one place that owns it.
        OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE ->
            "The app that opens that isn't installed on this device."
        OpenTargetUnavailable.Reasons.LAUNCH_FAILED ->
            "That app didn't open. Try again in a moment."
        OpenTargetUnavailable.Reasons.NOT_PERMITTED ->
            "I'm not allowed to open that."
        OpenTargetUnavailable.Reasons.NO_DELIVERY ->
            "Opening apps isn't supported here yet."
        // ⚠ A newer proxy naming a reason this build has never heard of. Saying something vague is
        // better than saying nothing, which is the failure this whole method exists to avoid.
        else -> "I couldn't open that."
    }

    private companion object {
        const val CANNOT_REACH_CONTROL = "I can't reach Control right now."
    }
}
