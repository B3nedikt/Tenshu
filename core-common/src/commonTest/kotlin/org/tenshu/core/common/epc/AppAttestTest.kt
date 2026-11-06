package org.tenshu.core.common.epc

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 4a of `docs/proposals/ios-proxy-architecture.md` — EPC measure 2
 * (app attestation) flow + the channel prelude. Uses `BootstrapSyncCryptoManager`
 * so it runs on the iOS simulator gate; the signature-strength properties (a
 * tampered attestation fails a real Ed25519 verify) live in
 * `AppAttestRealCryptoTest`.
 */
class AppAttestTest {

    private val crypto = BootstrapSyncCryptoManager()
    private val attestationKey = crypto.generateDeviceSigningKeyPair()

    private fun attestor() = SoftwareAppAttestor(crypto, attestationKey)
    private fun verifier(isTrusted: (ByteArray) -> Boolean = { true }) =
        SoftwareAppAttestVerifier(crypto, isTrusted)

    @Test
    fun validAttestationOverItsChallengeIsAttested() {
        val challenge = nextSecureRandomBytes(32)
        val result = verifier().verify(challenge, attestor().attest(challenge))
        val attested = assertIs<AppAttestResult.Attested>(result)
        assertTrue(attested.keyId.isNotEmpty())
    }

    @Test
    fun attestationBoundToADifferentChallengeIsRejected() {
        val signedOver = nextSecureRandomBytes(32)
        val attestation = attestor().attest(signedOver)
        val result = verifier().verify(nextSecureRandomBytes(32), attestation)
        assertIs<AppAttestResult.Rejected>(result)
    }

    @Test
    fun untrustedKeyIsRejectedEvenWithAValidSignature() {
        val challenge = nextSecureRandomBytes(32)
        val result = verifier(isTrusted = { false }).verify(challenge, attestor().attest(challenge))
        assertIs<AppAttestResult.Rejected>(result)
    }

    @Test
    fun malformedAttestationIsRejected() {
        assertIs<AppAttestResult.Rejected>(verifier().verify(nextSecureRandomBytes(32), byteArrayOf(0)))
        assertIs<AppAttestResult.Rejected>(verifier().verify(nextSecureRandomBytes(32), ByteArray(0)))
    }

    @Test
    fun verdictEncodingRoundTrips() {
        assertEquals(
            AppAttestResult.Attested("abc123"),
            decodeVerdict(encodeVerdict(AppAttestResult.Attested("abc123"))),
        )
        assertEquals(
            AppAttestResult.Rejected("nope"),
            decodeVerdict(encodeVerdict(AppAttestResult.Rejected("nope"))),
        )
    }

    // ── Channel prelude over the loopback (the 2b EpcTaskTransport harness) ──────

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

    private fun pairedSessions(): Pair<EpcSession, EpcSession> {
        val client = EpcHandshake.begin(crypto, EpcRole.CLIENT)
        val proxy = EpcHandshake.begin(crypto, EpcRole.PROXY)
        return EpcHandshake.complete(crypto, client, proxy.localPublicKey).session to
            EpcHandshake.complete(crypto, proxy, client.localPublicKey).session
    }

    /** A proxy channel handler that runs the attestation prelude, then gates operations. */
    private fun gatedTransport(
        verifier: AppAttestVerifier,
    ): Pair<EpcTaskTransport, EpcAttestationServer> {
        val (clientSession, proxySession) = pairedSessions()
        val server = EpcAttestationServer(verifier) { nextSecureRandomBytes(32) }
        val loopback = LoopbackTaskTransport { sealed ->
            val plain = proxySession.open(sealed)
            val reply = when {
                server.inPrelude -> server.onPreludeFrame(plain)
                server.isAttested -> "echo:".encodeToByteArray() + plain
                else -> throw EpcAttestationException("operation refused: channel not attested")
            }
            proxySession.seal(reply)
        }
        return EpcTaskTransport(loopback, clientSession) to server
    }

    @Test
    fun preludeAttestsThenOperationsFlow() = runTest {
        val (transport, server) = gatedTransport(verifier())

        val verdict = EpcAttestationClient(attestor()).attest(transport)
        assertIs<AppAttestResult.Attested>(verdict)
        assertTrue(server.isAttested)

        // Now operations are served end-to-end over the attested channel.
        assertContentEquals("echo:open".encodeToByteArray(), transport.call("open".encodeToByteArray()))
    }

    @Test
    fun rejectedAttestationBlocksOperations() = runTest {
        val (transport, server) = gatedTransport(verifier(isTrusted = { false }))

        val verdict = EpcAttestationClient(attestor()).attest(transport)
        assertIs<AppAttestResult.Rejected>(verdict)
        assertFalse(server.isAttested)

        // The gate refuses operation traffic on an unattested channel.
        var refused = false
        try {
            transport.call("open".encodeToByteArray())
        } catch (e: Throwable) {
            refused = true
        }
        assertTrue(refused, "operations must be refused after a rejected attestation")
    }
}
