package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.util.toByteArray
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A frame header is four bytes chosen by whoever holds the socket, and [UnixSocketServer] used to
 * hand them straight to `ByteBuffer.allocate` (2026-08-21 IPC audit item #3).
 *
 * ⚠⚠ **The obvious version of this test does not test anything, and it took a run against the
 * pre-fix server to notice.** Sending `Int.MAX_VALUE` and asserting the connection closes passes
 * *without the guard*: `allocate(Int.MAX_VALUE)` throws an `OutOfMemoryError`, which the read
 * loop's `catch (e: Exception)` does not catch, so the handler thread dies and `use {}` closes
 * the channel on the way out — the same observable ending, for the opposite reason. Measured:
 * **1/1 pass against the unguarded server.**
 *
 * So this sends a length that is merely *over the cap* rather than unallocatable, against a
 * server built with a small injected cap. Unguarded, that length is allocated happily and the
 * loop then blocks in `readFully` waiting for a body that never arrives — the connection stays
 * **open**, which is exactly what this asserts against. Guarded, it is refused before any
 * allocation. No dependence on heap size, and the two behaviours are genuinely distinguishable.
 */
class UnixSocketFrameLimitTest {

    private val cap = 1024

    @Test
    fun anOverCapFrameLengthClosesThatConnectionAndLeavesTheServerServing() = runBlocking {
        val proxy = TestProxy(coroutineScope = CoroutineScope(Dispatchers.Default))
        val socketPath = Files.createTempFile("tenshu-frame-limit", ".sock")
            .also { Files.delete(it) }
            .toAbsolutePath()
            .toString()

        UnixSocketServer(
            proxy.gateway,
            proxy.serializer,
            socketPath,
            maxFrameBytes = cap,
        ).start()

        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { hostile ->
                hostile.connect(UnixDomainSocketAddress.of(socketPath))
                hostile.write(
                    ByteBuffer.wrap(
                        (cap * 4).toByteArray() + byteArrayOf(StreamFramer.TYPE_RESPONSE)
                    )
                )

                assertTrue(
                    hostile.awaitClosed(),
                    "the server kept the connection open on an over-cap frame — it accepted the " +
                        "declared length and is now blocked waiting for a body that never comes",
                )
            }

            // ⭐ The other half: the cap must shed the connection, not the listener.
            SocketChannel.open(StandardProtocolFamily.UNIX).use { honest ->
                honest.connect(UnixDomainSocketAddress.of(socketPath))
                assertTrue(
                    honest.isConnected,
                    "the server stopped accepting after a hostile frame",
                )
            }
        } finally {
            Files.deleteIfExists(Path.of(socketPath))
        }
    }

    /**
     * Polls (non-blocking) for the peer ending the stream, so an open connection fails the test
     * instead of hanging it. ⚠ Both endings count: a close with nothing left to deliver reads as
     * EOF, one that races the write reads as a reset.
     */
    private fun SocketChannel.awaitClosed(timeoutMillis: Long = 5_000): Boolean {
        configureBlocking(false)
        val buffer = ByteBuffer.allocate(1)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            try {
                buffer.clear()
                if (read(buffer) == -1) return true
            } catch (e: IOException) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }
}
