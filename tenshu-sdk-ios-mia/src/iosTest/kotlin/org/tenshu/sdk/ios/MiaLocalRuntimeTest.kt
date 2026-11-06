package org.tenshu.sdk.ios

import app.tenshu.mia.data.NotConnectedConversationRepository
import app.tenshu.mia.model.MessageBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `mia-query-layer.md` §19: the route-1 in-process Mia runtime assembles the FULL production proxy
 * graph in this process and answers `askMia` from it. The Mia twin of [ContactsLocalRuntimeTest] /
 * [MapsLocalRuntimeTest].
 *
 * What it proves is the two claims the slice rests on, against the **real iOS
 * `PlatformProxyModule`** rather than a harness: the graph already hosts `:mia-service` and what it
 * reads, so a question is answered here; and that graph offers no action.
 */
class MiaLocalRuntimeTest {

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "mia-local-runtime-test-" + Random.nextLong().toString(16)

    private fun assemble() = MiaLocalRuntime.assemble(
        replicaDirectory = tempReplicaDir(),
        secureStorage = InMemorySecureStorageDelegate(),
    )

    private suspend fun MiaLocalRuntime.Assembly.reply(utterance: String): MessageBody {
        conversationRepository.send(utterance)
        val messages = conversationRepository.messages().first()
        assertFalse(messages.last().fromUser, "send() appends Mia's reply before it returns")
        return messages.last().body
    }

    /**
     * ⚠⚠ The sentence asserted is the one only a chain that RAN can produce. An unwired service,
     * an unregistered schema or a refused chain all end in `COULD_NOT_LOOK` or the generic
     * unavailable line, and "an empty reply" would not tell any of them from a working read.
     */
    @Test
    fun aReadIsAnsweredByTheInProcessProxy() = runBlocking {
        val said = assertIs<MessageBody.Text>(assemble().reply("find the invoice")).text

        assertTrue(said.endsWith("nothing matched."), said)
        assertFalse(said == MiaLocalRuntime.COULD_NOT_LOOK, said)
    }

    /**
     * The wiring half of `AskMiaActionOfferTest`: that test proves what `WITHHELD` does, this one
     * that the graph an iPhone actually runs is built with it.
     */
    @Test
    fun anActionUtteranceIsRefusedAndNothingIsOfferedToTap() = runBlocking {
        val assembly = assemble()

        listOf(
            "turn off the kitchen light",
            "run my good morning routine",
            "add lunch with anna tomorrow at 1pm",
        ).forEach { utterance ->
            val body = assembly.reply(utterance)

            // ⚠ A `Confirmation` here is the defect: a cell whose tap this runtime refuses.
            val said = assertIs<MessageBody.Text>(body, utterance).text
            assertTrue(said.contains("this device"), said)
            // ⚠⚠ Not "… — nothing matched.", which is what a refusal returned as ANSWERED renders
            // as, and not a claim about a Control channel this process does not have.
            assertFalse(said.contains("nothing matched"), said)
            assertFalse(said.contains("Control"), said)
        }
    }

    @Test
    fun withNoRuntimeTheSurfaceSaysSoAndInventsNothing() = runBlocking {
        // ⚠ The default matters: `MiaSurface` bound a mock here until 2026-10-02, and the mock
        // answers this exact utterance with a grid of photos nobody took.
        val repository = NotConnectedConversationRepository()
        repository.send("Show me a photo from last summer")

        val body = repository.messages().first().last().body
        assertEquals(
            NotConnectedConversationRepository.NOT_CONNECTED,
            assertIs<MessageBody.Text>(body).text,
        )
    }
}
