package app.tenshu.drive.ui

import app.tenshu.drive.data.SharedFolderEntry
import app.tenshu.drive.ui.screens.shared.SharedBrowseViewModel
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.file.shared.SharedFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3d.2b (`docs/proposals/drive-shared-realm-file-model.md` §6): the
 * shared-realm browse VM lists a realm's `SharedFile` tree, unwraps the lone
 * realm-root wrapper folder (3d.2b-i), and opens files via `readSharedFile`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedBrowseViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val realm = "realm-1"

    @Test
    fun realmRoot_unwrapsTheLoneWrapperFolder_andTitlesWithItsName() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Realm root holds the single wrapper "Holiday"; its contents nest under it.
        val repo = FakeFilesRepository(
            browseResults = mapOf(
                SharedFile.ROOT_PARENT to listOf(
                    SharedFolderEntry(fileId = "wrap", name = "Holiday", isFolder = true),
                ),
                "wrap" to listOf(
                    SharedFolderEntry(fileId = "f1", name = "beach.jpg", isFolder = false),
                    SharedFolderEntry(fileId = "d1", name = "sub", isFolder = true),
                ),
            ),
        )
        val vm = vms.own(SharedBrowseViewModel(realm, SharedFile.ROOT_PARENT, initialTitle = "Holiday", filesRepository = repo))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Holiday", state.title, "title comes from the wrapper folder's name")
        assertEquals(
            setOf("beach.jpg", "sub"),
            state.entries.map { it.name }.toSet(),
            "the root browse drills straight into the wrapper's contents",
        )
        assertTrue(!state.loading && !state.error)
        // Two reads: the root (one wrapper) then the unwrap into it.
        assertEquals(listOf(realm to SharedFile.ROOT_PARENT, realm to "wrap"), repo.browseCalls)
    }

    @Test
    fun subfolder_listsVerbatim_withoutUnwrapping() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            browseResults = mapOf(
                "sub" to listOf(SharedFolderEntry(fileId = "f2", name = "notes.txt", isFolder = false)),
            ),
        )
        // A non-root parent never unwraps, even if it has a single folder child.
        val vm = vms.own(SharedBrowseViewModel(realm, "sub", initialTitle = "sub", filesRepository = repo))
        advanceUntilIdle()

        assertEquals(listOf("notes.txt"), vm.uiState.value.entries.map { it.name })
        assertEquals(listOf(realm to "sub"), repo.browseCalls, "no unwrap drill for a nested folder")
    }

    @Test
    fun openingAFile_readsItsBytes() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            browseResults = mapOf("d" to listOf(SharedFolderEntry("f", "doc.bin", isFolder = false))),
            readSharedFileBytes = byteArrayOf(1, 2, 3, 4, 5),
        )
        val vm = vms.own(SharedBrowseViewModel(realm, "d", initialTitle = "d", filesRepository = repo))
        advanceUntilIdle()

        vm.onFileClicked(vm.uiState.value.entries.single())
        advanceUntilIdle()

        assertEquals(listOf(realm to "f"), repo.readSharedFileCalls)
        assertEquals("doc.bin", vm.uiState.value.openedFile?.name)
        assertEquals(5, vm.uiState.value.openedFile?.sizeBytes)
    }

    @Test
    fun clickingAFolder_doesNotReadFileBytes() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            browseResults = mapOf("d" to listOf(SharedFolderEntry("sub", "sub", isFolder = true))),
            readSharedFileBytes = byteArrayOf(9),
        )
        val vm = vms.own(SharedBrowseViewModel(realm, "d", initialTitle = "d", filesRepository = repo))
        advanceUntilIdle()

        // A folder row navigates (handled by the screen); the VM's file-open is a no-op.
        vm.onFileClicked(vm.uiState.value.entries.single())
        advanceUntilIdle()

        assertTrue(repo.readSharedFileCalls.isEmpty(), "a folder is never read as a file")
        assertNull(vm.uiState.value.openedFile)
    }
}
