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
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistrar
import org.tenshu.sync.proxy.admissionStamp
import org.tenshu.sync.proxy.ContentPropertyTagger
import org.tenshu.sync.proxy.flushPendingBlocks
import org.tenshu.sync.proxy.isPairingResponseConsumed
import org.tenshu.sync.proxy.security.PairingRendezvous

internal class ApprovePairingRequestImplementation(
    private val syncRelay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    /** CR-2 B4: the scheduler's resolver for a refused flush (through `SyncSchedulerHolder`). */
    private val freedContentResolver: () -> org.tenshu.sync.proxy.FreedContentResolver? = { null },
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val deviceId: String = params.read<TString>(ApprovePairingRequest.Params.DEVICE_ID).get()
        val activeContext = syncManager.activePairingContext ?: throw TenshuException(
            GenericError.create(executionContext.context, "No active pairing context")
        )
        if (syncManager.currentOwnerId == null) {
            throw TenshuException(
                GenericError.create(executionContext.context, syncManager.pairingAuthorityFailure())
            )
        }

        // 1. The request is the one this session SHOWED — never one the relay names now.
        //
        // ⚠⚠ This used to re-fetch the relay's request list and take the first entry with a
        // matching `deviceId`: its ECDH key, its signing key, its name, with no pairing-secret
        // filter. The SAS the user had just compared was derived from an *earlier* response, and
        // nothing bound the two — so a relay (House Rule #1: untrusted), or anyone who posted a
        // request for that `deviceId` first under any secret (the route is unauthenticated), chose
        // the keys that received the realm while both screens showed the genuine code.
        //
        // `GetPendingPairingRequests` pins each request at the moment it derives its SAS, on the
        // context itself. Approve admits the pin and takes nothing from the relay, so there is no
        // later response to be wrong about; no pin means no SAS was ever on screen for this
        // `deviceId` in this session, and that is refused before anything is sealed or signed.
        // Threat model T-P-02 / T-P-03 / T-P-14.
        val request = activeContext.shownRequest(deviceId)
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Pairing request not found for device $deviceId: this pairing session has " +
                        "not shown a verification code for it"
                )
            )

        // 2-3. Build the admission material (shared with the offer flow via
        // PairingAdmission): the encrypted key blob, the master/A33 delegation over the
        // joiner's Ed25519 SIGNING key (asset A4 — the key the relay registers; the
        // handshake X25519 key is an A8 ephemeral and stays out of the identity registry),
        // and the joiner's freshly-minted A33 capability so it becomes an admitter. The P0
        // guard (this device can't admit) throws inside `admit`.
        //
        // ⚠⚠ Minted ONCE per pin, then reused by every approve of this session for this
        // `deviceId` (`PairingContext.admissionFor`): every post for the pin carries identical
        // material, so a "consumed" answer means the joiner holds exactly what step 4 records.
        // Only sealed and public material is cached; the A1 keypair is resolved per approve.
        val pairingAdmission = PairingAdmission(syncManager, cryptoManager)
        val admitted = activeContext.admissionFor(deviceId) {
            val sharedSecret = cryptoManager.calculateSharedSecret(
                activeContext.privateKey,
                request.publicKey
            )
            pairingAdmission.admit(
                context = executionContext.context,
                sharedSecret = sharedSecret,
                newDeviceSigningPublicKey = request.signingPublicKey,
                newDeviceId = deviceId,
            ).withoutMasterKeyPair()
        }
        val newDeviceSigningPublicKey = request.signingPublicKey
        val epoch = admitted.epoch

        /**
         * One post, and whether its "already consumed" answer may be read as DELIVERED: only when
         * every earlier post under this session's hash was this pin's (identical) material — then
         * the joiner that acked the hash holds exactly what we record. A first-ever post answered
         * "consumed" is not ours, and neither is one after another `deviceId`'s post.
         */
        suspend fun postResponse(): TResult<Data?> {
            val onlyThisPinPostedBefore = activeContext.recordResponsePost(deviceId)
            val result = syncRelay.postPairingResponse(
                // F7: the joiner polls this mailbox by the same hash; the secret stays here.
                pairingSecretHash = PairingRendezvous.joinSecretHash(activeContext.secret),
                encryptedMasterKey = admitted.encryptedMasterKey,
                newDevicePublicKey = newDeviceSigningPublicKey,
                newDeviceId = deviceId,
                masterDelegation = admitted.masterDelegation,
                epoch = epoch,
                admissionCertChain = admitted.presentedChain,
                admittedAtHlc = syncManager.admissionStamp(),
            )
            return if (onlyThisPinPostedBefore && result is TResult.Failure<*> && result.isPairingResponseConsumed()) {
                TResult.Success.create(executionContext.context, null)
            } else {
                result
            }
        }

        // The pairing-response POST is the single load-bearing relay call in the whole
        // handshake: it's what places the encrypted master key in the mailbox the joiner
        // polls. Its result used to be discarded, so a failed POST looked identical to a
        // successful approve — the host dialog dismissed cleanly while the joiner polled a
        // mailbox that never filled and timed out on the SAS screen.
        var responseResult = postResponse()

        // One retry, for EVERY admitter. Its main job is a lost reply: the post landed, the
        // answer did not, and the joiner may already have fetched and acked — the retry then
        // reads "consumed" as delivered (see `postResponse`). That case is the same for a
        // delegated (A33-only) admitter, which used to surface it as a failure although the
        // joiner was paired; re-posting identical material is idempotent at the relay.
        //
        // An A1-holder additionally self-heals a relay that has forgotten this realm first. If
        // the relay lost its realm record or this device's registration — an in-memory relay
        // that restarted, or a redeploy without durable storage — the POST fails with "Realm not
        // found" / 401, and `RealmRegistrar`'s once-ever latch means this device never
        // re-bootstraps on its own (`ReregisterDevice` only fires on a sync-path 401, not on this
        // approve path). Re-assert the realm genesis with a master self-signature — the same
        // unauthenticated master-capability endpoint `ReregisterDevice` uses, granting no new
        // authority — then retry. A delegated admitter can't re-bootstrap (genesis needs A1), so
        // its retry is the plain re-post.
        //
        // ⚠ A "consumed" answer that `postResponse` did not turn into success is not retried:
        // the hash was acked by a joiner holding material this pin never posted.
        if (responseResult is TResult.Failure<*> && !responseResult.isPairingResponseConsumed()) {
            pairingAdmission.resolveMasterKeyPair()?.let { reBootstrapRealm(it) }
            responseResult = postResponse()
        }
        if (responseResult is TResult.Failure<*>) {
            throw TenshuException(responseResult.error)
        }

        // 4. Author Device B's `Device` entity in the synced DB with
        // `isAuthorized = true`. Per docs/adr/0007-sync-device-management.md step 7,
        // authorization is conferred by an existing realm device by writing the
        // synced Device entity; the resulting SyncBlock propagates via SyncObserver
        // to all realm devices including Device B itself.
        //
        // We **construct** the entity here from the pinned request (deviceId, the name
        // the approval prompt showed, the signing key) rather than looking up an existing
        // local entity. The original "find then flip" approach assumed Device B had
        // already self-published its `Device { isAuthorized = false }` and that block
        // had merged into Device A's DB before approve runs — but that's
        // chicken-and-egg: Device B can only self-publish AFTER `GetPairingStatus`
        // succeeds, and `GetPairingStatus` only succeeds AFTER this approve POSTs the
        // pairing response. Pre-fix, the lookup always returned null on a clean pair
        // → approve failed → the device never appeared on Device A's dashboard.
        //
        // Device B also self-publishes `Device { isAuthorized = true }`
        // (`PairingKeyInstaller`) — the joiner writes its own authoritative row rather
        // than self-pushing `false` for the host to flip (ADR 0007 D6). `Device` is plain
        // LastWriterWins applied whole-entity, so the later HLC wins outright and both
        // sides agree on `true`; A's `admittedAtEpoch` is the authoritative stamp.
        val authorizedDevice = Device.create(
            context = executionContext.context,
            deviceId = deviceId,
            name = request.deviceName,
            publicKey = newDeviceSigningPublicKey,
            isAuthorized = true,
            // Stamp the authoritative `admittedAtEpoch` from Device A's view (the
            // device *admitting* the new device). Device B's self-pushed entity
            // carries genesis-0 because B can't know A's current epoch yet — see the
            // T-K-02 carve-out in docs/adr/0007-sync-device-management.md status.
            admittedAtEpoch = epoch,
            // Admission lineage (delegated-device-admission-authority.md §5): who
            // *vouched* for this device — recorded only when the admission was
            // A33-delegated. An A1-minted capability is rooted at the realm master,
            // not at any device, so an owner-admitted row carries no lineage parent
            // (removing the owner DEVICE must not strip A1-rooted capabilities).
            // Must agree with the joiner's self-push stamp (PairingCompletion
            // derives the same value from chain[1]) — Device rows merge
            // whole-entity LWW, so divergent stamps would flap.
            admittedByDeviceId = admitted.admittedByDeviceId,
            // F0 / A′: stamp the A33 capability this admission minted, so a later revocation can
            // name the capability instead of the device-at-an-epoch. The admitter generated the
            // keypair, so this is free here and unreliable everywhere else (R1: the revoker often
            // cannot resolve the target's own AdmissionKeyAnnouncement).
            admissionPublicKey = admitted.admissionPublicKey,
        )
        // Saving is sufficient: `initIdentityService` registers a DataObserver that
        // adds the Device to `IdentityService.Relations.DEVICES` (and via SyncObserver
        // also generates a SyncBlock that propagates the entity to all realm devices).
        executionContext.saveData(authorizedDevice)

        // 5. Flush every SyncBlock currently sitting in the host's pending queue —
        // both the just-saved authorised-joiner `Device` AND the host's own
        // CreateRealm-time `Device` if its `SyncScheduler` hasn't drained yet.
        // The joiner's first `/sync/changes` pull happens moments later and
        // advances its cursor; any pre-pairing block not in the relay by then
        // gets permanently filtered out (relay uses `hlc.physicalTime`-based
        // filter; the host's old block has the lower hlc). See
        // [flushPendingBlocks] doc for the full timing rationale + the
        // residual general-case carve-out.
        flushPendingBlocks(executionContext, syncRelay, ContentPropertyTagger(syncManager), freedContentResolver())

        // 6. Cleanup
        syncManager.stopPairing()

        return null as T
    }

    /**
     * Repair this device's relay registration so a subsequent [SyncRelay.postPairingResponse]
     * retry finds the realm record **and** this device's registration. Best-effort: the result is
     * ignored, and any genuine failure re-surfaces on the retried response POST. No new authority
     * is granted — the same master-capability endpoints the owner already used at genesis.
     *
     * ⚠⚠ **This used to call `reassertGenesis` alone, and the KDoc justified it with "a 409 means
     * the realm already exists" — which is true and beside the point.** A 409 says the *realm* is
     * fine; it says nothing about whether the relay still knows *this device*. That combination —
     * realm present, device forgotten — is the commoner amnesia case, and genesis alone cannot
     * repair it: `createRealm` 409s, the retry is refused identically, and the user is told to
     * re-pair. [RealmRegistrar.reregisterThisDevice] keys its admit-device fallback on exactly that
     * 409, so it repairs both halves.
     *
     * The canonical bootstrap message lives in [RealmRegistrar.reassertGenesis] and the delegation
     * message in [RealmRegistrar.admitDevice] — one copy each, because both must match the relay
     * byte-for-byte.
     */
    private suspend fun reBootstrapRealm(masterKeyPair: SyncCryptoManager.KeyPair) {
        RealmRegistrar(syncManager, cryptoManager, syncRelay).reregisterThisDevice(masterKeyPair)
    }
}

