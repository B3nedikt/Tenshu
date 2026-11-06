package org.tenshu.core.common.transport

import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.TestProxy
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A proxy that dies **mid-call** must fail the caller, not wedge it — the desktop half of what
 * `BinderTaskTransport.asNotConnected` fixed on Android on 2026-08-03.
 *
 * ⚠⚠ Android's version of this crashed the client: a `DeadObjectException` is a `RemoteException`,
 * not an `IllegalStateException`, so it sailed past `RemoteBinaryTaskExecutor`'s catch and out of
 * whatever coroutine issued the read — *"killing Timeline every time an OOMing Control restarted"*.
 *
 * ⚠⚠ Desktop's was **quieter and worse**: it did not throw at all, it **hung**. The reader thread
 * hits EOF, closes the channel and schedules a reconnect *without* putting anything into
 * `responseQueue`, so a caller parked in a bare `take()` waited on a producer that would never
 * come — inside `NonCancellable`, so its own cancellation could not free it, and holding
 * `callLock`, so **every later `call()` queued behind it for the life of the process**.
 *
 * Measured before the fix, on this harness: an answering proxy replied in **6 ms**; a proxy that
 * died mid-call never returned at all, and neither did the unrelated call issued after it.
 *
 * ⚠ Every waiter runs on a **daemon** thread: a test that itself blocked on the wedge would hang
 * the suite rather than report it. [aLiveProxyAnswersTheSameCallPromptly] is the positive control —
 * without it, "the call settled" could equally mean the rig never issued one.
 */
class UnixSocketProxyDeathTest {

    /** Generous: the fix must fail a doomed call well inside this, and did so in ~50 ms. */
    private val settleTimeoutMs = 3_000L

    @Test
    fun aProxyThatDiesMidCallFailsTheCallerRatherThanWedgingIt() {
        val socketPath = newSocketPath()
        val requestReceived = CountDownLatch(1)
        val server = startServerThatDiesAfterReading(socketPath, requestReceived)
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)

            val outcome = callOnDaemonThread(transport, "doomed")
            check(requestReceived.await(5, TimeUnit.SECONDS)) { "server never saw the request" }

            assertTrue(
                outcome.latch.await(settleTimeoutMs, TimeUnit.MILLISECONDS),
                "the call never returned — the caller is wedged on a producer that will never come",
            )
            // ⚠⚠ `IllegalStateException` specifically, and not for tidiness: that is the type
            // `RemoteBinaryTaskExecutor` already folds into a typed `TResult.Failure`, which the
            // reachability engines read as "Control is gone". Any other throwable sails past that
            // catch and out of the calling coroutine — which is precisely how the Android twin
            // killed Timeline.
            assertTrue(
                outcome.result?.startsWith("threw IllegalStateException") == true,
                "expected the type the executor folds. Got: ${outcome.result}",
            )
        } finally {
            transport.unbind()
            server.interrupt()
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(socketPath)) }
        }
    }

    @Test
    fun aLaterCallIsNotHeldHostageByTheDeadOne() {
        // ⚠⚠ The consequence that made this more than one lost read. `call()` holds `callLock` for
        // the whole of its wait, so before the fix a single mid-call death took the transport down
        // with it — not just the caller.
        val socketPath = newSocketPath()
        val requestReceived = CountDownLatch(1)
        val server = startServerThatDiesAfterReading(socketPath, requestReceived)
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)

            val doomed = callOnDaemonThread(transport, "doomed")
            check(requestReceived.await(5, TimeUnit.SECONDS)) { "server never saw the request" }
            check(doomed.latch.await(settleTimeoutMs, TimeUnit.MILLISECONDS)) {
                "the first call must settle before this test means anything"
            }

            val later = callOnDaemonThread(transport, "unrelated")

            assertTrue(
                later.latch.await(settleTimeoutMs, TimeUnit.MILLISECONDS),
                "a later call was stuck behind `callLock` — the transport is wedged, not just the " +
                    "one read. Got: ${later.result ?: "still running"}",
            )
        } finally {
            transport.unbind()
            server.interrupt()
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(socketPath)) }
        }
    }

    @Test
    fun aCorrelatedCallFailsWhenItsProxyDiesMidCall() {
        // The two tests above meet a proxy that dies on the connection's *hello* — the first frame
        // a current client writes. This one negotiates first, so the death lands on a correlated
        // call parked on its own deferred (desktop-ipc.md §2.2), which only the reader's sweep of
        // that channel can fail.
        val socketPath = newSocketPath()
        val server = FakeSocketProxies.serve(socketPath, "CorrelatedDeathProxy") { ch, frame, _ ->
            when (frame.type) {
                StreamFramer.TYPE_HELLO -> with(FakeSocketProxies) {
                    ch.writeFrame(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO)
                }
                else -> ch.close() // the death: request read, no answer, connection gone
            }
        }
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)
            val outcome = callOnDaemonThread(transport, "doomed")
            assertTrue(
                outcome.latch.await(settleTimeoutMs, TimeUnit.MILLISECONDS),
                "the correlated call never returned — nothing failed its waiter when the socket died",
            )
            assertTrue(
                outcome.result?.startsWith("threw IllegalStateException") == true,
                "expected the type the executor folds. Got: ${outcome.result}",
            )
            assertTrue(
                server.received.map { it.type } ==
                    listOf(StreamFramer.TYPE_HELLO, StreamFramer.TYPE_CORRELATED_REQUEST),
                "the death must land on a correlated call. Frames: ${server.received.map { it.type }}",
            )
        } finally {
            transport.unbind()
            server.stop()
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(socketPath)) }
        }
    }

    @Test
    fun aLiveProxyAnswersTheSameCallPromptly() {
        // ⚠⚠ The positive control, and it is not optional. The assertions above are about a call
        // *settling*; a transport that never connected, a server that never accepted, or a harness
        // that never issued the call would each change what "settled" proves. This is the one that
        // shows the rig can tell a working round trip from the failure modes around it.
        val socketPath = newSocketPath()
        val requestReceived = CountDownLatch(1)
        val server = startEchoServer(socketPath, requestReceived)
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        try {
            awaitConnected(transport)

            val outcome = callOnDaemonThread(transport, "hello")

            assertTrue(
                outcome.latch.await(settleTimeoutMs, TimeUnit.MILLISECONDS),
                "an answering proxy must let `call()` return. Got: ${outcome.result ?: "still running"}",
            )
            assertTrue(
                outcome.result?.startsWith("returned") == true,
                "and it must be an answer, not a failure. Got: ${outcome.result}",
            )
        } finally {
            transport.unbind()
            server.interrupt()
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(socketPath)) }
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    private class Outcome {
        val latch = CountDownLatch(1)

        @Volatile
        var result: String? = null
    }

    /**
     * Issues one `call()` on a **daemon** thread and reports how it ended, leaving the latch
     * un-counted if it never ends.
     */
    private fun callOnDaemonThread(transport: UnixSocketTaskTransport, payload: String): Outcome {
        val outcome = Outcome()
        thread(isDaemon = true, name = "proxy-death-caller-$payload") {
            outcome.result = try {
                val bytes = runBlocking { transport.call(payload.encodeToByteArray()) }
                "returned ${bytes.size} bytes"
            } catch (e: Throwable) {
                "threw ${e::class.simpleName}: ${e.message}"
            }
            outcome.latch.countDown()
        }
        return outcome
    }

    private fun newSocketPath(): String =
        Files.createTempDirectory("uds-death").resolve("t.sock").toAbsolutePath().toString()

    private fun awaitConnected(transport: UnixSocketTaskTransport) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!transport.isConnected && System.nanoTime() < deadline) {
            transport.bind()
            if (!transport.isConnected) Thread.sleep(25)
        }
        check(transport.isConnected) { "transport never connected" }
    }

    /**
     * Accepts one connection, reads one framed request, then **closes the socket without
     * answering** — a proxy that died holding the call, which is what an OOMing or quitting Control
     * does.
     */
    private fun startServerThatDiesAfterReading(
        socketPath: String,
        requestReceived: CountDownLatch,
    ): Thread = thread(isDaemon = true, name = "ProxyDeathServer") {
        runCatching {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                serverChannel.bind(UnixDomainSocketAddress.of(socketPath))
                val ch = serverChannel.accept()
                fun readFully(buffer: ByteBuffer) {
                    while (buffer.hasRemaining()) {
                        if (ch.read(buffer) == -1) throw java.io.IOException("closed")
                    }
                }
                val header = ByteBuffer.allocate(4)
                readFully(header); header.flip()
                val length = header.getInt()
                readFully(ByteBuffer.allocate(1))
                readFully(ByteBuffer.allocate(length))
                requestReceived.countDown()
                // The death: no response, connection gone.
                ch.close()
                serverChannel.close()
            }
        }.onFailure {
            if (requestReceived.count > 0L) fail("server failed before reading a request: $it")
        }
    }

    /** Answers the hello, then echoes every correlated request (call id and all) — a proxy that is alive and well. */
    private fun startEchoServer(socketPath: String, requestReceived: CountDownLatch): Thread =
        thread(isDaemon = true, name = "ProxyAliveServer") {
            runCatching {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                    serverChannel.bind(UnixDomainSocketAddress.of(socketPath))
                    serverChannel.accept().use { ch ->
                        fun readFully(buffer: ByteBuffer) {
                            while (buffer.hasRemaining()) {
                                if (ch.read(buffer) == -1) throw java.io.IOException("closed")
                            }
                        }
                        val header = ByteBuffer.allocate(4)
                        val type = ByteBuffer.allocate(1)
                        while (ch.isOpen) {
                            header.clear(); readFully(header); header.flip()
                            val length = header.getInt()
                            type.clear(); readFully(type)
                            val payload = ByteBuffer.allocate(length)
                            readFully(payload)
                            // The connection opens with the wire hello; answer it as a current proxy.
                            if (type.get(0) == StreamFramer.TYPE_HELLO) {
                                ch.write(
                                    ByteBuffer.wrap(StreamFramer.frame(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO))
                                )
                                continue
                            }
                            requestReceived.countDown()
                            ch.write(
                                ByteBuffer.wrap(
                                    StreamFramer.frame(
                                        payload.array().copyOf(length),
                                        StreamFramer.TYPE_CORRELATED_RESPONSE,
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
}
