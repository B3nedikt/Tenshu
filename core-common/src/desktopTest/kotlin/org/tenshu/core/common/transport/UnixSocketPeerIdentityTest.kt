package org.tenshu.core.common.transport

import org.tenshu.core.common.TestProxy
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the desktop session-identity contract (`desktop-ipc-client-identity.md` P2).
 *
 * Before P2 every UDS peer was keyed under one hardcoded `"drive-desktop-client"`, which
 * caused a bug nothing else in the suite would catch: `remoteConnections` is keyed by that
 * id, so the **second app to connect replaced the first**, and every proxy→client push —
 * reactive results, stream callbacks — went to whichever app connected last. It presents as
 * "Drive's UI went dead", indistinguishable from an unrelated resubscription bug.
 *
 * These tests drive the server through raw sockets rather than the full client stack: the
 * connection is registered at accept time, before any frame is read, so a bare `connect` is
 * enough to observe the keying — and keeps the test about identity rather than about the
 * operation pipeline.
 */
class UnixSocketPeerIdentityTest {

    @Test
    fun twoAttestedPeersGetSeparateConnections() = withServer(
        // A verifier that attests each connection to a different app, i.e. Drive and Fit
        // running side by side.
        verifier = sequentialVerifier("app.tenshu.drive", "app.tenshu.fit"),
    ) { server, socketPath ->
        connect(socketPath)
        connect(socketPath)
        awaitRegistration(server, "app.tenshu.drive")
        awaitRegistration(server, "app.tenshu.fit")

        val drive = server.getRemoteConnection("app.tenshu.drive")
        val fit = server.getRemoteConnection("app.tenshu.fit")
        assertNotNull(drive, "Drive's connection must be reachable by its own id")
        assertNotNull(fit, "Fit's connection must be reachable by its own id")
        // The actual regression: one must not have displaced the other.
        assertNotEquals(drive, fit, "each peer needs its own connection, not a shared slot")
    }

    @Test
    fun unattestedPeersDoNotShareASlot() = withServer(
        // Both peers fail attestation — the case a shared "unknown" constant would collapse.
        verifier = PeerCredentialVerifier {
            PeerCredential(verified = false, appId = null, reason = "test: unattested")
        },
    ) { server, socketPath ->
        connect(socketPath)
        connect(socketPath)
        val ids = awaitConnectionCount(server, expected = 2)

        assertEquals(2, ids.size, "two unattested peers must occupy two slots, not one")
        assertTrue(
            ids.all { it.startsWith("unattested-") },
            "unattested peers must be keyed by a synthetic id, got $ids",
        )
        // Failing closed into *anonymous* is correct; failing closed into a shared identity
        // would recreate the very collision P2 exists to fix.
        assertNull(server.getRemoteConnection("app.tenshu.drive"))
    }

    // ── harness ───────────────────────────────────────────────────────────────────

    /** A verifier that attests connections to [appIds] in order, then stops attesting. */
    private fun sequentialVerifier(vararg appIds: String): PeerCredentialVerifier {
        val remaining = ArrayDeque(appIds.toList())
        return PeerCredentialVerifier {
            val next = synchronized(remaining) { remaining.removeFirstOrNull() }
            PeerCredential(
                verified = next != null,
                appId = next,
                reason = next?.let { "test: attested $it" } ?: "test: exhausted",
            )
        }
    }

    private fun withServer(
        verifier: PeerCredentialVerifier,
        body: (UnixSocketServer, String) -> Unit,
    ) {
        val proxy = TestProxy()
        val socketPath = Files.createTempFile("tenshu-peer-identity", ".sock")
            .also { Files.delete(it) }
            .toAbsolutePath()
            .toString()
        val server = UnixSocketServer(
            proxy.gateway, proxy.serializer, socketPath, peerVerifier = verifier,
        )
        server.start()
        // `start()` binds on its own thread, so the socket file appears asynchronously —
        // connecting immediately races it and fails with "No such file or directory".
        awaitSocket(socketPath)
        try {
            body(server, socketPath)
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(socketPath))
        }
    }

    private fun awaitSocket(socketPath: String) {
        val path = java.nio.file.Path.of(socketPath)
        repeat(TRIES) {
            if (Files.exists(path)) return
            Thread.sleep(POLL_MS)
        }
        error("server never bound $socketPath")
    }

    private fun connect(socketPath: String): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(socketPath))
        }

    /** Accept + registration happen on the server's own thread, so poll rather than sleep. */
    private fun awaitRegistration(server: UnixSocketServer, clientId: String) {
        repeat(TRIES) {
            if (server.getRemoteConnection(clientId) != null) return
            Thread.sleep(POLL_MS)
        }
        error("no connection registered for '$clientId' within ${TRIES * POLL_MS} ms")
    }

    private fun awaitConnectionCount(server: UnixSocketServer, expected: Int): Set<String> {
        repeat(TRIES) {
            val ids = server.registeredClientIds()
            if (ids.size >= expected) return ids
            Thread.sleep(POLL_MS)
        }
        return server.registeredClientIds()
    }

    private companion object {
        const val TRIES = 100
        const val POLL_MS = 20L
    }
}
