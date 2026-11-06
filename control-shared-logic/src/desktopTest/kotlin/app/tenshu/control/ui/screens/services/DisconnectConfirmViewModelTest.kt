package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.core.TContext
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.UnlockStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The confirmation factor must be one this device actually has.
 *
 * ⚠⚠ Regression for a **user-reported dead end** (2026-08-07, `tech-debt.md` §"Disconnect from
 * Tenshu Storage is unreachable"): the screen asked for a local-unlock passphrase unconditionally,
 * but desktop Control auto-unlocks from the login Keychain so that passphrase is optional and
 * usually absent. `unlockLocal` opens `getSecret(UNLOCK_WRAPPER) ?: return false`, so for those
 * users **every** input answered "Passphrase doesn't match" and the action could not be completed
 * at all. The bug was not that the gate was weak — it was that the gate could not be satisfied,
 * and nothing on screen said so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisconnectConfirmViewModelTest {

    /**
     * ⚠ `syncServiceCommonModule` is load-bearing: without it `UnlockStatus.create` throws inside
     * the VM's `init` coroutine, which fails SILENTLY — the state simply never leaves `resolving`,
     * and every assertion then fails for a reason that has nothing to do with the code under test.
     */
    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun noPassphraseConfigured_disconnectsWithoutOne() = vmTest { vms ->
        val repo = FakeSyncRepository(context, hasPassphrase = false)
        val client = FakePublisherClient()
        val vm = vms.own(DisconnectConfirmViewModel(client, repo))
        advanceUntilIdle()

        assertFalse(
            vm.uiState.value.requiresPassphrase,
            "with no UNLOCK_WRAPPER there is no passphrase to ask for",
        )

        // The whole point: confirming with an EMPTY passphrase must work, because there is no
        // passphrase in existence to type. This is the case that was previously impossible.
        vm.onConfirm()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.disconnected, "disconnect must be reachable without a passphrase")
        assertEquals(1, client.disconnectCalls)
        assertEquals(
            0,
            repo.unlockLocalCalls,
            "must not consult unlockLocal at all — it can only ever return false here",
        )
    }

    @Test
    fun passphraseConfigured_stillVerifiesIt() = vmTest { vms ->
        val repo = FakeSyncRepository(context, hasPassphrase = true, unlockSucceeds = false)
        val client = FakePublisherClient()
        val vm = vms.own(DisconnectConfirmViewModel(client, repo))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.requiresPassphrase)

        vm.onPassphraseChange("wrong")
        vm.onConfirm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.disconnected, "a wrong passphrase must still refuse")
        assertEquals(0, client.disconnectCalls)
        assertEquals("Passphrase doesn't match.", vm.uiState.value.error)
    }

    @Test
    fun unreadableUnlockStatus_failsClosedAndKeepsAskingForThePassphrase() = vmTest { vms ->
        // A null status is "we don't know", which must NOT be read as "no passphrase exists" —
        // that would turn an unreadable status into a silently dropped gate.
        val repo = FakeSyncRepository(context, hasPassphrase = null)
        val vm = vms.own(DisconnectConfirmViewModel(FakePublisherClient(), repo))
        advanceUntilIdle()

        assertTrue(
            vm.uiState.value.requiresPassphrase,
            "an unknown unlock status must fail closed, not open",
        )
    }
}

private class FakePublisherClient : PublisherClient {
    var disconnectCalls = 0
        private set

    override val account: StateFlow<PublisherAccount?> = MutableStateFlow(null)
    override val redeemedEntitlements: Flow<EntitlementSignature.SignedEntitlement> = emptyFlow()
    override fun currentSessionToken(): String? = null
    override suspend fun register(email: String, displayName: String?) = error("unused")
    override suspend fun verifyOtp(code: String) = error("unused")
    override suspend fun redeem(code: String) = error("unused")
    override suspend fun startCheckout(productId: String, withdrawalAcknowledged: Boolean) =
        error("unused")

    override suspend fun openBillingPortal() = error("unused")
    override suspend fun syncPlan() = error("unused")
    override suspend fun disconnect() { disconnectCalls += 1 }
}

/**
 * [hasPassphrase] `null` models an UNREADABLE [SyncRepository.getUnlockStatus] — the fail-closed
 * case — rather than "no passphrase configured".
 */
private class FakeSyncRepository(
    private val context: TContext,
    private val hasPassphrase: Boolean?,
    private val unlockSucceeds: Boolean = true,
) : SyncRepository {

    var unlockLocalCalls: Int = 0
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
    override suspend fun getUnlockStatus(): TResult<UnlockStatus> = ResultSuccessDto(
        hasPassphrase?.let {
            UnlockStatus.create(
                context = context,
                isSetUp = it,
                isLocked = false,
                hasPassphrase = it,
            )
        },
    )
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun relaxToHeadless(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> {
        unlockLocalCalls += 1
        return ResultSuccessDto(TBooleanDto(unlockSucceeds))
    }
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun clearBiometricSetup(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
}
