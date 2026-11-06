package org.tenshu.core.common.transport

import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The peer-credential gate's enforcement posture is one pure decision
 * ([PeerCredentialPolicy.decide]); these cover all four branches without a socket
 * or native code. Flipping `strict = true` is what turns enforcement on once a real
 * [PeerCredentialVerifier] lands (threat-model T-C-15).
 */
class PeerCredentialPolicyTest {

    private val verified = PeerCredential(verified = true, appId = "app.tenshu.drive", reason = "ok")
    private val unverified = PeerCredential(verified = false, reason = "not implemented")

    @Test
    fun verifiedPeerIsAdmittedQuietly() {
        val a = PeerCredentialPolicy(strict = true).decide(verified)
        assertTrue(a.admit); assertFalse(a.warn)
    }

    @Test
    fun nonStrictAdmitsUnverifiedQuietly() {
        // Today's posture: gate wired, not enforcing — no per-connection log spam.
        val a = PeerCredentialPolicy(strict = false).decide(unverified, developerMode = false)
        assertTrue(a.admit); assertFalse(a.warn)
        assertTrue(a.reason.contains("enforcement not active"))
    }

    @Test
    fun strictRejectsUnverified() {
        val a = PeerCredentialPolicy(strict = true).decide(unverified, developerMode = false)
        assertFalse(a.admit); assertTrue(a.warn)
    }

    @Test
    fun developerModeDowngradesStrictRejectToWarn() {
        // A16: unsigned dev builds connect under a visible warning instead of a hard fail.
        val a = PeerCredentialPolicy(strict = true).decide(unverified, developerMode = true)
        assertTrue(a.admit); assertTrue(a.warn)
    }

    @Test
    fun defaultVerifierReportsUnverified() {
        // The default verifier ignores the channel (no native peer-cred lookup yet);
        // a real unconnected channel keeps the test honest without a server.
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            assertFalse(UnverifiedPeerCredentialVerifier.verify(ch).verified)
        }
    }
}
