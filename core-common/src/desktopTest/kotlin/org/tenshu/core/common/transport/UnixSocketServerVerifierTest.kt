package org.tenshu.core.common.transport

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client side of the IPC gate (threat model A14; `desktop-ipc-client-identity.md` §11): a
 * transport with a `serverVerifier` refuses a server that fails it **before writing a byte**, keeps
 * retrying in the background, and connects once a server passes. The cryptography is
 * `MacPeerAttestation`'s (tested in `:macos-helpers`); here the verifier is a fake, so what is under
 * test is the transport's handling of its answer.
 *
 * ⚠⚠ Both directions on purpose: the refusal alone would pass against a transport that never
 * connects at all.
 */
class UnixSocketServerVerifierTest {

    private val socketPath = Files.createTempFile("tenshu-server-verifier", ".sock")
        .also { Files.delete(it) }
        .toAbsolutePath()
        .toString()

    private fun parser(): BinaryParser =
        createTestContext(createSyntheticTestRegistry()).let { BinaryParser(it.schemaProvider(), it.dataFactory()) }

    private var transport: UnixSocketTaskTransport? = null
    private var server: ServerSocketChannel? = null

    /** Every connection the fake server accepted, and how many bytes the client wrote on each. */
    private val accepted = CopyOnWriteArrayList<SocketChannel>()
    private val bytesReceived = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        transport?.unbind()
        runCatching { server?.close() }
        accepted.forEach { runCatching { it.close() } }
        runCatching { Files.deleteIfExists(Path.of(socketPath)) }
    }

    /** A server that accepts and counts what it reads; it never answers, so nothing else happens. */
    private fun startServer() {
        val s = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socketPath)) }
        server = s
        thread(isDaemon = true) {
            runCatching {
                while (s.isOpen) {
                    val client = s.accept() ?: break
                    accepted += client
                    thread(isDaemon = true) {
                        val buf = ByteBuffer.allocate(4096)
                        runCatching {
                            while (true) {
                                buf.clear()
                                val n = client.read(buf)
                                if (n < 0) break
                                bytesReceived.addAndGet(n)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    private fun verdict(verified: Boolean, calls: AtomicInteger = AtomicInteger()) = PeerCredentialVerifier {
        calls.incrementAndGet()
        PeerCredential(verified = verified, appId = if (verified) "app.tenshu.control.ios" else null, reason = "fake")
    }

    @Test
    fun aServerThatFailsTheCheckIsRefused_beforeAByteIsWritten_andRetried() {
        startServer()
        val checks = AtomicInteger()
        val subject = UnixSocketTaskTransport(parser(), socketPath, serverVerifier = verdict(false, checks))
            .also { transport = it }

        subject.bind()

        assertFalse(subject.isConnected, "a server that fails the check must not be connected to")
        assertTrue(awaitTrue { checks.get() >= 2 }, "the transport keeps retrying in the background; checks=${checks.get()}")
        // The impostor saw connections (the check needs one) and never a byte: no hello, no request.
        assertTrue(accepted.isNotEmpty())
        Thread.sleep(200)
        assertEquals(0, bytesReceived.get(), "nothing may be written to a server that failed the check")
    }

    @Test
    fun aServerThatPassesTheCheckIsConnected() {
        startServer()
        val checks = AtomicInteger()
        val subject = UnixSocketTaskTransport(parser(), socketPath, serverVerifier = verdict(true, checks))
            .also { transport = it }

        subject.bind()

        assertTrue(subject.isConnected, "a server that passes the check is connected to")
        assertEquals(1, checks.get(), "one check per connection")
    }

    @Test
    fun developerModeAdmitsAnUnverifiedServer() {
        startServer()
        val subject = UnixSocketTaskTransport(parser(), socketPath, serverVerifier = verdict(false), serverDeveloperMode = true)
            .also { transport = it }

        subject.bind()

        assertTrue(subject.isConnected, "developer mode (A16) admits an unverified server with a warning")
    }
}
