package app.tenshu.control.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderScannerTest {

    private fun tempDir(): File = Files.createTempDirectory("folderscanner").toFile()

    private fun write(dir: File, name: String, lastModified: Long): File {
        val f = File(dir, name)
        f.writeBytes(byteArrayOf(1, 2, 3))
        f.setLastModified(lastModified)
        return f
    }

    @Test
    fun returnsOnlyImagesNewerThanWatermark_oldestFirst() {
        val dir = tempDir()
        write(dir, "old.jpg", 1_000)
        write(dir, "new1.png", 3_000)
        write(dir, "new2.jpeg", 5_000)
        write(dir, "notes.txt", 9_000)       // not an image
        write(dir, "clip.mp4", 9_000)        // video excluded in v1

        val result = FolderScanner.scanNewImages(listOf(dir.absolutePath), sinceMillis = 2_000)

        assertEquals(listOf("new1.png", "new2.jpeg"), result.map { it.name })
        // Oldest-first so a watermark advances monotonically.
        assertEquals(listOf(3_000L, 5_000L), result.map { it.lastModifiedMillis })
    }

    @Test
    fun spansMultipleWatchedPaths_andToleratesMissingDirs() {
        val a = tempDir()
        val b = tempDir()
        write(a, "a.jpg", 4_000)
        write(b, "b.png", 6_000)

        val result = FolderScanner.scanNewImages(
            listOf(a.absolutePath, b.absolutePath, "/no/such/dir"),
            sinceMillis = Long.MIN_VALUE,
        )

        assertEquals(setOf("a.jpg", "b.png"), result.map { it.name }.toSet())
    }

    @Test
    fun emptyWhenNothingNewer() {
        val dir = tempDir()
        write(dir, "old.jpg", 1_000)
        assertEquals(emptyList(), FolderScanner.scanNewImages(listOf(dir.absolutePath), sinceMillis = 2_000))
    }
}
