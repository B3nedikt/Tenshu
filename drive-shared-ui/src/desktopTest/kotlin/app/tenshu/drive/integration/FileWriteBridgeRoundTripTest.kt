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
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the Phase 3 file-write bridge methods
 * (`createFolder` / `createFile` / `modifyContents` / `moveItem` / `deleteItem`)
 * over a real Unix-domain socket, backed by the in-process file-service. This is
 * the macOS Drive File Provider's write path exercised as the `.appex` will drive
 * it; reads (the Phase-2 methods, also registered) confirm each write actually
 * landed in the realm.
 */
class FileWriteBridgeRoundTripTest {

    // Short /tmp path — macOS Unix-domain sun_path caps at ~104 chars.
    private val tempDir = Files.createTempDirectory(Path.of("/tmp"), "fw")

    private lateinit var appKoin: AppIntegrationKoin
    private val client: TenshuClient get() = appKoin.client

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
    }

    @After
    fun tearDown() {
        // Cancel-and-join the proxy's background loops (ScheduleDrainLoop etc.)
        // before dropping the container — see AppIntegrationKoin.stop's docs for why a
        // bare close() leaves a loop NPE-ing against the torn-down DataFactory
        // and polluting the next runTest-based test.
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        appKoin.stop()
    }

    @Test
    fun createModifyMoveDeleteRoundTrip() {
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply {
            val state = FileProviderBridgeState()
            registerFileMethods(appKoin.koin, state)
            registerFileWriteMethods(appKoin.koin, state)
            start()
        }

        connect(socketPath).use { ch ->
            // createFolder → folder item.
            val folder = call(ch, "fileProvider.v2.createFolder", """{"parentId":"root","name":"docs"}""")
            assertTrue(folder.contains("\"isFolder\":true"), "createFolder flag: $folder")
            val docsId = idOf(folder, "docs")

            // createFile (base64 payload) → file item with the real size.
            val hello = "hello".encodeToByteArray()
            val created = call(ch, "fileProvider.v2.createFile", write(docsId, "note.txt", hello))
            assertTrue(created.contains("\"size\":${hello.size}"), "createFile size: $created")
            assertTrue(created.contains("\"parentId\":\"$docsId\""), "createFile parent: $created")
            val noteId = idOf(created, "note.txt")
            assertBytes(ch, noteId, hello)

            // modifyContents overwrites the bytes; the content version moves, the id does not.
            val updated = "hello world!".encodeToByteArray()
            val modified = call(ch, "fileProvider.v2.modifyContents", """{"id":"$noteId","data":"${b64(updated)}"}""")
            assertEquals(noteId, idOf(modified, "note.txt"))
            assertTrue(modified.contains("\"size\":${updated.size}"), "modify size: $modified")
            assertTrue(
                extractString(modified, "contentVersion") != extractString(created, "contentVersion"),
                "an overwrite must change the content version",
            )
            assertBytes(ch, noteId, updated)

            // A rename keeps the identifier — the whole point of the id map.
            val renamed = call(ch, "fileProvider.v2.moveItem", """{"id":"$noteId","parentId":"$docsId","name":"renamed.txt"}""")
            assertEquals(noteId, idOf(renamed, "renamed.txt"))
            assertBytes(ch, noteId, updated)
            assertTrue(!call(ch, "fileProvider.v2.listFolder", """{"id":"$docsId"}""").contains("\"name\":\"note.txt\""))

            // Renaming the FOLDER keeps its id and its child's, and the child still names it.
            val movedFolder = call(ch, "fileProvider.v2.moveItem", """{"id":"$docsId","parentId":"root","name":"papers"}""")
            assertEquals(docsId, idOf(movedFolder, "papers"))
            val child = call(ch, "fileProvider.v2.itemMetadata", """{"id":"$noteId"}""")
            assertEquals(noteId, idOf(child, "renamed.txt"))
            assertTrue(child.contains("\"parentId\":\"$docsId\""), "child after folder move: $child")
            assertBytes(ch, noteId, updated)

            // A no-op move is answered, not attempted.
            assertEquals(noteId, idOf(call(ch, "fileProvider.v2.moveItem", """{"id":"$noteId","parentId":"$docsId","name":"renamed.txt"}"""), "renamed.txt"))

            // A name is one path component.
            assertTrue(call(ch, "fileProvider.v2.createFolder", """{"parentId":"root","name":"a/b"}""").contains("invalid_request"))
            assertTrue(call(ch, "fileProvider.v2.moveItem", """{"id":"$noteId","parentId":"root","name":".."}""").contains("invalid_request"))

            // deleteItem removes it, and the identifier dies with it.
            assertTrue(call(ch, "fileProvider.v2.deleteItem", """{"id":"$noteId"}""").contains("\"ok\":true"))
            assertTrue(call(ch, "fileProvider.v2.itemMetadata", """{"id":"$noteId"}""").contains("not_found"))

            // A colliding folder create surfaces already_exists.
            assertTrue(
                call(ch, "fileProvider.v2.createFolder", """{"parentId":"root","name":"papers"}""").contains("already_exists"),
                "duplicate folder should conflict",
            )
        }
    }

    /**
     * A change-feed refresh lists the realm, prunes identifiers whose path is gone and mints
     * the rest. Racing a rename, an unserialised refresh prunes the identifier the rename just
     * re-pointed — so renames and refreshes are hammered against each other here and the
     * identifier must come through every one of them.
     */
    @Test
    fun anIdentifierSurvivesRenamesRacingFeedRefreshes() {
        val socketPath = tempDir.resolve("race.sock").toString()
        NativeBridgeServer(socketPath).apply {
            val state = FileProviderBridgeState()
            registerFileMethods(appKoin.koin, state)
            registerFileWriteMethods(appKoin.koin, state)
            start()
        }
        val noteId = connect(socketPath).use { ch ->
            idOf(call(ch, "fileProvider.v2.createFile", write("root", "a.txt", byteArrayOf(1))), "a.txt")
        }

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val refresher = kotlin.concurrent.thread {
            connect(socketPath).use { ch ->
                while (!stop.get()) call(ch, "fileProvider.v2.currentAnchor")
            }
        }
        try {
            connect(socketPath).use { ch ->
                repeat(30) { round ->
                    val name = if (round % 2 == 0) "b.txt" else "a.txt"
                    val moved = call(ch, "fileProvider.v2.moveItem", """{"id":"$noteId","parentId":"root","name":"$name"}""")
                    assertEquals(noteId, idOf(moved, name), "round $round: the rename changed the identifier")
                    val after = call(ch, "fileProvider.v2.itemMetadata", """{"id":"$noteId"}""")
                    assertEquals(noteId, idOf(after, name), "round $round: the identifier died after the rename: $after")
                }
            }
        } finally {
            stop.set(true)
            refresher.join(10_000)
        }
    }

    /**
     * Contents by hand-off: only a file NAME crosses the bridge, the bytes cross in the staging
     * directory. Sized past one ranged read, so a chunked path that dropped or repeated a chunk
     * would show.
     */
    @Test
    fun contentsCrossInTheStagingDirectory_notInTheReply() {
        val staging = tempDir.resolve("staging").toFile()
        val socketPath = tempDir.resolve("staged.sock").toString()
        NativeBridgeServer(socketPath).apply {
            val state = FileProviderBridgeState(stagingDir = staging)
            registerFileMethods(appKoin.koin, state)
            registerFileWriteMethods(appKoin.koin, state)
            start()
        }
        val payload = ByteArray(700_000) { (it * 31 % 251).toByte() }

        connect(socketPath).use { ch ->
            // Upload: the extension stages the bytes, then names them.
            java.io.File(staging, "upload-0001").writeBytes(payload)
            val created = call(ch, "fileProvider.v2.createFileFrom", """{"parentId":"root","name":"clip.bin","staged":"upload-0001"}""")
            val id = idOf(created, "clip.bin")
            assertTrue(created.contains("\"size\":${payload.size}"), "createFileFrom size: $created")
            assertTrue(!java.io.File(staging, "upload-0001").exists(), "a consumed upload must not linger as plaintext")

            // Download: the reply carries a name and a size, and no bytes.
            val fetched = call(ch, "fileProvider.v2.fetchContents", """{"id":"$id","staged":"download-0001"}""")
            assertTrue(fetched.contains("\"size\":${payload.size}"), "fetchContents size: $fetched")
            assertTrue(!fetched.contains("\"data\""), "no bytes may ride in the reply: $fetched")
            assertTrue(payload.contentEquals(java.io.File(staging, "download-0001").readBytes()), "staged bytes differ")

            // Overwrite by hand-off, read back by hand-off.
            val replacement = ByteArray(300_000) { (it % 7).toByte() }
            java.io.File(staging, "upload-0002").writeBytes(replacement)
            val modified = call(ch, "fileProvider.v2.modifyContentsFrom", """{"id":"$id","staged":"upload-0002"}""")
            assertEquals(id, idOf(modified, "clip.bin"))
            call(ch, "fileProvider.v2.fetchContents", """{"id":"$id","staged":"download-0002"}""")
            assertTrue(replacement.contentEquals(java.io.File(staging, "download-0002").readBytes()))

            // A name is a token: nothing that could address a file outside the directory.
            val outside = tempDir.resolve("outside.txt").toFile().apply { writeText("not yours") }
            val hostile = call(ch, "fileProvider.v2.createFileFrom", """{"parentId":"root","name":"x.txt","staged":"../outside.txt"}""")
            assertTrue(hostile.contains("invalid_request"), "a path as a staging name must be refused: $hostile")
            assertTrue(outside.exists(), "and must not be consumed")
            assertTrue(call(ch, "fileProvider.v2.fetchContents", """{"id":"$id","staged":"../leak"}""").contains("invalid_request"))

            // Nothing staged under that name, and an unknown id, fail without leaving a file.
            assertTrue(call(ch, "fileProvider.v2.createFileFrom", """{"parentId":"root","name":"y.txt","staged":"missing-0001"}""").contains("invalid_request"))
            assertTrue(call(ch, "fileProvider.v2.fetchContents", """{"id":"never-issued","staged":"download-0003"}""").contains("not_found"))
            assertTrue(!java.io.File(staging, "download-0003").exists())
        }
    }

    /**
     * "Date Modified" in Finder is the file's own date. A file dragged in keeps the date its
     * template carried, a rename leaves it alone, and it is never the moment of the upload —
     * which is what made a library backed up in a day read as modified that day.
     */
    @Test
    fun aCreatedFileKeepsItsOwnModifiedDate_andARenameDoesNotMoveIt() {
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply {
            val state = FileProviderBridgeState()
            registerFileMethods(appKoin.koin, state)
            registerFileWriteMethods(appKoin.koin, state)
            start()
        }

        connect(socketPath).use { ch ->
            val year2011 = 1_300_000_000_000L
            val before = System.currentTimeMillis()
            val old = call(
                ch, "fileProvider.v2.createFile",
                """{"parentId":"root","name":"old.jpg","data":"${b64(byteArrayOf(1, 2))}","modified":"$year2011"}""",
            )
            assertEquals(year2011, extractLong(old, "modified"), "the template's date must survive: $old")

            val renamed = call(
                ch, "fileProvider.v2.moveItem",
                """{"id":"${idOf(old, "old.jpg")}","parentId":"root","name":"older.jpg"}""",
            )
            assertEquals(year2011, extractLong(renamed, "modified"), "a rename is not a modification: $renamed")

            // No date on the template: the content really was written just now.
            val fresh = call(ch, "fileProvider.v2.createFile", write("root", "new.txt", byteArrayOf(3)))
            assertTrue(extractLong(fresh, "modified") >= before, "an undated create is modified now: $fresh")
        }
    }

    private fun extractLong(json: String, key: String): Long {
        val marker = "\"$key\":"
        val start = json.indexOf(marker).also { assertTrue(it >= 0, "no '$key' in: $json") } + marker.length
        return json.substring(start).takeWhile { it.isDigit() }.toLong()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun write(parentId: String, name: String, bytes: ByteArray): String =
        """{"parentId":"$parentId","name":"$name","data":"${b64(bytes)}"}"""

    private fun assertBytes(ch: SocketChannel, id: String, expected: ByteArray) {
        val reply = call(ch, "fileProvider.v2.readContents", """{"id":"$id"}""")
        val decoded = Base64.getDecoder().decode(extractString(reply, "data"))
        assertTrue(expected.contentEquals(decoded), "bytes for $id differ: '${decoded.decodeToString()}'")
    }

    /** The `id` of the item named [name] in a reply; item objects are flat, so a brace scan is safe. */
    private fun idOf(reply: String, name: String): String {
        val item = Regex("""\{[^{}]*"name":"${Regex.escape(name)}"[^{}]*\}""").find(reply)?.value
            ?: throw AssertionError("no item named '$name' in: $reply")
        return extractString(item, "id")
    }

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
