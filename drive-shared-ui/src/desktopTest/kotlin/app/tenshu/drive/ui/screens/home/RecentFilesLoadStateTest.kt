package app.tenshu.drive.ui.screens.home

import app.tenshu.drive.data.FileModel
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.drive.data.NoopSyncRulesPreferences
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The repository must not answer a *failed* read with an empty list, and the view model must carry
 * that distinction to the screen.
 *
 * ⚠ This is the half a UI test cannot reach. `FilesList` can be handed a `FAILED` phase directly,
 * but nothing proves the phase ever *arrives* — before this change the failure was folded into
 * `emptyList()` inside `TenshuFilesRepository`, so every consumer downstream was structurally
 * incapable of telling the two apart no matter how many branches it grew.
 */
class RecentFilesLoadStateTest {

    /** Delegates everything but the one read under test to the shared fake. */
    private class Repository(
        private val delegate: FakeFilesRepository = FakeFilesRepository(),
    ) : FilesRepository by delegate {
        val recent = MutableStateFlow<ListLoadState<FileModel>>(ListLoadState.Loading)
        override fun recentFiles(limit: Int): Flow<ListLoadState<FileModel>> = recent
    }

    private object NoOfflinePreferences : OfflinePreferences {
        override fun observe() = flowOf(emptyList<String>())
        override suspend fun setOffline(paths: List<String>) {}
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun OwnedViewModels.viewModel(repository: Repository) = own(HomeScreenViewModel(
        selectionViewModel = own(SelectionViewModel(
            repository,
            NoOfflinePreferences,
            NoopSyncRulesPreferences,
        )),
        filesRepository = repository,
    ))

    @Test
    fun aFailedReadReachesTheScreenAsFailedRatherThanAsNoFiles() = vmTest { vms ->
        val repository = Repository()
        val subject = vms.viewModel(repository)

        // Before the first emission: in flight, NOT empty.
        assertEquals(ListLoadPhase.LOADING, subject.uiState.value.recentFilesLoad)

        repository.recent.value = ListLoadState.Failed
        subject.uiState.first { it.recentFilesLoad == ListLoadPhase.FAILED }
        assertEquals(emptyList(), subject.uiState.value.recentFiles)
    }

    @Test
    fun aFailureAfterAGoodReadKeepsTheRowsOnScreen() = vmTest { vms ->
        // A read that breaks *after* answering should say it is no longer current, not blank the
        // list — the rows it already delivered are still the best thing anyone knows.
        val repository = Repository()
        val subject = vms.viewModel(repository)

        repository.recent.value = ListLoadState.Loaded(listOf(fileModel("/a.txt")))
        subject.uiState.first { it.recentFiles.size == 1 }

        repository.recent.value = ListLoadState.Failed
        subject.uiState.first { it.recentFilesLoad == ListLoadPhase.FAILED }
        assertEquals(1, subject.uiState.value.recentFiles.size, "rows survive a later failure")
    }

    @Test
    fun aGenuinelyEmptyDriveArrivesAsLoadedAndEmpty() = vmTest { vms ->
        val repository = Repository()
        val subject = vms.viewModel(repository)

        repository.recent.value = ListLoadState.Loaded(emptyList())
        subject.uiState.first { it.recentFilesLoad == ListLoadPhase.LOADED }
        assertEquals(emptyList(), subject.uiState.value.recentFiles)
    }

    private fun fileModel(path: String) = FileModel.File(
        path = path,
        name = path.substringAfterLast('/'),
        subLine = "",
        previewIcon = FileIcon.Other,
    )
}
