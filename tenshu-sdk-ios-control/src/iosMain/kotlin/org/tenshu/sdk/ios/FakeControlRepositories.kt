package org.tenshu.sdk.ios

import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.UnwrapResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.core.TContext
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * A no-proxy [SyncRepository] for the Control-on-iOS shell (C2c, genesis over fakes). Every call
 * succeeds locally — `createRealm` / `setUpUnlock` / `recoveryClaim` return success so the genesis
 * + recovery state machines (`NewUserViewModel` / `RecoveryViewModel`) advance through to completion
 * without a proxy or real crypto. Real, key-minting genesis (riding the C1 keystore) is the
 * in-process-collapse data plane (C4); this lets the **onboarding UX** be built + run first, exactly
 * as drive-ios stood up over `FakeFilesRepository`.
 *
 * Lifted from the `:control-shared-logic` desktopTest inline fakes into the umbrella's iosMain so the
 * SwiftUI app can bind the genesis VMs. Success-only (the genesis happy path); no call-tracking.
 *
 * **C6 device-pairing (B-1):** the pairing surface is enriched too so the SwiftUI "Add a device"
 * wizard ([app.tenshu.control.ui.screens.sync.PairingWizardViewModel] + [app.tenshu.control.sync.HostPairingMonitor])
 * is fully drivable over fakes before the real C4-RT proxy runtime lands:
 *  - [startPairing] hands the host monitor a real [SyncStatus] carrying a fake pairing string, so the
 *    Show tab renders a code;
 *  - [initiatePairing] returns a real [PairingInitiation] with a fixed SAS, so the joiner Approval
 *    step shows "123 456";
 *  - [awaitPairing] hands back a [TaskHandle] that resolves `Success(true)` after a short delay, so the
 *    joiner advances to the Success step on its own.
 * These need a [TContext] to build the composites; one is lazily assembled from the sync-service schema
 * (see [context]).
 */
class FakeControlSyncRepository : SyncRepository {

    /**
     * A minimal [TContext] for materialising the pairing composites the fakes return ([SyncStatus] /
     * [PairingInitiation]). Built lazily from `coreCommonModule` + the sync-service schema only — the
     * same `coreSchemaConfigs` seam production uses (`EpcDriveClient.assembleClient`), narrowed to the
     * one module these two composites need.
     */
    private val context: TContext by lazy {
        koinApplication {
            modules(
                coreCommonModule(),
                module {
                    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
                        listOf(syncServiceCommonModule)
                    }
                },
            )
        }.koin.get()
    }

    override val devices: Flow<List<Device>> = MutableStateFlow(emptyList())

    override suspend fun readmitDevice(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun revokeDevice(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    // Mirrors this fake's other write stubs: a benign success for screenshot/preview routes.
    override suspend fun forgetThisDevice(): TResult<TString> = ResultSuccessDto(null)

    override suspend fun getRecoveryPhrase(): TResult<TString> = ResultSuccessDto(null)
    override suspend fun getSyncStatus(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> = ResultSuccessDto(null)

    /**
     * Hand the [app.tenshu.control.sync.HostPairingMonitor] a live-looking host session: a [SyncStatus]
     * whose `pairingString` is a well-formed canonical `tenshu://join` link (decodable by
     * [onQrScanned]'s parser if ever scanned back), so the Show tab renders a copyable code over fakes.
     */
    override suspend fun startPairing(): TResult<SyncStatus> = ResultSuccessDto(
        SyncStatus.create(
            context = context,
            deviceId = "ios-control-device",
            ownerId = "ios-control-owner",
            isEnabled = true,
            pairingString = FAKE_PAIRING_STRING,
        )
    )

    override suspend fun stopPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun getDeviceId(): String = "ios-control-device"

    override suspend fun createRealm(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)

    override suspend fun reregisterDevice(): TResult<Data?> = ResultSuccessDto(null)

    override suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)

    /**
     * Return a real [PairingInitiation] carrying a fixed SAS so the joiner Approval step renders a code
     * the user can "verify" against the host. The real proxy derives the SAS from the X25519 ECDH; over
     * fakes it's a constant ([FAKE_SAS], shown as "123 456" after the UI's 3-3 split).
     */
    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation> = ResultSuccessDto(
        PairingInitiation.create(
            context = context,
            publicKey = ByteArray(32),
            sas = FAKE_SAS,
        )
    )

    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(false))

    /**
     * Hand back a handle that auto-completes the handshake: `await()` waits a short, human-plausible
     * delay (so the Approval spinner + SAS are actually visible) then resolves `Success(true)`, driving
     * the joiner wizard to its Success step with no second device. `cancel()` honours the user backing
     * out (the VM's `onBackToScanStart`).
     */
    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TaskHandle<TBoolean> = FakeControlPairingTaskHandle(FAKE_HANDSHAKE_DELAY_MS)

    override suspend fun getUnlockStatus(): TResult<UnlockStatus> = ResultSuccessDto(null)
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))

    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))

    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))
    override suspend fun relaxToHeadless(): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))

    override suspend fun clearBiometricSetup(): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))
    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(true))
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null

    // ⚠ Empty, not a fabricated recent timestamp: over fakes the roster then renders the honest
    // "No activity seen yet", which is exactly what a fake device is. Inventing a plausible
    // "Active just now" would make the DEBUG screenshot routes disagree with any real device.
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

    private companion object {
        /** 6-digit fake SAS; the UI renders it 3-3 split as "123 456". */
        const val FAKE_SAS = "123456"

        /** A well-formed (but inert) canonical host pairing link the Show tab renders + copies. */
        val FAKE_PAIRING_STRING = PairingUri.joinUri(
            ownerId = "ios-control-owner",
            hostDeviceId = "ios-control-device",
            hostPublicKey = ByteArray(32),
            pairingSecret = "ios-fake-secret".encodeToByteArray(),
        )

        /** How long [awaitPairing]'s fake handshake "waits" before completing (visible Approval). */
        const val FAKE_HANDSHAKE_DELAY_MS = 2_500L
    }
}

/**
 * A [TaskHandle] that drives the C6 pairing handshake to completion over fakes: [await] suspends for a
 * short, human-plausible delay (so the Approval SAS + spinner are visible) then resolves
 * `Success(true)`; [cancel] makes the in-flight await throw [kotlin.coroutines.cancellation.CancellationException]
 * (the VM treats that as a user back-out, not an error). The delay is read off
 * `FakeControlSyncRepository`'s companion via the call site, so it stays a single source of truth.
 */
private class FakeControlPairingTaskHandle(
    private val delayMs: Long,
) : TaskHandle<TBoolean> {
    override val taskId: String = "ios-fake-pairing"

    private var cancelled = false

    override suspend fun await(): TResult<TBoolean> {
        if (cancelled) throw kotlinx.coroutines.CancellationException("fake pairing cancelled")
        delay(delayMs)
        if (cancelled) throw kotlinx.coroutines.CancellationException("fake pairing cancelled")
        return ResultSuccessDto(TBooleanDto(true))
    }

    override fun cancel() {
        cancelled = true
    }
}

/**
 * A no-hardware [BiometricUnlockDelegate] for the shell: reports unavailable so the genesis flow
 * stays on the cross-platform **passphrase floor** (the only credential path that makes sense on iOS
 * today — there's no iOS `BiometricUnlockDelegate` impl yet; on iOS the wrap-key gate is intrinsic
 * to the C1 Keychain ACL, not an enroll/unwrap delegate). `enroll`/`unwrap` still succeed if a future
 * screen drives them.
 */
class FakeControlBiometricUnlockDelegate(
    private val available: Boolean = false,
) : BiometricUnlockDelegate {
    override suspend fun isAvailable(): Boolean = available
    override suspend fun hasEnrolled(): Boolean = false

    override suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult = EnrollResult.Success

    override suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult = UnwrapResult.NotAvailable

    override fun clear() = Unit
}
