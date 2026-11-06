package app.tenshu.control.ui.screens.settings.unlock

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
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.core.TContext
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

private val TEST_PROMPT_COPY = BiometricPromptCopy(
    title = "t",
    subtitle = "s",
    negativeButton = "n",
)

/**
 * Covers the Settings → Unlock toggle, including the **passphrase floor**:
 * biometric can only be enabled when a recovery passphrase already exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockSettingsViewModelTest {

    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_reflectsDelegateAvailabilityAndEnrolment() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true, enrolled = true)
        val vm = vms.own(UnlockSettingsViewModel(FakeSyncRepository(context), delegate))
        advanceUntilIdle() // availability is now probed off the constructor

        val state = vm.uiState.value
        assertTrue(state.biometricAvailable)
        assertTrue(state.biometricEnabled)
    }

    @Test
    fun toggleOn_withPassphrase_enrolsAndCommitsToProxy() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            enrolled = false,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository(context, hasPassphrase = true)
        val vm = vms.own(UnlockSettingsViewModel(repository, delegate))
        advanceUntilIdle() // availability is probed off the constructor

        vm.onToggle(enabled = true, host = BiometricPromptHost(), promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(1, delegate.enrollCalls)
        assertNotNull(repository.lastSetUpBiometricKey)
        assertContentEquals(delegate.lastEnrolledWrapKey, repository.lastSetUpBiometricKey)
        assertTrue(vm.uiState.value.biometricEnabled)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun toggleOn_withoutPassphrase_surfacesPassphraseRequired_noEnroll() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository(context, hasPassphrase = false)
        val vm = vms.own(UnlockSettingsViewModel(repository, delegate))
        advanceUntilIdle() // availability is probed off the constructor

        vm.onToggle(enabled = true, host = BiometricPromptHost(), promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, delegate.enrollCalls, "no Touch ID prompt without a recovery passphrase")
        assertEquals(0, repository.setUpBiometricCalls)
        assertEquals(UnlockSettingsError.PassphraseRequired, vm.uiState.value.error)
        assertFalse(vm.uiState.value.biometricEnabled)
    }

    @Test
    fun toggleOn_cancelled_leavesToggleOff_withoutError() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.CancelledByUser,
        )
        val repository = FakeSyncRepository(context)
        val vm = vms.own(UnlockSettingsViewModel(repository, delegate))

        vm.onToggle(enabled = true, host = BiometricPromptHost(), promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, repository.setUpBiometricCalls, "cancel ⇒ proxy untouched")
        assertFalse(vm.uiState.value.biometricEnabled)
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun toggleOn_proxyRejects_rollsBackDelegate() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(
            available = true,
            nextEnrollResult = EnrollResult.Success,
        )
        val repository = FakeSyncRepository(context, setUpBiometricResult = false)
        val vm = vms.own(UnlockSettingsViewModel(repository, delegate))
        advanceUntilIdle() // availability is probed off the constructor

        vm.onToggle(enabled = true, host = BiometricPromptHost(), promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(1, delegate.clearCalls)
        assertFalse(vm.uiState.value.biometricEnabled)
        assertEquals(UnlockSettingsError.EnableBiometricFailed, vm.uiState.value.error)
    }

    @Test
    fun toggleOff_clearsBothBlobs() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true, enrolled = true)
        val repository = FakeSyncRepository(context)
        val vm = vms.own(UnlockSettingsViewModel(repository, delegate))

        vm.onToggle(enabled = false, host = null, promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(1, delegate.clearCalls)
        assertEquals(1, repository.clearBiometricCalls)
        assertFalse(vm.uiState.value.biometricEnabled)
    }

    @Test
    fun toggleOn_whenHostIsNull_surfacesNotAvailable() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val vm = vms.own(UnlockSettingsViewModel(FakeSyncRepository(context), delegate))
        advanceUntilIdle() // availability is probed off the constructor

        vm.onToggle(enabled = true, host = null, promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, delegate.enrollCalls)
        assertEquals(UnlockSettingsError.BiometricUnavailable, vm.uiState.value.error)
    }

    @Test
    fun toggleOn_whenNotAvailable_isNoOp() = vmTest { vms ->
        val delegate = FakeBiometricUnlockDelegate(available = false)
        val vm = vms.own(UnlockSettingsViewModel(FakeSyncRepository(context), delegate))

        vm.onToggle(enabled = true, host = BiometricPromptHost(), promptCopy = TEST_PROMPT_COPY)
        advanceUntilIdle()

        assertEquals(0, delegate.enrollCalls)
        assertFalse(vm.uiState.value.biometricEnabled)
    }
}

private class FakeSyncRepository(
    private val context: TContext,
    private val hasPassphrase: Boolean = true,
    val setUpBiometricResult: Boolean = true,
) : SyncRepository {

    var lastSetUpBiometricKey: ByteArray? = null
        private set
    var setUpBiometricCalls: Int = 0
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
    override suspend fun getUnlockStatus(): TResult<UnlockStatus> =
        ResultSuccessDto(
            UnlockStatus.create(
                context = context,
                isSetUp = true,
                isLocked = false,
                hasPassphrase = hasPassphrase,
            )
        )
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun relaxToHeadless(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> {
        setUpBiometricCalls += 1
        lastSetUpBiometricKey = wrapKey
        return ResultSuccessDto(TBooleanDto(setUpBiometricResult))
    }
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
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
