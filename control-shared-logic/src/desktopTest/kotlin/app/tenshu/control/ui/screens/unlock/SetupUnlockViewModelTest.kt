package app.tenshu.control.ui.screens.unlock

import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.FakeBiometricUnlockDelegate
import app.tenshu.control.data.SyncRepository
import org.tenshu.core.file.sync.SyncPolicy
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
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Covers `SetupUnlockViewModel`'s two branches.
 *
 * The **headless default** (`control-keep-device-unlocked.md` §4.1): the screen
 * opens on device protection, and taking it must never call `setUpUnlock` —
 * that call is what wraps the payload *and clears the plaintext slot*, so a
 * single stray invocation seals the realm on every later cold start.
 *
 * The **passphrase floor** behind the advanced opt-in: biometric is enrolled
 * first, then the screen *requires* a recovery passphrase; submit wraps both via
 * the atomic `setUpUnlock(passphrase, wrapKey)`. Biometric-only is impossible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupUnlockViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun opensOnDeviceProtection_notTheLock() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val vm = vms.own(SetupUnlockViewModel(FakeSyncRepository(), delegate))
        advanceUntilIdle()

        assertFalse(
            vm.uiState.value.advancedUnlock,
            "post-pairing leads with device protection; the lock is the opt-in (§4.1)",
        )
    }

    @Test
    fun useDeviceProtection_completesWithoutWrapping() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))
        advanceUntilIdle()

        vm.onUseDeviceProtection()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.done)
        assertEquals(
            0, repository.setUpUnlockCalls,
            "setUpUnlock clears the plaintext JOINER_PAYLOAD — the headless path must not call it",
        )
        assertEquals(0, delegate.enrollCalls)
    }

    @Test
    fun exitAdvancedUnlock_returnsToHeadlessDefault_discardingEnrollment() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))
        advanceUntilIdle()

        vm.onShowAdvancedUnlock()
        assertTrue(vm.uiState.value.advancedUnlock)
        vm.onChooseBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()
        vm.onPassphraseChanged("strongphrase")

        vm.onExitAdvancedUnlock()

        assertFalse(vm.uiState.value.advancedUnlock)
        assertEquals(1, delegate.clearCalls, "backing out discards the pending enrollment")
        assertFalse(vm.uiState.value.showPassphrase)
        assertFalse(vm.uiState.value.biometricChosen)
        assertEquals("", vm.uiState.value.passphrase)

        // …and the headless exit still works, with nothing wrapped along the way.
        vm.onUseDeviceProtection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals(0, repository.setUpUnlockCalls)
    }

    @Test
    fun chooseBiometric_routesToRecoveryPassphrase_thenSubmitWrapsBoth() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        vm.onChooseBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        // Enrolled, routed to the recovery passphrase — but NOT wrapped yet.
        assertEquals(1, delegate.enrollCalls)
        assertTrue(vm.uiState.value.showPassphrase)
        assertTrue(vm.uiState.value.biometricChosen)
        assertEquals(0, repository.setUpUnlockCalls, "no wrapping until the passphrase floor is set")
        assertFalse(vm.uiState.value.done)

        vm.onPassphraseChanged("strongphrase")
        vm.onConfirmChanged("strongphrase")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.setUpUnlockCalls)
        assertContentEquals(
            delegate.lastEnrolledWrapKey,
            repository.lastSetUpUnlockWrapKey,
            "submit wraps the recovery phrase under the enrolled biometric wrap-key + the passphrase",
        )
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun choosePassphrase_wrapsWithNullKey_done() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        vm.onChoosePassphrase()
        vm.onPassphraseChanged("strongphrase")
        vm.onConfirmChanged("strongphrase")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.setUpUnlockCalls)
        assertNull(repository.lastSetUpUnlockWrapKey, "passphrase-only ⇒ no biometric wrap-key")
        assertEquals(0, delegate.enrollCalls, "passphrase path never enrolls biometric")
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun biometricCancelled_staysOnChoice_proxyUntouched() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.CancelledByUser,
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        vm.onChooseBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, repository.setUpUnlockCalls)
        assertFalse(vm.uiState.value.showPassphrase, "cancel keeps the user on the credential choice")
        assertFalse(vm.uiState.value.done)
        assertNull(vm.uiState.value.error, "cancel is not an error")
    }

    @Test
    fun enrollFailure_surfacesMessage_proxyUntouched() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Failure("hardware unavailable"),
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        vm.onChooseBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, repository.setUpUnlockCalls)
        assertEquals(
            SetupUnlockError.BiometricEnrollFailed("hardware unavailable"),
            vm.uiState.value.error,
        )
    }

    @Test
    fun submitRejectedAfterBiometric_clearsDelegate_andSurfacesError() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository(setUpUnlockResult = false)
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        vm.onChooseBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()
        vm.onPassphraseChanged("strongphrase")
        vm.onConfirmChanged("strongphrase")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(
            1, delegate.clearCalls,
            "a rejected setUpUnlock with a pending biometric ⇒ wipe the stranded enrollment",
        )
        assertFalse(vm.uiState.value.done)
        assertEquals(SetupUnlockError.BiometricSetupFailed, vm.uiState.value.error)
    }

    @Test
    fun back_fromBiometricRecovery_discardsEnrollment() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        vm.onChooseBiometric(BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()
        vm.onBack()

        assertEquals(1, delegate.clearCalls, "back discards the pending biometric enrollment")
        assertFalse(vm.uiState.value.showPassphrase)
        assertFalse(vm.uiState.value.biometricChosen)
    }

    @Test
    fun noBiometric_goesStraightToPassphrase() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = false)
        val repository = FakeSyncRepository()
        val vm = vms.own(SetupUnlockViewModel(repository, delegate))

        // No choice screen (biometricAvailable=false) — straight passphrase entry.
        vm.onPassphraseChanged("strongphrase")
        vm.onConfirmChanged("strongphrase")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.setUpUnlockCalls)
        assertNull(repository.lastSetUpUnlockWrapKey)
        assertTrue(vm.uiState.value.done)
    }
}

private val TEST_PROMPT_COPY = BiometricPromptCopy(
    title = "t",
    subtitle = "s",
    negativeButton = "n",
)

private class FakeSyncRepository(
    val setUpUnlockResult: Boolean = true,
) : SyncRepository {

    var setUpUnlockCalls: Int = 0
        private set
    var lastSetUpUnlockWrapKey: ByteArray? = null
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
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> {
        setUpUnlockCalls += 1
        lastSetUpUnlockWrapKey = biometricWrapKey
        return ResultSuccessDto(TBooleanDto(setUpUnlockResult))
    }
    override suspend fun relaxToHeadless(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
    override suspend fun clearBiometricSetup(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
}
