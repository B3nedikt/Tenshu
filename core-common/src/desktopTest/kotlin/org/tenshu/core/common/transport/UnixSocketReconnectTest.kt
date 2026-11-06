package org.tenshu.core.common.transport

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2 of `docs/proposals/core-client-resubscription.md`: the desktop transport must **reconnect on
 * its own** and announce it.
 *
 * `call()`/`callCancellable()` re-bind opportunistically, so a client that keeps *calling* would
 * limp along without a background retry — but a client that only **observes** (every reactive
 * screen; the sync-status banner) issues no calls at all and would stay dark indefinitely after
 * Control restarts.
 *
 * Uses a real Unix socket rather than a fake transport: the thing under test is precisely the
 * socket lifecycle, which a fake would define away.
 */
class UnixSocketReconnectTest {

    private val socketPath = Files.createTempFile("tenshu-reconnect-test", ".sock")
        .also { Files.delete(it) }
        .toAbsolutePath()
        .toString()

    /** The parser is never exercised — no frames are sent; only the socket lifecycle is under test. */
    private fun parser(): BinaryParser =
        createTestContext(createSyntheticTestRegistry()).let { BinaryParser(it.schemaProvider(), it.dataFactory()) }

    private val servers = mutableListOf<FakeProxy>()
    private var transport: UnixSocketTaskTransport? = null

    @AfterTest
    fun tearDown() {
        transport?.unbind()
        servers.forEach { runCatching { it.kill() } }
        runCatching { Files.deleteIfExists(java.nio.file.Path.of(socketPath)) }
    }

    /**
     * Stands in for the Control process. [kill] closes the accepted connections too, not just the
     * listening socket — closing only the listener leaves established sockets alive, which is not
     * what a dying proxy looks like (and silently makes this test vacuous).
     *
     * ⚠ [track] and [kill] share a lock, and a connection accepted *after* a kill is closed on the
     * spot rather than recorded. Tracking a channel is not instantaneous: the client's connect
     * completes in the kernel, so there is a window where the socket is established but the accept
     * thread has not yet reached `track`. Without the shared lock, a `kill` landing in that window
     * closes only the listener, leaving a live socket the transport never sees die — the test then
     * waits out its full 15 s and fails `drop not observed`, ~10% of runs under load and never on an
     * idle box (a 300 ms sleep between `accept()` and `track` fails both drop-detection tests every
     * time).
     *
     * ⚠ That is a **fixture** defect, never a transport one: a real Control that dies has its
     * sockets closed by the OS, so a real client does see EOF. Widening the timeout only makes the
     * test wait longer for an event that is never coming.
     */
    private class FakeProxy(private val server: ServerSocketChannel) {
        private val lock = Any()
        private val accepted = mutableListOf<java.nio.channels.SocketChannel>()
        private var killed = false

        fun track(ch: java.nio.channels.SocketChannel) {
            val alreadyDead = synchronized(lock) {
                if (killed) true else { accepted += ch; false }
            }
            // The proxy is already gone, so this connection must not outlive it.
            if (alreadyDead) runCatching { ch.close() }
        }

        fun kill() {
            val toClose = synchronized(lock) {
                killed = true
                accepted.toList().also { accepted.clear() }
            }
            runCatching { server.close() }
            toClose.forEach { runCatching { it.close() } }
        }
    }

    /** Accepts connections and parks them; the test only cares about lifecycle, not framing. */
    private fun startServer(): FakeProxy {
        Files.deleteIfExists(java.nio.file.Path.of(socketPath))
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        val proxy = FakeProxy(server)
        servers += proxy
        thread(isDaemon = true) {
            runCatching {
                while (server.isOpen) {
                    val client = server.accept() ?: break
                    proxy.track(client)
                    thread(isDaemon = true) { runCatching { while (client.isOpen) Thread.sleep(20) } }
                }
            }
        }
        return proxy
    }

    private fun awaitTrue(timeoutMs: Long = 15_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    /**
     * ⚠⚠ The client launched BEFORE the proxy. It has never been connected, so nothing about it
     * is a "re"-connection — and it still needs both halves: a background retry (which cannot hang
     * off a reader's death, since there is no reader), and an announcement when it does connect.
     * `Stream.changes` waits on that announcement to re-subscribe, so without it a listing opened
     * before Control loads on a manual retry and then never hears a change.
     */
    @Test
    fun aClientStartedBeforeTheProxy_connectsOnItsOwn_andAnnouncesIt() {
        val reconnects = AtomicInteger(0)
        val subject = UnixSocketTaskTransport(parser(), socketPath)
            .also { transport = it }
        subject.setOnReconnected { reconnects.incrementAndGet() }

        subject.bind()
        assertTrue(!subject.isConnected, "precondition: there is no proxy to connect to")
        assertEquals(0, reconnects.get())

        // The proxy starts. No call is issued: the observe-only client again.
        startServer()

        assertTrue(awaitTrue { subject.isConnected }, "the transport must connect on its own")
        assertTrue(awaitTrue { reconnects.get() == 1 }, "and announce it — something is waiting on that edge")
    }

    @Test
    fun reconnectsOnItsOwn_afterTheProxyDies_andAnnouncesIt() {
        val reconnects = AtomicInteger(0)
        val server = startServer()

        val subject = UnixSocketTaskTransport(parser(), socketPath)
            .also { transport = it }
        subject.setOnReconnected { reconnects.incrementAndGet() }

        subject.bind()
        assertTrue(subject.isConnected, "precondition: the initial bind connects")
        assertEquals(0, reconnects.get(), "a FIRST connect is not a reconnect — nothing to replay")

        // The proxy dies.
        server.kill()
        assertTrue(awaitTrue { !subject.isConnected }, "the transport must notice the socket died")

        // …and comes back. No call is issued in between: this is the observe-only client, which is
        // exactly the case the on-demand `bind()` inside `call()` does not cover.
        startServer()

        assertTrue(
            awaitTrue { subject.isConnected },
            "the transport must re-establish the connection in the background, with no call to prompt it",
        )
        assertTrue(
            awaitTrue { reconnects.get() >= 1 },
            "the reconnect must be announced so live streams can be re-subscribed",
        )
    }

    @Test
    fun reconnectAnnouncementReArms_acrossTwoProxyDeaths() {
        // The one-shot trap: a backstop that fires once and exits misses the second cycle. A
        // desktop Control can die repeatedly.
        val reconnects = AtomicInteger(0)
        var server = startServer()

        val subject = UnixSocketTaskTransport(parser(), socketPath)
            .also { transport = it }
        subject.setOnReconnected { reconnects.incrementAndGet() }
        subject.bind()
        assertTrue(subject.isConnected)

        repeat(2) { round ->
            server.kill()
            assertTrue(awaitTrue { !subject.isConnected }, "round $round: drop not observed")
            server = startServer()
            assertTrue(awaitTrue { subject.isConnected }, "round $round: did not reconnect")
        }

        assertTrue(
            awaitTrue { reconnects.get() >= 2 },
            "each reconnection must be announced; got ${reconnects.get()}",
        )
    }

    @Test
    fun deliberateUnbind_isNotFoughtByTheReconnectLoop() {
        // `unbind()` is app teardown. A reconnect loop that resurrected the socket afterwards would
        // keep a daemon thread (and the connection) alive past shutdown.
        val reconnects = AtomicInteger(0)
        startServer()

        val subject = UnixSocketTaskTransport(parser(), socketPath)
            .also { transport = it }
        subject.setOnReconnected { reconnects.incrementAndGet() }
        subject.bind()
        assertTrue(subject.isConnected)

        subject.unbind()
        assertTrue(!subject.isConnected, "unbind disconnects")

        Thread.sleep(1_500) // well past the initial backoff
        assertTrue(!subject.isConnected, "a deliberate unbind must stay unbound")
        assertEquals(0, reconnects.get(), "no reconnect may be announced after a deliberate unbind")
    }
}
