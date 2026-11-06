package app.tenshu.control.epc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.tenshu.core.common.epc.EpcPairingUri
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultEpcPairingHostTest {

    /** Records provision/serve/push and lets the test release the SAS — stands in for `EpcRendezvousAcceptor`. */
    private class FakeServer : EpcRendezvousServer {
        var provisioned = false
        var handler: (suspend (ByteArray) -> ByteArray)? = null
        var lastPush: ByteArray? = null
        private val served = CompletableDeferred<Unit>()
        val sas = CompletableDeferred<String>()

        override suspend fun provision() {
            provisioned = true
        }

        override suspend fun serve(handler: suspend (ByteArray) -> ByteArray) {
            this.handler = handler
            served.await() // mirror the real loop: suspend until cancelled
        }

        override suspend fun awaitEstablishedSas(): String = sas.await()

        override suspend fun pushCallback(plaintext: ByteArray): Boolean {
            lastPush = plaintext
            return true
        }
    }

    private val fixedSecret = ByteArray(16) { (it + 1).toByte() }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun emits_an_invite_for_the_minted_secret_then_surfaces_the_sas() = runTest {
        val server = FakeServer()
        var invite: String? = null
        var sas: String? = null

        val host = DefaultEpcPairingHost(
            relayBaseUrl = "https://relay.example",
            serverFactory = { server },
            dispatch = { _, request -> request },
            router = EpcCallbackRouter(),
            generateSecret = { fixedSecret },
        )

        val job = launch { host.run(onInvite = { invite = it }, onSas = { sas = it }) }
        advanceUntilIdle()

        // Provisioned, and the invite carries the minted secret + relay against this session.
        assertTrue(server.provisioned)
        val payload = EpcPairingUri.decode(invite!!)
        assertEquals("https://relay.example", payload.relayBaseUrl)
        assertContentEquals(fixedSecret, payload.pairingSecret)
        assertNull(sas) // no peer yet

        // Peer handshakes → the host surfaces the acceptor's SAS exactly once.
        server.sas.complete("421337")
        advanceUntilIdle()
        assertEquals("421337", sas)

        job.cancelAndJoin()
    }

    @Test
    fun dispatches_decrypted_ops_under_a_session_scoped_client_id() = runTest {
        val server = FakeServer()
        var dispatchedClientId: String? = null

        val host = DefaultEpcPairingHost(
            relayBaseUrl = "https://relay.example",
            serverFactory = { server },
            dispatch = { clientId, request ->
                dispatchedClientId = clientId
                "echo:".encodeToByteArray() + request
            },
            router = EpcCallbackRouter(),
            generateSecret = { fixedSecret },
        )

        val job = launch { host.run(onInvite = {}, onSas = {}) }
        advanceUntilIdle()

        val response = server.handler!!.invoke("list /Photos".encodeToByteArray())
        assertContentEquals("echo:list /Photos".encodeToByteArray(), response)
        assertTrue(dispatchedClientId!!.startsWith("epc:"))

        job.cancelAndJoin()
    }

    @Test
    fun a_provision_failure_propagates_out_of_run() = runTest {
        val server = object : EpcRendezvousServer {
            override suspend fun provision() = throw IllegalStateException("session cap reached")
            override suspend fun serve(handler: suspend (ByteArray) -> ByteArray) = Unit
            override suspend fun awaitEstablishedSas(): String = CompletableDeferred<String>().await()
            override suspend fun pushCallback(plaintext: ByteArray) = false
        }
        val host = DefaultEpcPairingHost(
            relayBaseUrl = "https://relay.example",
            serverFactory = { server },
            dispatch = { _, r -> r },
            router = EpcCallbackRouter(),
            generateSecret = { fixedSecret },
        )

        var caught: Throwable? = null
        val job = launch {
            try {
                host.run(onInvite = {}, onSas = {})
            } catch (t: Throwable) {
                caught = t
            }
        }
        advanceUntilIdle()
        job.join()

        assertTrue(caught is IllegalStateException)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun registers_a_push_route_while_serving_and_unregisters_on_teardown() = runTest {
        val server = FakeServer()
        val router = EpcCallbackRouter()
        val host = DefaultEpcPairingHost(
            relayBaseUrl = "https://relay.example",
            serverFactory = { server },
            dispatch = { _, r -> r },
            router = router,
            generateSecret = { fixedSecret },
        )
        val clientId = "epc:" + buildEpcPairingInvite("https://relay.example", fixedSecret).sessionId

        val job = launch { host.run(onInvite = {}, onSas = {}) }
        advanceUntilIdle()

        // A route exists while the session is live, and it forwards into the server's pushCallback.
        val push = router.route(clientId)
        assertTrue(push != null)
        assertTrue(push!!.invoke("badge=3".encodeToByteArray()))
        assertContentEquals("badge=3".encodeToByteArray(), server.lastPush)

        // Teardown unregisters the route — later pushes for this client drop.
        job.cancelAndJoin()
        advanceUntilIdle()
        assertNull(router.route(clientId))
    }
}
