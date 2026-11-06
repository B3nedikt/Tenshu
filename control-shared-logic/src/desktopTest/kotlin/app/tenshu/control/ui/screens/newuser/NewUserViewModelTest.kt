package app.tenshu.control.ui.screens.newuser

import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.FakeBiometricUnlockDelegate
import app.tenshu.control.data.SyncRepository
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
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Covers the **device-protection default** per
 * `docs/proposals/control-keep-device-unlocked.md` §4.1: the primary onboarding
 * CTA creates the realm headless (recovery phrase keystore-sealed, *no*
 * passphrase floor) so the realm autoloads on every cold start and background
 * sync survives a reboot. The expert passphrase/biometric lock stays reachable
 * as an opt-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewUserViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun useDeviceProtection_createsRealmHeadless_neverSealsWithPassphrase() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val repository = FakeSyncRepository()
        val vm = vms.own(NewUserViewModel(repository, delegate))

        vm.onUseDeviceProtection()
        advanceUntilIdle()

        assertEquals(1, repository.createRealmCalls, "the realm is bootstrapped")
        assertEquals(
            0, repository.setUpUnlockCalls,
            "headless default never wraps the phrase behind a passphrase — it stays keystore-sealed",
        )
        assertEquals(0, delegate.enrollCalls, "no biometric enrollment on the headless path")
        assertEquals(NewUserStep.YoureSetUp, vm.uiState.value.step)
    }

    @Test
    fun useDeviceProtection_realmBootstrapFails_surfacesErrorOnDefault() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val repository = FakeSyncRepository(createRealmSucceeds = false)
        val vm = vms.own(NewUserViewModel(repository, delegate))

        vm.onUseDeviceProtection()
        advanceUntilIdle()

        assertEquals(0, repository.setUpUnlockCalls)
        assertEquals(NewUserStep.SetupUnlock, vm.uiState.value.step)
        assertFalse(vm.uiState.value.advancedUnlock, "failure keeps the user on the device-protection default")
        assertEquals(NewUserError.RealmBootstrapFailed, vm.uiState.value.error)
    }

    @Test
    fun advancedUnlock_passphrase_sealsRealmWithFloor() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val repository = FakeSyncRepository()
        val vm = vms.own(NewUserViewModel(repository, delegate))

        vm.onShowAdvancedUnlock()
        assertTrue(vm.uiState.value.advancedUnlock)
        vm.onChoosePassphrase()
        vm.onUnlockPassphraseChanged("strongphrase")
        vm.onUnlockPassphraseConfirmChanged("strongphrase")
        vm.onUnlockSetupSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.createRealmCalls)
        assertEquals(1, repository.setUpUnlockCalls, "the expert opt-in still wraps behind the passphrase floor")
        assertEquals(NewUserStep.YoureSetUp, vm.uiState.value.step)
    }

    @Test
    fun exitAdvancedUnlock_returnsToDefault_discardsBiometricEnrollment() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository()
        val vm = vms.own(NewUserViewModel(repository, delegate))

        vm.onShowAdvancedUnlock()
        vm.onChooseBiometric(app.tenshu.control.data.BiometricPromptHost(), TEST_PROMPT_COPY)
        advanceUntilIdle()
        assertEquals(1, delegate.enrollCalls)

        vm.onExitAdvancedUnlock()

        assertFalse(vm.uiState.value.advancedUnlock, "back returns to the device-protection default")
        assertEquals(1, delegate.clearCalls, "the pending biometric enrollment is discarded")
        assertEquals(0, repository.createRealmCalls, "no realm was created by cancelling the opt-in")
    }
}

private val TEST_PROMPT_COPY = app.tenshu.control.data.BiometricPromptCopy(
    title = "t",
    subtitle = "s",
    negativeButton = "n",
)

private class FakeSyncRepository(
    val createRealmSucceeds: Boolean = true,
    val setUpUnlockResult: Boolean = true,
) : SyncRepository {

    var createRealmCalls: Int = 0
        private set
    var setUpUnlockCalls: Int = 0
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
    ): TResult<Data?> {
        createRealmCalls += 1
        return if (createRealmSucceeds) ResultSuccessDto(null)
        else ResultFailureDto(GenericErrorDto("simulated realm bootstrap failure"))
    }
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
