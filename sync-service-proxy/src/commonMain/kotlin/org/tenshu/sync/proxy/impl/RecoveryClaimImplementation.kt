package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.InvalidParameter
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.DeviceAlreadySetUpError
import org.tenshu.sync.RecoveryClaim
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

/**
 * Recovery flow per docs/adr/0007-sync-device-management.md / sync-relay-api.md §3.4.
 *
 * Derives master identity from the recovery phrase, fetches a fresh server challenge,
 * signs `(devicePublicKey || deviceId || challenge)` with the master private key, and
 * submits the claim. On success the relay has admitted this device — subsequent
 * JWT-authenticated requests verify against the registered public key.
 *
 * After the claim succeeds, the impl also pushes a `Device { isAuthorized = true }`
 * entity into the synced DB (§2.3 step 5). Recovery-admitted devices are authorised by
 * master self-delegation: the device proved possession of the master identity by
 * answering the challenge, so it admits itself — distinct from pairing where Device A
 * flips `isAuthorized` after approval.
 */
internal class RecoveryClaimImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val recoveryPhrase: TString = params.read(RecoveryClaim.Params.RECOVERY_PHRASE)
        val deviceName: TString = params.read(RecoveryClaim.Params.DEVICE_NAME)

        // Validate the user-typed phrase via BIP-39 before doing anything else. A typo
        // would otherwise produce a wrong master key, the relay would reject the claim
        // with an opaque "invalid signature" error, and the UI couldn't tell the user
        // *why*. Decoding here surfaces typed errors (UnknownWord/InvalidLength/
        // ChecksumMismatch) the recovery UI can render distinctly. ⚠ The decode here is
        // *only* validation — we pass the original phrase through because
        // `DefaultSyncCryptoManager` decodes it itself: `deriveKeys` already runs the
        // decoded entropy through Argon2id (threat-model T-RC-02, landed Phase 4 slice C)
        // and `deriveMasterKeyPair` derives a real Ed25519 key over HKDF-SHA-256.
        try {
            Bip39.decode(recoveryPhrase.get())
        } catch (_: Bip39InvalidMnemonicException) {
            // ⚠⚠ A typed code, never a sentence — `core-error-copy-catalogue.md` §5.0: no proxy
            // code creates a localised string. This ran in Control's process and was rendered in
            // whichever app called; the old `GenericError("Invalid recovery code: ${'$'}{e.message}")`
            // was therefore one app writing another app's copy, untranslatable, and — since only
            // `InternalError.internalDetail` survives into an `ErrorEvent` — discarded from the
            // logs too.
            //
            // `InvalidParameter` is the platform's existing shape for exactly this (a value-level
            // invariant the schema cannot encode, raised by an impl body), so no new composite and
            // no House Rule 9 bump. The decode's three distinct causes collapse to one
            // `BAD_FORMAT` here on purpose: this path is defence-in-depth for callers that are not
            // Control, whose ViewModel decodes BIP-39 itself and raises the precise typed error
            // with the offending word before any relay round-trip.
            throw TenshuException(
                InvalidParameter.create(
                    context = executionContext.context,
                    name = RecoveryClaim.Params.RECOVERY_PHRASE,
                    reason = InvalidParameter.Reasons.BAD_FORMAT,
                )
            )
        }

        // ⚠⚠ Nothing is seated on this device until the relay has ADMITTED the claim — the
        // `syncManager.initialize` below runs only on success. It used to run here, first, and
        // seating the master identity is not passive: it makes `isEnabled()` true and puts the
        // master keypair in memory, and the very next `SyncScheduler` tick then runs the lazy
        // personal-realm genesis (`RealmRegistrar.ensureRegistered` → `POST /sync/realm`) for
        // that key. A valid-but-wrong phrase — a mistyped word that still checksums, or the
        // phrase of a realm that no longer exists — therefore *created a fresh, empty realm*
        // under it: if the tick won the race the claim then found that realm and reported
        // Success (the recovering user lands in an empty account, believing it recovered), and
        // if the claim won it still failed with 404 but the realm was registered a tick later
        // and the device was left seated on the wrong phrase, so a retry with the right one
        // skipped `initialize` (`isEnabled()` already true) and kept the wrong keys.
        // `RecoveryOwnerIdBlindSystemTest.aPhraseWithNoRealm_*` pins both halves.
        // Neither the challenge nor the claim reads anything `initialize` seats: the claim is
        // unauthenticated, and the device key and id are device-local.
        val masterKeyPair = cryptoManager.deriveMasterKeyPair(recoveryPhrase.get())
        val deviceKeyPair = syncManager.getDeviceSigningKeyPair()
        val deviceId = syncManager.currentDeviceId
        val ownerId = org.tenshu.core.common.security
            .ownerIdForMasterPublicKey(masterKeyPair.publicKey)

        // ⚠⚠ Refuse, before any relay call, on a device that already belongs to ANOTHER realm
        // (T-RC-09). `initialize` below is guarded by `isEnabled()`, so recovering realm Y on a
        // device enabled for X kept X's keys; and on a locked X device it would seat Y's keys over
        // X's still-present unlock wrapper / joiner payload — two realms' custody in one bucket.
        // The user resets the device ("Forget this device" / "Disconnect this browser") first.
        //
        // `residentOwnerId`, NOT `currentOwnerId` / `isEnabled()`: the durable marker is what
        // answers "is this device set up?" — the in-memory one is null on a locked device and for
        // the seconds a cold-start autoload spends in Argon2id, and both of those are set up.
        //
        // ⚠ The SAME realm is let through on purpose: the lock screen's "Recover" is how a user
        // who forgot the passphrase gets back into the realm this device already belongs to
        // (resident, not enabled → `initialize` seats it). Recovering the realm the device is
        // already unlocked on is a harmless re-claim of the same device key.
        val residentOwnerId = syncManager.residentOwnerId
        if (residentOwnerId != null && residentOwnerId != ownerId) {
            throw TenshuException(DeviceAlreadySetUpError.create(executionContext.context))
        }

        // Step 1: fetch challenge. ⚠ Names no realm (F8).
        val challengeResult = relay.recoveryChallenge()
        if (challengeResult is TResult.Failure) {
            throw TenshuException(challengeResult.error)
        }
        val challenge = (challengeResult as TResult.Success).value!!.get()

        // Step 2: sign the canonical claim message — the shared builder the relay verifies.
        val message = org.tenshu.sync.common.recoveryClaimMessage(deviceKeyPair.publicKey, deviceId, challenge)
        val signature = cryptoManager.signEd25519(masterKeyPair.privateKey, message)

        // Step 3: submit the claim.
        val result = relay.recoveryClaim(
            masterPublicKey = masterKeyPair.publicKey,
            newDevicePublicKey = deviceKeyPair.publicKey,
            newDeviceId = deviceId,
            challenge = challenge,
            masterSignature = signature
        )

        // Step 4: per §2.3 step 5, push the recovering device's own Device entity into
        // the synced DB. Authorisation is `true` immediately because the device proved
        // possession of the master identity (master self-delegation) — in contrast to
        // pairing where Device A has to approve.
        if (result is TResult.Success) {
            // Admitted: only now seat the realm's identity on this device (see above). Must
            // precede the epoch fetch, which authenticates with a JWT for the seated ownerId.
            // Idempotent: re-deriving keys for the same phrase yields the same keys.
            if (!syncManager.isEnabled()) {
                syncManager.initialize(recoveryPhrase.get())
            }
            syncManager.setCurrentDeviceName(deviceName.get())

            // Fetch the realm's current epoch out-of-band before stamping our own
            // Device entity. Closes the recovery-race carve-out from
            // `docs/adr/0007-sync-device-management.md` (status header line 28+): a recovering
            // device has no local tombstones yet, so without this fetch
            // `syncManager.currentRealmEpoch` is 0 even if peers have rotated past
            // multiple revocations — every peer would then T-K-02-reject our self-
            // pushed Device.
            //
            // Seed `currentRealmEpoch` from the same value so the merge-time check on
            // *incoming* blocks (`SyncProcessor.resolveConflictAndSave`'s
            // `isStaleDeviceBlock`) is meaningful immediately, instead of accepting
            // stale-epoch Device blocks until tombstones drain. Both halves of the
            // carve-out close in lockstep here.
            //
            // If the fetch fails we surface the failure to the caller — the recovering
            // device's claim has already succeeded relay-side, but we don't ship a
            // stale-epoch Device block silently. The caller can decide whether to
            // retry or proceed.
            val epochResult = relay.fetchRealmEpoch(ownerId)
            if (epochResult is TResult.Failure) {
                throw TenshuException(epochResult.error)
            }
            val realmEpoch = (epochResult as TResult.Success).value!!.get()
            syncManager.seedCurrentRealmEpoch(realmEpoch)

            val ownDevice = org.tenshu.identity.Device.create(
                context = executionContext.context,
                deviceId = deviceId,
                name = deviceName.get(),
                publicKey = deviceKeyPair.publicKey,
                isAuthorized = true,
                admittedAtEpoch = realmEpoch,
                // F5b: carry our own A33 key, if we hold one — see PairingKeyInstaller.
                admissionPublicKey = syncManager.getAdmissionKeyPair()?.publicKey ?: ByteArray(0),
            )
            // Saving is sufficient: `initIdentityService` registers a DataObserver
            // that maintains `IdentityService.Relations.DEVICES` automatically.
            executionContext.saveData(ownDevice)
        }

        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }
}
