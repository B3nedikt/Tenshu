package org.tenshu.core.common.epc

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Phase 2c of `docs/proposals/ios-proxy-architecture.md` — the full client-side
 * bring-up: [EpcConnector] performs the handshake over a plain loopback against
 * an [EpcResponder], both sides agree on the SAS, and the returned
 * [EpcConnection.transport] then carries encrypted operation traffic.
 *
 * Uses [BootstrapSyncCryptoManager] so it runs on the iOS simulator gate; the
 * real-crypto SAS-agreement / MITM properties are pinned in
 * `EpcConnectorRealCryptoTest`.
 */
class EpcConnectorTest {

    private val crypto = BootstrapSyncCryptoManager()

    /**
     * A stateful in-process transport that is a plain pipe for the first
     * (handshake) call and an [EpcResponder]-backed proxy thereafter — modelling
     * how a real transport carries the cleartext hello then sealed operations.
     */
    private class ResponderLoopback(
        private val responder: EpcResponder,
        private val process: (ByteArray) -> ByteArray,
    ) : TaskTransport {
        var accepted: EpcResponder.Accepted? = null
            private set

        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null

        override suspend fun call(request: ByteArray): ByteArray {
            val session = accepted?.session
            return if (session == null) {
                responder.accept(request).also { accepted = it }.serverHello
            } else {
                session.seal(process(session.open(request)))
            }
        }
    }

    @Test
    fun connectorAndResponderEstablishAnEncryptedChannel() = runTest {
        val loopback = ResponderLoopback(EpcResponder(crypto)) { plain ->
            "echo:${plain.decodeToString()}".encodeToByteArray()
        }

        val connection = EpcConnector(crypto).connect(loopback)

        // Both ends derived the same SAS from the handshake.
        assertEquals(loopback.accepted!!.sas, connection.sas)

        // The returned transport now carries sealed operation traffic end to end.
        val response = connection.transport.call("hello".encodeToByteArray())
        assertContentEquals("echo:hello".encodeToByteArray(), response)
    }

    @Test
    fun multipleOperationsRideTheEstablishedChannel() = runTest {
        val loopback = ResponderLoopback(EpcResponder(crypto)) { it }
        val connection = EpcConnector(crypto).connect(loopback)
        repeat(10) { i ->
            val payload = "op-$i".encodeToByteArray()
            assertContentEquals(payload, connection.transport.call(payload))
        }
    }

    @Test
    fun aWrongVersionServerHelloIsRejected() = runTest {
        val badProxy = object : TaskTransport {
            override val isConnected = true
            override fun bind() {}
            override fun unbind() {}
            override fun sendFile(uri: String) {}
            override var onCallback: ((ByteArray) -> Unit)? = null
            override suspend fun call(request: ByteArray): ByteArray =
                byteArrayOf(99) + ByteArray(5) // version 99 ServerHello
        }
        assertFailsWith<EpcHandshakeFormatException> { EpcConnector(crypto).connect(badProxy) }
    }
}
