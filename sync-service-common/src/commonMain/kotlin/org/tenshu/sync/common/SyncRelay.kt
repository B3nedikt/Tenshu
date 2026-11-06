package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.core.TComponent
import org.tenshu.core.entity.DataId
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.TombstonesResponse

/**
 * Interface for the sync backend relay.
 */
interface SyncRelay : TComponent {
    /**
     * `POST /sync/push` — [block] and the **complete** edge set of the row it carries, applied by the
     * relay in one transaction (`content-plane-cr2-b3-wire-spec.md` §3.1; the body is an
     * `EdgedPush` stamped with `RELAY_PROTOCOL_VERSION`).
     *
     * ⚠⚠ The one push path every client takes — Android, desktop, iOS and the JS runtime. [edges]
     * is empty by default, which is right for every block that is not a standalone entity block of
     * a content-referencing type, and for a batch (which the relay 400s when it carries any).
     *
     * ⚠ Non-empty [edges] must be `contentEdgesOf` of the payload this block carries — the complete
     * set, `none` included — and never a store re-read (`content-plane-cr2-quorumless.md` §10.11).
     *
     * ⚠⚠ ...and **already tagged**: each `propertyId` is the keyed property tag, never the schema id
     * (`content-plane-cr2-b3-wire-spec.md` §11; `ContentPropertyTagger` in sync-service-proxy).
     * `RestSyncRelay` refuses an untagged edge rather than send it.
     */
    suspend fun pushBlock(block: SyncBlock, edges: List<ContentEdge> = emptyList()): TResult<Data?>

    /**
     * [pushBlock] with the typed refusal and its way out (`content-plane-cr2-b4-spec.md` §2.1):
     * a `409` naming freed content comes back as [PushOutcome.Refused], parsed from the JSON body, and
     * [revive] rides the push as `EdgedPush.revive`.
     *
     * ⚠⚠ Every drain that sends edges calls this, not [pushBlock]: the resolution (repair and revive,
     * or retire) needs the ids, and [pushBlock]'s failure is a message.
     *
     * ⚠ The default cannot revive and cannot tell a refusal from any other failure: it is what a relay
     * fake without the typed refusal answers, and a non-empty [revive] there throws rather than pushing
     * without it (which a real relay would refuse anyway, and a fake would wrongly accept).
     */
    suspend fun pushEdged(
        block: SyncBlock,
        edges: List<ContentEdge> = emptyList(),
        revive: List<RevivedContent> = emptyList(),
    ): PushOutcome {
        if (revive.isNotEmpty()) {
            error("pushEdged with a revive is not implemented by ${this::class.simpleName}")
        }
        return when (val result = pushBlock(block, edges)) {
            is TResult.Failure<*> -> PushOutcome.Failed(result.error)
            else -> PushOutcome.Accepted
        }
    }

    /**
     * Ask the relay whether a push would be accepted right now — `/sync/push`'s gates, no body.
     *
     * ⚠⚠ The move fence's acknowledged round-trip. A cached "the last tick was fine" cannot close the
     * window in which connectivity drops after it; asking at move time can.
     *
     * ⚠ [WriteProbe.UNSUPPORTED] — the default, and a relay that predates the route (`404`) — means
     * "could not ask", which the caller treats as the last tick's cached verdict, not as a refusal: an
     * un-upgraded relay must not defer every move.
     */
    suspend fun probeWrite(): WriteProbe = WriteProbe.UNSUPPORTED

    /**
     * Pulls the device's `since` blocks AND every other realm device's last-known
     * signed HLC tip (per `sync-backend-relay-plan.md` §5.1 / threat-model §11.3).
     * Caller passes its own [tip] — the relay records it before assembling the
     * response so the next poll's peer-tip view reflects the most recent state.
     * Receivers verify each `peerTip.signature` against their `KnownDevices` so a
     * hostile relay can't forge a tip claiming a peer has observed a different HLC
     * than they actually have. A `POST`, not a `GET`, because GET-with-body is fragile
     * across proxies.
     */
    suspend fun getChanges(sinceTimestamp: Long, tip: SignedHlcTip): TResult<ChangesResponse>

    /**
     * Pushes a [DeleteBlock] (tombstone) to the relay. Wire-distinct from [pushBlock] per
     * `docs/adr/0006-sync-tombstones.md` — tombstones are a separate wire-type-level
     * block kind. The relay keeps the tombstone **marker** indefinitely, so a long-offline
     * device still observes the delete and clients re-pull via [getTombstones]; the tombstone
     * **body** is dropped by `SyncMailbox.gcSweep` once every realm device has acked (via the
     * HLC-tip piggyback) and the retention window has elapsed.
     *
     * ⭐ `content-plane-cr2-b3-wire-spec.md` §3.2: the body is a `CascadedTombstonePush` carrying
     * [cascade], the wire names of every descendant the recursive delete removed (the persisted
     * `TombstoneCascades`), so
     * the relay removes the whole subtree's edges at this tombstone's HLC. Empty by default.
     */
    suspend fun pushTombstone(block: DeleteBlock, cascade: List<String> = emptyList()): TResult<Data?>

    /**
     * Pulls tombstones with `arrivalSeq > sinceTimestamp`. Sibling of [getChanges];
     * scheduler snapshots `lastSyncTimestamp` once per tick and uses it for both calls so
     * a tombstone arriving between the two pulls isn't skipped (the natural race if each
     * pull advanced the timestamp inside its own loop).
     *
     * Returns a [TombstonesResponse] wrapping the tombstones plus the relay's arrival-time
     * `nextCursor` — see [ChangesResponse] for the rationale. Receivers advance
     * `lastSyncTimestamp` from this cursor, NOT from `tombstone.hlc.physicalTime`.
     */
    suspend fun getTombstones(sinceTimestamp: Long): TResult<TombstonesResponse>

    /**
     * Pulls one page of the **merged log** — blocks and tombstones together, from a single
     * ordered query under a single cursor (`sync-lane-cursor-split.md` Option C / L3).
     * Supersedes a [getChanges] + [getTombstones] pair: it carries the same signed HLC tip
     * and returns the same peer tips.
     *
     * ⚠⚠ **The default reports [SyncLogFetch.RelayUnsupported] rather than throwing**, and
     * that choice is load-bearing. An implementation that has not been taught the merged lane
     * genuinely does not support it, so the honest answer is the one that makes the caller
     * fall back to the two lanes it *does* implement. A throwing default would instead reach
     * the caller through its generic catch as a **network failure**, flipping the device to
     * offline and halting the pull entirely — every un-updated [SyncRelay] in the tree, test
     * fakes included, would silently stop syncing.
     */
    suspend fun getSyncLog(
        sinceTimestamp: Long,
        tip: SignedHlcTip
    ): SyncLogFetch = SyncLogFetch.RelayUnsupported

    /**
     * One page of the merged log walking **backwards** from [beforeSeq] (exclusive) — the
     * newest-first catch-up direction (`sync-device-catch-up.md` C2 / §5.4).
     *
     * Same route, same response shape, same "unsupported" default and for the same reason as
     * [getSyncLog]: a relay without `/sync/log` has no `before=` either.
     */
    suspend fun getSyncLogBefore(
        beforeSeq: Long,
        tip: SignedHlcTip
    ): SyncLogFetch = SyncLogFetch.RelayUnsupported

    /**
     * Outcome of [getSyncLog]. A plain sealed result, not a `TResult` — this SPI is
     * proxy-internal (the [HubOfferFetch] precedent), and a plain result needs no [TContext]
     * to express the default, which is what lets "unsupported" be the safe fallback for every
     * implementation that has not overridden it.
     */
    sealed interface SyncLogFetch {
        data class Found(val response: SyncLogResponse) : SyncLogFetch

        /**
         * The relay predates `/sync/log` — it answered a bare unmatched-route 404.
         *
         * ⚠ Distinct from [Error] because the caller's response is opposite: latch the merged
         * lane off and keep using the two-lane path, **without** reporting the device offline.
         * A relay older than the client is working, not unreachable.
         */
        data object RelayUnsupported : SyncLogFetch

        data class Error(val message: String) : SyncLogFetch
    }
    /**
     * Fetch every block the relay holds for the given entity, regardless of
     * whether it's an entity update or a property update.
     *
     * The relay does not (and must not) understand the difference between
     * entity blocks and property blocks — that distinction lives inside the
     * encrypted [SyncBlock.encryptedPayload] as
     * [org.tenshu.sync.DataBlockPayload.propertyId]. Callers decrypt each
     * returned block to find the property they're interested in.
     *
     * See `docs/sync/threat-model.md` §"Property-ID exposure to relay" for
     * the rationale — exposing `propertyId` on the wire let the relay
     * profile users by the schema vocabulary they used.
     *
     * ⚠⚠ [entityWireId] is the entity's **wire id** (`WireIdResolver`, under the realm this relay
     * serves) — the name its blocks are stored under — never the `DataId`. A serialised `DataId`
     * would put a File's path on the wire in the clear, and its local hash matches no stored block
     * (`content-plane-cr2-b3-wire-spec.md` §11.3).
     */
    suspend fun fetchEntityBlocks(
        entityWireId: String
    ): TResult<TList<SyncBlock>>

    /**
     * Handshake: Device B posts its pubkeys to wait for the master keys.
     *
     * ⚠⚠ F7 (`relay-metadata-minimisation.md`): the relay sees neither the pairing secret nor the
     * device name. [pairingSecretHash] is `PairingRendezvous.joinSecretHash(secret)` and
     * [sealedDeviceName] is `PairingRendezvous.sealJoinDeviceName(…)` — both computed by the
     * caller, so this SPI never holds the raw secret and cannot leak it.
     *
     * @param publicKey ephemeral X25519 ECDH key (asset A8) used by the host
     *                  for SAS derivation; never registered.
     * @param signingPublicKey persistent Ed25519 device signing key (asset A4)
     *                  the host signs the master delegation over and the
     *                  relay registers in `KnownDevicesStore`. JWT
     *                  verification on every later request resolves to this.
     */
    suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray
    ): TResult<Data?> // Success implies request posted

    /**
     * Handshake: Device A retrieves pending pairing requests — as the relay holds them, hashed
     * secret and sealed name. The caller filters by its own hash and unseals the name
     * (`GetPendingPairingRequestsImplementation`).
     */
    suspend fun getPairingRequests(ownerId: String): PairingRequestsFetch

    /**
     * One pending join-flow request **as the relay serves it** (F7): never the secret, never the
     * name in clear. Plain class — this SPI is proxy-internal, not IPC; the IPC-facing
     * [PairingRequest] is built from it after unsealing.
     */
    class RelayPairingRequest(
        val deviceId: String,
        val sealedDeviceName: ByteArray,
        val publicKey: ByteArray,
        val signingPublicKey: ByteArray,
        val pairingSecretHash: String,
    )

    /** Outcome of [getPairingRequests]. */
    sealed interface PairingRequestsFetch {
        data class Found(val requests: List<RelayPairingRequest>) : PairingRequestsFetch

        /** The poll failed (transport, 401, 5xx). [error] carries the relay's message markers. */
        data class Failed(val error: org.tenshu.core.TError) : PairingRequestsFetch
    }

    /**
     * Handshake: Device A provides the encrypted master keys + admits the new device by
     * signing a delegation over `(newDevicePublicKey || newDeviceId || epoch)`.
     * Per `sync-relay-api.md` §3.3 the relay verifies the delegation and registers
     * `(ownerId, newDeviceId) → newDevicePublicKey` so the new device's first JWT-authed
     * request verifies the moment it consumes the encrypted master key.
     *
     * Two admitter shapes (`delegated-device-admission-authority.md` §4.3):
     * [admissionCertChain] `null` → [masterDelegation] is signed by the realm master
     * key (A1), verified as always; non-null (the [encodeAdmissionCertChain] bytes of
     * the admitter's chain) → the delegation is signed by the admitting device's A33
     * key and the relay walks the chain to the realm master + checks the admission
     * revocation list.
     */
    suspend fun postPairingResponse(
        pairingSecretHash: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray? = null,
        admittedAtHlc: String? = null,
    ): TResult<Data?>

    /**
     * Handshake: Device B polls for the encrypted master keys. Peek-only — Device B
     * must call [ackPairingResponse] after consuming. [pairingSecretHash] travels in a POST
     * body, never a query string (F4/F7).
     */
    suspend fun getPairingResponse(pairingSecretHash: String): TResult<Data?>

    /**
     * Handshake: Device B acknowledges consumption of the master key. Replaces the
     * destructive read on [getPairingResponse] so a device that crashes between fetch
     * and processing can re-fetch within the TTL.
     */
    suspend fun ackPairingResponse(pairingSecretHash: String): TResult<Data?>

    /**
     * Offer flow (`sync-pairing-inverted-direction.md` §3.4): the responder (an existing
     * realm device that scanned a new device's offer QR) posts the single sealed
     * offer-response keyed by [pairingSecretHash]. [hostSignature] is an Ed25519 signature
     * by the responder's registered device key over `status || hash || publicKeyA || payload`
     * (`MasterDelegationVerifier.offerResponseMessage`). On [status] == "approved" the
     * registration fields ([offererDeviceId]/[offererSigningKey]/[masterDelegation]/[epoch],
     * optionally [admissionCertChain]) are required — the relay verifies the delegation and
     * queues the offerer's `KnownDevicesStore` registration, applying it when the offerer
     * fetches the approval (the offer-flow analogue of [postPairingResponse]). A 409 (hash
     * already consumed) surfaces as a [TResult.Failure].
     */
    suspend fun postOfferResponse(
        ownerId: String,
        responderDeviceId: String,
        pairingSecretHash: String,
        status: String,
        publicKeyA: ByteArray,
        payload: ByteArray?,
        hostSignature: ByteArray,
        offererDeviceId: String? = null,
        offererSigningKey: ByteArray? = null,
        masterDelegation: ByteArray? = null,
        epoch: Long? = null,
        admissionCertChain: ByteArray? = null,
        admittedAtHlc: String? = null,
    ): TResult<Data?> = error("postOfferResponse not implemented by ${this::class.simpleName}")

    /**
     * Offer flow: the offerer (the new device) polls its rendezvous by [pairingSecretHash].
     * Anonymous (the offerer has no credentials yet, T-P1). Returns [OfferResponseFetch.Found]
     * with the responder's ephemeral public key + sealed payload once posted,
     * [OfferResponseFetch.NotFound] while pending, or [OfferResponseFetch.Error] on a transport
     * failure the caller should surface rather than silently keep polling.
     */
    suspend fun getOfferResponse(pairingSecretHash: String): OfferResponseFetch =
        error("getOfferResponse not implemented by ${this::class.simpleName}")

    /** Outcome of [getOfferResponse]. Plain result (this SPI is proxy-internal, not IPC). */
    sealed interface OfferResponseFetch {
        data class Found(
            val ownerId: String,
            val status: String,
            val publicKeyA: ByteArray,
            val payload: ByteArray?,
        ) : OfferResponseFetch

        data object NotFound : OfferResponseFetch

        data class Error(val message: String) : OfferResponseFetch
    }

    /**
     * Headless hub pairing (hub proposal §11, H2): the hub — unpaired, credential-less —
     * posts its **signed ephemeral offer** to the `/sync/pairing/hub-offer` mailbox keyed by
     * [pairingSecretHash] = `SHA-256(printedSecret)` (hex). Unauthenticated by design (the
     * hub has no relay credentials yet); the Ed25519 [signature] by the hub's printed static
     * key over `hubOfferMessage(ephemeralPublicKey, deviceId, deviceName)` is the integrity
     * anchor — verified phone-side against the physically scanned label, never by the relay.
     * The name itself travels only as [sealedDeviceName] (`PairingRendezvous.sealHubDeviceName`,
     * F7): the signature covers the clear name, which the phone recovers by unsealing.
     * Public material + a signature only (House Rule #1 holds trivially). A re-post under
     * the same hash replaces the previous offer (re-arming; the printed secret is
     * long-lived). A [TResult.Failure] whose message starts with
     * `HUB_PAIRING_UNSUPPORTED_BY_RELAY` means the relay predates the route.
     */
    suspend fun postHubOffer(
        pairingSecretHash: String,
        ephemeralPublicKey: ByteArray,
        deviceId: String,
        sealedDeviceName: ByteArray,
        signature: ByteArray,
    ): TResult<Data?> = error("postHubOffer not implemented by ${this::class.simpleName}")

    /**
     * Headless hub pairing: the phone fetches (and **consumes** — single-fetch, §11.4) the
     * hub's signed offer by [pairingSecretHash]. Unauthenticated (the phone has no relation
     * to the hub yet; the hash is the rendezvous capability). [HubOfferFetch.NotFound] means
     * no live offer — the hub isn't armed, or the offer expired / was already consumed.
     */
    suspend fun getHubOffer(pairingSecretHash: String): HubOfferFetch =
        error("getHubOffer not implemented by ${this::class.simpleName}")

    /** Outcome of [getHubOffer]. Plain result (this SPI is proxy-internal, not IPC). */
    sealed interface HubOfferFetch {
        data class Found(
            val ephemeralPublicKey: ByteArray,
            val deviceId: String,
            val sealedDeviceName: ByteArray,
            val signature: ByteArray,
        ) : HubOfferFetch

        data object NotFound : HubOfferFetch

        /** The relay predates `/sync/pairing/hub-offer` — surface "relay does not support hub pairing yet". */
        data object RelayUnsupported : HubOfferFetch

        data class Error(val message: String) : HubOfferFetch
    }

    /**
     * Realm bootstrap (`POST /sync/realm`, sync-relay-api.md §3.2). Posts the first
     * device's master self-signature; on success the relay registers the realm and
     * the first device's public key, allowing subsequent JWT-authenticated calls.
     *
     * [masterSelfSignature] must be over the canonical concatenation
     * `ownerId || masterPublicKey || firstDevicePublicKey || firstDeviceId` (see
     * `MasterDelegationVerifier.realmBootstrapMessage` on the server side; clients
     * must produce the same byte sequence).
     */
    suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String? = null,
    ): TResult<Data?>

    /**
     * Admit an additional device to an existing realm (`POST /sync/admit-device`,
     * `sync-multi-realm-runtime.md` §3.1). Used for **shared realms**: the
     * realm's OWNER signs [masterDelegation] over `(newDevicePublicKey || newDeviceId ||
     * epoch)` (the `MasterDelegationVerifier.pairingResponseMessage` shape) with the
     * realm's Ed25519 master key, admitting another of the user's devices' A4 to the
     * shared [ownerId] so that device's JWT verifies on the shared stream.
     *
     * Unauthenticated by JWT — the master delegation is the capability (mirrors
     * [createRealm]/[recoveryClaim]). Decoupled from the pairing handshake: the admitted
     * device already holds the realm's keys (A12 via its synced `SharedRealmMembership`);
     * only the relay-side registration is missing. Default throws so test fakes that
     * don't exercise shared-realm admission keep compiling.
     */
    suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String? = null,
    ): TResult<Data?> = error("admitDevice not implemented by ${this::class.simpleName}")

    /**
     * Revoke a device's **admission capability** (A33) on the relay
     * (`POST /sync/admission/revoke`, `delegated-device-admission-authority.md`
     * §5). Unauthenticated — [signature] (the realm master's Ed25519 over
     * `AdmissionRevocation.message(ownerId, deviceId, epoch, revokedAt, deviceA33PublicKey)`)
     * is the authorisation. Appending it stops the chain-verified pairing path
     * admitting through that device's cert and every chain running through it.
     * Strips *minting power only* — device removal (tombstone, epoch bump) is
     * the separate existing flow this rides alongside. Default throws so test
     * fakes that don't exercise admission revocation keep compiling.
     */
    suspend fun revokeAdmission(
        ownerId: String,
        deviceId: String,
        epoch: Long,
        revokedAt: Long,
        signature: ByteArray,
        /**
         * The A33 **capability key** being revoked (F1′). ⚠ Required
         * (`admission-revocation-names-the-capability.md` §7g): there is no keyless,
         * device-at-an-epoch revocation, and the relay refuses a body without a key.
         */
        deviceA33PublicKey: ByteArray,
    ): TResult<Data?> = error("revokeAdmission not implemented by ${this::class.simpleName}")

    /**
     * Drop a sealed membership grant onto the relay grant mailbox
     * (`POST /sync/grant/{address}`, `shared-realm-membership-grant.md` §4).
     * JWT-authed by the grantor's personal identity (this relay's own auth) — the
     * mailbox is bounded per grantor. [address] is the hex grant address
     * (`deriveGrantAddress`); [sealedGrant] is opaque to the relay (sealed to the
     * grantee's A13). Default throws so non-shared-realm fakes keep compiling.
     */
    suspend fun dropGrant(address: String, sealedGrant: ByteArray): TResult<Data?> =
        error("dropGrant not implemented by ${this::class.simpleName}")

    /**
     * Pick up + delete a sealed grant (`GET /sync/grant/{address}`). Anonymous —
     * the address is the bearer capability. Returns the sealed blob, or
     * `Success(null)` when the mailbox slot is empty/expired (the relay's 404).
     */
    suspend fun pickupGrant(address: String): TResult<org.tenshu.core.TByteArray?> =
        error("pickupGrant not implemented by ${this::class.simpleName}")

    /**
     * Admit a member's per-realm device to a shared realm by presenting the Q30
     * capability chain (`POST /sync/realm-member/admit`,
     * `shared-realm-membership-grant.md` §4). Unauthenticated by JWT — the
     * master-signed [capability] + member-authority-signed [binding] are the
     * capability. The relay verifies the chain against the realm's stored master
     * key + revocation list, then registers `(realmId, binding.deviceId)`.
     */
    suspend fun admitMember(
        realmId: String,
        capability: MembershipCapability,
        binding: MemberDeviceBinding,
    ): TResult<Data?> = error("admitMember not implemented by ${this::class.simpleName}")

    /**
     * Revoke a member authority (`POST /sync/realm-member/revoke`). Unauthenticated
     * by JWT — the realm-master signature on [revocation] is the authorisation. The
     * relay appends it to the per-realm revocation list and purges that authority's
     * admitted device registrations.
     */
    suspend fun revokeMember(
        realmId: String,
        revocation: MembershipRevocation,
    ): TResult<Data?> = error("revokeMember not implemented by ${this::class.simpleName}")

    /**
     * Recovery: fetch a fresh challenge (`GET /sync/recovery-challenge`, sync-relay-api.md
     * §3.4 step 1). Returns the raw challenge bytes — the caller signs
     * `(newDevicePublicKey || newDeviceId || challenge)` with the master identity and
     * follows up with [recoveryClaim].
     *
     * ⚠ Names no realm (`relay-metadata-minimisation.md` F8): the relay issues a challenge to
     * anyone, so this route says nothing about whether an `ownerId` has a realm.
     */
    suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray>

    /**
     * Recovery: submit the master-signed claim (`POST /sync/recovery-claim`,
     * sync-relay-api.md §3.4 step 2). On success the relay registers the new device's
     * public key and consumes the challenge — subsequent JWT-authed requests verify.
     *
     * Carries the master **public key**, not the `ownerId`: the relay verifies the signature
     * against it before looking up `sha256(masterPublicKey)`, so "no such realm" (404) is told
     * only to a caller who proved the master key (F8).
     */
    suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?>

    /**
     * Out-of-band realm-epoch fetch (`GET /sync/realm-epoch`, sync-relay-api.md §3.6).
     * Closes the recovery-race carve-out in `docs/adr/0007-sync-device-management.md`: a recovering
     * (or pairing-claim) device has no local tombstones yet, so reading
     * [SyncManager.currentRealmEpoch] returns 0 even if other realm devices have
     * advanced. Bootstrap impls call this before stamping `admittedAtEpoch` into their
     * self-pushed `Device` entity, and seed [SyncManager]'s epoch view so the merge-
     * time T-K-02 check is meaningful immediately rather than only after tombstones
     * drain.
     *
     * ⚠ Device-JWT authenticated (`relay-metadata-minimisation.md` F8): call it only once the
     * relay has registered this device (after recovery-claim / the admitter's response). The
     * relay reads the realm from the JWT; [ownerId] is this device's own realm, for fakes and
     * logs, and is not sent. Trust surface documented in threat-model T-K-02: the relay can
     * over- or understate the count, but T-K-02 enforcement is `<` only, so overstatement is
     * harmless and understatement degrades to the ADR 0007 carve-out.
     */
    suspend fun fetchRealmEpoch(ownerId: String): TResult<org.tenshu.core.number.TInteger>

    companion object {
        const val ID = "tenshu/sync/relay"
    }
}

/** The answer to [SyncRelay.probeWrite]. */
enum class WriteProbe {
    /** The relay answered and would accept a push from this device now. */
    ACCEPTED,

    /** Refused (`401`/`402`/other), unreachable, or timed out — a write would not land. */
    REFUSED,

    /** Could not ask: an implementation or relay without the probe. Not evidence either way. */
    UNSUPPORTED,
}
