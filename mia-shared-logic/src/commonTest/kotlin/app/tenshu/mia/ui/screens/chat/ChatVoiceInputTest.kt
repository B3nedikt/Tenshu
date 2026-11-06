package app.tenshu.mia.ui.screens.chat

import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.VoiceInput
import app.tenshu.mia.model.Conversation
import app.tenshu.mia.model.MiaMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Speaking to Mia (AV-1, `inference-service.md` IN-D11) — the view-model half.
 *
 * ⭐ There is deliberately nothing here about queries: **a transcript is just an utterance**, so
 * voice ends at the composer and MQ-1's pipeline is untouched.
 */
class ChatVoiceInputTest {

    @BeforeTest
    fun setUp() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    private class Repo(private val replyingFlow: Flow<Boolean> = flowOf(false)) : ConversationRepository {
        val sent = mutableListOf<String>()
        override fun messages(): Flow<List<MiaMessage>> = flowOf(emptyList())
        override fun replying(): Flow<Boolean> = replyingFlow
        override fun history(): Flow<List<Conversation>> = flowOf(emptyList())
        override suspend fun send(text: String) { sent += text }
    }

    private class FakeVoice : VoiceInput {
        override val listening = MutableStateFlow(false)
        override val partial = MutableStateFlow("")
        val finals = MutableSharedFlow<String>(extraBufferCapacity = 4)
        override val transcripts: Flow<String> = finals
        var starts = 0
        var stops = 0
        override fun start() { starts++; listening.value = true }
        override fun stop() { stops++; listening.value = false }
    }

    @Test
    fun withNoRecogniserBoundThereIsNoMic() = vmTest { vms ->
        // ⚠⚠ IN-D11's rule, at the only place it can be enforced: absence of the binding is the
        // "this device cannot do it privately" signal, and the mic is gated on exactly that.
        val viewModel = vms.own(ChatViewModel(Repo(), voice = null))

        assertFalse(viewModel.canListen)
        // And tapping is inert rather than a crash, for the shell that renders one anyway.
        viewModel.toggleListening()
        assertFalse(viewModel.uiState.value.listening)
    }

    @Test
    fun tappingTheMicStartsAndStopsListening() = vmTest { vms ->
        val voice = FakeVoice()
        val viewModel = vms.own(ChatViewModel(Repo(), voice))

        viewModel.toggleListening()
        assertTrue(viewModel.uiState.value.listening)

        viewModel.toggleListening()
        assertFalse(viewModel.uiState.value.listening)
        assertEquals(1, voice.starts)
        assertEquals(1, voice.stops)
    }

    @Test
    fun partialsFillTheComposerAsTheyArrive() = vmTest { vms ->
        val voice = FakeVoice()
        val viewModel = vms.own(ChatViewModel(Repo(), voice))

        voice.partial.value = "when was I"
        assertEquals("when was I", viewModel.uiState.value.input)
        voice.partial.value = "when was I last in paris"

        // ⚠ A recogniser that appears to do nothing for four seconds reads as broken.
        assertEquals("when was I last in paris", viewModel.uiState.value.input)
    }

    @Test
    fun aFinalTranscriptIsPutInTheComposer_neverSent() = vmTest { vms ->
        val repo = Repo()
        val voice = FakeVoice()
        val viewModel = vms.own(ChatViewModel(repo, voice))

        voice.finals.emit("show me my photos from munich")

        // ⚠⚠ The load-bearing decision. A misheard word is the failure voice actually has, and
        // MQ-D7's confirmation only covers the WRITE intents — a misheard search would answer the
        // wrong question with nothing for the user to notice.
        assertEquals("show me my photos from munich", viewModel.uiState.value.input)
        assertEquals(emptyList(), repo.sent)
    }

    @Test
    fun sendingWhileListeningClosesTheMicrophone() = vmTest { vms ->
        val repo = Repo()
        val voice = FakeVoice()
        val viewModel = vms.own(ChatViewModel(repo, voice))
        voice.finals.emit("what did I do yesterday")
        viewModel.toggleListening()

        viewModel.send()

        // ⚠ The microphone must not stay open behind an answer — the OS's recording indicator is
        // how a user finds out about that, and it reads as spying.
        assertEquals(listOf("what did I do yesterday"), repo.sent)
        assertFalse(viewModel.uiState.value.listening)
        assertEquals(1, voice.stops)
    }

    @Test
    fun theMicIsInertWhileMiaIsReplying() = vmTest { vms ->
        val voice = FakeVoice()
        val viewModel = vms.own(ChatViewModel(Repo(replyingFlow = flowOf(true)), voice))

        viewModel.toggleListening()

        // ⚠ Starting a recogniser under a running answer races the composer's own state, and the
        // user has nothing to say yet.
        assertEquals(0, voice.starts)
    }

    // ⚠ `onCleared`'s release is not asserted here: it is `protected` on `ViewModel`, and a
    // reflective back door into the framework would be testing Compose rather than Mia. The
    // microphone's real lifecycle guard is the Activity's `onDestroy` → `release()`, which only a
    // device can exercise (§8.5).
}
