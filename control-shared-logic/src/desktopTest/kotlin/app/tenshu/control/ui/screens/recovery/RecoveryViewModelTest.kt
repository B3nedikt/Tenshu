package app.tenshu.control.ui.screens.recovery

import app.tenshu.control.data.SyncRepository
import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.DeviceAlreadySetUpError
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.assertEquals

/**
 * The recovery screen's reading of a refusal it cannot fix by retyping: the device already belongs
 * to a different realm (threat model T-RC-09). It must reach the screen as the catalogue entry that
 * tells the user to forget this device first — `error_device_already_set_up`, rendered by
 * `ErrorCopyKey.label()` on Android/desktop and `ErrorCopySurface` on iOS — and never as the
 * "phrase rejected" copy, which would send the user to retype a phrase that was right.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aDeviceAlreadySetUp_rendersTheForgetThisDeviceCopy_notPhraseRejected() = vmTest { vms ->
        val repository = FakeSyncRepository(ResultFailureDto(DeviceAlreadySetUpErrorDto()))
        val vm = vms.own(RecoveryViewModel(repository))

        VALID_PHRASE.split(" ").forEachIndexed { i, w -> vm.onWordChange(i, w) }
        vm.onRecover()
        advanceUntilIdle()

        assertEquals(1, repository.recoveryClaimCalls)
        val state = vm.uiState.value
        assertEquals(RecoveryStep.Entry, state.step)
        assertEquals(RecoveryError.Unexpected(ErrorCopyKey("error_device_already_set_up")), state.error)
    }

    private companion object {
        /** BIP-39's all-zeros 24-word vector — the last word is fixed by the checksum. */
        val VALID_PHRASE = "abandon ".repeat(23) + "art"
    }
}

/** Mirrors `:sync-service-common`'s `internal` DTO — fieldless, matched by schema id. */
private class DeviceAlreadySetUpErrorDto :
    DeviceAlreadySetUpError, CompositeDto(emptyList())

private class FakeSyncRepository(
    private val recoveryClaimResult: TResult<Data?>,
) : SyncRepository {

    var recoveryClaimCalls: Int = 0
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
    ): TResult<Data?> = error("not used")
    override suspend fun reregisterDevice(): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun recoveryClaim(
        recoveryPhrase: String,
        deviceName: String,
    ): TResult<Data?> {
        recoveryClaimCalls += 1
        return recoveryClaimResult
    }
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
