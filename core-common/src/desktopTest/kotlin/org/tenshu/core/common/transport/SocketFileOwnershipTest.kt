package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.NativeBridgeServer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A server that quits removes the socket file **it** bound, and only that one.
 *
 * Two Controls can still overlap past `ControlInstanceGuard` (a pre-guard build, or locking
 * unavailable), and the later one unlinks and
 * rebinds the shared paths. The earlier one's clean quit then used to unlink by path: the later
 * Control kept running with no name a new client could connect to.
 */
class SocketFileOwnershipTest {

    // Short path — macOS Unix-domain sun_path caps at ~104 chars; see shortTempDirectory.
    private val tempDir = org.tenshu.core.common.shortTempDirectory("own")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun connects(path: Path): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(path)) }
    }.getOrDefault(false)

    @Test
    fun aBridgeServerThatStopsLeavesItsSuccessorsSocket() {
        val path = tempDir.resolve("bridge.sock")
        val older = NativeBridgeServer(socketPath = path.toString()).also { it.start() }
        val newer = NativeBridgeServer(socketPath = path.toString()).also { it.start() }

        older.stop()
        assertTrue(connects(path), "the older server's stop removed the newer server's socket")

        newer.stop()
        assertFalse(Files.exists(path), "a server that stops still takes its own socket file with it")
    }

    @Test
    fun anIpcServerThatQuitsLeavesItsSuccessorsSocket() {
        val path = tempDir.resolve("tenshu.sock")
        val proxy = TestProxy(coroutineScope = CoroutineScope(Dispatchers.Default))
        val older = UnixSocketServer(proxy.gateway, proxy.serializer, path.toString()).also { it.start() }
        val newer = UnixSocketServer(proxy.gateway, proxy.serializer, path.toString()).also { it.start() }

        older.removeSocketFile()
        assertTrue(connects(path), "the older server's quit removed the newer server's socket")

        newer.removeSocketFile()
        assertFalse(Files.exists(path), "a server that quits still takes its own socket file with it")
    }

    /**
     * ⚠⚠ Check-then-delete is one step against a takeover: while a successor holds its claim (between its unlink
     * and its bind), the old server's removal must not run — it would match, then delete the socket the successor
     * is about to bind. Contended, the removal gives up and leaves the file, the safe direction.
     */
    @Test
    fun aRemovalDoesNotRunDuringATakeover() {
        val path = tempDir.resolve("race.sock")
        Files.writeString(path, "stand-in for a bound socket")
        val older = BoundSocketFile.claim(path).bound()

        val successor = BoundSocketFile.claim(path) // the takeover is in progress: lock held
        try {
            older.removeIfStillOurs()
            assertTrue(Files.exists(path), "a removal ran while a successor was taking the path over")
        } finally {
            successor.release()
        }
        // Not asserted here: what the older server may remove afterwards. Where the file system has no file key
        // (Windows) the successor's claim already replaced the owner token, so the older server rightly leaves the
        // file; an uncontended removal is pinned by the two tests above.
    }
}
