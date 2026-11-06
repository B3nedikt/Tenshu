package org.tenshu.core.common.epc

import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 2a of `docs/proposals/ios-proxy-architecture.md` — the EPC handshake +
 * session **protocol logic**. Runs against [BootstrapSyncCryptoManager]
 * (pure-Kotlin, cross-platform) so the suite also executes on the iOS simulator
 * gate, where the real crypto backends are still throwing stubs.
 *
 * The bootstrap manager is a *stub* (every keypair is the same `"dummy"`, the
 * cipher is a malleable XOR) — it proves the framing, sequencing, directional
 * key split, and replay/reorder rules, but **cannot** prove the crypto-strength
 * properties (a real ECDH so a MITM's SAS diverges; AEAD so tampering fails).
 * Those live in `EpcRealCryptoTest` (desktop-only, real BouncyCastle backends).
 */
class EpcHandshakeTest {

    private val crypto = BootstrapSyncCryptoManager()

    /** Pair an app and a proxy by exchanging their ephemeral public keys honestly. */
    private fun pair(): Pair<EpcHandshake.Established, EpcHandshake.Established> {
        val client = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val proxy = EpcHandshake.begin(crypto, EpcRole.PROXY)
        val clientSide = EpcHandshake.complete(crypto, client, proxy.localPublicKey)
        val proxySide = EpcHandshake.complete(crypto, proxy, client.localPublicKey)
        return clientSide to proxySide
    }

    @Test
    fun bothEndsDeriveTheSameSas() {
        val (clientSide, proxySide) = pair()
        assertEquals(clientSide.sas, proxySide.sas)
        assertEquals(6, clientSide.sas.length, "SAS is a 6-digit string")
        assertTrue(clientSide.sas.all { it.isDigit() })
    }

    @Test
    fun clientToProxyFrameRoundTrips() {
        val (clientSide, proxySide) = pair()
        val message = "list /Photos".encodeToByteArray()
        val opened = proxySide.session.open(clientSide.session.seal(message))
        assertContentEquals(message, opened)
    }

    @Test
    fun proxyToClientFrameRoundTrips() {
        val (clientSide, proxySide) = pair()
        val callback = byteArrayOf(0, 1, 2, 3, 42, -7)
        val opened = clientSide.session.open(proxySide.session.seal(callback))
        assertContentEquals(callback, opened)
    }

    @Test
    fun manyFramesRoundTripInOrder() {
        val (clientSide, proxySide) = pair()
        repeat(50) { i ->
            val payload = "frame-$i".encodeToByteArray()
            assertContentEquals(payload, proxySide.session.open(clientSide.session.seal(payload)))
        }
    }

    @Test
    fun replayingAFrameIsRejected() {
        val (clientSide, proxySide) = pair()
        val frame = clientSide.session.seal("once".encodeToByteArray())
        proxySide.session.open(frame)
        assertFailsWith<EpcChannelException> { proxySide.session.open(frame) }
    }

    @Test
    fun reorderingFramesIsRejected() {
        val (clientSide, proxySide) = pair()
        val first = clientSide.session.seal("first".encodeToByteArray())
        val second = clientSide.session.seal("second".encodeToByteArray())
        proxySide.session.open(second)
        // First now has a lower sequence than the last accepted one.
        assertFailsWith<EpcChannelException> { proxySide.session.open(first) }
    }

    @Test
    fun aRejectedFrameDoesNotGateOutTheNextLegitimateOne() {
        val (clientSide, proxySide) = pair()
        val good0 = clientSide.session.seal("zero".encodeToByteArray())
        val good1 = clientSide.session.seal("one".encodeToByteArray())
        proxySide.session.open(good0)
        // A replay of frame 0 fails but must not advance the receiver past frame 1.
        assertFailsWith<EpcChannelException> { proxySide.session.open(good0) }
        assertContentEquals("one".encodeToByteArray(), proxySide.session.open(good1))
    }

    @Test
    fun aFrameCannotBeReflectedToItsSender() {
        // Directional keys: the client must not be able to open a frame it sealed
        // (that would mean a single key for both directions).
        val (clientSide, _) = pair()
        val frame = clientSide.session.seal("reflect me".encodeToByteArray())
        assertFailsWith<Throwable> { clientSide.session.open(frame) }
    }

    @Test
    fun aTruncatedFrameIsRejected() {
        val (clientSide, proxySide) = pair()
        clientSide.session.seal("x".encodeToByteArray())
        assertFailsWith<EpcChannelException> { proxySide.session.open(byteArrayOf(1, 2, 3)) }
    }
}
