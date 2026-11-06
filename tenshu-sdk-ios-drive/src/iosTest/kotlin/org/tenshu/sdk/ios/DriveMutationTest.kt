package org.tenshu.sdk.ios

import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.ui.common.createfolder.CreateFolderDialogViewModel
import app.tenshu.drive.ui.common.rename.RenameFileDialogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reactive coverage of the file-mutation view models (create folder / rename / move) over the fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveMutationTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun folder(path: String) = FileModel.Folder(path, path.substringAfterLast('/'), "", FileIcon.Folder)
    private fun file(path: String) = FileModel.File(path, path.substringAfterLast('/'), "", FileIcon.Document)

    @Test
    fun createFolder_builds_the_child_path_and_records_it() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = emptyList())
        val vm = DriveMutationSurface.createFolder(fake, "/Photos")

        vm.onFolderNameChanged("Holiday")
        vm.createFolder()
        advanceUntilIdle()

        assertEquals(listOf("/Photos/Holiday"), fake.createdFolders)
        assertTrue(vm.uiState.value.created)
    }

    @Test
    fun createFolder_at_root_uses_a_single_slash() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = emptyList())
        val vm = DriveMutationSurface.createFolder(fake, "/")

        vm.onFolderNameChanged("New")
        vm.createFolder()
        advanceUntilIdle()

        assertEquals(listOf("/New"), fake.createdFolders)
    }

    @Test
    fun createFolder_onto_an_existing_name_offers_keep_both() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(folder("/Photos")))
        val vm = DriveMutationSurface.createFolder(fake, "/")

        vm.onFolderNameChanged("Photos")
        vm.createFolder()
        advanceUntilIdle()

        assertEquals(
            CreateFolderDialogViewModel.ERROR_ALREADY_EXISTS,
            vm.uiState.value.errorMessage,
        )
        assertTrue(vm.uiState.value.canKeepBoth)
        assertFalse(vm.uiState.value.created)
        assertEquals(emptyList(), fake.createdFolders)

        vm.keepBoth()
        advanceUntilIdle()

        assertEquals(listOf("/Photos (1)"), fake.createdFolders)
        assertTrue(vm.uiState.value.created)
    }

    @Test
    fun keep_both_is_offered_for_a_collision_and_nothing_else() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = emptyList())
        val vm = DriveMutationSurface.createFolder(fake, "/")

        // A validation refusal is not a collision, so the counter remedy makes no sense — the old
        // dialog offered it for ANY error, because the error was only ever a presence flag.
        vm.onFolderNameChanged("a/b")
        assertFalse(vm.uiState.value.canKeepBoth)

        vm.keepBoth()
        advanceUntilIdle()
        assertEquals(emptyList(), fake.createdFolders)
    }

    @Test
    fun a_folder_name_containing_a_separator_is_rejected_as_typed() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = emptyList())
        val vm = DriveMutationSurface.createFolder(fake, "/Photos")

        vm.onFolderNameChanged("a/b")

        // Joined to the parent this would create "/Photos/a/b" — whose parent "/Photos/a" need
        // not exist, since CreateFolderImplementation checks only that the path itself is free.
        assertEquals(
            CreateFolderDialogViewModel.ERROR_NAME_HAS_SEPARATOR,
            vm.uiState.value.errorMessage,
        )
        assertFalse(vm.uiState.value.canCreate)

        vm.createFolder()
        advanceUntilIdle()
        assertEquals(emptyList(), fake.createdFolders)

        vm.onFolderNameChanged("b")
        assertNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.canCreate)
    }

    @Test
    fun an_empty_folder_name_is_not_submittable() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = emptyList())
        val vm = DriveMutationSurface.createFolder(fake, "/Photos")

        // The dialog opens on an empty field, and Compose's confirm button was never disabled —
        // confirming straight away created a folder with no name.
        assertFalse(vm.uiState.value.canCreate)

        vm.onFolderNameChanged("   ")
        assertFalse(vm.uiState.value.canCreate)

        vm.createFolder()
        advanceUntilIdle()
        assertEquals(emptyList(), fake.createdFolders)
    }

    @Test
    fun rename_calls_the_repository_with_the_new_name() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))
        val vm = DriveMutationSurface.rename(fake, "/a.txt")

        vm.onNewNameChanged("b.txt")
        vm.renameFile()
        advanceUntilIdle()

        assertEquals(listOf("/a.txt" to "b.txt"), fake.renamed)
        // The committed new path — what SwiftUI's detail screen re-addresses itself to, since the
        // rename moved the file out from under the path both it and the preview beneath it hold.
        assertEquals("/b.txt", vm.uiState.value.renamedPath)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun rename_reports_no_new_path_until_the_write_commits() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))
        val vm = DriveMutationSurface.rename(fake, "/a.txt")

        vm.onNewNameChanged("b.txt")
        vm.renameFile()

        // Not yet advanced: the coroutine has not run. A host that re-addressed itself on the TAP
        // would point at a path the move has not created — the same dead-path read the signal
        // exists to prevent.
        assertNull(vm.uiState.value.renamedPath)
        assertTrue(vm.uiState.value.isRenaming)
    }

    @Test
    fun rename_onto_an_existing_name_reports_it_and_writes_nothing() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt"), file("/b.txt")))
        val vm = DriveMutationSurface.rename(fake, "/a.txt")

        vm.onNewNameChanged("b.txt")
        vm.renameFile()
        advanceUntilIdle()

        // The refusal is reported as a token (ADR 0016 — the UI owns the copy) and the file keeps
        // its old path, so the host must NOT treat this as a departure. Before this, the result
        // was discarded: the dialog closed and nothing had happened.
        assertEquals(RenameFileDialogViewModel.ERROR_ALREADY_EXISTS, vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.renamedPath)
        assertEquals(emptyList(), fake.renamed)
        assertEquals(listOf("/a.txt", "/b.txt"), fake.files.value.map { it.path })
    }

    @Test
    fun editing_the_name_again_clears_the_refusal() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt"), file("/b.txt")))
        val vm = DriveMutationSurface.rename(fake, "/a.txt")

        vm.onNewNameChanged("b.txt")
        vm.renameFile()
        advanceUntilIdle()
        vm.onNewNameChanged("c.txt")

        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun the_current_name_is_not_submittable() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))
        val vm = DriveMutationSurface.rename(fake, "/a.txt")

        // Seeded with the file's own name: MoveFile rejects a target that exists and the file's
        // OWN path exists, so submitting this would report "already exists" about itself.
        assertEquals("a.txt", vm.uiState.value.newName)
        assertFalse(vm.uiState.value.canRename)

        vm.onNewNameChanged("   ")
        assertFalse(vm.uiState.value.canRename)

        vm.onNewNameChanged("b.txt")
        assertTrue(vm.uiState.value.canRename)

        // …and the guard is the view model's, not just the button's.
        vm.onNewNameChanged("a.txt")
        vm.renameFile()
        advanceUntilIdle()
        assertEquals(emptyList(), fake.renamed)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun a_name_containing_a_separator_is_rejected_as_typed() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))
        val vm = DriveMutationSurface.rename(fake, "/a.txt")

        vm.onNewNameChanged("sub/b.txt")

        // Reported while typing, not on submit — the button is disabled, so a submit-time message
        // would never be reachable. The name is path STRUCTURE: joined to the parent it would
        // relocate the file, into a folder that need not even have a row.
        assertEquals(
            RenameFileDialogViewModel.ERROR_NAME_HAS_SEPARATOR,
            vm.uiState.value.errorMessage,
        )
        assertFalse(vm.uiState.value.canRename)

        vm.renameFile()
        advanceUntilIdle()
        assertEquals(emptyList(), fake.renamed)
        assertNull(vm.uiState.value.renamedPath)

        // Editing the slash away clears it and the name becomes submittable again.
        vm.onNewNameChanged("b.txt")
        assertNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.canRename)
    }

    @Test
    fun move_calls_the_repository_and_flags_moved() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(folder("/Photos")))
        val bound = DriveMutationSurface.movePicker(fake, "/a.txt", "/Photos", this.backgroundScope, dispatcher)

        bound.viewModel.move()
        advanceUntilIdle()

        assertEquals(listOf("/a.txt" to "/Photos"), fake.moved)
        assertTrue(bound.viewModel.uiState.value.moved)
    }

    @Test
    fun move_into_the_source_parent_is_disabled() = runTest(dispatcher) {
        val fake = FakeFilesRepository()
        // Source lives in "/Docs"; browsing "/Docs" must disable "Move here" (no-op move).
        val bound = DriveMutationSurface.movePicker(fake, "/Docs/a.txt", "/Docs", this.backgroundScope, dispatcher)

        assertEquals(false, bound.viewModel.uiState.value.canMoveHere)
    }
}
