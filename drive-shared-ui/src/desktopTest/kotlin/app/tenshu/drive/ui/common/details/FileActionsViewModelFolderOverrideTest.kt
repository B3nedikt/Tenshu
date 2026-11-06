package app.tenshu.drive.ui.common.details

import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.SyncRulesPreferences
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.FakeFilesRepository
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * The folder-level sync-rule override toggle (`docs/proposals/drive-offline-online.md`
 * §4.10 Phase 2b): a folder's `…` menu flips its effective policy, writing a sparse
 * `SyncOverrides` entry (or clearing it when the choice equals the device default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileActionsViewModelFolderOverrideTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private object NoopOfflinePreferences : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    private class FakeOfflinePreferences(private val pins: List<String>) : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(pins)
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    private fun file(path: String) =
        FileModel.File(path = path, name = path.substringAfterLast("/"), subLine = "", previewIcon = FileIcon.GenericFile)

    private fun folder(path: String) =
        FileModel.Folder(path = path, name = path.substringAfterLast("/"), subLine = "", previewIcon = FileIcon.Folder)

    private class FakeSyncRulesPreferences(
        default: SyncPolicy = SyncPolicy.ONLINE_ONLY,
        overrides: List<PathSyncOverride> = emptyList(),
    ) : SyncRulesPreferences {
        val ruleSet = MutableStateFlow(SyncRuleSet(default, overrides))
        val setOverridesCalls = mutableListOf<List<PathSyncOverride>>()
        override fun observe(): Flow<SyncRuleSet> = ruleSet
        override fun observeDefaultPolicy(): Flow<SyncPolicy> = ruleSet.map { it.deviceDefault }
        override suspend fun setDefaultPolicy(policy: SyncPolicy) {
            ruleSet.value = ruleSet.value.copy(deviceDefault = policy)
        }
        override suspend fun setOverrides(overrides: List<PathSyncOverride>) {
            setOverridesCalls += overrides
            ruleSet.value = ruleSet.value.copy(overrides = overrides)
        }
        override fun observeMaxLocalBytes(): Flow<Long> = flowOf(0L)
        override suspend fun setMaxLocalBytes(maxLocalBytes: Long) = Unit
    }

    private val repo = FakeFilesRepository { _, _, _ -> error("unused") }

    @Test
    fun keepFolderOffline_writesAKeepOverride_thenClearsItBackToTheDefault() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences(default = SyncPolicy.ONLINE_ONLY)
        val vm = vms.own(FileActionsViewModel(repo, NoopOfflinePreferences, rules, "/Photos", isFolder = true))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.folderKeptOffline, "starts online-only (device default)")

        // Keep offline → a sparse KEEP_OFFLINE override for the folder.
        vm.onFolderOfflineToggled()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.folderKeptOffline)
        assertEquals(
            listOf(PathSyncOverride("/Photos", SyncPolicy.KEEP_OFFLINE)),
            rules.setOverridesCalls.last(),
        )

        // Back to online-only == device default → the override is cleared, not stored.
        vm.onFolderOfflineToggled()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.folderKeptOffline)
        assertEquals(emptyList(), rules.setOverridesCalls.last())
    }

    @Test
    fun underAKeepOfflineDefault_theToggleWritesAnInverseOnlineOnlyOverride() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences(default = SyncPolicy.KEEP_OFFLINE)
        val vm = vms.own(FileActionsViewModel(repo, NoopOfflinePreferences, rules, "/Archive", isFolder = true))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.folderKeptOffline, "starts kept-offline (device default)")

        // The inverse exception: mark this folder online-only despite the keep-everything default.
        vm.onFolderOfflineToggled()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.folderKeptOffline)
        assertEquals(
            listOf(PathSyncOverride("/Archive", SyncPolicy.ONLINE_ONLY)),
            rules.setOverridesCalls.last(),
        )
    }

    @Test
    fun keepFolderOffline_eagerlyDownloadsTheFoldersCurrentContents() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository { _, _, _ -> error("unused") }
        repo.filesByFolder = mapOf(
            "/Photos" to listOf(file("/Photos/a.jpg"), folder("/Photos/sub")),
            "/Photos/sub" to listOf(file("/Photos/sub/b.jpg")),
        )
        val rules = FakeSyncRulesPreferences(default = SyncPolicy.ONLINE_ONLY)
        val vm = vms.own(FileActionsViewModel(repo, NoopOfflinePreferences, rules, "/Photos", isFolder = true))
        advanceUntilIdle()

        vm.onFolderOfflineToggled() // → KEEP_OFFLINE
        advanceUntilIdle()

        assertEquals(setOf("/Photos/a.jpg", "/Photos/sub/b.jpg"), repo.downloadFileCalls.toSet())
        assertTrue(repo.evictFileCalls.isEmpty())
    }

    @Test
    fun makeFolderOnlineOnly_evictsContents_butSkipsPinnedFiles() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository { _, _, _ -> error("unused") }
        repo.filesByFolder = mapOf(
            "/Archive" to listOf(file("/Archive/a.jpg"), file("/Archive/keep.jpg")),
        )
        // Device keeps everything offline; one file is individually pinned.
        val rules = FakeSyncRulesPreferences(default = SyncPolicy.KEEP_OFFLINE)
        val offline = FakeOfflinePreferences(listOf("/Archive/keep.jpg"))
        val vm = vms.own(FileActionsViewModel(repo, offline, rules, "/Archive", isFolder = true))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.folderKeptOffline, "starts kept-offline (device default)")

        vm.onFolderOfflineToggled() // → ONLINE_ONLY override on the folder
        advanceUntilIdle()

        // Only the unpinned file is evicted; the per-file pin wins over the folder rule.
        assertEquals(listOf("/Archive/a.jpg"), repo.evictFileCalls)
        assertTrue(repo.downloadFileCalls.isEmpty())
    }
}
