package app.tenshu.drive.ui.screens.movepicker

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.FakeFilesRepository
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MovePickerViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun move_callsRepositoryWithSourceAndBrowsedFolder_andMarksMoved() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.own(MovePickerViewModel(repo, sourcePath = "/photos/cat.jpg", folderPath = "/archive"))

        vm.move()
        advanceUntilIdle()

        assertEquals(listOf("/photos/cat.jpg" to "/archive"), repo.moveCalls)
        assertTrue(vm.uiState.value.moved, "a successful move signals the host to pop")
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isMoving)
    }

    @Test
    fun move_surfacesAlreadyExistsError_withoutMarkingMoved() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            moveResult = RepositoryResult.Error(RepositoryError.AlreadyExists),
        )
        val vm = vms.own(MovePickerViewModel(repo, sourcePath = "/a.txt", folderPath = "/docs"))

        vm.move()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.moved)
        assertEquals(MovePickerViewModel.ERROR_ALREADY_EXISTS, vm.uiState.value.errorMessage)
    }

    @Test
    fun move_mapsUnknownErrorToGenericFailure() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            moveResult = RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)),
        )
        val vm = vms.own(MovePickerViewModel(repo, sourcePath = "/a.txt", folderPath = "/docs"))

        vm.move()
        advanceUntilIdle()

        assertEquals(MovePickerViewModel.ERROR_FAILED, vm.uiState.value.errorMessage)
    }

    @Test
    fun canMoveHere_isFalse_whenBrowsingTheItemsCurrentParent() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePath = "/docs/a.txt",
            folderPath = "/docs",
        ))
        assertFalse(vm.uiState.value.canMoveHere, "moving into the current parent is a no-op")
    }

    @Test
    fun canMoveHere_isTrue_whenBrowsingADifferentFolder() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePath = "/docs/a.txt",
            folderPath = "/archive",
        ))
        assertTrue(vm.uiState.value.canMoveHere)
    }

    @Test
    fun initialState_derivesSourceNameAndRootFolderLabel() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePath = "/report.pdf",
            folderPath = "/",
        ))
        assertEquals("report.pdf", vm.uiState.value.sourceName)
        assertEquals(1, vm.uiState.value.sourceCount)
        assertEquals("", vm.uiState.value.currentFolderName, "root folder has no name label")
    }

    // Multi-select *Move* — the picker now takes the whole selection.

    @Test
    fun move_multipleSources_movesEachIntoBrowsedFolder_andMarksMoved() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.own(MovePickerViewModel(
            repo,
            sourcePaths = listOf("/photos/a.jpg", "/photos/b.jpg", "/docs/c.pdf"),
            folderPath = "/archive",
        ))

        vm.move()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "/photos/a.jpg" to "/archive",
                "/photos/b.jpg" to "/archive",
                "/docs/c.pdf" to "/archive",
            ),
            repo.moveCalls,
        )
        assertTrue(vm.uiState.value.moved)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun initialState_multipleSources_setsCountAndClearsSingleName() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePaths = listOf("/a.txt", "/b.txt", "/c.txt"),
            folderPath = "/",
        ))
        assertEquals(3, vm.uiState.value.sourceCount)
        assertEquals("", vm.uiState.value.sourceName, "no single name for a multi-selection")
    }

    @Test
    fun canMoveHere_multipleSources_falseWhenBrowsingTheirSharedParent() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePaths = listOf("/docs/a.txt", "/docs/b.txt"),
            folderPath = "/docs",
        ))
        assertFalse(vm.uiState.value.canMoveHere, "moving into the shared parent is a no-op")
    }

    @Test
    fun canMoveHere_multipleSources_trueWhenSelectionSpansFolders() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePaths = listOf("/docs/a.txt", "/photos/b.jpg"),
            folderPath = "/docs",
        ))
        assertTrue(vm.uiState.value.canMoveHere, "a spanning selection has no shared no-op parent")
    }

    @Test
    fun canMoveHere_falseWhenBrowsingAFolderThatIsItselfSelected() = vmTest { vms ->
        val vm = vms.own(MovePickerViewModel(
            FakeFilesRepository(),
            sourcePaths = listOf("/archive", "/notes.txt"),
            folderPath = "/archive",
        ))
        assertFalse(vm.uiState.value.canMoveHere, "can't move a selection into one of its own members")
    }
}
