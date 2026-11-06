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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Phase 2c, crypto-strength half — the connector/responder bring-up against the
 * real BouncyCastle backends. Desktop/JVM only (iOS crypto backends still throw).
 */
class EpcConnectorRealCryptoTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    private class ResponderLoopback(private val responder: EpcResponder) : TaskTransport {
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
                session.seal(session.open(request))
            }
        }
    }

    @Test
    fun realHandshakeAgreesOnSasAndCarriesTraffic() = runTest {
        val loopback = ResponderLoopback(EpcResponder(crypto))
        val connection = EpcConnector(crypto).connect(loopback)

        assertEquals(loopback.accepted!!.sas, connection.sas)
        assertEquals(6, connection.sas.length)

        val payload = "open /shared/Budget.xlsx".encodeToByteArray()
        assertContentEquals(payload, connection.transport.call(payload))
    }

    @Test
    fun independentConnectionsGetDifferentSas() = runTest {
        val a = EpcConnector(crypto).connect(ResponderLoopback(EpcResponder(crypto)))
        val b = EpcConnector(crypto).connect(ResponderLoopback(EpcResponder(crypto)))
        assertNotEquals(a.sas, b.sas)
    }
}
