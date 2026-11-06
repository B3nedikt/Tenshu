package org.tenshu.sync.common

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.common.util.HLCManager
import org.tenshu.core.security.SyncCryptoManager

/**
 * Component responsible for managing the state and configuration of data synchronization.
 */
interface SyncManager {

    /**
     * Unique identifier for the current device's sync state.
     */
    val currentDeviceId: String

    /**
     * The owner of the data being synchronized.
     *
     * ⚠ In-memory only, and therefore **null during the async key-load window** on a
     * cold start (see the `autoLoadScope` note in `SyncManagerImpl.init`). Read it when
     * you mean "the realm is live in this session"; read [residentOwnerId] when you mean
     * "this device belongs to a realm".
     */
    val currentOwnerId: String?

    /**
     * The durable realm-membership marker: this device's owner id as it survives process
     * death, independent of whether the keys have been loaded back yet.
     *
     * ⚠⚠ The distinction is not cosmetic. [currentOwnerId] is populated by `initialize`,
     * which on a cold start runs *asynchronously* behind an Argon2id stretch — a window of
     * seconds on a phone. Anything that answers a **durable** question ("has this device
     * been set up?", "is this device unpaired?") from [currentOwnerId] reads a provisioned
     * device as a blank one for the length of that window: Control would land on the
     * "Welcome to Tenshu / Start fresh on this device" onboarding screen on a cold start, and
     * `PairingWizardViewModel` would take the *offerer* role on a device that already holds a
     * realm. iOS's `ControlSurface.hasResidentIdentity()` reads the same slot this does.
     *
     * The default implementation is the in-memory value, so test fakes keep the in-memory
     * meaning; `SyncManagerImpl` overrides it with the persisted slot, which
     * every discard path already clears via `Keys.CUSTODY_SLOTS`.
     */
    val residentOwnerId: String? get() = currentOwnerId

    /**
     * Hybrid Logical Clock manager for this device.
     */
    val hlcManager: HLCManager

    /**
     * The **block** lane's pull cursor — the highest `relay_seq` this device has been served
     * on `GET /sync/changes`.
     *
     * ⚠ Per-lane (`sync-lane-cursor-split.md` L1): this does not gate tombstones. The two
     * inbound lanes share the relay's counter but have very different densities (500 blocks can
     * span ~56 000 seq; 500 tombstones span 500), so one shared cursor would let the denser lane
     * throttle the sparser one to +500/tick. Each lane advances on its own evidence only.
     */
    var lastSyncTimestamp: Long

    /**
     * The **tombstone** lane's pull cursor — the highest `relay_seq` served on
     * `GET /sync/tombstones`. Sibling of [lastSyncTimestamp]; see there for why they are split.
     *
     * ⚠ On a store written before the lanes split (no tombstone cursor row) this seeds from
     * [lastSyncTimestamp] rather than 0, so an upgrade does not replay every tombstone in history.
     */
    var lastTombstoneSyncTimestamp: Long

    /**
     * The **backfill floor** — how far back from the relay's tip this device has walked while
     * catching up newest-first (`sync-device-catch-up.md` C2 / §5.4).
     *
     * `null` = never started (walk from the tip) · `> 0` = in progress · `0` = complete. See
     * [org.tenshu.core.common.proxy.db.ObjectStore.loadBackfillCursor] for why the three states
     * must stay distinguishable.
     *
     * ⚠ This does NOT replace [lastSyncTimestamp]. The two passes walk toward each other from
     * opposite ends of the log and stop when they meet, so every seq is covered by exactly one of
     * them, and neither pass changes the other's meaning.
     */
    var backfillCursor: Long?

    /**
     * Whether this store has finished a first forward pull (`ios-sync-activity-indicator.md`
     * §3.1). `null` = no marker row. Lives beside the cursors and is wiped with them — see
     * [org.tenshu.core.common.proxy.db.ObjectStore.loadFirstPullMarker] for why the cursor itself
     * cannot answer this.
     *
     * ⚠ Like [backfillCursor], assigning `null` is a no-op: a marker is only ever written forward.
     * [resetPullProgress] is what removes it.
     */
    var firstPullMarker: FirstPullMarker?

    /**
     * ⚠ True when a first-pull marker row exists but holds a value this build does not know.
     * [firstPullMarker] reads such a row as `null` (absent); a caller that must treat unknown as
     * **not pulled** (`ios-shared-store-sqlite.md` B.22.7) asks this too. Default false: a manager
     * without a marker row has nothing unknown.
     */
    val firstPullMarkerUnrecognised: Boolean get() = false

    /**
     * Return every stored position in the relay's log to *never pulled*: the forward cursor and the
     * tombstone cursor to zero, and the first-pull marker, the backfill floor and the backfill
     * target to absent.
     *
     * ⚠⚠ These rows are positions in ONE realm's log and share one lifecycle. Zeroing the forward
     * cursor alone leaves the rest describing a log this device no longer reads: a surviving
     * `COMPLETED` marker (or a surviving tombstone cursor, which the scheduler reads as "has
     * pulled before") makes a freshly joined realm look already pulled, so its first pull raises
     * no sync-activity signal, and never does if that realm is empty. Every caller that resets the
     * forward cursor for a realm replacement or a device reset goes through here.
     */
    fun resetPullProgress()

    /**
     * Re-open the backward walk: return [backfillCursor] to `null` (*never walked*) so the next
     * tick starts again from the tip.
     *
     * ⚠⚠ **Assigning `null` to [backfillCursor] does NOT do this** — the implementation's setter
     * discards nulls, so the "walk from the tip" state cannot be reached by assignment. Without
     * this method the `0` latch is permanent, which is precisely how a device ends up believing
     * it is caught up while history is missing and no code anywhere can correct it.
     */
    fun reopenBackfill()

    /**
     * Whether this device created, recovered or joined its realm on a build that describes every
     * content object as it uploads it (`content-plane-refcount.md` §9.7). Such a
     * realm has no undescribed content, so the content-declaration backfill is complete from the
     * start: a new device never runs the pass, whose cost is one manifest `GET` per content object.
     * ⚠ A device upgraded in place, whose realm predates the seam, never sets it and still runs
     * the pass. Default `false` for test doubles.
     */
    val contentDescribedFromGenesis: Boolean get() = false

    /**
     * Where the backward walk must stop — the forward cursor's value at the moment backfill
     * engaged and the forward pass was promoted to a live tail
     * (`sync-device-catch-up.md` §5.4). `null` until handover; read as `0`.
     */
    var backfillTarget: Long?

    /**
     * Whether this device's personal realm has been registered on the relay
     * (`POST /sync/realm`). A persistent, once-ever latch for the local-first lazy
     * registration (ADR 0019): the realm is created locally with no relay contact,
     * then `RealmRegistrar` performs the genesis the first time a storage
     * subscription unlocks relay traffic — and sets this so it never re-attempts
     * (the relay still holds the realm across client restarts; relay *amnesia* is
     * recovered separately by `ReregisterDevice`, not by re-running the genesis).
     */
    var realmRelayRegistered: Boolean

    /**
     * Whether this device **joined a realm it did not itself fund** — set once at
     * pairing completion by `installReceivedPairingPayload` (both pairing directions); the iOS
     * route-1 runtime sets it independently. A joiner adopts an existing realm
     * that the owner already registered on the relay (`POST /sync/realm`), and
     * that registration was gated on the owner's storage subscription. Completing
     * the relay-mediated pairing handshake is a sound proxy for "owner-funded":
     * the joiner only obtains the encrypted master key because the relay served
     * the host's `POST /sync/pairing/response`, which it rejects (`404`) unless
     * the realm is registered (`storage-entitlement-sync.md` §3.3).
     *
     * Read by the personal-realm storage sync gate so a freshly-paired device can
     * run its **bootstrap pull** even though its own device-local `hasStorage` is
     * still false — otherwise the gate would block the very pull that delivers the
     * entitlement that would flip it true (the cross-device bootstrap deadlock,
     * ADR 0019 + `storage-entitlement-sync.md`). Distinct from
     * [realmRelayRegistered] (which means "*this* device ran genesis" and must not
     * be set for a joiner).
     */
    var joinedFundedRealm: Boolean

    /**
     * Initializes the sync manager with the user's recovery phrase.
     */
    fun initialize(recoveryPhrase: String)

    /**
     * Returns the plaintext recovery phrase persisted by [initialize], if
     * available. Used by `GetRecoveryPhrase` to back the "Set up account
     * recovery" surface in Settings per
     * `docs/proposals/onboarding-low-friction.md` §7.4.
     *
     * Returns null when:
     * - no realm has been created yet (pre-`initialize` / pre-`createRealm`);
     * - the device is locked (the plaintext lives only in memory of an
     *   unlocked session — `setUpLocalUnlock` removes the persisted plaintext
     *   in favour of the passphrase-wrapped form, which `unlockLocal` then
     *   restores by re-running `initialize`).
     *
     * Threat-model context: while the device is unlocked, the phrase is
     * persisted in [org.tenshu.core.common.delegate.SecureStorageDelegate]
     * under the platform's secure-storage primitive (Android Keystore-sealed
     * SharedPreferences / macOS Keychain / web IndexedDB under the
     * WebAuthn-PRF unlock). It has the same at-rest protection as every other
     * secret in that store. The deferred-capture flow in the redesigned
     * onboarding reads this rather than re-deriving from the master key — the
     * BIP-39 entropy → Argon2id → master-key direction is one-way, so true
     * re-derivation isn't cryptographically possible.
     */
    fun getRecoveryPhrase(): String?

    /**
     * Local-unlock primitive per `docs/proposals/control-onboarding-ux.md`
     * §3 / §4b.5 / §4a.6. Uses passphrase-derived encryption (PBKDF2-
     * SHA-512 KDF + the existing [SyncCryptoManager] encrypt/decrypt) over
     * the plaintext recovery phrase; the biometric credential source is
     * [setUpBiometricUnlock].
     *
     * `setUpLocalUnlock` reads the currently-persisted plaintext recovery
     * phrase, wraps it under [passphrase], persists the wrapper, and
     * deletes the plaintext. Returns `false` when no plaintext phrase is
     * available (caller must initialize first via createRealm /
     * recoveryClaim / pairing).
     */
    fun setUpLocalUnlock(passphrase: String): Boolean

    /**
     * Atomic first-time unlock setup with a **passphrase floor**: always wraps
     * the plaintext recovery phrase under [passphrase] (`UNLOCK_WRAPPER`), and —
     * when [biometricWrapKey] is non-null — *also* wraps it under that key
     * (`BIOMETRIC_WRAPPER`), then clears the plaintext **once**. Both wrappers
     * derive from the same plaintext read, which is why this must be one call:
     * [setUpLocalUnlock] clears the plaintext, so calling it then
     * [setUpBiometricUnlock] would starve the second of its payload.
     *
     * The mandatory [passphrase] parameter is the structural enforcement of the
     * floor — biometric unlock can never exist without a recovery passphrase
     * (`docs/proposals/desktop-masterkeystore.md`; the passphrase is the way back
     * in if the biometric credential is ever lost — re-sign, new device, OS
     * change). The UI enrolls [biometricWrapKey] with the platform delegate
     * **first** (enroll-first), so a delegate failure leaves the proxy untouched.
     *
     * Returns `false` when no plaintext phrase is available (caller must
     * initialize first via createRealm / recoveryClaim / pairing).
     */
    fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): Boolean

    /**
     * Decrypts the persisted unlock wrapper with [passphrase] and loads
     * the recovery phrase + master keys back into memory. Returns
     * `false` when no wrapper exists or the passphrase doesn't decrypt.
     * After a successful call, [currentMasterKeyPair] is populated and
     * [getUnlockStatus] reports `isLocked = false`.
     */
    fun unlockLocal(passphrase: String): Boolean

    /**
     * Re-seals the realm: purges the in-memory derived keys, master key,
     * realm-encryption key and recovery phrase so [getUnlockStatus] reports
     * `isLocked = true` again, leaving the persisted unlock wrapper and the
     * device signing key untouched. The inverse of [unlockLocal] — backs a
     * user-initiated "Lock now". Idempotent: locking an already-sealed (or
     * never-unlocked) realm is a no-op. The in-process sync loop self-heals,
     * idling once it observes the cleared keys on the next tick.
     */
    fun lock()

    /**
     * Biometric counterpart to [setUpLocalUnlock] per the tech-debt entry
     * "Android Biometric Unlock — Missing, Undermines the Onboarding-Low-
     * Friction UX" + proposal §4.1. Wraps the persisted plaintext recovery
     * phrase under [wrapKey] (a 32-byte AES key the UI biometric-protects
     * separately via `BiometricUnlockDelegate`) and persists the result.
     *
     * Caller contract: the UI generates [wrapKey], enrolls it with the
     * platform biometric delegate **first**, and only then calls this — so
     * a delegate failure (cancelled prompt, no biometric enrolled, etc.)
     * leaves the proxy untouched and the user can simply retry.
     *
     * Coexists with [setUpLocalUnlock]: when a passphrase wrapper is
     * already present, this only adds the biometric slot and leaves the
     * passphrase wrapper alone (per §4.1: biometric is the primary surface,
     * passphrase is the fallback). When no passphrase wrapper exists, this
     * is the first-time setup path and also deletes the plaintext
     * `RECOVERY_PHRASE` slot, matching `setUpLocalUnlock`'s contract.
     *
     * Returns `false` when no plaintext phrase is available (caller must
     * initialize first via createRealm / recoveryClaim / pairing).
     */
    fun setUpBiometricUnlock(wrapKey: ByteArray): Boolean

    /**
     * Decrypts the persisted biometric wrapper with [wrapKey] and loads
     * the recovery phrase + master keys back into memory. The caller
     * obtains [wrapKey] by running the platform biometric delegate's
     * unwrap path (BiometricPrompt → Keystore-backed Cipher).
     *
     * Returns `false` when no biometric wrapper exists or the wrap-key
     * doesn't decrypt it. After a successful call,
     * [currentMasterKeyPair] is populated and [getUnlockStatus] reports
     * `isLocked = false`.
     */
    fun unlockBiometric(wrapKey: ByteArray): Boolean

    /**
     * Removes the biometric unlock wrapper. Used when the user disables
     * biometric unlock from Settings, or when the platform delegate's
     * blob has been invalidated (e.g. by a biometric re-enrollment that
     * tripped `setInvalidatedByBiometricEnrollment(true)` on the Keystore
     * key). Idempotent; the passphrase wrapper (if any) is untouched.
     */
    fun clearBiometricSetup()

    /**
     * Turns the **"Encrypt on unlock only"** hard mode back off
     * (`docs/proposals/control-keep-device-unlocked.md` §4.2, toggle 2 — the
     * inverse of [setUpUnlock]). Re-persists the plaintext A1 payload from the
     * currently *unlocked* session (the unlock that opened it already re-seeded the
     * plaintext slot) and removes both the passphrase (`UNLOCK_WRAPPER`) and
     * biometric (`BIOMETRIC_WRAPPER`) wrappers, so `init`'s autoload gate fires on
     * the next cold start and the realm boots **headless** — background sync
     * resumes with no app-level unlock.
     *
     * Returns `false` when the realm is **locked** (no in-memory plaintext to fall
     * back on) — dropping the wrappers then would strand the realm unrecoverably;
     * the caller must be inside an unlocked session. Idempotent on an
     * already-headless realm. The biometric Keystore key is cleared separately by
     * the UI delegate. Default no-op (`false`) so non-proxy impls + test fakes keep
     * compiling.
     */
    fun relaxToHeadless(): Boolean = false

    /**
     * Purge **every** at-rest slot of this device's realm identity, so the next launch is a
     * genuinely fresh device (`ForgetThisDevice`). Platform-agnostic twin of iOS's
     * `ControlSurface.wipeDeviceState` custody purge.
     *
     * ⚠⚠ Distinct from [discardSupersededRealm], which fires only when *joining a different realm*
     * and deliberately keeps `DEVICE_ID` / `DEVICE_SIGNING_KEY` — the device stays itself, it just
     * changes realm. This clears those too, because the whole point is to stop being this device:
     * under option F a removed identity never returns, so rejoining needs a NEW `deviceId`.
     *
     * ⚠ Does **not** touch the object store — that is [wipeLocalStore], called separately because it
     * suspends. Leaves in-memory key material cleared so the running session cannot keep signing
     * with keys that no longer exist at rest.
     */
    fun forgetThisDevice()

    /**
     * Drop this device's local copy of realm data (`ios-identity-reinstall-gate.md` D3).
     *
     * ⚠⚠ A discard that keeps the store is not a smaller discard, it is a **broken** one: custody is
     * cleared, the process keeps running, `currentDeviceId` mints a fresh id registered with no
     * realm, and the surviving store keeps the app looking signed-in to a realm it has already left —
     * `401` on every push, a growing outbound queue, and no way back except deleting the data
     * directory by hand.
     *
     * @return `false` when the store offers no wipe ([WipeableObjectStore] is an optional capability,
     * so an unimplemented one must be **reported**, never silently skipped — the caller folds this
     * into its user-visible outcome).
     */
    suspend fun wipeLocalStore(): Boolean

    /**
     * Purge every at-rest artifact of a *superseded* realm after this device adopts a
     * different one via pairing (the genesis-then-join case: a device that ran its own
     * `CreateRealm`, or paired to another owner, and then joins a new realm).
     *
     * Pairing is adopt-not-merge: the joiner switches to the host's realm. But the old
     * realm's `RECOVERY_PHRASE` (and any unlock wrapper that wraps it) outlive the join,
     * and both the construction-time rehydrate (`init`'s `phrase`-first `when`) and the
     * unlock-wrap selection (`readWrappablePayload`'s `phrase`-first order) prefer that
     * stale phrase over the freshly-written joiner payload — silently reverting the
     * device back onto its own, now-orphaned realm on the next restart or unlock (it
     * then pushes/pulls the wrong owner's stream, so sync appears one-directional).
     *
     * Called from `PairingCompletion` only when the adopted owner differs from the prior
     * one, so a fresh joiner (no prior realm) and a re-pair to the *same* realm are both
     * no-ops on the credential. Clears `RECOVERY_PHRASE` + both unlock wrappers (the lock
     * is rebound to the joined realm by re-running unlock setup), resets the own-realm
     * relay latches (`realmRelayRegistered` + the pull cursor — the joined realm starts
     * its own), and drops the stale in-memory master keypair (a joiner holds no A1). The
     * just-written joiner payload / ownerId and the stable device signing key are kept.
     */
    fun discardSupersededRealm()

    /**
     * State of the local-unlock primitive — read by the landing-state
     * router on app launch to decide between Active / Locked. `isSetUp`
     * is true when **either** a passphrase or biometric wrapper has been
     * persisted; `isLocked` is true when a wrapper exists but the master
     * keys are not loaded. `hasPassphrase` is true when a passphrase
     * (`UNLOCK_WRAPPER`) specifically exists — Settings uses it to enforce
     * the passphrase floor before enabling biometric.
     */
    data class UnlockStatus(
        val isSetUp: Boolean,
        val isLocked: Boolean,
        val hasPassphrase: Boolean = false,
    )

    fun getUnlockStatus(): UnlockStatus

    /**
     * Initializes the sync manager with raw derived keys received via the pairing
     * handshake. [ownerId] is the host realm's ownerId — Device B (the new device)
     * adopts it so its subsequent JWTs verify against the relay's `KnownDevicesStore`
     * entry that `POST /sync/pairing/response` registered as `(ownerId, this.deviceId)`.
     * The ownerId comes from the pairing code's URL (`ownerId=...` query param) and
     * is plumbed through `startHandshake → GetPairingStatusImplementation`.
     *
     * [realmEncryptionKeyPair] is the realm's A13 (X25519 encryption sub-key,
     * `tenshu-rei.md` §2.3), delivered to the joiner in the same pairing key-blob
     * (`sync-multi-realm-runtime.md` §3.1 option B). A paired joiner never
     * receives the recovery phrase, so it cannot *derive* A13 — it must be handed it
     * so it can unwrap shared-realm master keys (`Kr`) wrapped to A13. `null` when the
     * host couldn't supply it (e.g. locked, no phrase available) — the joiner then
     * can't join shared realms but personal sync is unaffected.
     */
    fun initializeWithKeys(
        keys: SyncCryptoManager.DerivedKeys,
        ownerId: String,
        realmEncryptionKeyPair: SyncCryptoManager.KeyPair? = null,
        admissionKeyPair: SyncCryptoManager.KeyPair? = null,
        admissionCertChain: List<AdmissionCapabilityCert> = emptyList(),
    )

    /**
     * This device's **admission keypair** (asset A33,
     * `delegated-device-admission-authority.md` §4.1) — delivered via the
     * pairing key-blob v2 on a joiner, `null` on a device that never received
     * one (an A1-holder admits with the master key directly and needs no A33;
     * a joiner paired before admission keys existed has none and remains a
     * non-admitter). Paired with [getAdmissionCertChain]; both present or both absent.
     */
    fun getAdmissionKeyPair(): SyncCryptoManager.KeyPair? = null

    /**
     * The capability-cert chain (leaf → A1-signed root) vouching for this
     * device's [getAdmissionKeyPair] — presented to the relay when this device
     * admits the next one, and extended by one link for each child it mints.
     * Empty when the device holds no admission capability.
     */
    fun getAdmissionCertChain(): List<AdmissionCapabilityCert> = emptyList()

    /**
     * Install (or replace) this device's admission material outside the
     * pairing flow — the migration/re-root path
     * (`delegated-device-admission-authority.md` §8): a joiner without one
     * self-mints [keyPair] (empty [chain]) and later installs the A1-holder's
     * granted chain; a re-rooted device swaps a deep chain for a flat one.
     * Persists alongside the joiner payload where the impl supports it, so
     * admitter status survives restarts. Default no-op so test fakes and
     * non-proxy impls keep compiling.
     */
    fun installAdmissionMaterial(
        keyPair: SyncCryptoManager.KeyPair,
        chain: List<AdmissionCapabilityCert>,
    ) = Unit

    /**
     * The realm's A13 (X25519 encryption sub-key, `tenshu-rei.md` §2.3) for this
     * device, or `null` if unavailable. On a **host** (phrase-holder) it is derived
     * on demand from the recovery phrase; on a **joiner** it is the A13 delivered via
     * the pairing key-blob ([initializeWithKeys]). Used to unwrap shared-realm master
     * keys (`Kr`) — the auto-join observer + the shared-realm bootstrapper resolve A13
     * through this accessor so both phrase-holding and paired devices participate.
     * Default `null` so test fakes that don't exercise shared realms keep compiling.
     */
    fun getRealmEncryptionKeyPair(): SyncCryptoManager.KeyPair? = null

    /**
     * Returns whether synchronization is enabled.
     */
    fun isEnabled(): Boolean

    /**
     * Returns the derived keys used for E2EE synchronization.
     */
    fun getDerivedKeys(): SyncCryptoManager.DerivedKeys?

    /**
     * Returns the device's persistent Ed25519 signing keypair (asset A4 in
     * `docs/sync/threat-model.md`). Used to sign relay-side JWTs per
     * `sync-backend-relay-plan.md` §4.1. Lazy-initialized on first call.
     *
     * ⚠ **Persisted at rest** via `SecureStorageDelegate`, so the device keeps one stable
     * signing identity across restarts (`SyncManagerImpl.getDeviceSigningKeyPair`). It has to
     * be: the relay's device registry is durable (`SqliteDeviceRegistry`), so a regenerated
     * key would break established relay-side trust rather than merely re-register.
     */
    fun getDeviceSigningKeyPair(): SyncCryptoManager.KeyPair

    /**
     * Realm master identity Ed25519 keypair (asset A1 in `docs/sync/threat-model.md`).
     * Derived from the recovery phrase during [initialize]; null before that. Used to
     * sign master delegations during pairing / recovery (`sync-relay-api.md` §3.3, §3.4).
     *
     * Per the threat model, the master key is held only in memory while needed —
     * deliberately not persisted across app restarts. After a restart the user must
     * re-enter the recovery code to re-derive it.
     */
    val currentMasterKeyPair: SyncCryptoManager.KeyPair?

    /**
     * Bundles this device's current realm state into a single [RealmContext] —
     * the seam for the multi-realm-per-device runtime
     * (`docs/proposals/sync-multi-realm-runtime.md`).
     *
     * Returns `null` until the realm is enabled (same gate as [isEnabled] —
     * `derivedKeys != null` — plus a non-null [currentOwnerId]); on a joiner the
     * bundle's `masterKeyPair` is `null` (A1 is host-only). The default
     * implementation is a pure read-model composing the existing single-realm
     * accessors, so every impl + test fake inherits it. The multi-realm emit/ingest
     * path reads `RealmContext`s held in a registry (one per realm), not these
     * single-valued accessors.
     */
    fun currentRealmContext(): RealmContext? {
        val ownerId = currentOwnerId ?: return null
        val keys = getDerivedKeys() ?: return null
        return RealmContext(
            ownerId = ownerId,
            deviceId = currentDeviceId,
            derivedKeys = keys,
            deviceSigningKeyPair = getDeviceSigningKeyPair(),
            masterKeyPair = currentMasterKeyPair,
        )
    }

    /**
     * Reseats the realm master keypair (asset A1) and ownerId after a
     * page-reload unlock path has restored it from at-rest storage —
     * the web SDK's WebAuthn-PRF-driven unwrap of a wrapped master key
     * per `docs/web/architecture.md` §4.4 / `docs/web/roadmap.md` Phase 5.
     *
     * Distinct from [initialize] in that it does **not** persist or
     * re-derive a recovery phrase: the caller has already proved possession
     * of the keypair via the unwrap step. Distinct from [initializeWithKeys]
     * in that it carries asset A1 (the master keypair) rather than the
     * derived shared keys (asset A2/A3) — a recovering device, not a
     * pairing handshake.
     *
     * The ownerId is rederived from [keyPair.publicKey] via the canonical
     * `ownerIdForMasterPublicKey(...)` mapping (threat-model §A1) so the
     * relay's `KnownDevicesStore` lookup keys match what `CreateRealm`
     * registered originally. JWT issuance picks up the new ownerId on
     * the next request.
     */
    fun reseatMasterKeyPair(keyPair: SyncCryptoManager.KeyPair)

    /**
     * Human-readable name for this device, set during pairing/recovery and used when
     * this device pushes its own `Device` entity into the synced DB per
     * `docs/adr/0007-sync-device-management.md` step 5. Null before pairing/recovery.
     */
    val currentDeviceName: String?

    /**
     * Setter complement for [currentDeviceName]. Called by `InitiatePairing` (Device B's
     * pairing entry point), `CreateRealm` and `RecoveryClaim`.
     */
    fun setCurrentDeviceName(name: String)

    /**
     * Current realm epoch. Per `docs/sync/threat-model.md` T-K-02, every `Device` entity
     * block carries the realm epoch under which the device was admitted; on revocation,
     * the epoch advances and other realm devices reject blocks signed by the revoked
     * device with a post-revocation HLC.
     *
     * Derivation is **local + deterministic**: the count of `DeleteBlock`s observed for
     * `Device` entities (per `docs/adr/0007-sync-device-management.md` §"Realm-state data" — option (b),
     * derived cache, no synced `RealmState` entity). All devices converge on the same
     * value once tombstones propagate; until then they have a stale view, the same way
     * they have a stale view of any other Syncable entity.
     *
     * Wired in [org.tenshu.sync.proxy.initSyncService] from the proxy-side
     * `TombstoneRegistry` via [setRealmEpochProvider]; defaults to `0L` until that runs
     * (so bootstrap operations like `CreateRealm` and the very first `RecoveryClaim`
     * see epoch 0, which is correct for genesis).
     */
    val currentRealmEpoch: Long

    /**
     * Wires the [currentRealmEpoch] derivation. Called once during proxy initialization
     * after the `TombstoneRegistry` exists; the provider closure reads from it on every
     * `currentRealmEpoch` access (no cached value to invalidate).
     */
    fun setRealmEpochProvider(provider: () -> Long)

    /**
     * Seeds the realm-epoch floor for a freshly-recovered (or pairing-claim) device that
     * fetched the relay's authoritative epoch out-of-band per
     * [SyncRelay.fetchRealmEpoch]. The effective [currentRealmEpoch] becomes
     * `max(provider(), seeded)` — once the receiver's tombstones drain past the seeded
     * value, the local provider wins again and the seed is harmless. Closes the
     * recovery-race carve-out documented in `docs/adr/0007-sync-device-management.md`'s
     * status header by giving the merge-time T-K-02 check a meaningful floor before
     * tombstones land.
     *
     * Called from `RecoveryClaimImplementation` after a successful claim (with the
     * relay-fetched epoch). Bootstrap impls that legitimately want epoch 0 (e.g.
     * `CreateRealm` — genesis) simply don't call it. Idempotent: re-seeding with a
     * higher value advances the floor; re-seeding with a lower value is a no-op.
     */
    fun seedCurrentRealmEpoch(epoch: Long)

    /**
     * Observable HLC-piggyback divergence warning state (per
     * `docs/proposals/sync-backend-relay-plan.md` §5.1 / `docs/sync/threat-model.md`
     * §11.3 / T-T-04). Backed by `HlcDivergenceTracker` after [setDivergenceWarningSource]
     * runs; emits an `Any?` (the concrete type is `org.tenshu.sync.proxy.DivergenceWarning`,
     * but the proxy-side type isn't visible to `:sync-service-common` so callers downcast
     * if they need the variants). UI consumers observe non-null to surface a "your relay
     * may be lying" warning.
     *
     * Defaults to a flow that's perpetually `null` until proxy init wires the real source.
     */
    val divergenceWarning: StateFlow<Any?>

    /**
     * Wires the [divergenceWarning] flow to a real source (production: the proxy's
     * `HlcDivergenceTracker.warning`). Mirrors the [setRealmEpochProvider] late-binding
     * pattern — the tracker exists only after `initSyncService` runs, but the SyncManager
     * is constructed earlier in the DI graph.
     */
    fun setDivergenceWarningSource(source: StateFlow<Any?>)

    /**
     * Generates a new pairing context for linking another device.
     */
    fun generatePairingContext(): PairingContext

    /**
     * Clears the current active pairing context and stops the handshake session.
     */
    fun stopPairing()

    /**
     * Current active pairing context, if any.
     */
    val activePairingContext: PairingContext?

    /**
     * Stores a pending handshake session (as Device B). [hostOwnerId] is the realm
     * ownerId from the pairing-code URL — Device B adopts it as its own [currentOwnerId]
     * once `GetPairingStatus` succeeds (so its JWT-iss matches what the relay's
     * `KnownDevicesStore` has registered).
     */
    fun startHandshake(
        pairingSecret: String,
        myKeyPair: SyncCryptoManager.KeyPair,
        hostOwnerId: String
    )

    /**
     * Retrieves the pending handshake session, if any.
     */
    fun getHandshake(pairingSecret: String): HandshakeContext?

    /**
     * Per-handshake state held until [GetPairingStatus] consumes it. Carries Device B's
     * ephemeral keypair AND the host realm's ownerId so the on-success path can
     * `initializeWithKeys(keys, ownerId)` with the right ownerId.
     */
    data class HandshakeContext(
        val keyPair: SyncCryptoManager.KeyPair,
        val hostOwnerId: String
    )

    /**
     * Clears a pending handshake.
     */
    fun clearHandshake(pairingSecret: String)

    /**
     * Returns the formatted pairing string for the current active context.
     */
    fun getPairingString(): String?

    class PairingContext(
        val deviceId: String,
        val ownerId: String,
        val publicKey: ByteArray,
        val privateKey: ByteArray,
        val secret: String
    ) {
        /**
         * What the host put in front of the user for one joiner: the request the SAS was derived
         * from, and the SAS. The keys are private copies — no caller can change a pin by mutating
         * an array it still holds.
         */
        class ShownRequest(
            val deviceId: String,
            val deviceName: String,
            publicKey: ByteArray,
            signingPublicKey: ByteArray,
            val sas: String,
        ) {
            val publicKey: ByteArray = publicKey.copyOf()
            val signingPublicKey: ByteArray = signingPublicKey.copyOf()

            /** True when a relay-served request carries exactly the keys this pin stands for. */
            fun hasKeys(publicKey: ByteArray, signingPublicKey: ByteArray): Boolean =
                this.publicKey.contentEquals(publicKey) &&
                    this.signingPublicKey.contentEquals(signingPublicKey)
        }

        @OptIn(ExperimentalAtomicApi::class)
        private val shownRequests = AtomicReference<Map<String, ShownRequest>>(emptyMap())

        /**
         * Pin [candidate] as what the user is being shown for its `deviceId`, and return the pin
         * that stands — [candidate] itself, or the one already there.
         *
         * ⚠⚠ **This is the binding between the SAS and the admission** (threat model T-P-14):
         * `ApprovePairingRequest` admits the pinned keys and takes nothing from the relay, so the
         * code the user compared is the code of the keys that get the realm.
         *
         * ⚠⚠ **First pin wins, for the life of this context.** The UI replaces its request from
         * every poll, so a later poll that re-pointed the pin would leave code X on screen over
         * key Y for as long as its answer was in flight. A `deviceId` that turns up with different
         * keys needs a new session, i.e. a new context. Compare-and-set, because two polls may
         * overlap and must agree on one tuple.
         *
         * The pins are fields of this object and so share its lifetime exactly: `stopPairing`
         * drops the context, and a poll still in flight for it pins into an object nothing reads.
         */
        @OptIn(ExperimentalAtomicApi::class)
        fun pinShownRequest(candidate: ShownRequest): ShownRequest {
            while (true) {
                val current = shownRequests.load()
                current[candidate.deviceId]?.let { return it }
                if (shownRequests.compareAndSet(current, current + (candidate.deviceId to candidate))) {
                    return candidate
                }
            }
        }

        /** The pin for [deviceId], or null when this session never showed a SAS for it. */
        @OptIn(ExperimentalAtomicApi::class)
        fun shownRequest(deviceId: String): ShownRequest? = shownRequests.load()[deviceId]

        @OptIn(ExperimentalAtomicApi::class)
        private val admissions = AtomicReference<Map<String, Any>>(emptyMap())

        /**
         * The admission material approved for [deviceId]'s pin — minted by [mint] on the first
         * approve, then returned **unchanged** to every later approve in this session.
         *
         * ⚠⚠ Why: minting fresh material per approve (a fresh A33 capability inside a fresh blob)
         * breaks a retry. If the first approve lands but its replies are lost and the joiner acks
         * it, the second approve reads the relay's "consumed" as delivered and records its own
         * capability on the `Device` row — one the joiner never received, so a later revocation
         * naming it would miss the capability the joiner actually holds. With one mint per pin,
         * every post for this pin carries identical material and "consumed" means the joiner holds
         * what we record.
         *
         * Opaque here (`:sync-service-common` cannot see the proxy's admission type); the value
         * must carry no private key in clear — the proxy caches only the sealed blob and public
         * material. Shares this context's lifetime like the pins. First mint wins under a race.
         */
        @OptIn(ExperimentalAtomicApi::class)
        fun <T : Any> admissionFor(deviceId: String, mint: () -> T): T {
            admissions.load()[deviceId]?.let {
                @Suppress("UNCHECKED_CAST")
                return it as T
            }
            val candidate = mint()
            while (true) {
                val current = admissions.load()
                current[deviceId]?.let {
                    @Suppress("UNCHECKED_CAST")
                    return it as T
                }
                if (admissions.compareAndSet(current, current + (deviceId to candidate))) return candidate
            }
        }

        @OptIn(ExperimentalAtomicApi::class)
        private val responsePosts = AtomicReference<List<String>>(emptyList())

        /**
         * Record that a `POST /sync/pairing/response` for [deviceId] is about to be sent under this
         * session's rendezvous, and answer whether **every earlier** post in this session was for
         * [deviceId] too (and there was at least one).
         *
         * ⚠ That answer is what lets the caller read the relay's "already consumed" as
         * *delivered*: the session's hash was acked by a joiner, and if the only material ever
         * posted under it is this pin's ([admissionFor]), the joiner holds exactly that. If
         * another `deviceId` was posted under the same hash first, the ack may have been its.
         */
        @OptIn(ExperimentalAtomicApi::class)
        fun recordResponsePost(deviceId: String): Boolean {
            while (true) {
                val earlier = responsePosts.load()
                if (responsePosts.compareAndSet(earlier, earlier + deviceId)) {
                    return earlier.isNotEmpty() && earlier.all { it == deviceId }
                }
            }
        }
    }

    // ── Offer flow (sync-pairing-inverted-direction.md) ──────────────────────────
    // Two per-session states, both keyed by the pairingSecretHash so the offerer poll
    // and the responder approve can find their ephemeral private key across op calls
    // WITHOUT it ever crossing the op/IPC boundary (privB/privA stay proxy-side).

    /**
     * Offerer (Device B / new device) session: the ephemeral X25519 keypair whose private
     * half decrypts the responder's sealed payload, plus the self-chosen device name.
     * Default no-ops so test fakes + non-proxy impls keep compiling.
     */
    fun startOfferSession(
        pairingSecretHash: String,
        ephemeralKeyPair: SyncCryptoManager.KeyPair,
        deviceName: String,
    ) = Unit

    fun getOfferSession(pairingSecretHash: String): OfferSession? = null

    fun clearOfferSession(pairingSecretHash: String) = Unit

    /**
     * Pin the responder's ephemeral key from its **scanned announce** (A2 of
     * `sync-pairing-scanned-announce.md`).
     *
     * ⚠⚠ This is the security half of the announce, not bookkeeping. Once pinned,
     * `GetOfferStatus` refuses a terminal `approved` whose `publicKeyA` differs — **before**
     * decrypting or installing anything. That upgrades T-P-07 from *detect-after-install* (the
     * post-hoc SAS, which only works if a human compares two codes) to *reject-before-install*.
     * Without a pin there is no lock, which is the path for a responder that sends no announce.
     */
    fun pinAnnouncedOfferKey(pairingSecretHash: String, publicKeyA: ByteArray) = Unit

    data class OfferSession(
        val ephemeralKeyPair: SyncCryptoManager.KeyPair,
        val deviceName: String,
        /**
         * The responder's ephemeral key as announced pre-approval, or `null` when no announce was
         * seen (an old responder, or a relay that has not been upgraded). ⚠ `null` means "no lock
         * available", never "any key is fine" — the difference matters at the comparison site.
         */
        val announcedPublicKeyA: ByteArray? = null,
    )

    /**
     * Responder (Device A / existing device) session: stashed between `RespondToPairingOffer`
     * (which parses the scanned QR + derives the SAS) and `ApprovePairingOffer`/`DenyPairingOffer`.
     * Holds the responder's ephemeral keypair (privA seals the payload) plus everything scanned
     * from the offerer's QR that approval needs — its ephemeral key, its Ed25519 signing key +
     * device id (for the relay-side registration delegation), and its name.
     */
    fun startResponderSession(
        pairingSecretHash: String,
        ephemeralKeyPair: SyncCryptoManager.KeyPair,
        offererPublicKey: ByteArray,
        offererSigningKey: ByteArray,
        offererDeviceId: String,
        deviceName: String,
    ) = Unit

    fun getResponderSession(pairingSecretHash: String): ResponderSession? = null

    fun clearResponderSession(pairingSecretHash: String) = Unit

    data class ResponderSession(
        val ephemeralKeyPair: SyncCryptoManager.KeyPair,
        val offererPublicKey: ByteArray,
        val offererSigningKey: ByteArray,
        val offererDeviceId: String,
        val deviceName: String,
    )

    companion object {
        const val ID = "tenshu/sync/manager"
    }
}

/** The states of [SyncManager.firstPullMarker]; absent is `null`. */
enum class FirstPullMarker {
    /** A first forward pull was begun and has not finished — including a kill mid-backlog. */
    STARTED,

    /** A forward pull finished with nothing more to come on any lane. */
    COMPLETED,
}
