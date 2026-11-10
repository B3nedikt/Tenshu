package app.tenshu.control.bridge

import app.tenshu.control.data.SyncPauseState
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.transport.StreamFramer
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.SyncHealthHolder
import java.lang.reflect.Proxy
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tray's Pause/Resume over the bridge, in the order `Main.kt` wires it: the bridge methods are registered
 * **before** the `SyncPauseState` single joins Koin. A lookup made at registration time would be null for the
 * whole run, so `setSyncPaused` would do nothing and `getSyncState` would always say "not paused".
 */
class ControlBridgePauseTest {

    private val tempDir = org.tenshu.core.common.shortTempDirectory("tp")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun pauseWorksWhenThePauseSwitchJoinsKoinAfterTheBridgeMethods() {
        // The pause path never touches the sync manager; any call would fail the test loudly.
        val syncManager = Proxy.newProxyInstance(
            SyncManager::class.java.classLoader,
            arrayOf(SyncManager::class.java),
        ) { _, method, _ -> error("SyncManager.${method.name} is not part of the pause path") } as SyncManager
        val app = koinApplication {
            modules(module {
                single { syncManager }
                single { SyncHealthHolder() }
            })
        }
        val socketPath = tempDir.resolve("bridge.sock").toString()
        val server = NativeBridgeServer(socketPath).apply { registerControlMethods(app.koin); start() }
        try {
            // Main.kt's order: the switch is loaded only after registration.
            val pause = SyncPauseState(initiallyPaused = false)
            app.koin.loadModules(listOf(module { single { pause } }))

            val reply = connect(socketPath).use { call(it, "setSyncPaused", """{"paused":"true"}""") }

            assertTrue(pause.isPaused(), "setSyncPaused did not reach the pause switch: $reply")
            assertTrue(reply.contains("\"paused\":true"), "the reply must report the new state: $reply")

            connect(socketPath).use { call(it, "setSyncPaused", """{"paused":"false"}""") }
            assertTrue(!pause.isPaused(), "resume must reach the pause switch too")
        } finally {
            server.stop()
        }
    }

    private fun call(ch: SocketChannel, method: String, params: String): String {
        val req = """{"v":1,"id":"t","method":"$method","params":$params}""".encodeToByteArray()
        ch.write(ByteBuffer.wrap(StreamFramer.frame(req)))
        val header = ByteBuffer.allocate(4); readFully(ch, header); header.flip()
        val length = header.getInt()
        readFully(ch, ByteBuffer.allocate(1))
        val payload = ByteBuffer.allocate(length); readFully(ch, payload)
        return payload.array().decodeToString()
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

    private fun readFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (ch.read(buf) == -1) error("eof")
    }
}
