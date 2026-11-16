package app.tenshu.drive.integration

import app.tenshu.control.bridge.FileProviderBridgeState
import app.tenshu.control.bridge.registerFileMethods
import app.tenshu.control.bridge.registerFileWriteMethods
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.transport.StreamFramer
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the Phase 2 file-read bridge methods
 * (`listFolder` / `itemMetadata` / `readContents`) over a real Unix-domain
 * socket, backed by the in-process file-service. This is the macOS Drive File
 * Provider's read path exercised exactly as the `.appex` will drive it: JSON
 * request in, JSON (+ base64 bytes) out — proving the chain DSL the handlers run
 * resolves real listings and bytes, which the pure-shape tests in control-shared
 * can't cover.
 */
class FileBridgeRoundTripTest {

    // Short /tmp path — macOS Unix-domain sun_path caps at ~104 chars.
    private val tempDir = org.tenshu.core.common.shortTempDirectory("fb")

    private lateinit var appKoin: AppIntegrationKoin
    private val client: TenshuClient get() = appKoin.client

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
    }

    @After
    fun tearDown() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        appKoin.stop()
    }

    @Test
    fun listFolderItemMetadataAndReadContents() = runBlocking {
        val fileSystem = client.fileService.defaultFileSystem
        val helloBytes = "hello tenshu".encodeToByteArray()
        fileSystem.createFolder("/Photos")
        fileSystem.uploadFile(parentPath = "/", fileName = "hello.txt", data = helloBytes)
        fileSystem.uploadFile(parentPath = "/Photos", fileName = "cat.bin", data = byteArrayOf(1, 2, 3, 4, 5))

        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply { registerFileMethods(appKoin.koin, FileProviderBridgeState()); start() }

        connect(socketPath).use { ch ->
            // listFolder at the realm root: the file + the folder, with folder flag.
            val rootReply = call(ch, "fileProvider.v2.listFolder", id("root"))
            assertTrue(rootReply.contains("\"isFolder\":true"), "root missing folder flag: $rootReply")
            val helloId = idOf(rootReply, "hello.txt")
            val photosId = idOf(rootReply, "Photos")
            assertTrue(!rootReply.contains("\"path\""), "the realm path must not cross the bridge: $rootReply")

            // listFolder descends into a subfolder, and the child names its parent.
            val subReply = call(ch, "fileProvider.v2.listFolder", id(photosId))
            idOf(subReply, "cat.bin")
            assertTrue(subReply.contains("\"parentId\":\"$photosId\""), "child should name its parent: $subReply")

            // The same item has the same id however it is reached.
            val metaReply = call(ch, "fileProvider.v2.itemMetadata", id(helloId))
            assertEquals(helloId, idOf(metaReply, "hello.txt"))
            assertTrue(metaReply.contains("\"parentId\":\"root\""), "root child: $metaReply")
            assertTrue(metaReply.contains("\"size\":${helloBytes.size}"), "wrong size: $metaReply")
            assertTrue(metaReply.contains("\"isFolder\":false"), "should be a file: $metaReply")

            // readContents round-trips the real bytes through base64.
            val contentsReply = call(ch, "fileProvider.v2.readContents", id(helloId))
            val decoded = Base64.getDecoder().decode(extractString(contentsReply, "data"))
            assertTrue(helloBytes.contentEquals(decoded), "bytes differ: '${decoded.decodeToString()}'")

            // listAll is the working set: every item at every depth, folders included.
            val all = call(ch, "fileProvider.v2.listAll")
            assertEquals(helloId, idOf(all, "hello.txt"))
            assertEquals(photosId, idOf(all, "Photos"))
            idOf(all, "cat.bin")
            assertTrue(all.contains("\"anchor\":\""), "listAll should carry the anchor: $all")

            // A folder deleted behind the bridge takes its children's rows with it. Reading a
            // child then fails a level UP the chain — and must still say "gone", not "try again".
            val catId = idOf(subReply, "cat.bin")
            fileSystem.deleteFile("/Photos")
            val orphan = call(ch, "fileProvider.v2.readContents", id(catId))
            assertTrue(orphan.contains("\"code\":\"not_found\""), "a missing parent is absence: $orphan")
            assertTrue(call(ch, "fileProvider.v2.itemMetadata", id(catId)).contains("\"code\":\"not_found\""))

            // The v1 names are gone, not re-purposed: an extension from before the identifier
            // scheme must fail cleanly rather than have its paths read as ids (or the reverse).
            val v1 = call(ch, "listFolder", """{"path":"/"}""")
            assertTrue(v1.contains("\"error\"") && !v1.contains("\"items\""), "v1 listFolder must not answer: $v1")

            // An identifier this daemon never issued is not_found — including a raw path.
            assertTrue(call(ch, "fileProvider.v2.itemMetadata", id("never-issued")).contains("not_found"))
            assertTrue(call(ch, "fileProvider.v2.itemMetadata", id("/hello.txt")).contains("not_found"))
        }
    }

    /**
     * Changes made outside the bridge — the Drive app, another device — must reach Finder as
     * deltas against the anchor it holds, and a rename through the bridge must arrive as an
     * UPDATE of the same identifier, never as a delete.
     */
    @Test
    fun changesSinceReportsOutsideEditsAsDeltas() = runBlocking {
        val fileSystem = client.fileService.defaultFileSystem
        fileSystem.uploadFile(parentPath = "/", fileName = "kept.txt", data = byteArrayOf(1))
        fileSystem.uploadFile(parentPath = "/", fileName = "doomed.txt", data = byteArrayOf(2))

        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply {
            val state = FileProviderBridgeState()
            registerFileMethods(appKoin.koin, state)
            registerFileWriteMethods(appKoin.koin, state)
            start()
        }

        connect(socketPath).use { ch ->
            val all = call(ch, "fileProvider.v2.listAll")
            val keptId = idOf(all, "kept.txt")
            val doomedId = idOf(all, "doomed.txt")
            val a0 = extractString(all, "anchor")
            assertEquals(a0, extractString(call(ch, "fileProvider.v2.currentAnchor"), "anchor"), "nothing changed yet")

            // Nothing changed → same anchor, empty delta.
            val quiet = call(ch, "fileProvider.v2.changesSince", """{"anchor":"$a0"}""")
            assertTrue(quiet.contains("\"updated\":[]") && quiet.contains("\"deleted\":[]"), quiet)

            // An upload, an overwrite and a delete behind the bridge's back.
            fileSystem.uploadFile(parentPath = "/", fileName = "arrived.txt", data = byteArrayOf(3))
            fileSystem.replaceFile(parentPath = "/", fileName = "kept.txt", data = byteArrayOf(9, 9))
            fileSystem.deleteFile("/doomed.txt")

            val delta = call(ch, "fileProvider.v2.changesSince", """{"anchor":"$a0"}""")
            assertTrue(delta.contains("\"expired\":false"), delta)
            idOf(delta, "arrived.txt")
            assertEquals(keptId, idOf(delta, "kept.txt"), "an overwrite keeps the identifier")
            assertTrue(delta.contains("\"deleted\":[\"$doomedId\"]"), "the deleted id should be reported: $delta")
            val a1 = extractString(delta, "anchor")
            assertTrue(a1 != a0, "the anchor must advance")

            // A rename through the bridge: same id, reported as an update, not deleted.
            val renamed = call(ch, "fileProvider.v2.moveItem", """{"id":"$keptId","parentId":"root","name":"renamed.txt"}""")
            assertEquals(keptId, idOf(renamed, "renamed.txt"))
            val afterRename = call(ch, "fileProvider.v2.changesSince", """{"anchor":"$a1"}""")
            assertEquals(keptId, idOf(afterRename, "renamed.txt"))
            assertTrue(afterRename.contains("\"deleted\":[]"), "a rename must not delete: $afterRename")

            // A reader still on the first anchor is answered from history, not expired.
            val late = call(ch, "fileProvider.v2.changesSince", """{"anchor":"$a0"}""")
            assertTrue(late.contains("\"expired\":false"), late)
            assertEquals(keptId, idOf(late, "renamed.txt"))

            // An anchor this daemon never issued expires.
            assertTrue(call(ch, "fileProvider.v2.changesSince", """{"anchor":"v1"}""").contains("\"expired\":true"))
        }
    }

    /**
     * A Finder thumbnail is one of the realm's STORED thumbnail classes, chosen by the size
     * asked for, and "this file has none" is an answer rather than an error.
     *
     * Runs on the JVM's real renderer (`realPreviews`): the default stub answers the same 1x1
     * PNG for every file, text files included, which can show neither which class was served
     * nor that a file has none. The edges are what tell a stored class from a render of the
     * original — 256 px and 100 px are the classes' own, where `File.Preview` would have
     * rendered the local original at the size asked for.
     *
     * ⚠ The bytes are local here (this process uploaded them), so the pull a byte-less device
     * makes is not exercised.
     */
    @Test
    fun thumbnailServesTheStoredClassAndSaysWhenThereIsNone() = runBlocking {
        appKoin.stop()
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test", realPreviews = true)
        val fileSystem = client.fileService.defaultFileSystem
        val picture = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB).let { image ->
            image.createGraphics().apply { color = Color.ORANGE; fillRect(0, 0, 640, 240); dispose() }
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
        fileSystem.uploadFile(parentPath = "/", fileName = "picture.png", data = picture)
        fileSystem.uploadFile(parentPath = "/", fileName = "notes.txt", data = "no picture".encodeToByteArray())
        fileSystem.createFolder("/Folder")

        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply { registerFileMethods(appKoin.koin, FileProviderBridgeState()); start() }

        connect(socketPath).use { ch ->
            val root = call(ch, "fileProvider.v2.listFolder", id("root"))
            fun thumbnail(name: String, edge: Int) =
                call(ch, "fileProvider.v2.thumbnail", """{"id":"${idOf(root, name)}","width":$edge,"height":$edge}""")

            // A request above the list class is answered by the 256 px class, which is a JPEG.
            val large = thumbnail("picture.png", 512)
            assertTrue(large.contains("\"available\":true"), large)
            val largeBytes = Base64.getDecoder().decode(extractString(large, "data"))
            assertTrue(large.contains("\"size\":${largeBytes.size}"), large)
            assertEquals(listOf(0xFF, 0xD8), largeBytes.take(2).map { it.toInt() and 0xFF }, "expected a JPEG")
            val largeImage = ImageIO.read(largeBytes.inputStream())
            assertEquals(256, maxOf(largeImage.width, largeImage.height), "expected the LARGE class, not a render")

            // A small request is answered by the 100 px class.
            val small = ImageIO.read(Base64.getDecoder().decode(extractString(thumbnail("picture.png", 64), "data")).inputStream())
            assertEquals(100, maxOf(small.width, small.height), "expected the LIST class")

            // Nothing mints a thumbnail for a text file or a folder: an answer, not an error.
            for (name in listOf("notes.txt", "Folder")) {
                val none = thumbnail(name, 256)
                assertTrue(none.contains("\"available\":false") && !none.contains("\"error\""), "$name: $none")
            }
            val ofRoot = call(ch, "fileProvider.v2.thumbnail", """{"id":"root","width":256,"height":256}""")
            assertTrue(ofRoot.contains("\"available\":false"), ofRoot)

            // An identifier this daemon never issued is not_found; a size it cannot use is refused.
            val unknown = call(ch, "fileProvider.v2.thumbnail", """{"id":"never-issued","width":256,"height":256}""")
            assertTrue(unknown.contains("not_found"), unknown)
            val noSize = call(ch, "fileProvider.v2.thumbnail", id(idOf(root, "picture.png")))
            assertTrue(noSize.contains("invalid_request"), noSize)
        }
    }

    /** The `id` of the item named [name] in a reply; item objects are flat, so a brace scan is safe. */
    private fun idOf(reply: String, name: String): String {
        val item = Regex("""\{[^{}]*"name":"${Regex.escape(name)}"[^{}]*\}""").find(reply)?.value
            ?: throw AssertionError("no item named '$name' in: $reply")
        return extractString(item, "id")
    }

    private fun id(value: String) = """{"id":"$value"}"""

    // base64 payloads never contain a bare quote, so a quoted-value scan is safe.
    private fun extractString(json: String, key: String): String {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker).also { assertTrue(it >= 0, "no '$key' in: $json") } + marker.length
        return json.substring(start, json.indexOf('"', start))
    }

    private fun call(ch: SocketChannel, method: String, params: String = "{}"): String {
        val req = """{"v":1,"id":"t","method":"$method","params":$params}""".encodeToByteArray()
        ch.write(ByteBuffer.wrap(StreamFramer.frame(req)))
        return readFrame(ch)
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

    private fun readFrame(ch: SocketChannel): String {
        val header = ByteBuffer.allocate(4); readFully(ch, header); header.flip()
        val length = header.getInt()
        readFully(ch, ByteBuffer.allocate(1))
        val payload = ByteBuffer.allocate(length); readFully(ch, payload)
        return payload.array().decodeToString()
    }

    private fun readFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (ch.read(buf) == -1) error("eof")
    }
}
