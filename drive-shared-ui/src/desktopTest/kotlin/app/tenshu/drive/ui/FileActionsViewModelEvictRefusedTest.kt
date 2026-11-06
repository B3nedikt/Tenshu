package app.tenshu.drive.ui

import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.common.details.FileActionsViewModel
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * *Remove download* when the bytes are **pinned** by a parked move
 * (`content-plane-refcount.md` §6d).
 *
 * ⚠⚠ The optimistic flip was harmless while eviction could not fail. D3's pin makes it fail, and the
 * result was being discarded — so the row reported the file as online-only while its bytes were
 * still on disk. A UI asserting the opposite of what happened is worse than the refusal it hides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileActionsViewModelEvictRefusedTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingOfflinePrefs(initial: List<String>) : OfflinePreferences {
        val writes = mutableListOf<List<String>>()
        private val current = initial
        override fun observe(): Flow<List<String>> = flowOf(current)
        override suspend fun setOffline(paths: List<String>) {
            writes += paths
        }
    }

    @Test
    fun aRefusedEvictionRevertsTheRowAndThePreference() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = RecordingOfflinePrefs(listOf("/pinned.bin"))
        val repo = FakeFilesRepository().apply {
            // ⚠ Any Error is enough: the VM reverts on the failure, not on which one it was.
            evictFileResult = RepositoryResult.Error(RepositoryError.UnexpectedError("pinned"))
        }
        val vm = vms.own(
            FileActionsViewModel(repo, prefs, NoopSyncRulesPreferences, "/pinned.bin", isFolder = false),
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isAvailableOffline, "precondition: the file is kept offline")

        vm.onDownloadClicked()
        advanceUntilIdle()

        assertEquals(listOf("/pinned.bin"), repo.evictFileCalls, "precondition: eviction was attempted")
        assertTrue(
            vm.uiState.value.isAvailableOffline,
            "the refusal stands, so the row must still say the file is kept offline",
        )
        assertEquals(
            listOf("/pinned.bin"),
            prefs.writes.last(),
            "and the durable preference is put back — the next screen reads that, not the flag",
        )
    }

    @Test
    fun aSuccessfulEvictionStillClearsTheRow() = vmTest { vms ->
        // ⚠ The negative half. Without it, a revert that fired unconditionally would satisfy the
        // test above while breaking every ordinary "Remove download".
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = RecordingOfflinePrefs(listOf("/ordinary.bin"))
        val repo = FakeFilesRepository()
        val vm = vms.own(
            FileActionsViewModel(repo, prefs, NoopSyncRulesPreferences, "/ordinary.bin", isFolder = false),
        )
        advanceUntilIdle()

        vm.onDownloadClicked()
        advanceUntilIdle()

        assertTrue(!vm.uiState.value.isAvailableOffline, "an accepted eviction clears the row")
        assertEquals(emptyList(), prefs.writes.last(), "and the path leaves the preference")
    }
}
