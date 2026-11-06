package app.tenshu.drive.data

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFolderEnumerationTest {

    private val root = Files.createTempDirectory("folder-upload-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun listFolderEntries_walksTree_directoriesBeforeContainedFiles() = runTest {
        // root/a.txt, root/sub/, root/sub/b.txt, root/sub/deep/c.txt
        java.io.File(root, "a.txt").writeText("a")
        val sub = java.io.File(root, "sub").apply { mkdirs() }
        java.io.File(sub, "b.txt").writeText("b")
        val deep = java.io.File(sub, "deep").apply { mkdirs() }
        java.io.File(deep, "c.txt").writeText("c")

        val listing = DesktopFilesRepositoryPlatformDelegate().listFolderEntries(root.absolutePath)

        assertEquals(root.name, listing.rootFolderName)

        val byPath = listing.entries.associateBy { it.relativePath }
        assertEquals(false, byPath.getValue("a.txt").isDirectory)
        assertEquals(java.io.File(root, "a.txt").absolutePath, byPath.getValue("a.txt").leafUriString)
        assertTrue(byPath.getValue("sub").isDirectory)
        assertEquals("", byPath.getValue("sub").leafUriString)
        // Relative paths use POSIX separators regardless of host OS.
        assertEquals(false, byPath.getValue("sub/b.txt").isDirectory)
        assertEquals(true, byPath.getValue("sub/deep").isDirectory)
        assertEquals(false, byPath.getValue("sub/deep/c.txt").isDirectory)

        val order = listing.entries.map { it.relativePath }
        assertTrue(
            order.indexOf("sub") < order.indexOf("sub/b.txt"),
            "directory must precede the files it contains: $order",
        )
        assertTrue(
            order.indexOf("sub/deep") < order.indexOf("sub/deep/c.txt"),
            "nested directory must precede its files: $order",
        )
    }

    @Test
    fun listFolderEntries_emptyFolder_yieldsNoEntries() = runTest {
        val listing = DesktopFilesRepositoryPlatformDelegate().listFolderEntries(root.absolutePath)
        assertEquals(root.name, listing.rootFolderName)
        assertTrue(listing.entries.isEmpty())
    }
}
