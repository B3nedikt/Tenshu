package org.tenshu.core.common.epc

import org.tenshu.core.common.security.BouncyCastleAeadBackend
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.BouncyCastleKdfBackend
import org.tenshu.core.common.security.BouncyCastleX25519Backend
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The crypto-strength half of EPC Phase 2a — wired against the real
 * BouncyCastle backends (`DefaultSyncCryptoManager`), so these pin properties
 * the pure-Kotlin `BootstrapSyncCryptoManager` stub can't express. Desktop/JVM
 * only: the iOS crypto backends are still throwing stubs (Phase 2b gating), so
 * these would throw on the iOS gate — the cross-platform protocol logic lives
 * in `EpcHandshakeTest` instead.
 */
class EpcRealCryptoTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    private fun pair(): Pair<EpcHandshake.Established, EpcHandshake.Established> {
        val client = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val proxy = EpcHandshake.begin(crypto, EpcRole.PROXY)
        return EpcHandshake.complete(crypto, client, proxy.localPublicKey) to
            EpcHandshake.complete(crypto, proxy, client.localPublicKey)
    }

    @Test
    fun honestPairingAgreesOnSasAndChannel() {
        val (clientSide, proxySide) = pair()
        assertEquals(clientSide.sas, proxySide.sas)
        val message = "open /Documents/report.pdf".encodeToByteArray()
        assertContentEquals(message, proxySide.session.open(clientSide.session.seal(message)))
    }

    @Test
    fun independentPairingsProduceDifferentSas() {
        // Real ephemeral keypairs are random, so two fresh pairings almost never
        // share a SAS — proves the keys aren't the stub's constant "dummy".
        assertNotEquals(pair().first.sas, pair().first.sas)
    }

    @Test
    fun aManInTheMiddleProducesDivergentSas() {
        // An attacker terminates a separate ephemeral exchange with each honest
        // end. Because the real ECDH binds both public keys, the shared secret —
        // and therefore the SAS — differs on the two screens, and the user
        // comparing them catches the substitution (whitepaper EPC measure 1).
        val client = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val proxy = EpcHandshake.begin(crypto, EpcRole.PROXY)
        val attackerToClient = EpcHandshake.begin(crypto, EpcRole.PROXY)
        val attackerToProxy = EpcHandshake.begin(crypto, EpcRole.CLIENT)

        val clientSide = EpcHandshake.complete(crypto, client, attackerToClient.localPublicKey)
        val proxySide = EpcHandshake.complete(crypto, proxy, attackerToProxy.localPublicKey)

        assertNotEquals(clientSide.sas, proxySide.sas)
    }

    @Test
    fun aTamperedFrameFailsAuthentication() {
        val (clientSide, proxySide) = pair()
        val frame = clientSide.session.seal("sensitive".encodeToByteArray())
        // Flip a byte inside the AEAD blob (past the 8-byte sequence prefix).
        val tampered = frame.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<Throwable> { proxySide.session.open(tampered) }
    }

    @Test
    fun flippingTheSequencePrefixFailsTheBoundAad() {
        val (clientSide, proxySide) = pair()
        val frame = clientSide.session.seal("first".encodeToByteArray())
        // Sequence 0 → forge it to look like 1 in the prefix; the AEAD's bound
        // AAD no longer matches, so it fails rather than silently re-ordering.
        val forged = frame.copyOf()
        forged[EpcSession.SEQUENCE_SIZE - 1] = 1
        assertFailsWith<Throwable> { proxySide.session.open(forged) }
    }
}
