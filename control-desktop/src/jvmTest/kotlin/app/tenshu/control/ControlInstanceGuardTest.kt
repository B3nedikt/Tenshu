package app.tenshu.control

import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.storage.SingleInstanceLock
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `desktop-macos-distribution.md` §2.11: one Control per data root, and how a second one leaves. */
class ControlInstanceGuardTest {
    // ⚠ Short on purpose: a Unix socket path has a 102-byte budget on macOS.
    private val dir: File = Files.createTempDirectory("cig").toFile()
    private val lockFile = File(dir, ControlInstanceGuard.LOCK_FILE)
    private val binarySocket = File(dir, "ipc.sock").path
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanup.asReversed().forEach { runCatching { it() } }
        dir.deleteRecursively()
    }

    @Test
    fun theFirstControlRunsAndHoldsTheLock() {
        val decision = assertIs<ControlInstanceGuard.Decision.Run>(decide())
        val lock = decision.lock!!
        cleanup += { lock.release() }
        assertIs<SingleInstanceLock.Outcome.HeldElsewhere>(SingleInstanceLock.tryAcquire(lockFile))
    }

    @Test
    fun aSecondControlDefersWhileTheFirstHoldsTheLock() {
        val first = assertIs<ControlInstanceGuard.Decision.Run>(decide()).lock!!
        cleanup += { first.release() }
        val second = assertIs<ControlInstanceGuard.Decision.Defer>(decide())
        assertTrue("pid ${ProcessHandle.current().pid()}" in second.holder, second.holder)
    }

    @Test
    fun aStaleSocketFileFromAKilledControlDoesNotBlockTheStart() {
        File(binarySocket).writeText("")
        val decision = assertIs<ControlInstanceGuard.Decision.Run>(decide())
        cleanup += { decision.lock?.release() }
    }

    @Test
    fun aRunningControlFromBeforeTheGuardIsDetectedByItsSocketAndTheLockIsLetGo() {
        // A pre-guard Control holds no lock but serves ipc.sock.
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(binarySocket))
        cleanup += { server.close() }

        assertIs<ControlInstanceGuard.Decision.Defer>(decide())
        // Not kept: once the old copy quits, the next start must be able to take the lock.
        val after = assertIs<SingleInstanceLock.Outcome.Acquired>(SingleInstanceLock.tryAcquire(lockFile))
        after.lock.release()
    }

    @Test
    fun theHandOffAsksTheRunningControlToShowItsWindow() {
        val opened = AtomicInteger()
        val bridgePath = File(dir, "bridge.sock").path
        val bridge = NativeBridgeServer(bridgePath).apply {
            register("openControlWindow") {
                opened.incrementAndGet()
                mapOf("ok" to true)
            }
            start()
        }
        cleanup += { bridge.stop() }

        assertTrue(ControlInstanceGuard.requestOpenWindow(bridgePath))
        assertEquals(1, opened.get())
    }

    @Test
    fun theHandOffReportsFailureWhenNothingAnswers() {
        assertFalse(ControlInstanceGuard.requestOpenWindow(File(dir, "bridge.sock").path))
        val bridgePath = File(dir, "b2.sock").path
        // A bridge without the method answers with an error, not a result.
        val bridge = NativeBridgeServer(bridgePath).apply { start() }
        cleanup += { bridge.stop() }
        assertFalse(ControlInstanceGuard.requestOpenWindow(bridgePath))
    }

    private fun decide() = ControlInstanceGuard.decide(lockFile, binarySocket, log = {})
}
