package app.tenshu.control.ui.screens.settings.security

import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.AppLockAvailability
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.AppLockSettings
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
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.core.TContext
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

@OptIn(ExperimentalCoroutinesApi::class)
class SecuritySettingsViewModelTest {

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

    private fun vm(
        vms: OwnedViewModels,
        settings: AppLockSettings = AppLockSettings(InMemorySecureStorageDelegate()),
        available: Boolean = true,
        repository: FakeSyncRepository = FakeSyncRepository(context),
        delegate: FakeBiometricUnlockDelegate = FakeBiometricUnlockDelegate(available = true),
        // Ph-reauth (RA-4). Success by default so the pre-existing cases keep asserting what they
        // were written to assert.
        reauthenticator: FakeReauthenticator = FakeReauthenticator(AppLockAuthResult.Success),
    ) = vms.own(SecuritySettingsViewModel(
        settings,
        Availability(available),
        repository,
        delegate,
        reauthenticator,
    ))

    @Test
    fun available_togglePersistsThroughSettings() = vmTest { vms ->
        val settings = AppLockSettings(InMemorySecureStorageDelegate())
        val vm = vm(vms, settings = settings, available = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.available)
        assertTrue(vm.uiState.value.requireUnlock, "default on")

        vm.onRequireUnlockChanged(false)
        assertFalse(vm.uiState.value.requireUnlock)
        assertFalse(settings.requireUnlock.value, "the choice is written through to the pref")
    }

    @Test
    fun unavailable_toggleIsIgnored() = vmTest { vms ->
        val settings = AppLockSettings(InMemorySecureStorageDelegate())
        val vm = vm(vms, settings = settings, available = false)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.available)

        vm.onRequireUnlockChanged(false)
        assertTrue(settings.requireUnlock.value, "no secure lock screen ⇒ the toggle is inert")
    }

    @Test
    fun encryptOnUnlock_reflectsWrapperPresence() = vmTest { vms ->
        val vm = vm(vms, repository = FakeSyncRepository(context, isSetUp = true))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.encryptOnUnlock, "a persisted wrapper ⇒ hard mode on")
    }

    @Test
    fun disable_relaxesToHeadless_andClearsBiometric() = vmTest { vms ->
        val repository = FakeSyncRepository(context, isSetUp = true)
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val vm = vm(vms, repository = repository, delegate = delegate)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.encryptOnUnlock)

        // Simulate the proxy dropping the wrapper on relax.
        repository.isSetUp = false
        vm.onDisableEncryptOnUnlock(BiometricPromptHost(), PROMPT)
        advanceUntilIdle()

        assertEquals(1, repository.relaxCalls, "disable relaxes the realm to headless")
        assertEquals(1, delegate.clearCalls, "and clears the biometric Keystore key")
        assertFalse(vm.uiState.value.encryptOnUnlock, "hard mode reads back off")
    }

    /**
     * ⚠⚠ The RA-4 guard (`control-keep-device-unlocked.md` §6.1), written so that DELETING the gate
     * turns it red: it drives the disable with a refusing prompt and asserts `relaxToHeadless` was
     * never called.
     *
     * ⚠⚠ Why this one is gated at all, when enabling is not: disabling is the only control on the
     * Security screen that **lowers** the at-rest posture — it strips the passphrase wrapper off A1
     * and leaves the OS keystore alone holding it. Enabling is a step up, and the passphrase the
     * user must set is itself the proof.
     */
    @Test
    fun refusingTheReauthPrompt_leavesHardModeOn() = vmTest { vms ->
        val repository = FakeSyncRepository(context, isSetUp = true)
        val delegate = FakeBiometricUnlockDelegate(available = true)
        val reauthenticator = FakeReauthenticator(AppLockAuthResult.Cancelled)
        val vm = vm(vms, repository = repository, delegate = delegate, reauthenticator = reauthenticator)
        advanceUntilIdle()

        vm.onDisableEncryptOnUnlock(BiometricPromptHost(), PROMPT)
        advanceUntilIdle()

        assertEquals(1, reauthenticator.calls, "disabling must ask")
        assertEquals(0, repository.relaxCalls, "a refused prompt must not relax the realm")
        assertEquals(0, delegate.clearCalls, "nor clear the biometric key")
        assertTrue(vm.uiState.value.encryptOnUnlock, "hard mode stays on")
        assertFalse(vm.uiState.value.busy, "and the row is usable again")
    }

    /**
     * ⚠⚠ The pass-through: a device that cannot present any prompt must still be able to turn hard
     * mode off. Failing closed here would strand a user whose biometrics broke inside a mode whose
     * whole cost is that a locked cold start pauses background sync.
     */
    @Test
    fun aDeviceThatCannotPrompt_canStillDisable() = vmTest { vms ->
        val repository = FakeSyncRepository(context, isSetUp = true)
        val vm = vm(vms, 
            repository = repository,
            reauthenticator = FakeReauthenticator(AppLockAuthResult.Unavailable),
        )
        advanceUntilIdle()

        repository.isSetUp = false
        vm.onDisableEncryptOnUnlock(BiometricPromptHost(), PROMPT)
        advanceUntilIdle()

        assertEquals(1, repository.relaxCalls, "unaskable ⇒ proceed, never a one-way door")
    }

    private class FakeReauthenticator(private val result: AppLockAuthResult) : Reauthenticator {
        var calls = 0
            private set

        override suspend fun authenticate(
            host: BiometricPromptHost,
            copy: ReauthPromptCopy,
        ): AppLockAuthResult {
            calls++
            return result
        }
    }

    private companion object {
        val PROMPT = ReauthPromptCopy(title = "Confirm it's you", subtitle = "why")
    }

    private class Availability(private val available: Boolean) : AppLockAvailability {
        override suspend fun isAvailable(): Boolean = available
    }
}

private class FakeSyncRepository(
    private val context: TContext,
    var isSetUp: Boolean = false,
) : SyncRepository {

    var relaxCalls: Int = 0
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
    override suspend fun createRealm(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)
    override suspend fun reregisterDevice(): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)
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
                isSetUp = isSetUp,
                isLocked = false,
                hasPassphrase = isSetUp,
            )
        )
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun relaxToHeadless(): TResult<TBoolean> {
        relaxCalls += 1
        return ResultSuccessDto(TBooleanDto(true))
    }
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
    override suspend fun clearBiometricSetup(): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()
    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
}
