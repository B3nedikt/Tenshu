package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.TestProxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A **cancelled** `call()` must never hand its response to the next caller.
 *
 * The call is correlated (`desktop-ipc.md` §2.2), so the id makes mis-delivery unrepresentable —
 * this pins it. The bug it was written for lived on the pre-§2.2 uncorrelated wire (removed at the
 * 1.0.1 re-cut): a caller cancelled around the write left its reply unclaimed in one shared queue,
 * and from then on every call took the **previous** call's response. `pagingSource`'s
 * `flatMapLatest` cancels the in-flight page load on every new `PagingData` generation, so opening
 * Drive's Files listing tripped it (`Key "…" was already used`).
 *
 * The cancellation window is a resume, not a wall-clock race, so the test drives the caller on a
 * dispatcher it pumps by hand: write, let the server take the frame, cancel, *then* deliver the
 * resume. That is the exact interleaving, every run.
 */
class UnixSocketCancelledCallSkewTest {

    /**
     * Against a current Control (`desktop-ipc.md` §2.2): the call is correlated, so the cancel really
     * abandons it — and its reply, which here arrives only **after** the caller has gone, must be
     * dropped by id rather than delivered to whoever asks next.
     */
    @Test
    fun aCancelledCorrelatedCallsLateReplyIsDroppedNotDelivered() =
        assertCancelledCallDoesNotSkew("correlated", doomedPayload = "hold-doomed") { path, release ->
            FakeSocketProxies.correlatedEcho(path, release)
        }

    private fun assertCancelledCallDoesNotSkew(
        tag: String,
        doomedPayload: String = "doomed",
        startServer: (String, CountDownLatch) -> FakeSocketProxies.Handle,
    ) = runBlocking {
        val socketPath = Files.createTempDirectory("uds-skew-$tag")
            .resolve("test.sock").toAbsolutePath().toString()
        val release = CountDownLatch(1)
        val server = startServer(socketPath, release)
        val transport = UnixSocketTaskTransport(TestProxy().parser, socketPath)
        val manual = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        try {
            withTimeout(5_000) {
                while (!transport.isConnected) {
                    transport.bind()
                    if (!transport.isConnected) delay(50)
                }
            }
            // Negotiate first, so the pumped call below is exactly one write + one wait.
            assertEquals("warm", withTimeout(5_000) { transport.call("warm".encodeToByteArray()) }.decodeToString())
            val framesBefore = server.received.size

            // The doomed call: it reaches the wire, then its caller goes away.
            val doomed = scope.launch(manual) {
                runCatching { transport.call(doomedPayload.encodeToByteArray()) }
            }
            manual.pumpOne(5_000)                                // start it; the write goes out
            val deadline = System.nanoTime() + 5_000_000_000L
            while (server.received.size == framesBefore && System.nanoTime() < deadline) Thread.sleep(5)
            check(server.received.size > framesBefore) { "server never saw the request" }
            doomed.cancel()
            manual.pumpUntil(5_000) { doomed.isCompleted }       // deliver the resume post-cancel
            release.countDown()                                  // a held reply now arrives, late
            Thread.sleep(100)

            // The next caller must get its own answer.
            val response = withTimeout(5_000) { transport.call("real".encodeToByteArray()) }
            assertEquals(
                "real",
                response.decodeToString(),
                "call() returned the abandoned request's response — the shared responseQueue is " +
                    "skewed, and stays skewed for every later call",
            )
        } finally {
            release.countDown()
            scope.cancel()
            transport.unbind()
            server.stop()
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(socketPath)) }
        }
    }

    /** A dispatcher that queues every block until the test decides to run it. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.put(block)
        }

        /**
         * Runs exactly one queued block — enough to start the coroutine and let it suspend into
         * the write, leaving its *resume* queued for the test to deliver after the cancel.
         */
        fun pumpOne(timeoutMs: Long) {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)?.run()
        }

        fun pumpUntil(timeoutMs: Long, done: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!done() && System.nanoTime() < deadline) {
                queue.poll(20, TimeUnit.MILLISECONDS)?.run()
            }
        }
    }
}
