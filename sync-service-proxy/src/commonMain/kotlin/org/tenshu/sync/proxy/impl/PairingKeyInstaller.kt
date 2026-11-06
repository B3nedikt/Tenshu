package org.tenshu.sync.proxy.impl

import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.sync.common.DataIdMigrator
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.PairingKeyBlob

/**
 * The new-device key-install path shared by both pairing directions: decrypt the sealed key
 * blob under the ECDH shared secret, adopt the realm's derived keys + `ownerId` + optional
 * A13/A33 material, and self-publish this device's authorised `Device` row. Extracted from
 * `PairingCompletion` (the join flow's Device-B side) so the offer flow's `GetOfferStatus`
 * reuses this security-critical sequence verbatim — the only per-direction differences (how
 * the encrypted blob is fetched, and whether the relay poll is ack'd) stay with each caller.
 *
 * Ordering matches the join flow exactly (`docs/adr/0007-sync-device-management.md`, the
 * genesis-then-join fix, and `storage-entitlement-sync.md` §3.3).
 */
internal suspend fun installReceivedPairingPayload(
    executionContext: ExecutionContext,
    syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    relay: SyncRelay,
    myEphemeralPrivateKey: ByteArray,
    otherDevicePublicKey: ByteArray,
    ownerId: String,
    encryptedBlob: ByteArray,
    deviceName: String,
) {
    // Decrypt + unpack (SR-3b.4): identity + volume keys, and — when the admitter could
    // supply them — the realm's A13 + this device's freshly-minted A33 capability.
    val sharedSecret = cryptoManager.calculateSharedSecret(myEphemeralPrivateKey, otherDevicePublicKey)
    // ⚠ A blob of no known shape is refused before any local state changes, so the device stays
    // whole in whatever realm it was in.
    val unpacked = PairingKeyBlob.unpack(cryptoManager.decrypt(encryptedBlob, sharedSecret))
        ?: throw TenshuException(
            GenericError.create(
                executionContext.context,
                "The pairing key bundle has an unrecognised shape. Nothing was installed — " +
                    "update both devices and pair again.",
            ),
        )

    // Adopt the admitter realm's ownerId — load-bearing: without it this device signs JWTs
    // under a mismatched ownerId and every push 401s. Capture the prior realm first so a
    // genesis-then-join (or re-pair to a different owner) can be detected + superseded.
    val priorOwnerId = syncManager.currentOwnerId
    // ⚠⚠ Before the new keys go in, and deliberately NOT guarded: an owed rekey tombstone names its
    // target by `DataId`, and the wire name is derived from that *plus the realm key* — so one left
    // behind here is replayed under the realm this device is joining and tombstones whatever lives
    // at the same path there, on every peer. ⚠ Recording the originating owner on the marker is not
    // the alternative: the retry exists because there was no realm to name the target in, so none
    // was knowable when it was written. ⚠⚠ A failure must fail the pairing rather than be logged —
    // running it here means a throw leaves the device in its *original* realm, whole and consistent,
    // where swallowing it would arm a cross-realm delete on the next unlock.
    // ⚠⚠ `residentOwnerId`, NOT `priorOwnerId`. The in-memory value is null while the previous realm
    // is locked or its keys are still loading behind the Argon2id stretch — and pairing can complete
    // in exactly that window, which would skip this cleanup and leave the marker to be replayed
    // under the realm being joined. The durable slot is the membership question (see
    // `SyncManager.residentOwnerId`); the session slot answers a different one.
    // ⚠ The `discardSupersededRealm()` gate below reads the session value and inherits that gap for
    // its own purge — pre-existing, security-adjacent, and not this change's to move.
    val priorResidentOwnerId = syncManager.residentOwnerId
    if (priorResidentOwnerId != null && priorResidentOwnerId != ownerId) {
        val discarded = DataIdMigrator(
            executionContext,
            tombstoneEmitter = null,
            ownerId = { priorResidentOwnerId },
        ).discardOwedTombstones()
        if (discarded > 0) {
            println("PairingKeyInstaller -> discarded $discarded owed tombstone(s) from the superseded realm")
        }
    }
    syncManager.initializeWithKeys(
        unpacked.derivedKeys,
        ownerId = ownerId,
        realmEncryptionKeyPair = unpacked.realmEncryptionKeyPair,
        admissionKeyPair = unpacked.admissionKeyPair,
        admissionCertChain = unpacked.admissionCertChain,
    )

    // Adopt-not-merge: purge a superseded prior realm so the rehydrate/unlock-wrap paths
    // can't silently revert this device onto its orphaned stream.
    if (priorOwnerId != null && priorOwnerId != ownerId) {
        syncManager.discardSupersededRealm()
    }

    // Reaching here means the relay served an admitter's response for a *registered* realm
    // (registration was gated on the owner's storage subscription) — open the personal-realm
    // storage sync gate so this joiner's bootstrap pull runs before its own `hasStorage` is
    // known (else the gate blocks the very pull that delivers the entitlement).
    syncManager.joinedFundedRealm = true

    // Seed the realm epoch from the relay's OOB view so this device's self-pushed Device row
    // carries the right `admittedAtEpoch` (T-K-02) rather than genesis-0. Soft-fail to the
    // local view on relay error — pairing already succeeded crypto-side.
    val seededEpoch: Long = run {
        val epochResult = relay.fetchRealmEpoch(ownerId)
        if (epochResult is TResult.Success) {
            val value = epochResult.value!!.get()
            syncManager.seedCurrentRealmEpoch(value)
            value
        } else {
            syncManager.currentRealmEpoch
        }
    }

    // Self-publish this device's authorised Device row. `isAuthorized = true`: the admitter's
    // response only arrived because the relay verified the delegation + registered this device.
    // Lineage (chain[1]) records the delegated admitter that vouched, if any (A1-rooted = none).
    val ownDevice = Device.create(
        context = executionContext.context,
        deviceId = syncManager.currentDeviceId,
        name = deviceName,
        publicKey = syncManager.getDeviceSigningKeyPair().publicKey,
        isAuthorized = true,
        admittedAtEpoch = seededEpoch,
        admittedByDeviceId = unpacked.admissionCertChain.getOrNull(1)?.deviceId ?: "",
        // ⚠⚠ F5b: this row REPLACES the admitter's copy (an entity save carries the whole
        // property set), so it must carry the A33 key the admitter captured on it (F0) — else the
        // key is gone realm-wide and a later revocation of this device has no key to name
        // (`admission-revocation-names-the-capability.md` §7g.6). It is our own key, from the blob.
        admissionPublicKey = unpacked.admissionKeyPair?.publicKey ?: ByteArray(0),
    )
    executionContext.saveData(ownDevice)
}
