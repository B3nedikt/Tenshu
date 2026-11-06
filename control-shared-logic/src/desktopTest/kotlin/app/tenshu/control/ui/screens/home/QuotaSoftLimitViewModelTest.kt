package app.tenshu.control.ui.screens.home

import app.tenshu.control.data.CommerceProducts
import app.tenshu.control.data.QuotaSoftLimitPreferences
import app.tenshu.control.ui.screens.services.StorageStatusModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * §2.3 state: show the one-time free-tier soft-limit banner iff the device is on
 * the Free tier with active storage, usage ≥ 80% of the free limit, and it hasn't
 * already been dismissed/actioned on this device. Both dismiss and See-plans
 * permanently mark it gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotaSoftLimitViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // Any limit not equal to a paid tier constant maps to Free (planTierForLimit).
    private val freeLimit = 1_000L

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun status(used: Long, limit: Long = freeLimit, hasStorage: Boolean = true) =
        StorageStatusModel(hasStorage = hasStorage, usedBytes = used, limitBytes = limit)

    @Test
    fun visible_whenFreeTierAtOrOver80PercentAndNotDismissed() = vmTest { vms ->
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = FakeQuotaSoftLimitPreferences(initial = false),
            storageStatusFlow = MutableStateFlow(status(used = 800L)), // exactly 80%
        ))
        advanceUntilIdle()
        assertTrue(vm.uiState.first().visible)
    }

    @Test
    fun hidden_whenBelow80Percent() = vmTest { vms ->
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = FakeQuotaSoftLimitPreferences(initial = false),
            storageStatusFlow = MutableStateFlow(status(used = 799L)), // 79.9%
        ))
        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    @Test
    fun hidden_whenPaidTier() = vmTest { vms ->
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = FakeQuotaSoftLimitPreferences(initial = false),
            // Basic limit → planTierForLimit == Basic, so the free nudge never shows,
            // even when way over 80%.
            storageStatusFlow = MutableStateFlow(
                status(used = CommerceProducts.BASIC_LIMIT_BYTES, limit = CommerceProducts.BASIC_LIMIT_BYTES),
            ),
        ))
        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    @Test
    fun hidden_whenNoActiveStorage() = vmTest { vms ->
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = FakeQuotaSoftLimitPreferences(initial = false),
            storageStatusFlow = MutableStateFlow(status(used = 900L, hasStorage = false)),
        ))
        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    @Test
    fun hidden_whenAlreadyDismissed() = vmTest { vms ->
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = FakeQuotaSoftLimitPreferences(initial = true),
            storageStatusFlow = MutableStateFlow(status(used = 900L)),
        ))
        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    @Test
    fun onDismiss_persistsAndHides() = vmTest { vms ->
        val prefs = FakeQuotaSoftLimitPreferences(initial = false)
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = prefs,
            storageStatusFlow = MutableStateFlow(status(used = 900L)),
        ))
        advanceUntilIdle()
        assertTrue(vm.uiState.first().visible)

        vm.onDismiss()
        advanceUntilIdle()
        assertTrue(prefs.dismissed)
        assertFalse(vm.uiState.first().visible)
    }

    @Test
    fun onSeePlans_alsoPersistsDismissed() = vmTest { vms ->
        val prefs = FakeQuotaSoftLimitPreferences(initial = false)
        val vm = vms.own(QuotaSoftLimitViewModel(
            preferences = prefs,
            storageStatusFlow = MutableStateFlow(status(used = 900L)),
        ))
        advanceUntilIdle()

        vm.onSeePlans()
        advanceUntilIdle()
        assertTrue(prefs.dismissed)
    }
}

private class FakeQuotaSoftLimitPreferences(initial: Boolean) : QuotaSoftLimitPreferences {
    private val state = MutableStateFlow(initial)
    val dismissed: Boolean get() = state.value

    override fun observeDismissed(): Flow<Boolean> = state

    override suspend fun setDismissed() {
        state.value = true
    }
}
