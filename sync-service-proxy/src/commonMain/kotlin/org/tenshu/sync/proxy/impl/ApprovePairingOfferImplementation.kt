package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTBoolean
import org.tenshu.identity.Device
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.PairingFailure
import org.tenshu.sync.ApprovePairingOffer
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistrar
import org.tenshu.sync.proxy.admissionStamp
import org.tenshu.sync.proxy.ContentPropertyTagger
import org.tenshu.sync.proxy.flushPendingBlocks
import org.tenshu.sync.proxy.isRelayUnauthorized
import org.tenshu.sync.proxy.throwIfTypedAdmitRefusal
import org.tenshu.sync.proxy.security.offerResponseMessage

/**
 * Responder approve (`sync-pairing-inverted-direction.md` §3.3 step 3). Symmetric to
 * `ApprovePairingRequestImplementation` but posts to the offer rendezvous instead of the
 * owner-keyed pairing mailbox: build the admission material via the shared [PairingAdmission]
 * (encrypted key blob + master/A33 delegation over the offerer's signing key + the offerer's
 * own A33 capability), sign the offer-response with this device's A4 key, POST it, then author
 * the offerer's authorised `Device` row.
 *
 * Symmetric in the relay-forgot-the-realm self-heal too (see the retry below) — without it
 * Approve is a permanent dead end against a wiped relay.
 */
internal class ApprovePairingOfferImplementation(
    private val relay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    /** The scheduler's resolver for a refused flush (through `SyncSchedulerHolder`). */
    private val freedContentResolver: () -> org.tenshu.sync.proxy.FreedContentResolver? = { null },
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val ctx = executionContext.context
        val hash = (params?.get(ApprovePairingOffer.Params.PAIRING_SECRET_HASH) as TString).get()
        val session = syncManager.getResponderSession(hash) ?: throw TenshuException(
            PairingFailedError.create(ctx, PairingFailure.NoPendingOffer),
        )
        val ownerId = syncManager.currentOwnerId ?: throw TenshuException(
            PairingFailedError.create(ctx, PairingFailure.NoAuthority),
        )

        // Shared ECDH secret (privA · offererPubB) seals the payload the offerer decrypts.
        val sharedSecret = cryptoManager.calculateSharedSecret(
            session.ephemeralKeyPair.privateKey, session.offererPublicKey,
        )
        // Admission material — same helper the join flow uses. The delegation signs the
        // offerer's Ed25519 SIGNING key (the key the relay registers), NOT its X25519 key.
        val admitted = PairingAdmission(syncManager, cryptoManager).admit(
            context = ctx,
            sharedSecret = sharedSecret,
            newDeviceSigningPublicKey = session.offererSigningKey,
            newDeviceId = session.offererDeviceId,
        )

        val publicKeyA = session.ephemeralKeyPair.publicKey
        val hostSignature = cryptoManager.signEd25519(
            privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
            message = offerResponseMessage("approved", hash, publicKeyA, admitted.encryptedMasterKey),
        )
        suspend fun postResponse() = relay.postOfferResponse(
            ownerId = ownerId,
            responderDeviceId = syncManager.currentDeviceId,
            pairingSecretHash = hash,
            status = "approved",
            publicKeyA = publicKeyA,
            payload = admitted.encryptedMasterKey,
            hostSignature = hostSignature,
            offererDeviceId = session.offererDeviceId,
            offererSigningKey = session.offererSigningKey,
            masterDelegation = admitted.masterDelegation,
            epoch = admitted.epoch,
            admissionCertChain = admitted.presentedChain,
            admittedAtHlc = syncManager.admissionStamp(),
        )
        var result = postResponse()

        // The relay refuses the OFFERER: it was removed from this realm and is back under the
        // same identity (F2′). Terminal, and about the other device, so the self-heal below
        // cannot change it — tell the approver what happened instead
        // (`admission-revocation-names-the-capability.md` §7h). Likewise a refusal of THIS device's
        // admission capability (`admission-revoked`, §7j.5) — terminal for a chain-presenting admitter.
        result.throwIfTypedAdmitRefusal(ctx)

        // Self-heal a relay that has forgotten this realm — the offer flow's twin of the join
        // flow's repair (`ApprovePairingRequestImplementation`). Without it, Approve
        // is a permanent dead end after a relay data wipe / restore-from-backup: the relay
        // rejects the POST at `knownDevices.getPublicKey(...)` with a 401 ("Unknown responder
        // device"), `RealmRegistrar`'s once-ever latch means this device never re-bootstraps on
        // its own, and the scheduler's 401 recovery cannot help because the responder is the
        // device the relay does not know — every tap is another 401, and the offerer polls a
        // mailbox nothing will ever write.
        //
        // Re-assert the genesis with the master self-signature — the same master-capability
        // endpoint, granting no new authority — then retry once. Gated on holding A1: genesis
        // needs the master identity, so a delegated (A33-only) admitter skips straight to
        // surfacing the error, which is the honest answer for a device that cannot repair this.
        if (result is TResult.Failure<*> && admitted.masterKeyPair != null) {
            // ⚠⚠ `reregisterThisDevice`, NOT `reassertGenesis`. Genesis alone repairs only the
            // rarer half — a relay that lost the whole realm. When the relay still HAS the realm
            // but has forgotten this device, `createRealm` answers 409 (a Failure *on purpose*,
            // per `reassertGenesis`'s KDoc), the retry is refused identically, and the user is told
            // to re-pair a device that a self-admit would have fixed. `reregisterThisDevice` keys
            // its admit-device fallback on exactly that 409.
            RealmRegistrar(syncManager, cryptoManager, relay)
                .reregisterThisDevice(admitted.masterKeyPair)
            result = postResponse()
            // A relay that had forgotten this device answers its 401 before the removal check,
            // so the refusal of the offerer can first appear on the retry.
            result.throwIfTypedAdmitRefusal(ctx)
        }
        if (result is TResult.Failure<*>) {
            // A 401 that survived the self-heal means the relay will not accept THIS device's
            // identity — it is not the joiner that is being refused. Say so: the raw
            // "401 Unauthorized — Unknown responder device" on the pairing screen verbatim
            // reads as a problem with the device being added and offers the user no next
            // step. Reachable when the repair is impossible (an A33-only admitter) or itself failed.
            val unauthorized = TenshuException(result.error).isRelayUnauthorized()
            if (unauthorized) {
                throw TenshuException(
                    // ⚠⚠ The user-facing sentence lives in `:control-shared`'s strings.xml against
                    // this reason, and in the iOS twin
                    // (`core-error-copy-catalogue.md` §1 and EC-4). The proxy names the reason;
                    // the app that shows the screen owns the words.
                    PairingFailedError.create(ctx, PairingFailure.AdmitterUnrecognised),
                )
            }
            throw TenshuException(result.error)
        }

        // Author the offerer's Device row (isAuthorized = true) — the relay already verified
        // the delegation + registered the device, so this reflects an authoritative admission.
        // Field-level LWW converges with the offerer's own self-push (GetOfferStatus).
        val offererDevice = Device.create(
            context = ctx,
            deviceId = session.offererDeviceId,
            name = session.deviceName,
            publicKey = session.offererSigningKey,
            isAuthorized = true,
            admittedAtEpoch = admitted.epoch,
            admittedByDeviceId = admitted.admittedByDeviceId,
            // F0 / A′: stamp the A33 capability this admission minted, so a later revocation can
            // name the capability instead of the device-at-an-epoch. The admitter generated the
            // keypair, so this is free here and unreliable everywhere else (R1: the revoker often
            // cannot resolve the target's own AdmissionKeyAnnouncement).
            admissionPublicKey = admitted.admissionPublicKey,
        )
        executionContext.saveData(offererDevice)

        // Flush pending blocks so the offerer's first /sync/changes pull sees this + any
        // undrained genesis Device block (same timing rationale as ApprovePairingRequest).
        flushPendingBlocks(executionContext, relay, ContentPropertyTagger(syncManager), freedContentResolver())

        syncManager.clearResponderSession(hash)
        return true.toTBoolean(ctx) as T
    }
}
