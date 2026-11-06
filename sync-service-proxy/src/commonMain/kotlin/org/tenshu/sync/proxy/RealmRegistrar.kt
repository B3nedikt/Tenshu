package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.pairingDelegationMessage
import org.tenshu.sync.common.realmBootstrapMessage

/**
 * Performs the first-device realm genesis on the relay — `POST /sync/realm`, the
 * bootstrap previously inlined in `CreateRealmImplementation`. Extracted so it can
 * run at two moments (local-first reframe, ADR 0019):
 *
 *  - **eagerly** by `CreateRealm`, when a storage subscription is already bound (or
 *    in test/legacy wirings where storage gating is absent — the default), and
 *  - **lazily** by `SyncScheduler`, the first time personal-realm relay traffic is
 *    unlocked. A realm created without a subscription stays local-only; the genesis
 *    is deferred until the user subscribes, then runs on the next sync tick.
 *
 * Signs the canonical bootstrap message with the **in-memory** master keypair
 * ([SyncManager.currentMasterKeyPair]), so no recovery phrase is needed at the
 * deferred call (the phrase isn't persisted in cleartext; the master keypair is
 * reseated into memory on unlock).
 *
 * **Runs at most once ever.** Success sets the persistent
 * [SyncManager.realmRelayRegistered] latch, so the genesis is never re-attempted
 * across client restarts — the relay still holds the realm, so a re-`POST` would
 * only 409 (and 409 must stay a failure for `ReregisterDeviceImplementation`'s
 * admit-fallback). Relay *amnesia* (an in-memory relay losing its registry) is a
 * different failure recovered by `ReregisterDevice` on a 401, not by re-running
 * this genesis. A device that has already synced (`lastSyncTimestamp > 0`) is
 * treated as registered too. ⚠ Load-bearing for a **joiner**: it never runs genesis, and this
 * is where it latches [SyncManager.realmRelayRegistered] after its first pull (see
 * `SyncManagerImpl.discardSupersededRealm`), which keeps its pulls open (`SyncScheduler`'s
 * `pullAllowed`) once its own push gate closes.
 *
 * A genuine failure (offline) leaves the latch unset so a later tick retries.
 *
 * NB: distinct from `RealmRegistry` (the multi-realm registry for shared realms).
 * This is the *personal* realm's one-time relay genesis.
 */
class RealmRegistrar(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
) {
    /**
     * Registers the personal realm on the relay unless it already has been.
     * Returns the relay result, or `null` when nothing was sent — already
     * registered, no realm derived (`!isEnabled()`), or the session is locked
     * ([SyncManager.currentMasterKeyPair] is null) — in which case the caller
     * simply retries on a later tick.
     */
    suspend fun ensureRegistered(): TResult<Data?>? {
        if (!syncManager.isEnabled()) return null

        // Once-ever: the persistent latch, plus a prior successful pull
        // (lastSyncTimestamp > 0) which proves the realm + this device are already
        // registered — how a joiner, which never runs genesis, latches. Either ⇒ skip.
        if (syncManager.realmRelayRegistered || syncManager.lastSyncTimestamp > 0) {
            syncManager.realmRelayRegistered = true
            return null
        }

        val master = syncManager.currentMasterKeyPair ?: return null

        val deviceKeyPair = syncManager.getDeviceSigningKeyPair()
        val deviceId = syncManager.currentDeviceId
        val ownerId = ownerIdForMasterPublicKey(master.publicKey)

        // Canonical bootstrap message — the shared `realmBootstrapMessage` builder the relay verifies.
        val message = realmBootstrapMessage(ownerId, master.publicKey, deviceKeyPair.publicKey, deviceId)
        val signature = cryptoManager.signEd25519(master.privateKey, message)

        val result = relay.createRealm(
            ownerId = ownerId,
            masterPublicKey = master.publicKey,
            firstDevicePublicKey = deviceKeyPair.publicKey,
            firstDeviceId = deviceId,
            masterSelfSignature = signature,
            admittedAtHlc = syncManager.admissionStamp(),
        )
        // ⚠⚠ A `409` here is SUCCESS, not failure: `ownerId` is derived from the master public key,
        // so "a realm already exists for this ownerId" means *our own* realm is registered — the
        // state this function exists to reach. Reporting it as a failure made genesis look broken
        // whenever anything else registered first, and something else always can: the scheduler
        // calls this as soon as pushes are allowed, while `CreateRealmImplementation` holds a
        // DIFFERENT registrar instance with its own latch. `BootstrapSystemTest` caught it on
        // 2026-08-18 — `createRealm should succeed; got … 409 Conflict — Realm already exists`.
        // ⚠ The latch is set for a `409` too: our realm IS registered, so the once-ever work is
        // done. The result is returned unchanged — this class has no `TContext` to build a success
        // from; the caller that does ([CreateRealmImplementation]) maps it.
        if (result !is TResult.Failure<*> || result.isRealmAlreadyExistsFailure() || result.isRealmErasedFailure()) {
            syncManager.realmRelayRegistered = true
        }
        return result
    }

    /**
     * This device's master identity (A1) if it holds one — in-memory first, else derived from
     * the stored recovery phrase. `null` for a pairing **joiner**, which received derived keys
     * and a one-shot admission delegation but never the master key.
     *
     * A1 is exactly the capability that separates "can re-assert its own relay registration"
     * from "must be re-admitted by another device", so every caller of [reassertGenesis] /
     * [reregisterThisDevice] gates on this being non-null.
     */
    fun masterIdentity(): SyncCryptoManager.KeyPair? =
        syncManager.currentMasterKeyPair
            ?: syncManager.getRecoveryPhrase()?.let { cryptoManager.deriveMasterKeyPair(it) }

    /**
     * Re-assert this realm's genesis on the relay: `POST /sync/realm` signed with [masterKeyPair],
     * the same unauthenticated **master-capability** endpoint the owner used at genesis. Grants no
     * new authority — the relay verifies the master self-signature exactly as it did the first time.
     *
     * The single home for the canonical bootstrap message
     * (`ownerId || masterPublicKey || firstDevicePublicKey || firstDeviceId`), which must equal the
     * relay's `MasterDelegationVerifier.realmBootstrapMessage` **byte-for-byte**. It used to be
     * spelled out in four places ([ensureRegistered], `ReregisterDeviceImplementation`, and both
     * pairing-approve self-heals); four copies of a byte-exact protocol message is a drift hazard
     * whose failure mode is a 401 that looks like a key problem.
     *
     * A `409` (the realm record still exists) comes back as a [TResult.Failure] **on purpose** —
     * [reregisterThisDevice] keys its admit-device fallback on exactly that.
     */
    suspend fun reassertGenesis(masterKeyPair: SyncCryptoManager.KeyPair): TResult<Data?> {
        val deviceKeyPair = syncManager.getDeviceSigningKeyPair()
        val deviceId = syncManager.currentDeviceId
        val ownerId = ownerIdForMasterPublicKey(masterKeyPair.publicKey)
        val message = realmBootstrapMessage(ownerId, masterKeyPair.publicKey, deviceKeyPair.publicKey, deviceId)
        val signature = cryptoManager.signEd25519(masterKeyPair.privateKey, message)
        return relay.createRealm(
            ownerId = ownerId,
            masterPublicKey = masterKeyPair.publicKey,
            firstDevicePublicKey = deviceKeyPair.publicKey,
            firstDeviceId = deviceId,
            masterSelfSignature = signature,
            admittedAtHlc = syncManager.admissionStamp(),
        )
    }

    /**
     * Restore this device's relay registration after the relay lost it — a restore from a backup
     * predating this device's admission, an operator data wipe, a move to a different relay host,
     * or (historically) an in-memory relay restarting under a long-lived daemon.
     *
     * Two steps, because the relay can have forgotten either the realm or just this device:
     *  1. [reassertGenesis] — recreates the realm record with this device as first device.
     *     Deterministic (same `ownerId` + master key), so a relay that lost everything is
     *     fully repaired here.
     *  2. On failure — most likely a `409`, i.e. the realm exists but this device is not in
     *     `KnownDevicesStore` — re-admit self with a master-signed delegation over
     *     `devicePublicKey || deviceId || epoch(8 BE)`, the shape `POST /sync/admit-device`
     *     verifies. A genuine network failure fails both calls and returns the second's error,
     *     which the caller retries.
     *
     * Idempotent and safe to retry. The body of `ReregisterDeviceImplementation`, lifted here so
     * the [SyncScheduler] can run the same repair on a sustained sync-path 401 without going
     * through the operation layer.
     */
    suspend fun reregisterThisDevice(masterKeyPair: SyncCryptoManager.KeyPair): TResult<Data?> {
        val genesis = reassertGenesis(masterKeyPair)
        if (genesis !is TResult.Failure<*>) {
            syncManager.realmRelayRegistered = true
            return genesis
        }

        val admit = admitDevice(
            masterKeyPair = masterKeyPair,
            deviceId = syncManager.currentDeviceId,
            devicePublicKey = syncManager.getDeviceSigningKeyPair().publicKey,
        )
        if (admit !is TResult.Failure<*>) {
            // ⚠ Only for the SELF case. `realmRelayRegistered` is a claim about *this* device, so
            // [admitDevice] must never set it — re-admitting a peer says nothing about us.
            syncManager.realmRelayRegistered = true
        }
        return admit
    }

    /**
     * Admit `deviceId` to this realm on the relay with a master-signed delegation — the second
     * half of [reregisterThisDevice], lifted so it can also admit a **peer**.
     *
     * ⚠⚠ **The single home for the delegation message** `devicePublicKey || deviceId || epoch(8
     * BE)`, which must equal what `POST /sync/admit-device` verifies **byte-for-byte** — the same
     * reason [reassertGenesis] owns the bootstrap message. A second copy of a byte-exact protocol
     * message is a drift hazard whose failure mode is a `401` that looks like a key problem.
     *
     * Grants no new authority: it is the same delegation, signed by the same A1-holder, that
     * admission has always used. Idempotent, so re-admitting an already-registered device is a
     * cheap no-op rather than an error — which is what lets a caller offer it speculatively.
     *
     * ⚠ Signs at [SyncManager.currentRealmEpoch]. A revocation raises the epoch past itself
     * precisely so a later re-admission outranks it (`RevokeDeviceImplementation` step 4), so
     * reading the epoch here rather than caching it is load-bearing.
     */
    suspend fun admitDevice(
        masterKeyPair: SyncCryptoManager.KeyPair,
        deviceId: String,
        devicePublicKey: ByteArray,
    ): TResult<Data?> {
        val ownerId = ownerIdForMasterPublicKey(masterKeyPair.publicKey)
        val epoch = syncManager.currentRealmEpoch
        val delegationMessage = pairingDelegationMessage(devicePublicKey, deviceId, epoch)
        val delegation = cryptoManager.signEd25519(masterKeyPair.privateKey, delegationMessage)
        return relay.admitDevice(
            ownerId = ownerId,
            newDeviceId = deviceId,
            newDevicePublicKey = devicePublicKey,
            masterDelegation = delegation,
            epoch = epoch,
            admittedAtHlc = syncManager.admissionStamp(),
        )
    }
}
