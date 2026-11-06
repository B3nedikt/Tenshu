package app.tenshu.drive.ui

import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.drive.ui.selection.SelectionViewModel
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
import kotlin.test.assertTrue

/**
 * Tier-1 gate (`docs/proposals/storage-capacity-gating.md` §2) on the multi-select
 * share entry points: no storage ⇒ the action is suppressed and a gate decision
 * is surfaced; storage present ⇒ the action proceeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionViewModelStorageGateTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private object NoopOfflinePreferences : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    @Test
    fun shareFolder_withoutStorage_blocksAndDoesNotShare() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))
        advanceUntilIdle() // let the cached storageStatus populate

        vm.toggleSelection("/photos", isFolder = true)
        vm.onShareClicked()
        advanceUntilIdle()

        assertTrue(repo.shareFolderCalls.isEmpty(), "no storage ⇒ folder is not shared")
        assertEquals(StorageGateDecision.NeedsStorage, vm.storageGateBlock.value)
    }

    @Test
    fun shareFolder_withStorage_proceeds() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L),
        )
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))
        advanceUntilIdle()

        vm.toggleSelection("/photos", isFolder = true)
        vm.onShareClicked()
        advanceUntilIdle()

        assertEquals(listOf("/photos"), repo.shareFolderCalls)
        assertNull(vm.storageGateBlock.value)
    }

    @Test
    fun shareLinkRequested_gatesOnStorage() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))
        advanceUntilIdle()

        assertNull(vm.onShareLinkRequested("/a.txt"), "no storage ⇒ no path to open")
        assertEquals(StorageGateDecision.NeedsStorage, vm.storageGateBlock.value)

        vm.onStorageGateDismissed()
        assertNull(vm.storageGateBlock.value)
    }

    @Test
    fun shareLinkRequested_full_blocksWithFull() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 1_000L, limitBytes = 1_000L),
        )
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))
        advanceUntilIdle()

        assertNull(vm.onShareLinkRequested("/a.txt"))
        assertEquals(StorageGateDecision.Full, vm.storageGateBlock.value)
    }

    @Test
    fun shareLinkRequested_withStorage_returnsPath() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L),
        )
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))
        advanceUntilIdle()

        assertEquals("/a.txt", vm.onShareLinkRequested("/a.txt"))
        assertNull(vm.storageGateBlock.value)
    }
}
