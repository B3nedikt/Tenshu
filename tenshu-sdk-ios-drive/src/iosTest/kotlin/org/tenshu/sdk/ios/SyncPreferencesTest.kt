package org.tenshu.sdk.ios

import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reactive coverage of the Sync Preferences settings VM over recording preference fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeOfflinePrefs(initial: List<String>) : OfflinePreferences {
        val flow = MutableStateFlow(initial)
        val writes = mutableListOf<List<String>>()
        override fun observe(): Flow<List<String>> = flow
        override suspend fun setOffline(paths: List<String>) {
            writes += paths
            flow.value = paths
        }
    }

    private class FakeSyncRulesPrefs : SyncRulesPreferences {
        val defaultPolicy = MutableStateFlow<SyncPolicy>(SyncPolicy.ONLINE_ONLY)
        val policyWrites = mutableListOf<SyncPolicy>()
        override fun observe(): Flow<SyncRuleSet> = flowOf(SyncRuleSet.Default)
        override fun observeDefaultPolicy(): Flow<SyncPolicy> = defaultPolicy
        override suspend fun setDefaultPolicy(policy: SyncPolicy) {
            policyWrites += policy
            defaultPolicy.value = policy
        }
        override suspend fun setOverrides(overrides: List<PathSyncOverride>) = Unit
        override fun observeMaxLocalBytes(): Flow<Long> = flowOf(0L)
        override suspend fun setMaxLocalBytes(maxLocalBytes: Long) = Unit
    }

    @Test
    fun uiState_reflects_the_offline_list_and_default_policy() = vmTest(dispatcher) { vms ->
        val offline = FakeOfflinePrefs(listOf("/a.txt", "/b.txt"))
        val rules = FakeSyncRulesPrefs().apply { defaultPolicy.value = SyncPolicy.KEEP_OFFLINE }
        val vm = vms.own(DriveSettingsSurface.syncPreferences(offline, rules))
        advanceUntilIdle()

        assertEquals(listOf("/a.txt", "/b.txt"), vm.uiState.value.offlineFiles)
        assertTrue(vm.uiState.value.keepOfflineByDefault)
    }

    @Test
    fun toggling_keep_offline_by_default_writes_the_policy() = vmTest(dispatcher) { vms ->
        val rules = FakeSyncRulesPrefs()
        val vm = vms.own(DriveSettingsSurface.syncPreferences(FakeOfflinePrefs(emptyList()), rules))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.keepOfflineByDefault)

        vm.setKeepOfflineByDefault(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.keepOfflineByDefault)
        assertEquals(listOf<SyncPolicy>(SyncPolicy.KEEP_OFFLINE), rules.policyWrites)
    }

    @Test
    fun removing_an_offline_file_updates_state_and_persists() = vmTest(dispatcher) { vms ->
        val offline = FakeOfflinePrefs(listOf("/a.txt", "/b.txt"))
        val vm = vms.own(DriveSettingsSurface.syncPreferences(offline, FakeSyncRulesPrefs()))
        advanceUntilIdle()

        vm.removeOfflineFile("/a.txt")
        advanceUntilIdle()

        assertEquals(listOf("/b.txt"), vm.uiState.value.offlineFiles)
        assertEquals(listOf(listOf("/b.txt")), offline.writes)
    }
}
