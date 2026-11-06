package app.tenshu.drive.ui.common.search

import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
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
import kotlin.test.assertTrue

/**
 * The search bar's result wiring: [FilesSearchBarViewModel] feeds the typed
 * query (debounced) into [app.tenshu.drive.data.FilesRepository.searchFiles] and
 * publishes the mapped rows into `uiState.files`. Replaces the pre-wiring stub
 * where the callbacks were empty and `files` was always empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSearchBarViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val report = FileModel.File("/docs/report.pdf", "report.pdf", "1 MB", FileIcon.GenericFile)

    @Test
    fun typingAQuery_populatesResults() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository().apply {
            searchResultsByQuery = mapOf("rep" to listOf(report))
        }
        val vm = vms.own(FilesSearchBarViewModel(repo))

        vm.onQueryChanged("rep")
        advanceUntilIdle() // let the debounce elapse and the search flow emit

        assertEquals("rep", vm.uiState.value.query, "query is reflected in state")
        assertEquals(
            listOf("report.pdf"),
            vm.uiState.value.files.map { it.name },
            "matching row is published to uiState.files",
        )
    }

    @Test
    fun clearingTheQuery_emptiesResults() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository().apply {
            searchResultsByQuery = mapOf("rep" to listOf(report))
        }
        val vm = vms.own(FilesSearchBarViewModel(repo))

        vm.onQueryChanged("rep")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.files.isNotEmpty(), "precondition: has results")

        vm.onQueryChanged("")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.files.isEmpty(), "blank query clears the results")
    }

    @Test
    fun collapsingTheBar_resetsQueryAndResults() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository().apply {
            searchResultsByQuery = mapOf("rep" to listOf(report))
        }
        val vm = vms.own(FilesSearchBarViewModel(repo))

        vm.onActiveChanged(true)
        vm.onQueryChanged("rep")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.files.isNotEmpty(), "precondition: has results")

        vm.onActiveChanged(false)
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.query, "query is cleared on collapse")
        assertTrue(vm.uiState.value.files.isEmpty(), "results are cleared on collapse")
    }
}
