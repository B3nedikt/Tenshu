package app.tenshu.control.ui.screens.locked

import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.FakeBiometricUnlockDelegate
import app.tenshu.control.data.UnwrapResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.TBooleanDto
import app.tenshu.control.data.SyncRepository
import org.tenshu.sync.RecoveryPhraseUnreadableError
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Covers the biometric branch added by the
 * `docs/tech-debt.md` §"Android Biometric Unlock — Missing, Undermines
 * the Onboarding-Low-Friction UX" track. Focus: the VM's orchestration
 * (delegate outcome → proxy call → state transition) is exercised; the
 * `BiometricPrompt` system UI itself is desktop-untestable and lives in
 * the manual on-device sweep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockedViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun biometricUnlock_routesWrapKeyToProxy_andFlipsUnlocked() = vmTest { vms ->
        val wrapKey = ByteArray(32) { it.toByte() }
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.Success(wrapKey),
        )
        val repository = FakeSyncRepository(unlockBiometricResult = true)
        val vm = vms.own(LockedViewModel(repository, delegate))

        vm.onUnlockBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(1, delegate.unwrapCalls)
        assertContentEquals(wrapKey, repository.lastUnlockBiometricKey)
        assertTrue(vm.uiState.value.unlocked)
        assertFalse(vm.uiState.value.biometricInFlight)
    }

    @Test
    fun biometricCancelled_revealsPassphrase_withoutError() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.CancelledByUser,
        )
        val vm = vms.own(LockedViewModel(FakeSyncRepository(), delegate))

        vm.onUnlockBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.passphraseExpanded, "passphrase entry revealed on cancel")
        assertEquals(null, state.error, "cancel is not an error")
        assertFalse(state.unlocked)
    }

    @Test
    fun useBiometric_reArmsAfterAnAccidentalCancel() = vmTest { vms ->
        // `passphraseExpanded` was a one-way latch: a cancelled prompt sets it with no error copy,
        // and merely backgrounding the app while the sheet is up counts as a cancel. Without a way
        // back the session was stuck on the passphrase field with the fingerprint reader silently
        // unavailable — killing Control was the only recovery. `onUseBiometric` is the inverse.
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.CancelledByUser,
        )
        val vm = vms.own(LockedViewModel(FakeSyncRepository(), delegate))

        vm.onUnlockBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.passphraseExpanded, "precondition: cancel latched passphrase")

        vm.onUseBiometric()

        val state = vm.uiState.value
        assertFalse(state.passphraseExpanded, "the screen must return to the biometric path")
        assertEquals(null, state.error)
        // The screen re-arms its auto-prompt off `passphraseExpanded == false`, and the enrollment
        // flags must survive — they are what gates the affordance being offered at all.
        assertTrue(state.biometricEnrolled)
        assertTrue(state.biometricAvailable)
    }

    @Test
    fun useBiometric_clearsATypedPassphrase() = vmTest { vms ->
        // Don't leave a half-typed secret sitting in VM state behind a biometric prompt.
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.CancelledByUser,
        )
        val vm = vms.own(LockedViewModel(FakeSyncRepository(), delegate))

        vm.onUsePassphrase()
        vm.onPassphraseChanged("half-typed secret")
        vm.onUseBiometric()

        assertEquals("", vm.uiState.value.passphrase)
    }

    @Test
    fun proxyRejectsWrapKey_clearsBiometric_andSurfacesPassphrase() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.Success(ByteArray(32)),
        )
        val repository = FakeSyncRepository(unlockBiometricResult = false)
        val vm = vms.own(LockedViewModel(repository, delegate))

        vm.onUnlockBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(
            1, delegate.clearCalls,
            "stale on-device blob is wiped after proxy rejects",
        )
        assertEquals(
            1, repository.clearBiometricCalls,
            "proxy biometric wrapper is dropped in sync",
        )
        val state = vm.uiState.value
        assertFalse(state.unlocked)
        assertFalse(state.biometricEnrolled)
        assertTrue(state.passphraseExpanded)
        assertEquals(LockedError.BiometricUnlockFailed(), state.error)
    }

    /**
     * ⚠⚠ The whole point of the typed error: `getOrNull()` collapses every failure to `null`, so
     * without carrying the error through, the proxy distinguishes this case and the screen then
     * throws the distinction away — the user is told the passphrase was wrong when it was right.
     */
    @Test
    fun unreadableStoredPhrase_isNotReportedAsABadPassphrase() = vmTest { vms ->
        val repository = FakeSyncRepository(phraseUnreadable = true)
        val vm = vms.own(LockedViewModel(repository, FakeBiometricUnlockDelegate()))

        vm.onPassphraseChanged("hunter2")
        vm.onUnlock()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.unlocked)
        assertEquals(LockedError.RecoveryPhraseUnreadable, state.error)
        assertEquals(
            0, state.failureCount,
            "the attempt limit counts wrong passphrases; this was a correct one over a phrase " +
                "that cannot be read, and counting it would lock the user out over a condition " +
                "no number of correct attempts can clear",
        )
    }

    /**
     * ⚠⚠ The biometric path additionally *destroyed* state on failure. Clearing enrollment over an
     * unreadable phrase throws away a working credential, and the re-enrollment it invites cannot
     * succeed either — the phrase is what is unreadable, not the wrap key.
     */
    @Test
    fun unreadableStoredPhrase_doesNotClearBiometricEnrollment() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.Success(ByteArray(32)),
        )
        val repository = FakeSyncRepository(phraseUnreadable = true)
        val vm = vms.own(LockedViewModel(repository, delegate))

        vm.onUnlockBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, delegate.clearCalls, "the on-device blob is fine — do not drop it")
        assertEquals(0, repository.clearBiometricCalls, "nor the proxy's biometric wrapper")
        val state = vm.uiState.value
        assertFalse(state.unlocked)
        assertTrue(state.passphraseExpanded, "still fall back to the passphrase field")
        assertEquals(LockedError.RecoveryPhraseUnreadable, state.error)
    }

    @Test
    fun keyInvalidated_clearsBothBlobs_nudgesReEnrollment() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = true,
            nextUnwrapResult = UnwrapResult.KeyInvalidated,
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(LockedViewModel(repository, delegate))

        vm.onUnlockBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(1, delegate.clearCalls)
        assertEquals(1, repository.clearBiometricCalls)
        val state = vm.uiState.value
        assertFalse(state.biometricEnrolled)
        assertTrue(state.passphraseExpanded)
        assertEquals(LockedError.BiometricEnrollmentChanged, state.error)
    }
}

private val TEST_PROMPT_COPY = BiometricPromptCopy(
    title = "t",
    subtitle = "s",
    negativeButton = "n",
)

private class FakeSyncRepository(
    val unlockBiometricResult: Boolean = true,
    /**
     * Stand in for a wrapper written by an earlier build whose phrase this build cannot decode.
     * The proxy raises `RecoveryPhraseUnreadableError`; the point of the tests below is that the
     * ViewModel must not flatten it into "wrong credential" the way `getOrNull()` alone does.
     */
    val phraseUnreadable: Boolean = false,
) : SyncRepository {

    var lastUnlockBiometricKey: ByteArray? = null
        private set
    var clearBiometricCalls: Int = 0
        private set

    override val devices: Flow<List<Device>> = MutableStateFlow(emptyList())

    override suspend fun readmitDevice(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun revokeDevice(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    // ⚠ Widening SyncRepository broke every implementor — the interface change is
    // only 'additive' from the caller's side.
    override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

    override suspend fun getRecoveryPhrase(): TResult<TString> = ResultSuccessDto(null)
    override suspend fun getSyncStatus(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> =
        ResultSuccessDto(null)
    override suspend fun startPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun stopPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> =
        ResultSuccessDto(null)
    override suspend fun getDeviceId(): String = ""
    override suspend fun createRealm(
        recoveryPhrase: String,
        deviceName: String,
    ): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun reregisterDevice(): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun recoveryClaim(
        recoveryPhrase: String,
        deviceName: String,
    ): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation> = ResultSuccessDto(null)
    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(false))
    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TaskHandle<TBoolean> = error("not used in this test")
    override suspend fun getUnlockStatus(): TResult<UnlockStatus> = ResultSuccessDto(null)
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun relaxToHeadless(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        if (phraseUnreadable) ResultFailureDto(UnreadablePhraseErrorDto())
        else ResultSuccessDto(TBooleanDto(false))
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> {
        lastUnlockBiometricKey = wrapKey
        return if (phraseUnreadable) ResultFailureDto(UnreadablePhraseErrorDto())
        else ResultSuccessDto(TBooleanDto(unlockBiometricResult))
    }
    override suspend fun clearBiometricSetup(): TResult<TBoolean> {
        clearBiometricCalls += 1
        return ResultSuccessDto(TBooleanDto(true))
    }
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
}

/**
 * Mirrors `:sync-service-common`'s `RecoveryPhraseUnreadableErrorDto`, which is `internal` and so
 * cannot be constructed from here. Fieldless, exactly like the real one — the ViewModel matches on
 * the schema id and reads nothing else.
 */
private class UnreadablePhraseErrorDto :
    RecoveryPhraseUnreadableError, CompositeDto(emptyList())
