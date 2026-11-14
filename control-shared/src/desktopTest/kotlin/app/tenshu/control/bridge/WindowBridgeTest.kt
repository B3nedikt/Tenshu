package app.tenshu.control.bridge

import app.tenshu.control.lifecycle.WindowController
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.transport.StreamFramer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof of the slice-1A window-lifecycle bridge methods: a real UDS
 * round-trip flips the [WindowController] the daemon holds.
 */
class WindowBridgeTest {

    // Short /tmp path: macOS Unix-domain sun_path caps at ~104 chars, and the
    // default /var/folders temp dir blows past it once a socket name is appended.
    private val tempDir = Files.createTempDirectory(Path.of("/tmp"), "tw")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun openControlWindowShowsAndQuitAppSignalsQuit() = runBlocking {
        val controller = WindowController(initiallyVisible = false)
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply {
            registerWindowMethods(controller)
            start()
        }

        connect(socketPath).use { ch ->
            assertFalse(controller.visible.value, "precondition: starts hidden")

            // openControlWindow → controller.show()
            call(ch, "openControlWindow")
            assertTrue(controller.visible.value, "openControlWindow should show the window")

            // quitApp → controller.requestQuit() fires on quitRequested
            val quit = async { withTimeout(2000) { controller.quitRequested.first() } }
            kotlinx.coroutines.yield()
            call(ch, "quitApp")
            quit.await() // throws on timeout if the signal never arrived
        }
    }

    @Test
    fun openControlWindowRunsTheReviveHookAndQuitAppDoesNot() {
        // The hook is how a deferring second Control gets the running one to revive its tray —
        // the deferring copy itself must never spawn one.
        val controller = WindowController(initiallyVisible = false)
        var opened = 0
        val socketPath = tempDir.resolve("hook.sock").toString()
        NativeBridgeServer(socketPath).apply {
            registerWindowMethods(controller, onOpenWindow = { opened++ })
            start()
        }

        connect(socketPath).use { ch ->
            call(ch, "openControlWindow")
            assertEquals(1, opened)
            call(ch, "quitApp")
            assertEquals(1, opened, "quitApp must not revive the tray")
        }
    }

    private fun call(ch: SocketChannel, method: String) {
        val req = """{"v":1,"id":"t","method":"$method","params":{}}""".encodeToByteArray()
        ch.write(ByteBuffer.wrap(StreamFramer.frame(req)))
        readFrame(ch) // drain the {ok:true} reply so the channel stays in sync
    }

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

    private fun readFrame(ch: SocketChannel) {
        val header = ByteBuffer.allocate(4); readFully(ch, header); header.flip()
        val length = header.getInt()
        readFully(ch, ByteBuffer.allocate(1))
        readFully(ch, ByteBuffer.allocate(length))
    }

    private fun readFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (ch.read(buf) == -1) error("eof")
    }
}
