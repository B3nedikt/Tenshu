package app.tenshu.drive.ui.common.details

import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.ui.FakeFilesRepository
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-file offline toggle (`docs/proposals/drive-offline-online.md` §2):
 * the label/icon track the persisted offline *intent*, and tapping pins+downloads
 * or unpins+evicts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileActionsViewModelOfflineToggleTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeOfflinePreferences(initial: List<String>) : OfflinePreferences {
        val state = MutableStateFlow(initial)
        val setOfflineCalls = mutableListOf<List<String>>()
        override fun observe(): Flow<List<String>> = state
        override suspend fun setOffline(paths: List<String>) {
            setOfflineCalls += paths
            state.value = paths
        }
    }

    @Test
    fun makeAvailableOffline_pinsAndDownloads() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository { _, _, _ -> error("unused") }
        val prefs = FakeOfflinePreferences(initial = emptyList())
        val vm = vms.own(FileActionsViewModel(repo, prefs, NoopSyncRulesPreferences, "/report.pdf", isFolder = false))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isAvailableOffline, "starts online-only")

        vm.onDownloadClicked()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isAvailableOffline, "flips to kept-offline")
        assertEquals(listOf(listOf("/report.pdf")), prefs.setOfflineCalls, "pins the path")
        assertEquals(listOf("/report.pdf"), repo.downloadFileCalls, "forces the bytes local")
        assertTrue(repo.evictFileCalls.isEmpty(), "does not evict when pinning")
    }

    @Test
    fun removeDownload_unpinsAndEvicts() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository { _, _, _ -> error("unused") }
        val prefs = FakeOfflinePreferences(initial = listOf("/report.pdf"))
        val vm = vms.own(FileActionsViewModel(repo, prefs, NoopSyncRulesPreferences, "/report.pdf", isFolder = false))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isAvailableOffline, "starts kept-offline")

        vm.onDownloadClicked()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isAvailableOffline, "flips to online-only")
        assertEquals(listOf(emptyList()), prefs.setOfflineCalls, "removes the path from the pinned set")
        assertEquals(listOf("/report.pdf"), repo.evictFileCalls, "evicts the local bytes")
        assertTrue(repo.downloadFileCalls.isEmpty(), "does not download when unpinning")
    }
}
