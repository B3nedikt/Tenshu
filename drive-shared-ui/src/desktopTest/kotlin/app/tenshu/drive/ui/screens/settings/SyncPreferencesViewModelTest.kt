package app.tenshu.drive.ui.screens.settings

import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
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
 * The per-device default-policy toggle (`docs/proposals/drive-offline-online.md`
 * §4.10 Phase 2): the toggle reflects [SyncRulesPreferences.observeDefaultPolicy]
 * and writes back through [SyncRulesPreferences.setDefaultPolicy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private object NoopOfflinePreferences : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    /** Fake whose default-policy flow is the single source of truth; a write
     *  feeds back into it, mirroring the real reactive read-after-write. */
    private class FakeSyncRulesPreferences(
        initial: SyncPolicy = SyncPolicy.ONLINE_ONLY,
    ) : SyncRulesPreferences {
        val policy = MutableStateFlow(initial)
        override fun observe(): Flow<SyncRuleSet> = policy.map { SyncRuleSet(it, emptyList()) }
        override fun observeDefaultPolicy(): Flow<SyncPolicy> = policy
        override suspend fun setDefaultPolicy(policy: SyncPolicy) { this.policy.value = policy }
        override suspend fun setOverrides(overrides: List<PathSyncOverride>) = Unit
        val maxLocalBytes = MutableStateFlow(0L)
        override fun observeMaxLocalBytes(): Flow<Long> = maxLocalBytes
        override suspend fun setMaxLocalBytes(maxLocalBytes: Long) {
            this.maxLocalBytes.value = maxLocalBytes
        }
    }

    @Test
    fun toggle_seedsFromTheDefaultPolicyFlow() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences(initial = SyncPolicy.KEEP_OFFLINE)
        val vm = vms.own(SyncPreferencesViewModel(NoopOfflinePreferences, rules))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keepOfflineByDefault, "keep-offline default reflects KEEP_OFFLINE")
    }

    @Test
    fun setKeepOffline_writesPolicyAndReflectsIt() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences(initial = SyncPolicy.ONLINE_ONLY)
        val vm = vms.own(SyncPreferencesViewModel(NoopOfflinePreferences, rules))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.keepOfflineByDefault, "starts online-only")

        vm.setKeepOfflineByDefault(true)
        advanceUntilIdle()
        assertEquals(SyncPolicy.KEEP_OFFLINE, rules.policy.value, "persisted KEEP_OFFLINE")
        assertTrue(vm.uiState.value.keepOfflineByDefault, "toggle now on")

        vm.setKeepOfflineByDefault(false)
        advanceUntilIdle()
        assertEquals(SyncPolicy.ONLINE_ONLY, rules.policy.value, "persisted ONLINE_ONLY")
        assertFalse(vm.uiState.value.keepOfflineByDefault, "toggle now off")
    }

    // --- Local-cache ceiling (retention-quota-model.md Face B) -------------------

    @Test
    fun cacheCeiling_seedsFromTheStoredValue() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences()
        rules.maxLocalBytes.value = 10_000_000_000L
        val vm = vms.own(SyncPreferencesViewModel(NoopOfflinePreferences, rules))
        advanceUntilIdle()
        assertEquals(10_000_000_000L, vm.uiState.value.maxLocalBytes)
    }

    @Test
    fun cacheCeiling_defaultsToNoLimit() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vms.own(SyncPreferencesViewModel(NoopOfflinePreferences, FakeSyncRulesPreferences()))
        advanceUntilIdle()
        // ⚠ The shipped default. A screen that opened showing some limit the user never
        // chose would misreport what the device is actually doing.
        assertEquals(CacheCeilingChoices.NO_LIMIT, vm.uiState.value.maxLocalBytes)
    }

    @Test
    fun setCacheCeiling_writesItAndReflectsIt() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences()
        val vm = vms.own(SyncPreferencesViewModel(NoopOfflinePreferences, rules))
        advanceUntilIdle()

        vm.setMaxLocalBytes(5_000_000_000L)
        assertEquals(5_000_000_000L, vm.uiState.value.maxLocalBytes, "optimistic before the write lands")
        advanceUntilIdle()
        assertEquals(5_000_000_000L, rules.maxLocalBytes.value, "the write reaches the preference")
    }

    @Test
    fun setCacheCeiling_canBeClearedBackToNoLimit() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rules = FakeSyncRulesPreferences()
        rules.maxLocalBytes.value = 5_000_000_000L
        val vm = vms.own(SyncPreferencesViewModel(NoopOfflinePreferences, rules))
        advanceUntilIdle()

        vm.setMaxLocalBytes(CacheCeilingChoices.NO_LIMIT)
        advanceUntilIdle()
        // "No limit" is a real choice the user can return to, not an unset state.
        assertEquals(0L, rules.maxLocalBytes.value)
    }

    @Test
    fun cacheCeilingChoices_snapDownwardNeverUpward() {
        // ⚠ A stored value between offers must map to the offer BELOW it. Snapping up
        // would silently raise a user's limit just by opening the screen.
        assertEquals(1_000_000_000L, CacheCeilingChoices.nearest(4_000_000_000L))
        assertEquals(CacheCeilingChoices.NO_LIMIT, CacheCeilingChoices.nearest(500_000_000L))
        assertEquals(50_000_000_000L, CacheCeilingChoices.nearest(999_000_000_000L))
        assertEquals(CacheCeilingChoices.NO_LIMIT, CacheCeilingChoices.nearest(0L))
    }
}
