package org.tenshu.core.common.epc

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.security.BouncyCastleAeadBackend
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.BouncyCastleKdfBackend
import org.tenshu.core.common.security.BouncyCastleX25519Backend
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import org.tenshu.core.common.transport.TaskTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2b, crypto-strength half — wires [EpcTaskTransport] against the real
 * BouncyCastle backends so it can prove the wire really carries authenticated
 * ciphertext (XChaCha20-Poly1305), which the pure-Kotlin stub in
 * `EpcTaskTransportTest` can't. Desktop/JVM only (iOS crypto backends still
 * throw — Phase 2b on device gates on CryptoKit wiring).
 */
class EpcTaskTransportRealCryptoTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    private class LoopbackTaskTransport(
        val handle: suspend (ByteArray) -> ByteArray,
    ) : TaskTransport {
        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override suspend fun call(request: ByteArray) = handle(request)
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
    }

    private fun handshake(): Pair<EpcSession, EpcSession> {
        val client = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val proxy = EpcHandshake.begin(crypto, EpcRole.PROXY)
        return EpcHandshake.complete(crypto, client, proxy.localPublicKey).session to
            EpcHandshake.complete(crypto, proxy, client.localPublicKey).session
    }

    @Test
    fun theWireCarriesAuthenticatedCiphertextNotPlaintext() = runTest {
        val (clientSession, proxySession) = handshake()
        val request = "secret request body".encodeToByteArray()
        var onWire = ByteArray(0)

        val transport = EpcTaskTransport(
            LoopbackTaskTransport { sealed ->
                onWire = sealed
                proxySession.seal(proxySession.open(sealed))
            },
            clientSession,
        )

        assertContentEquals(request, transport.call(request))
        assertFalse(onWire.contentEquals(request))
        // 8-byte sequence prefix + 24-byte nonce + 16-byte Poly1305 tag of overhead.
        assertTrue(onWire.size >= request.size + 8 + 24 + 16, "got ${onWire.size} bytes")
    }

    @Test
    fun aTamperedResponseFailsTheCall() = runTest {
        val (clientSession, proxySession) = handshake()
        val transport = EpcTaskTransport(
            LoopbackTaskTransport { sealed ->
                val response = proxySession.seal(proxySession.open(sealed))
                response.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
            },
            clientSession,
        )
        assertFailsWith<Throwable> { transport.call("ping".encodeToByteArray()) }
    }
}
