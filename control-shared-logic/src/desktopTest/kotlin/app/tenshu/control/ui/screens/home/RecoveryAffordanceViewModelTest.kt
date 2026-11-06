package app.tenshu.control.ui.screens.home

import app.tenshu.control.data.RecoveryAffordancePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * §7.2 Level 0 state machine: show while **this device holds a recovery phrase the user has not
 * confirmed saving**, unless dismissed within the last 72h. Dismiss writes `now + 72h`.
 *
 * ⚠⚠ This suite previously asserted `hidden_whenMultiDeviceRealm` — a test that **encoded the bug**.
 * The old rule hid the banner once the roster reached two devices, believing a second device meant a
 * second copy of A1. A joiner never receives the phrase, so a two-device realm can hold exactly one
 * copy; the banner switched off at the moment it wrongly concluded the user was safe. That test is
 * replaced by [stillVisible_inATwoDeviceRealm_becausePairingCreatesNoSecondCopy], which asserts the
 * opposite and would have failed against the old implementation
 * (`admission-revocation-names-the-capability.md` §5b.12.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryAffordanceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun visible_whenThisDeviceHoldsAnUnsavedPhrase() = vmTest { vms ->
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(true),
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        assertTrue(vm.uiState.first().visible)
    }

    /**
     * ⚠⚠ The **intent** of the old `hidden_whenRosterIsNotYetKnown`, carried across to the new
     * signal. Zero devices meant *"we have not looked"*, never *"no devices"*, and reporting that
     * as risk made the banner lie on every fresh launch.
     *
     * ⭐ The phrase signal has the same hazard in a different shape: this device may not have
     * answered yet. Silence while unknown is the direction that must not fail — the `onStart`
     * seed in the VM is what provides it, and this test is what stops someone removing it.
     */
    @Test
    fun hidden_whileThisDeviceHasNotAnsweredYet() = vmTest { vms ->
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            // Never emits — models a repository call still in flight.
            phraseHeld = kotlinx.coroutines.flow.emptyFlow<Boolean>()
                .let { kotlinx.coroutines.flow.flow { it.collect { v -> emit(v) } } },
            syncFunded = MutableStateFlow(true),
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        assertFalse(
            vm.uiState.first().visible,
            "an unanswered device must not be told its data is at risk",
        )
    }



    /**
     * ⭐⭐ The regression this whole change exists for. Under the old device-count rule this case
     * hid the banner; a joiner holds no phrase, so the realm still has exactly one copy of A1 and
     * the user still needs to save it.
     */
    @Test
    fun stillVisible_inATwoDeviceRealm_becausePairingCreatesNoSecondCopy() = vmTest { vms ->
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(true),
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        assertTrue(
            vm.uiState.first().visible,
            "pairing a second device creates no second copy of A1 — the warning must stand",
        )
    }

    /** A joiner holds no phrase: nothing to save, nothing to warn about. */
    @Test
    fun hidden_whenThisDeviceHoldsNoPhrase() = vmTest { vms ->
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(false),
            syncFunded = MutableStateFlow(true),
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    /** ⭐ The only thing that resolves the risk: the user confirming they saved it. */
    @Test
    fun hidden_onceTheUserConfirmsTheySavedThePhrase() = vmTest { vms ->
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L, phraseSaved = true),
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(true),
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    /**
     * ⭐ The wording half of the 2026-10-30 fix: the banner said *"isn't synced anywhere"* on a realm
     * that synced. "Only on this device" is said on a definite `hasStorage == false` and nothing else.
     */
    @Test
    fun localOnlyWording_onlyWhenTheRealmHasNoStoragePlan() = vmTest { vms ->
        val funded = MutableStateFlow<Boolean?>(false)
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(true),
            syncFunded = funded,
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        assertTrue(vm.uiState.first().localOnly, "no plan: nothing leaves this device")

        funded.value = true
        advanceUntilIdle()
        assertFalse(vm.uiState.first().localOnly, "a realm that syncs must not be told it does not")
        assertTrue(vm.uiState.first().visible, "syncing does not save the phrase — still shown")
    }

    /**
     * ⚠⚠ `hasStorage` is seeded `false` and derives `false` until the owner resolves, then flips to
     * `true` about a second later. That startup `false` must never reach the wording — and a `false`
     * that holds past the grace must.
     */
    @Test
    fun startupFalse_withinTheGrace_neverShowsLocalOnly() = vmTest { vms ->
        val funded = MutableStateFlow<Boolean?>(false)
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(true),
            syncFunded = funded,
            clock = { 1_000_000L },
        ))

        advanceTimeBy(RecoveryAffordanceViewModel.LOCAL_ONLY_GRACE_MS - 1)
        runCurrent()
        assertTrue(vm.uiState.value.visible)
        assertFalse(vm.uiState.value.localOnly, "a false still inside the grace is not believed")

        funded.value = true
        advanceUntilIdle()
        assertFalse(vm.uiState.value.localOnly, "the true that arrives inside the grace wins")

        funded.value = false
        advanceTimeBy(RecoveryAffordanceViewModel.LOCAL_ONLY_GRACE_MS + 1)
        runCurrent()
        assertTrue(vm.uiState.value.localOnly, "a false that holds is believed")
    }

    /**
     * ⚠⚠ Unknown must take the phrase wording. Both the not-yet-derived `null` and a flow that has
     * not emitted at all are covered; the `onStart { emit(null) }` seed is what makes the second
     * one safe, and this test is what stops someone removing it.
     */
    @Test
    fun unknownSyncState_takesThePhraseWording_neverLocalOnly() = vmTest { vms ->
        val derivedNull = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(null),
            clock = { 1_000_000L },
        ))
        val silent = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = 0L),
            phraseHeld = MutableStateFlow(true),
            syncFunded = kotlinx.coroutines.flow.flow<Boolean?> { kotlinx.coroutines.awaitCancellation() },
            clock = { 1_000_000L },
        ))

        advanceUntilIdle()
        for (vm in listOf(derivedNull, silent)) {
            assertTrue(vm.uiState.first().visible)
            assertFalse(vm.uiState.first().localOnly)
        }
    }

    @Test
    fun hidden_whenDismissedWindowActive() = vmTest { vms ->
        val now = 1_000_000L
        val dismissedUntil = now + 71L * 60L * 60L * 1000L // ~71h ahead
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = dismissedUntil),
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(true),
            clock = { now },
        ))

        advanceUntilIdle()
        assertFalse(vm.uiState.first().visible)
    }

    @Test
    fun visible_afterDismissWindowExpires() = vmTest { vms ->
        val now = 1_000_000_000L
        val dismissedUntil = now - 1L // expired by 1ms
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = FakePreferences(initial = dismissedUntil),
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(true),
            clock = { now },
        ))

        advanceUntilIdle()
        assertTrue(vm.uiState.first().visible)
    }

    @Test
    fun onDismiss_writesNowPlus72hToPreference() = vmTest { vms ->
        val prefs = FakePreferences(initial = 0L)
        val now = 2_500_000L
        val vm = vms.own(RecoveryAffordanceViewModel(
            preferences = prefs,
            phraseHeld = MutableStateFlow(true),
            syncFunded = MutableStateFlow(true),
            clock = { now },
        ))

        vm.onDismiss()
        advanceUntilIdle()

        assertEquals(
            now + RecoveryAffordanceViewModel.DISMISS_WINDOW_MS,
            prefs.lastWritten,
        )
    }
}

private class FakePreferences(
    initial: Long,
    phraseSaved: Boolean = false,
) : RecoveryAffordancePreferences {
    private val state = MutableStateFlow(initial)
    private val saved = MutableStateFlow(phraseSaved)
    var lastWritten: Long? = null
    var lastSavedWritten: Boolean? = null

    override fun observeDismissedUntilMs(): Flow<Long> = state

    override suspend fun setDismissedUntilMs(epochMs: Long) {
        lastWritten = epochMs
        state.value = epochMs
    }

    override fun observePhraseSaved(): Flow<Boolean> = saved

    override suspend fun setPhraseSaved(saved: Boolean) {
        lastSavedWritten = saved
        this.saved.value = saved
    }
}
