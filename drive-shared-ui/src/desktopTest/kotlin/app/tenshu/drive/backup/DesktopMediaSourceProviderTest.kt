package app.tenshu.drive.backup

import app.tenshu.ingest.backup.MediaType
import app.tenshu.drive.data.DesktopMediaSourceProvider
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end desktop source adapter over a temp directory tree. */
class DesktopMediaSourceProviderTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("synced-folders-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String, mtime: Long) {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText("x")
        file.setLastModified(mtime)
    }

    private fun provider() = DesktopMediaSourceProvider(roots = listOf(root.absolutePath))

    @Test
    fun listBuckets_returnsRootAndSubdirsWithMedia_skipsNonMedia() = runTest {
        write("a.jpg", 1_000)
        write("Camera/b.png", 2_000)
        write("Camera/notes.txt", 3_000)     // non-media → ignored
        write("Empty/.keep", 4_000)          // no media → no bucket

        val buckets = provider().listBuckets().associateBy { it.displayName }

        // The root dir (has a.jpg) and Camera (has b.png) are buckets; Empty is not.
        assertEquals(setOf(root.name, "Camera"), buckets.keys)
        assertEquals(1, buckets.getValue("Camera").itemCount)
        assertTrue(buckets.getValue("Camera").sampleThumbnailUri!!.endsWith("b.png"))
    }

    @Test
    fun listNewItems_respectsCursor_oldestFirst() = runTest {
        write("Camera/one.jpg", 1_000)
        write("Camera/two.jpg", 2_000)
        write("Camera/three.jpg", 3_000)
        val bucketId = File(root, "Camera").absolutePath

        val all = provider().listNewItems(bucketId, sinceCursor = null)
        assertEquals(listOf("one.jpg", "two.jpg", "three.jpg"), all.map { it.fileName })
        assertEquals("1000", all.first().cursor)

        // After processing through mtime 2000, only the newest item remains.
        val after = provider().listNewItems(bucketId, sinceCursor = "2000")
        assertEquals(listOf("three.jpg"), after.map { it.fileName })
    }

    @Test
    fun listNewItems_excludesVideoUnlessOptedIn() = runTest {
        write("Camera/clip.mp4", 1_000)
        write("Camera/pic.jpg", 2_000)
        val bucketId = File(root, "Camera").absolutePath

        assertEquals(listOf("pic.jpg"), provider().listNewItems(bucketId, null, includeVideo = false).map { it.fileName })

        val withVideo = provider().listNewItems(bucketId, null, includeVideo = true)
        assertEquals(listOf("clip.mp4", "pic.jpg"), withVideo.map { it.fileName })
        assertEquals(MediaType.VIDEO, withVideo.first { it.fileName == "clip.mp4" }.type)
    }
}
