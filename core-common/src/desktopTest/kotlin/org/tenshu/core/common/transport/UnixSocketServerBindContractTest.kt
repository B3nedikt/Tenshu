package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.TestProxy
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [UnixSocketServer.start] must return with its socket **already bound**.
 *
 * ⚠⚠ This is a one-assertion test for a one-line guarantee, and it is here because the absence of
 * that guarantee was invisible for months. `start()` used to spawn its accept thread and return, so
 * `bind()` happened whenever that thread was next scheduled. A client connecting in the gap finds no
 * listener, and `UnixSocketTaskTransport.openConnection` **swallows** the failure — null channel, no
 * reconnect armed, no log — so the symptom surfaces far away as a request that never crosses and a
 * caller that waits out its entire budget.
 *
 * It surfaced as `PhaseL2CancellationTest.socket_cancel_frame_stops_the_server_side_coroutine`
 * timing out at 8 s in full-gate runs while passing in isolation — one of the rotating victims in
 * `docs/tech-debt.md`, where it sat for over a week filed as "a wall-clock assertion racing 407 test
 * tasks for 4 cores", with the suggested remedy being to raise the timeout. It was measured instead:
 * **24 ms of 8 000 ms**, a 333× margin. Nothing that finishes in 24 ms fails at 8 s because the box
 * is busy.
 *
 * ⚠ Connecting on the **first** attempt is the whole assertion. A retry loop here would pass against
 * the broken version and test nothing — which is precisely how the original defect stayed invisible.
 *
 * ⭐ Measured against the pre-fix `start()`: this fails **5 of 5** runs with no load and no forced
 * deschedule, i.e. an immediate connect loses that race essentially always. The window was never
 * narrow — `PhaseL2CancellationTest` survived it only because it builds a parser, a transport and an
 * executor between `start()` and its first call, which is usually enough for the accept thread to
 * get scheduled.
 */
class UnixSocketServerBindContractTest {

    @Test
    fun start_returns_only_once_the_socket_accepts_connections() = runBlocking {
        val proxy = TestProxy(coroutineScope = CoroutineScope(Dispatchers.Default))
        val socketPath = Files.createTempFile("tenshu-bind-contract", ".sock")
            .also { Files.delete(it) }
            .toAbsolutePath()
            .toString()

        UnixSocketServer(proxy.gateway, proxy.serializer, socketPath).start()

        try {
            // No retry, no sleep: if `start()` kept its promise this connects now or never.
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                assertTrue(
                    channel.isConnected,
                    "start() returned but the socket was not accepting — see this class's KDoc",
                )
            }
        } finally {
            Files.deleteIfExists(Path.of(socketPath))
        }
    }
}
