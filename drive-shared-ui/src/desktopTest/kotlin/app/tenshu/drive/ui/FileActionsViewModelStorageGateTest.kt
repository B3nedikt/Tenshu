package app.tenshu.drive.ui

import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.domain.StorageGateDecision
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
import kotlin.test.assertNull

/**
 * Tier-1 gate (`docs/proposals/storage-capacity-gating.md` §2) on the per-file
 * *Link* action: with storage it opens the create-link sheet; without, it raises
 * the gate dialog state instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileActionsViewModelStorageGateTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val noopOfflinePrefs = object : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    @Test
    fun link_withStorage_opensCreateLinkSheet() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L),
        )
        val vm = vms.own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, "/doc.pdf", isFolder = false))
        advanceUntilIdle()

        vm.onLinkClicked()

        assertEquals("/doc.pdf", vm.uiState.value.showCreateShareLinkForPath)
        assertNull(vm.uiState.value.storageGateBlock)
    }

    @Test
    fun link_withoutStorage_raisesGateDialog() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val vm = vms.own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, "/doc.pdf", isFolder = false))
        advanceUntilIdle()

        vm.onLinkClicked()

        assertNull(vm.uiState.value.showCreateShareLinkForPath, "no sheet without storage")
        assertEquals(StorageGateDecision.NeedsStorage, vm.uiState.value.storageGateBlock)

        vm.onStorageGateDismissed()
        assertNull(vm.uiState.value.storageGateBlock)
    }

    @Test
    fun link_full_raisesFullGate() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 1_000L, limitBytes = 1_000L),
        )
        val vm = vms.own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, "/doc.pdf", isFolder = false))
        advanceUntilIdle()

        vm.onLinkClicked()

        assertNull(vm.uiState.value.showCreateShareLinkForPath)
        assertEquals(StorageGateDecision.Full, vm.uiState.value.storageGateBlock)
    }

    // Mode-B folder *Share* (drive-manage-access-ux.md §2.1) — the producer entry
    // that previously threw TODO("Not yet implemented"). Regression coverage.

    @Test
    fun share_folder_withStorage_firesProducerAndCollapses() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L),
        )
        val vm = vms.own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, "/Photos", isFolder = true))
        advanceUntilIdle()

        val collapse = vm.onShareClicked()
        advanceUntilIdle()

        assertEquals(true, collapse, "host should collapse the sheet after a successful share")
        assertEquals(listOf("/Photos"), repo.shareFolderCalls)
        assertNull(vm.uiState.value.storageGateBlock)
    }

    @Test
    fun share_folder_withoutStorage_raisesGateAndKeepsSheet() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val vm = vms.own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, "/Photos", isFolder = true))
        advanceUntilIdle()

        val collapse = vm.onShareClicked()
        advanceUntilIdle()

        assertEquals(false, collapse, "host keeps the sheet composed to host the gate dialog")
        assertEquals(emptyList<String>(), repo.shareFolderCalls, "no producer call when gated")
        assertEquals(StorageGateDecision.NeedsStorage, vm.uiState.value.storageGateBlock)
    }

    @Test
    fun share_file_isNoOp() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L),
        )
        val vm = vms.own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, "/doc.pdf", isFolder = false))
        advanceUntilIdle()

        val collapse = vm.onShareClicked()
        advanceUntilIdle()

        assertEquals(true, collapse)
        assertEquals(emptyList<String>(), repo.shareFolderCalls, "files are not Mode-B shareable")
        assertNull(vm.uiState.value.storageGateBlock)
    }
}
