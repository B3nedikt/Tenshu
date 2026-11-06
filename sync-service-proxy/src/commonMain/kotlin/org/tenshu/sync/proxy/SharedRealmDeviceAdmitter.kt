package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncRelay

/**
 * SR-3b.4b (`sync-multi-realm-runtime.md` §3.1): the OWNER side of shared-realm device
 * admission. A device joining a shared realm gets its keys (A12 via the synced
 * `SharedRealmMembership` + A13 via pairing, SR-3b.1/4a) and locally registers the realm
 * (SR-3b.2), but its A4 isn't registered at the relay for the shared `ownerId` — so its
 * JWT on the shared stream is rejected. This admits it: the realm's OWNER signs a
 * delegation over `(deviceA4PublicKey || deviceId || epoch)` — the
 * `MasterDelegationVerifier.pairingResponseMessage` shape the relay already verifies —
 * with the realm's **Ed25519 master key** (held only by the owner, in
 * [RealmContext.masterKeyPair]) and posts it to `POST /sync/admit-device`.
 *
 * Reuses the device's existing (personal) [relay] client — `admitDevice` is
 * unauthenticated (the master delegation is the capability) and takes the `ownerId` as a
 * param, so no per-realm relay client is needed to admit (that's SR-3b.4c, for the
 * shared stream's own push/pull).
 *
 * Only an OWNER can admit (it alone holds the realm's master key); calling [admit] for a
 * realm whose [RealmContext.masterKeyPair] is null (a joiner) returns a failure.
 */
class SharedRealmDeviceAdmitter(
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
) {

    /**
     * Admit [deviceId] (with A4 public key [deviceA4PublicKey]) to the shared [realm].
     * [epoch] is the shared realm's admission epoch (0 in v1 — shared realms have no
     * per-realm Device-tombstone tracking yet); it is bound into the signed delegation
     * exactly as the pairing path binds the personal realm's epoch.
     */
    suspend fun admit(
        realm: RealmContext,
        deviceId: String,
        deviceA4PublicKey: ByteArray,
        epoch: Long = 0L,
    ): TResult<Data?> {
        // Owner-only: a joiner holds no master key and cannot sign admissions. This is a
        // programmer-error path (callers must check ownership), so it throws rather than
        // returning a TResult.Failure (which would need a TContext the admitter doesn't hold).
        val masterKeyPair = realm.masterKeyPair
            ?: throw IllegalStateException(
                "Only the shared realm's OWNER (master-key holder) can admit devices",
            )

        // Canonical delegation message — the shared builder the relay verifies.
        val message = org.tenshu.sync.common.pairingDelegationMessage(deviceA4PublicKey, deviceId, epoch)
        val delegation = cryptoManager.signEd25519(masterKeyPair.privateKey, message)

        return relay.admitDevice(
            ownerId = realm.ownerId,
            newDeviceId = deviceId,
            newDevicePublicKey = deviceA4PublicKey,
            masterDelegation = delegation,
            epoch = epoch,
        )
    }
}
