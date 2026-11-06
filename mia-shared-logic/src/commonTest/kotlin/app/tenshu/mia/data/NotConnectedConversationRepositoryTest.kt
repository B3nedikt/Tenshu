package app.tenshu.mia.data

import app.tenshu.mia.model.MessageBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Service
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What Mia says when it cannot ask — the two repositories a shell can hold without a working
 * runtime behind them.
 */
class NotConnectedConversationRepositoryTest {

    private fun lastText(messages: List<app.tenshu.mia.model.MiaMessage>): String =
        assertIs<MessageBody.Text>(messages.last().body).text

    /**
     * ⚠⚠ The utterances are the mock's own trigger words. A not-connected repository that had
     * been built by trimming the mock would still answer one of them with a photo grid, a place
     * card or "Turning on …" — invented rows rendered as the user's own.
     */
    @Test
    fun everyQuestionGetsTheSameTrueSentence_andNeverARichResult() = runTest {
        val repository = NotConnectedConversationRepository()

        listOf(
            "Show me a photo from last summer",
            "Where was that café again?",
            "Turn on the sofa lamp",
        ).forEach { utterance ->
            repository.send(utterance)
            val messages = repository.messages().first()
            assertTrue(messages[messages.size - 2].fromUser)
            assertFalse(messages.last().fromUser)
            assertEquals(NotConnectedConversationRepository.NOT_CONNECTED, lastText(messages))
        }

        assertTrue(repository.history().first().isEmpty(), "no seeded history")
        assertFalse(repository.replying().first())
    }

    @Test
    fun blankInputIsIgnored() = runTest {
        val repository = NotConnectedConversationRepository()
        repository.send("   ")
        assertTrue(repository.messages().first().isEmpty())
    }

    /** A client whose proxy cannot be asked at all — the failure both sentences below describe. */
    private class DeadClient : TenshuClient {
        override val environment: TenshuEnvironment get() = error("unused")
        override val proxyConnected: StateFlow<Boolean> = MutableStateFlow(false)
        override fun <T : Service> getService(id: String): T = error("no proxy")
        override fun init() = Unit
        override fun destroy() = Unit
    }

    /** Over a channel to Control, the cause worth naming is Control. */
    @Test
    fun overAChannel_aDeadProxySaysItCannotReachControl() = runTest {
        val repository = TenshuConversationRepository(DeadClient(), now = { 0L }, timeZoneId = { "UTC" })

        repository.send("photos from last summer")

        assertEquals(
            TenshuConversationRepository.CANNOT_REACH_CONTROL,
            lastText(repository.messages().first()),
        )
    }

    /**
     * ⚠⚠ The iOS route-1 wiring: the proxy is the app's own process, so there is no Control
     * channel and the shared sentence would be false every time it appeared. The same dead
     * client, with the runtime's own sentence, must not mention Control at all.
     */
    @Test
    fun inProcess_theSameFailureNamesNoAppTheUserWouldOpenForNothing() = runTest {
        val sentence = "Something went wrong on this device, so I couldn't look that up."
        val repository = TenshuConversationRepository(
            DeadClient(),
            couldNotLook = sentence,
            now = { 0L },
            timeZoneId = { "UTC" },
        )

        repository.send("photos from last summer")

        val said = lastText(repository.messages().first())
        assertEquals(sentence, said)
        assertFalse(said.contains("Control"), said)
    }
}
