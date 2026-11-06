package org.tenshu.core.common.epc

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2b of `docs/proposals/ios-proxy-architecture.md` — proves an
 * [EpcTaskTransport] carries an encrypted client→proxy operation round-trip over
 * a plain in-process loopback, with the proxy side seeing only plaintext after
 * it decrypts (no proxy-core change).
 *
 * Runs against [BootstrapSyncCryptoManager] so it executes on the iOS simulator
 * gate too; the "wire bytes are real ciphertext / tamper fails" properties (the
 * stub's XOR can't prove those) live in `EpcTaskTransportRealCryptoTest`.
 */
class EpcTaskTransportTest {

    private val crypto = BootstrapSyncCryptoManager()

    /**
     * A minimal in-process [TaskTransport]: [handle] stands in for the proxy's
     * accept→gateway path and may push callbacks back to the client mid-call.
     */
    private class LoopbackTaskTransport(
        val handle: suspend (request: ByteArray, pushCallback: (ByteArray) -> Unit) -> ByteArray,
    ) : TaskTransport {
        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override suspend fun call(request: ByteArray): ByteArray =
            handle(request) { onCallback?.invoke(it) }
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
    }

    private fun handshake(): Pair<EpcSession, EpcSession> {
        val client = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val proxy = EpcHandshake.begin(crypto, EpcRole.PROXY)
        val clientSide = EpcHandshake.complete(crypto, client, proxy.localPublicKey)
        val proxySide = EpcHandshake.complete(crypto, proxy, client.localPublicKey)
        return clientSide.session to proxySide.session
    }

    @Test
    fun requestResponseRoundTripsEncrypted() = runTest {
        val (clientSession, proxySession) = handshake()
        var sawPlaintextOnWire = true
        val request = "open /Documents/report.pdf".encodeToByteArray()

        val loopback = LoopbackTaskTransport { sealedRequest, _ ->
            // The proxy side never sees the request plaintext on the wire…
            sawPlaintextOnWire = sealedRequest.contentEquals(request)
            val plain = proxySession.open(sealedRequest)        // …only after it decrypts.
            assertContentEquals(request, plain)
            proxySession.seal("ok: ${plain.decodeToString()}".encodeToByteArray())
        }
        val transport = EpcTaskTransport(loopback, clientSession)

        val response = transport.call(request)
        assertContentEquals("ok: open /Documents/report.pdf".encodeToByteArray(), response)
        assertFalse(sawPlaintextOnWire, "request must be sealed on the wire")
    }

    @Test
    fun sequentialCallsAdvanceTheCounters() = runTest {
        val (clientSession, proxySession) = handshake()
        val transport = EpcTaskTransport(
            LoopbackTaskTransport { sealed, _ ->
                val n = proxySession.open(sealed).decodeToString().toInt()
                proxySession.seal((n * 2).toString().encodeToByteArray())
            },
            clientSession,
        )
        for (i in 1..20) {
            assertEquals((i * 2).toString(), transport.call(i.toString().encodeToByteArray()).decodeToString())
        }
    }

    @Test
    fun pushedCallbackRoundTripsEncrypted() = runTest {
        val (clientSession, proxySession) = handshake()
        val callbackPayload = "stream-chunk-1".encodeToByteArray()

        val loopback = LoopbackTaskTransport { sealedRequest, pushCallback ->
            proxySession.open(sealedRequest)
            // Proxy emits a callback before the response (in-order on proxy→client).
            pushCallback(proxySession.seal(callbackPayload))
            proxySession.seal("done".encodeToByteArray())
        }
        val transport = EpcTaskTransport(loopback, clientSession)
        val received = mutableListOf<ByteArray>()
        transport.onCallback = { received.add(it) }

        val response = transport.call("subscribe".encodeToByteArray())

        assertContentEquals("done".encodeToByteArray(), response)
        assertEquals(1, received.size)
        assertContentEquals(callbackPayload, received.single())
    }

    @Test
    fun cancellableCallRoundTripsEncrypted() = runTest {
        val (clientSession, proxySession) = handshake()
        val transport = EpcTaskTransport(
            object : TaskTransport {
                override val isConnected = true
                override fun bind() {}
                override fun unbind() {}
                override suspend fun call(request: ByteArray) = error("should use callCancellable")
                override suspend fun callCancellable(request: ByteArray, taskId: String): ByteArray {
                    assertEquals("task-7", taskId, "taskId stays in the clear for routing")
                    return proxySession.seal(proxySession.open(request))
                }
                override fun sendFile(uri: String) {}
                override var onCallback: ((ByteArray) -> Unit)? = null
            },
            clientSession,
        )
        val echoed = transport.callCancellable("payload".encodeToByteArray(), "task-7")
        assertContentEquals("payload".encodeToByteArray(), echoed)
    }

    @Test
    fun delegatesConnectionLifecycleToInner() = runTest {
        val (clientSession, _) = handshake()
        var bound = false
        val inner = object : TaskTransport {
            override val isConnected get() = bound
            override fun bind() { bound = true }
            override fun unbind() { bound = false }
            override suspend fun call(request: ByteArray) = request
            override fun sendFile(uri: String) {}
            override var onCallback: ((ByteArray) -> Unit)? = null
        }
        val transport = EpcTaskTransport(inner, clientSession)
        assertFalse(transport.isConnected)
        transport.bind()
        assertTrue(transport.isConnected)
        transport.unbind()
        assertFalse(transport.isConnected)
    }
}
