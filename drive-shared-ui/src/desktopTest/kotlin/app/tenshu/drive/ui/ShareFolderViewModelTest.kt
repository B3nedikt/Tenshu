package app.tenshu.drive.ui

import app.tenshu.drive.data.InboundSharedRealm
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.drive.ui.screens.shared.SharedScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
 * Phase 3d.1 of `docs/proposals/drive-shared-realm-file-model.md` (Mode-B outbound):
 *  - the *Share* card hands a single selected folder to the sharing screen
 *    (`drive-manage-access-ux.md` §11) and clears the selection — it shares
 *    nothing itself;
 *  - the Shared tab loads the member's Mode-B mounts into its *Shared folders*
 *    group via [FilesRepository.sharedFolders].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareFolderViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private object NoopOfflinePreferences : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    @Test
    fun shareAction_opensTheSingleSelectedFolder_andClearsSelection() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))

        vm.toggleSelection("/photos", isFolder = true)
        val target = vm.onShareRequested()
        advanceUntilIdle()

        assertEquals("/photos", target, "opens sharing for exactly the selected folder")
        assertTrue(repo.shareFolderCalls.isEmpty(), "the screen shares on the first add, not the card")
        assertTrue(vm.selectedPaths.value.isEmpty(), "selection is cleared")
    }

    @Test
    fun shareAction_isNoOpForMultiSelection() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))

        vm.toggleSelection("/a", isFolder = true)
        vm.toggleSelection("/b", isFolder = true)
        assertEquals(null, vm.onShareRequested(), "a multi-selection does not share (v1)")
        advanceUntilIdle()

        assertTrue(!vm.canShareSelection.value, "Share is disabled for a multi-selection")
    }

    @Test
    fun shareAction_isNoOpForSingleFile() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.own(SelectionViewModel(repo, NoopOfflinePreferences, NoopSyncRulesPreferences))

        // A single *file* must not share — Mode-B is folder-only (3d.2b).
        vm.toggleSelection("/notes.txt", isFolder = false)
        advanceUntilIdle()
        assertTrue(!vm.canShareSelection.value, "Share is disabled for a single file")

        assertEquals(null, vm.onShareRequested(), "a single file does not share")
    }

    @Test
    fun canShareSelection_trueOnlyForSingleFolder() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vms.own(SelectionViewModel(FakeFilesRepository(), NoopOfflinePreferences, NoopSyncRulesPreferences))

        vm.toggleSelection("/folder", isFolder = true)
        advanceUntilIdle()
        assertTrue(vm.canShareSelection.value, "a single folder is shareable")
    }

    @Test
    fun sharedScreen_loadsModeBFoldersIntoState() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            sharedFoldersResult = listOf(
                SharedFolderModel(sharedRealmId = "realm-1", name = "Holiday", parentPath = "/"),
                SharedFolderModel(sharedRealmId = "realm-2", name = "Docs", parentPath = "/work"),
            )
        )
        val vm = vms.own(SharedScreenViewModel(repo, FakeContactsRepository(), FakeMembershipRepository()))
        advanceUntilIdle()

        assertEquals(
            setOf("Holiday", "Docs"),
            vm.uiState.value.sharedFolders.map { it.name }.toSet(),
            "the Shared tab surfaces the member's Mode-B mounts",
        )
    }

    @Test
    fun sharedScreen_loadsInboundRealmsIntoState() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            sharedWithMeResult = listOf(
                InboundSharedRealm(sharedRealmId = "realm-a", name = "Team Photos"),
                InboundSharedRealm(sharedRealmId = "realm-b", name = null),
            )
        )
        val vm = vms.own(SharedScreenViewModel(repo, FakeContactsRepository(), FakeMembershipRepository()))
        advanceUntilIdle()

        assertEquals(
            listOf("Team Photos", null),
            vm.uiState.value.sharedWithMe.map { it.name },
            "the *Shared with me* tab surfaces inbound realms (name null until the wrapper syncs)",
        )
    }
}
