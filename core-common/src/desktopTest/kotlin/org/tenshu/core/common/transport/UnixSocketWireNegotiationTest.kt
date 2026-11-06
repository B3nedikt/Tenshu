package org.tenshu.core.common.transport

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.transport.FakeSocketProxies.writeFrame
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The desktop apps and Control are separately installed processes, so the wire is negotiated per
 * connection with a hello (`docs/protocol/desktop-ipc.md` §2.2), and anything the client cannot be
 * sure of fails **closed**:
 *
 * | client | proxy                         | expected                                           |
 * |--------|-------------------------------|----------------------------------------------------|
 * | new    | new                           | correlated (`UnixSocketLostReplyTest`)             |
 * | new    | answers no hello (plain reply)| the call fails and the connection is dropped       |
 * | new    | hello below the floor         | the call fails and the connection is dropped       |
 * | new    | mute                          | the call fails and the connection is dropped       |
 * | no hello | new                         | the proxy drops the connection                     |
 *
 * The pre-hello wire and the revision-1 wire never reached a user (1.0.1 re-cut), so neither is
 * served; a later revision's feature is gated on the negotiated revision.
 */
class UnixSocketWireNegotiationTest {

    private fun newSocketPath(tag: String): String =
        Files.createTempDirectory("uds-neg-$tag").resolve("t.sock").toAbsolutePath().toString()

    private fun awaitConnected(transport: UnixSocketTaskTransport) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!transport.isConnected && System.nanoTime() < deadline) {
            transport.bind()
            if (!transport.isConnected) Thread.sleep(25)
        }
        check(transport.isConnected) { "transport never connected" }
    }

    /** Runs [transport]'s first call on a daemon thread and returns how it ended, or null if it never did. */
    private fun firstCallOutcome(transport: UnixSocketTaskTransport): Throwable? {
        val done = CountDownLatch(1)
        var outcome: Throwable? = null
        thread(isDaemon = true) {
            outcome = runCatching { runBlocking { transport.call("x".encodeToByteArray()) } }.exceptionOrNull()
            done.countDown()
        }
        assertTrue(done.await(3, TimeUnit.SECONDS), "the call never settled")
        return outcome
    }

    @Test
    fun aPeerThatAnswersTheHelloWithAPlainReplyIsDropped() {
        // A peer that does not speak the hello reads it as a plain request and answers TYPE_RESPONSE.
        // No shipped Control does that; the client must not guess at an uncorrelated wire.
        val socketPath = newSocketPath("nohello")
        val server = FakeSocketProxies.serve(socketPath, "NoHelloProxy") { ch, frame, _ ->
            ch.writeFrame(frame.payload, StreamFramer.TYPE_RESPONSE)
        }
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)
            val outcome = firstCallOutcome(transport)
            assertTrue(outcome is IllegalStateException, "expected the type the executor folds, got $outcome")
            assertTrue(server.disconnected.await(3, TimeUnit.SECONDS), "the connection must be dropped")
            assertEquals(listOf(StreamFramer.TYPE_HELLO), server.received.map { it.type }, "no request may follow")
        } finally {
            transport.unbind()
            server.stop()
            Files.deleteIfExists(Path.of(socketPath))
        }
    }

    @Test
    fun aProxyBelowTheWireRevisionFloorIsDropped() {
        val socketPath = newSocketPath("lowrev")
        val server = FakeSocketProxies.correlatedEcho(
            socketPath,
            helloRevision = StreamFramer.WIRE_REVISION_FLOOR - 1,
        )
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)
            val outcome = firstCallOutcome(transport)
            assertTrue(outcome is IllegalStateException, "expected the type the executor folds, got $outcome")
            assertTrue(server.disconnected.await(3, TimeUnit.SECONDS), "the connection must be dropped")
            assertEquals(listOf(StreamFramer.TYPE_HELLO), server.received.map { it.type }, "no request may follow")
        } finally {
            transport.unbind()
            server.stop()
            Files.deleteIfExists(Path.of(socketPath))
        }
    }

    @Test
    fun aNewClientOnANewProxyCorrelatesAndSurfacesAProxyFailureAsIllegalState() = runBlocking {
        val socketPath = newSocketPath("new")
        val server = FakeSocketProxies.correlatedEcho(socketPath)
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)
            assertEquals("ok", withTimeout(5_000) { transport.call("ok".encodeToByteArray()) }.decodeToString())
            // IllegalStateException is the type RemoteBinaryTaskExecutor folds into TResult.Failure.
            assertFailsWith<IllegalStateException> {
                withTimeout(5_000) { transport.call("fail".encodeToByteArray()) }
            }
            assertEquals(
                listOf(StreamFramer.TYPE_HELLO, StreamFramer.TYPE_CORRELATED_REQUEST, StreamFramer.TYPE_CORRELATED_REQUEST),
                server.received.map { it.type },
            )
        } finally {
            transport.unbind()
            server.stop()
            Files.deleteIfExists(Path.of(socketPath))
        }
    }

    @Test
    fun aClientThatSendsAPlainRequestIsDroppedByTheRealServer() {
        // The real server: a plain uncorrelated TYPE_RESPONSE request is the pre-hello wire, which
        // no shipped client speaks. It is not served; the connection is closed.
        val proxy = TestProxy()
        val socketPath = newSocketPath("plainclient")
        UnixSocketServer(proxy.gateway, proxy.serializer, socketPath).start()
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socketPath))
                ch.writeFrame(byteArrayOf(0xFF.toByte()), StreamFramer.TYPE_RESPONSE)
                // Non-blocking poll, so a server that keeps the connection open fails the test
                // instead of hanging it. A reset counts as closed too.
                ch.configureBlocking(false)
                val buffer = ByteBuffer.allocate(5)
                val deadline = System.nanoTime() + 5_000_000_000L
                var read = 0
                while (read == 0 && System.nanoTime() < deadline) {
                    read = runCatching { ch.read(buffer) }.getOrDefault(-1)
                    if (read == 0) Thread.sleep(10)
                }
                assertEquals(-1, read, "the server must close the connection rather than answer")
            }
        } finally {
            Files.deleteIfExists(Path.of(socketPath))
        }
    }

    @Test
    fun aProxyThatNeverAnswersTheHelloFailsTheCallAndDropsTheConnection() {
        // No answer at all: the client cannot know which wire is safe, so it must not guess.
        // Dropping the socket makes any late reply undeliverable, and the reconnect loop
        // negotiates afresh.
        val socketPath = newSocketPath("mute")
        val server = FakeSocketProxies.serve(socketPath, "MuteProxy") { _, _, _ -> }
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath, helloTimeoutMs = 300)
        try {
            awaitConnected(transport)
            val done = CountDownLatch(1)
            var outcome: Throwable? = null
            thread(isDaemon = true) {
                outcome = runCatching { runBlocking { transport.call("x".encodeToByteArray()) } }.exceptionOrNull()
                done.countDown()
            }
            assertTrue(done.await(3, TimeUnit.SECONDS), "the call waited on a proxy that will never answer")
            assertTrue(outcome is IllegalStateException, "expected the type the executor folds, got $outcome")
            assertTrue(server.disconnected.await(3, TimeUnit.SECONDS), "the unnegotiated connection must be dropped")
            assertEquals(listOf(StreamFramer.TYPE_HELLO), server.received.map { it.type }, "no request may follow")
        } finally {
            transport.unbind()
            server.stop()
            Files.deleteIfExists(Path.of(socketPath))
        }
    }
}
