package org.tenshu.sdk.ios

import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The in-app UI test double itself: reactive listing + recorded mutations. */
class FakeFilesRepositoryTest {

    private fun file(path: String) = FileModel.File(path, path.substringAfterLast('/'), "", FileIcon.Document)

    @Test
    fun createFolder_records_and_appends_to_the_reactive_listing() = runTest {
        val fake = FakeFilesRepository(initialListing = emptyList())

        val result = fake.createFolder("/New")

        assertTrue(result is RepositoryResult.Success)
        assertEquals(listOf("/New"), fake.createdFolders)
        assertEquals(listOf("New"), fake.files.value.map { it.name })
    }

    @Test
    fun deleteFile_records_and_removes_from_the_listing() = runTest {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt"), file("/b.txt")))

        fake.deleteFile("/a.txt")

        assertEquals(listOf("/a.txt"), fake.deletedPaths)
        assertEquals(listOf("/b.txt"), fake.files.value.map { it.path })
    }

    @Test
    fun rename_and_move_are_recorded() = runTest {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))

        val result = fake.renameFile("/a.txt", "b.txt")

        // A rename is a MoveFile: it RE-KEYS the entry (returning the new path) rather than
        // relabelling it in place, so the old path stops resolving — the property a host that
        // addresses the file by path depends on.
        assertEquals(RepositoryResult.Success("/b.txt"), result)
        assertEquals(listOf("/a.txt" to "b.txt"), fake.renamed)
        assertEquals(listOf("/b.txt"), fake.files.value.map { it.path })
        assertEquals(listOf("b.txt"), fake.files.value.map { it.name })

        fake.moveFile("/b.txt", "/Photos")

        assertEquals(listOf("/b.txt" to "/Photos"), fake.moved)
    }

    @Test
    fun fileDetails_synthesises_a_default_for_a_listed_path() = runTest {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))

        val details = fake.fileDetails("/a.txt").first()

        assertEquals("a.txt", details?.file?.name)
    }
}
