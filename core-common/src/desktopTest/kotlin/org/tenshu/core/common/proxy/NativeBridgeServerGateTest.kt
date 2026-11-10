package org.tenshu.core.common.proxy

import org.tenshu.core.common.transport.PeerCredential
import org.tenshu.core.common.transport.PeerCredentialPolicy
import org.tenshu.core.common.transport.PeerCredentialVerifier
import org.tenshu.core.common.transport.StreamFramer
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the peer-credential gate is actually wired into [NativeBridgeServer]'s
 * accept path: a strict policy over an unverified peer **closes** the connection
 * before any frame is served, while a verified peer is admitted. The default
 * (non-strict) admit path is covered by `NativeBridgeServerTest.socketRoundTripPing`.
 */
class NativeBridgeServerGateTest {

    // Short /tmp path — macOS Unix-domain sun_path caps at ~104 chars.
    private val tempDir = org.tenshu.core.common.shortTempDirectory("gate")
    private val servers = mutableListOf<NativeBridgeServer>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop() }
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun startServer(server: NativeBridgeServer): NativeBridgeServer =
        server.also { servers.add(it); it.start() }

    @Test
    fun strictGateRejectsUnverifiedPeer() {
        val socketPath = tempDir.resolve("bridge.sock").toString()
        startServer(
            NativeBridgeServer(
                socketPath = socketPath,
                // Default Unverified verifier + strict policy → reject.
                peerPolicy = PeerCredentialPolicy(strict = true),
            )
        )

        connect(socketPath).use { ch ->
            // The gate runs before any read, so the server closes the channel; the
            // (best-effort) ping write may buffer. A rejection then surfaces in one
            // of two kernel-dependent ways: a clean EOF (the gate closed before our
            // ping arrived; the write got EPIPE), or ECONNRESET (the gate closed
            // with the ping still unread — close-with-unread-data resets the peer).
            // Which one wins is a JVM-warm-up-dependent race; both mean "closed
            // before any frame was served". Only actual reply bytes fail the test.
            runCatching { ch.write(ByteBuffer.wrap(StreamFramer.frame(ping()))) }
            val read = try {
                ch.read(ByteBuffer.allocate(4))
            } catch (e: SocketException) {
                -1 // connection reset — rejected without a reply, same as EOF
            }
            assertEquals(-1, read, "rejected peer should see EOF or reset, not a reply")
        }
    }

    @Test
    fun verifiedPeerIsAdmittedUnderStrictPolicy() {
        val socketPath = tempDir.resolve("bridge.sock").toString()
        val verified = PeerCredentialVerifier {
            PeerCredential(verified = true, appId = "app.tenshu.test", reason = "test-verified")
        }
        startServer(
            NativeBridgeServer(
                socketPath = socketPath,
                peerVerifier = verified,
                peerPolicy = PeerCredentialPolicy(strict = true),
            )
        )

        connect(socketPath).use { ch ->
            ch.write(ByteBuffer.wrap(StreamFramer.frame(ping())))
            val reply = readFrame(ch).decodeToString()
            assertTrue(reply.contains("\"pong\":true"), "verified peer should be served: $reply")
        }
    }

    private fun ping() = """{"v":1,"id":"g","method":"ping","params":{}}""".encodeToByteArray()

    private fun connect(socketPath: String): SocketChannel {
        val address = UnixDomainSocketAddress.of(socketPath)
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                ch.connect(address); return ch
            } catch (e: Exception) {
                ch.close(); Thread.sleep(20)
            }
        }
        throw AssertionError("could not connect to bridge socket")
    }

    private fun readFrame(ch: SocketChannel): ByteArray {
        val header = ByteBuffer.allocate(4); readFully(ch, header); header.flip()
        val length = header.getInt()
        readFully(ch, ByteBuffer.allocate(1)) // type byte
        val payload = ByteBuffer.allocate(length); readFully(ch, payload)
        return payload.array()
    }

    private fun readFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (ch.read(buf) == -1) error("eof")
    }
}
