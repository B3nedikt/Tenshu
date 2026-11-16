package app.tenshu.windows

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real peer lookups over a real AF_UNIX socket, on a Windows host. The peer is this test JVM itself
 * — an unpackaged `java.exe` — so the positive path (a package family name) is not reachable here;
 * that needs a side-loaded MSIX (`desktop-windows-port.md` W2 gate).
 *
 * ⚠ On Windows availability is asserted **hard**: a binding that silently failed to resolve would
 * make every peer unattested, and a strict Control would refuse every app with no visible cause.
 */
class WindowsPeerProcessTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val dir = Files.createTempDirectory("wpp")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun availableExactlyOnWindows() {
        assertEquals(windows, WindowsPeerProcess.isAvailable())
    }

    @Test
    fun theFactsDescribeThePeerProcess_andAnUnpackagedPeerHasNoFamilyName() {
        if (!windows) return
        val path = dir.resolve("p.sock")
        val startedAt = System.currentTimeMillis()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(path))
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                client.connect(UnixDomainSocketAddress.of(path))
                server.accept().use { accepted ->
                    val facts = assertIs<WindowsPeerProcess.Result.Facts>(WindowsPeerProcess.read(accepted)).facts

                    assertEquals(ProcessHandle.current().pid(), facts.pid, "the peer is this JVM")
                    assertTrue(facts.alive)
                    assertTrue(facts.createdAtMillis <= startedAt, "this JVM was created before the accept")
                    assertNull(facts.packageFamilyName, "a plain java.exe has no package identity")
                    assertTrue(facts.imagePath.orEmpty().endsWith("java.exe", ignoreCase = true), "${facts.imagePath}")

                    // …and so the decision refuses it, whatever the anchor says.
                    assertIs<WindowsPeerAttestation.Unattested>(
                        WindowsPeerDecision.decide(facts, System.currentTimeMillis()) { setOf("4k2m9q8vxz1ab") },
                    )
                }
            }
        }
    }
}
