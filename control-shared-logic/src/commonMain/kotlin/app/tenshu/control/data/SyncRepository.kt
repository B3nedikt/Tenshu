package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus

interface SyncRepository {
    /** The realm's device roster, live. */
    val devices: Flow<List<Device>>

    suspend fun revokeDevice(deviceId: String): TResult<Data?>

    /**
     * Restore a **peer** device's relay registration from this device's roster
     * ([org.tenshu.sync.ReadmitDevice]).
     *
     * The exit for a device the relay has stopped recognising (`401`) that cannot repair itself —
     * a pairing **joiner**, which holds no master identity.
     *
     * ⚠ Requires A1 on **this** device, refuses `deviceId == currentDeviceId` (`ReregisterDevice`'s
     * job), and refuses a revoked device. Idempotent, and therefore safe to offer without knowing
     * whether the peer is actually stranded: only the peer can observe its own `401`, and this side
     * cannot tell a stranded device from a merely offline one (see
     * [SyncDashboardUiState.deviceLastSeen]'s note on why that is an observation, never a verdict).
     */
    suspend fun readmitDevice(deviceId: String): TResult<Data?>

    suspend fun getRecoveryPhrase(): TResult<TString>

    /**
     * Discard this device's realm identity so the next launch is a fresh device
     * (`ForgetThisDevice`). Returns a human-readable outcome — a leftover roster row is an
     * *announced* result with a named remedy, never a silent one.
     *
     * ⚠⚠ Callers must route through the recovery-phrase reveal when this device holds the phrase
     * (`admission-revocation-names-the-capability.md` §5b.12.2). Never a block — a broken or sold
     * device must still be forgettable — but never silent either: this may be the realm's only copy
     * of A1, and nothing else will tell the user before it is gone.
     */
    suspend fun forgetThisDevice(): TResult<TString>
    suspend fun getSyncStatus(): TResult<SyncStatus>

    /**
     * The proxy's live sync-health row, or `null` until it has derived one
     * (`SyncHealthStatus`, one element keyed on `SINGLETON_KEY`).
     *
     * Read by the pairing wizard to tell an *entitlement-refused* realm from an unreachable one
     * after a join (`enforcement-followup-tracks.md` Track D). ⚠⚠ The distinction is the whole
     * point and the taxonomy makes it: `CONNECTIVITY_NEEDS_ATTENTION` means the relay
     * **answered and refused the write**, while an unreachable relay is `CONNECTIVITY_OFFLINE`.
     * So a wizard keyed on this can never accuse a paying user's realm because their network
     * blipped — the taxonomy carries that guard, not the caller.
     *
     * ⚠ Defaulted to "no row yet" rather than abstract: every fake and the not-connected default
     * would otherwise have to answer a question they have no opinion on, and **silence is the
     * correct answer** here — an unknown health state must leave the wizard quiet, never accusing.
     */
    fun syncHealth(): Flow<SyncHealthStatus?> = kotlinx.coroutines.flow.flowOf(null)

    /**
     * The schema pull fence (`core-schema-skew-surface.md`): the services this device is
     * behind the realm on, as sorted ids. **Empty** = the fence is clear; **`null`** = not yet
     * known (no row until the proxy's first tick after unlock, §7 Q3), which a surface must treat
     * as neither fine nor fenced.
     *
     * ⚠ A fenced device is **reachable**, pushes, and reads locally — nothing else on this
     * repository looks wrong while it is fenced, which is why this is its own read.
     *
     * ⚠ Defaulted to "unknown" for the same reason as [syncHealth]: a fake or not-connected
     * repository has no opinion, and silence is the honest answer.
     */
    fun schemaFence(): Flow<List<String>?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>>
    suspend fun startPairing(): TResult<SyncStatus>
    suspend fun stopPairing(): TResult<SyncStatus>
    suspend fun approvePairingRequest(deviceId: String): TResult<Data?>
    suspend fun getDeviceId(): String

    /**
     * First-device realm bootstrap (docs/adr/0007-sync-device-management.md). The caller picks
     * this when no realm exists yet — derives the master identity from [recoveryPhrase],
     * registers it on the relay via `/sync/realm`, and pushes the device's own
     * `Device { isAuthorized = true }` entity into the synced DB.
     */
    suspend fun createRealm(recoveryPhrase: String, deviceName: String): TResult<Data?>

    /**
     * Re-assert this device's relay registration without re-pairing, after the relay
     * forgot it (e.g. an in-memory relay restart dropped its device registry, 401-ing
     * every authenticated call). Idempotent + safe to retry; only succeeds for a
     * master-identity holder (owner / recovered device). See `ReregisterDevice`.
     */
    suspend fun reregisterDevice(): TResult<Data?>

    /**
     * Recovery-code re-admission for an existing realm (docs/adr/0007-sync-device-management.md).
     * The caller picks this when the realm already exists and this device has the
     * recovery phrase — runs the `/sync/recovery-challenge` + `/sync/recovery-claim`
     * dance and pushes its own `Device { isAuthorized = true }` entity.
     */
    suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String): TResult<Data?>

    suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation>

    suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): TResult<TBoolean>

    /**
     * A single cancellable pairing-wait task (§7.5). The returned
     * [TaskHandle] resolves once the handshake completes (`Success(true)`) or
     * the proxy-side ~5-min budget elapses (`Success(false)` → "timed out").
     * Cancelling it tears down the in-proxy handshake — it subsumes
     * [cancelPairing] once the wait is running. Use it instead of a repeated
     * [pollPairingStatus] loop; uniform cross-process and in-process.
     */
    suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): TaskHandle<TBoolean>

    suspend fun getUnlockStatus(): TResult<UnlockStatus>

    suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean>

    /**
     * Atomic passphrase-floor setup: always wraps under [passphrase], and also
     * under [biometricWrapKey] when non-null — one call so the plaintext is read
     * once. The mandatory passphrase enforces the floor (biometric never alone).
     * Used by the first-run unlock-setup screen. The UI enrolls [biometricWrapKey]
     * with the platform delegate first (enroll-first). See `SyncManager.setUpUnlock`.
     */
    suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean>

    /**
     * Turns "Encrypt on unlock only" back off (`control-keep-device-unlocked.md`
     * §4.2, toggle 2): re-persists the plaintext payload from the unlocked session
     * and drops the passphrase/biometric wrappers so the realm autoloads headless
     * again. `Success(false)` when the realm is locked (nothing to relax to).
     */
    suspend fun relaxToHeadless(): TResult<TBoolean>

    suspend fun unlockLocal(passphrase: String): TResult<TBoolean>

    /**
     * Re-seal the realm — purges the in-memory keys so the next landing goes back
     * through the LockedScreen. Backs a user-initiated "Lock now". Only the real
     * proxy-backed [TenshuSyncRepository] supports it; the default throws so the
     * many test doubles / fakes don't each need a no-op override.
     */
    suspend fun lock(): TResult<TBoolean> =
        throw NotImplementedError("lock() is only supported by the proxy-backed repository")

    /**
     * Biometric counterpart to [setUpLocalUnlock]. The UI is expected to
     * have already enrolled [wrapKey] with the platform
     * `BiometricUnlockDelegate` before this call — so a delegate failure
     * leaves the proxy untouched. See `SyncManager.setUpBiometricUnlock`
     * for the coexistence + plaintext-deletion contract.
     */
    suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean>

    /**
     * Biometric counterpart to [unlockLocal]. The UI obtains [wrapKey] by
     * running the platform `BiometricUnlockDelegate.unwrapWrapKey()` path.
     */
    suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean>

    /**
     * Wipes the biometric unlock wrapper (Settings "disable biometric"
     * or recovery from an invalidated Keystore key). Idempotent.
     */
    suspend fun clearBiometricSetup(): TResult<TBoolean>

    /**
     * Discards the proxy-side ephemeral keypair stashed under
     * [pairingSecret] by `InitiatePairing`. Idempotent — safe to call
     * even if no handshake is currently pending.
     */
    suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean>

    /**
     * Offer-flow responder (`sync-pairing-inverted-direction.md` §5.2): this existing device
     * scanned a new device's `tenshu://offer?…` QR. Parses it proxy-side, derives the shared
     * secret + SAS, and returns the [PairingOfferApprovalPrompt] (device name + SAS +
     * `pairingSecretHash`) the approval UI shows. The default throws so the many test doubles
     * that never scan an offer don't each need a no-op override (mirrors [lock]).
     */
    suspend fun respondToPairingOffer(scannedUri: String): TResult<PairingOfferApprovalPrompt> =
        throw NotImplementedError("respondToPairingOffer() is only supported by the proxy-backed repository")

    /**
     * Hub-flow responder (hub proposal §11.3, H2): this existing device scanned a headless
     * hub's PRINTED `tenshu://hub?…` label. Fetches the hub's signed ephemeral offer from
     * the relay, verifies it against the printed key proxy-side, and returns the approval
     * prompt (device name + `pairingSecretHash`; `sas` empty — the signature replaces it).
     * Approve/deny then reuse [approvePairingOffer] / [denyPairingOffer] unchanged.
     */
    suspend fun respondToHubPairing(scannedUri: String): TResult<PairingOfferApprovalPrompt> =
        throw NotImplementedError("respondToHubPairing() is only supported by the proxy-backed repository")

    /**
     * Offer-flow responder approve: seals + posts the approved offer-response and admits the
     * offerer's device, keyed by the [pairingSecretHash] from [respondToPairingOffer].
     */
    suspend fun approvePairingOffer(pairingSecretHash: String): TResult<TBoolean> =
        throw NotImplementedError("approvePairingOffer() is only supported by the proxy-backed repository")

    /**
     * Offer-flow responder deny: posts a signed `denied` offer-response and drops the responder
     * session. Idempotent.
     */
    suspend fun denyPairingOffer(pairingSecretHash: String): TResult<TBoolean> =
        throw NotImplementedError("denyPairingOffer() is only supported by the proxy-backed repository")

    /**
     * Offer-flow **offerer** (`sync-pairing-inverted-direction.md` §5.1): this *new* device mints
     * an offer session and returns the `tenshu://offer?…` URI to display plus the
     * `pairingSecretHash` to poll via [getOfferStatus]. The default throws so test doubles that
     * never offer don't each need an override (mirrors [respondToPairingOffer]).
     */
    suspend fun offerPairing(deviceName: String): TResult<OfferPairingSession> =
        throw NotImplementedError("offerPairing() is only supported by the proxy-backed repository")

    /**
     * Offerer poll: `pending` → keep polling; `approved` → the proxy has already installed the
     * received keys (this device is paired) and the [OfferStatus.sas] carries the SAS for the
     * user's post-hoc comparison; `denied` → the responder declined.
     */
    suspend fun getOfferStatus(pairingSecretHash: String): TResult<OfferStatus> =
        throw NotImplementedError("getOfferStatus() is only supported by the proxy-backed repository")

    /**
     * Offerer teardown: zero the offerer's ephemeral private key and drop the offer session.
     * Idempotent.
     */
    suspend fun cancelPairingOffer(pairingSecretHash: String): TResult<TBoolean> =
        throw NotImplementedError("cancelPairingOffer() is only supported by the proxy-backed repository")

    /**
     * Read-only: the [SyncPolicy] a given device keeps by default
     * (`docs/proposals/drive-offline-online.md` §4.6, "what each device keeps"),
     * read through the typed `FileSynchronisationSettings` surface. `null` when the
     * device has no explicit policy row (never written) — the
     * device list then shows no policy line rather than claiming a default.
     */
    suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy?

    /**
     * When this device last ingested a write from each realm device, keyed by `deviceId`
     * (`tech-debt.md`, *"a `Device` row that outlives the identity it describes"*). One read for
     * the whole roster, not an op per row.
     *
     * ⚠ An absent key means "nothing heard from it **on this device**" — which covers both a peer
     * that has been quiet and a row whose identity no longer exists. The two are genuinely
     * indistinguishable from here, so the surface must present it as an observation, never as a
     * verdict.
     */
    suspend fun deviceLastSeen(): Map<String, Long>

    /**
     * Each realm device's self-published hardware model (`DeviceHardware`), keyed by `deviceId` —
     * the immutable half of the roster label beside the user-chosen `Device.name`.
     *
     * ⚠ An absent key is ordinary (the device has not run since the row existed, or is locked):
     * render the name alone.
     *
     * ⚠ **Live, unlike [deviceLastSeen].** A peer publishes its row only after it has joined, so on
     * the admitting device the row routinely arrives *after* the roster has rendered; a one-shot
     * read would leave the new device's model off the screen until something re-ran the pass.
     */
    val deviceHardwareNames: Flow<Map<String, String>>
}

