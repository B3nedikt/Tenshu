package org.tenshu.sync.proxy

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.tenshu.core.common.util.runCatchingCancellable

import org.tenshu.contact.common.GrantCapability
import org.tenshu.contact.common.GrantPayload
import org.tenshu.contact.common.AcceptancePayload
import org.tenshu.contact.common.sealAcceptance
import org.tenshu.contact.common.unsealAcceptance
import org.tenshu.core.common.util.deriveAcceptanceAddress
import org.tenshu.identity.AwaitedAcceptance
import org.tenshu.identity.OutboundAcceptance
import org.tenshu.core.TenshuException
import org.tenshu.sync.common.MembershipAcceptance
import org.tenshu.sync.common.MemberRowAttestation
import org.tenshu.sync.common.MembershipRecordVerifier
import org.tenshu.contact.common.sealGrant
import org.tenshu.contact.common.unsealGrant
import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.util.deriveGrantAddress
import org.tenshu.core.common.util.deriveRekeyAddress
import org.tenshu.core.common.util.deriveKeyRequestAddress
import org.tenshu.core.common.util.sha256
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.GrantedMembership
import org.tenshu.identity.IdentityService
import org.tenshu.identity.Member
import org.tenshu.identity.MemberAuthorityKey
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.identity.PendingGrant
import org.tenshu.identity.RetainedRealmKey
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.sync.common.MemberDeviceBinding
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.common.toStoredBytes
import org.tenshu.core.common.relation.relationReference

/**
 * Orchestrates the cross-user shared-realm membership grant (GR-3/GR-6,
 * `shared-realm-membership-grant.md` §§3–4, §9–12). Constructed per op call
 * (like [SharedRealmBootstrapper]); the entry points are the proxy ops
 * `GrantSharedRealmMembership` / `AcceptSharedRealmGrant` / `RemoveSharedRealmMember`
 * and the GR-6 `PollPendingGrants` / `DeclinePendingGrant`.
 *
 * **Owner mints A26 (Option A).** The as-built [GrantPayload] carries a *pre-signed*
 * capability binding the member-authority public key, so the owner must know — and
 * therefore mints — the A26 keypair at grant time, signs the capability with the
 * realm master, and ships the A26 **private** key inside the payload (already sealed
 * to the grantee's A13). The owner owns the realm master and could admit any device
 * regardless, so transiently holding A26 is no escalation; it keeps only the public
 * half — persisted in a [GrantedMembership] roster row (GR-6) — to revoke later.
 *
 * **Durable owner custody (GR-6).** The realm master keypair (A1) is persisted by
 * [SharedRealmBootstrapper] as an [OwnedRealmMaster] row, so [ownerMasterKeyPair]
 * can rehydrate it (and re-register the realm) across sessions and on the owner's
 * other devices — closing the "grant/remove is same-session only" limit.
 *
 * **Per-realm device keys.** The accepting/binding device admits itself with a fresh
 * per-realm Ed25519 device key + realm-local device id (D2 unlinkability), and the
 * realm's relay JWT is signed with that key via [perRealmDeviceRelay] (registered
 * explicitly).
 *
 * **Kr rotation on removal is GR-7, and it shipped** (§14). [removeMember] posts the
 * master-signed revocation (the relay stops admitting + purges the member's devices — the
 * practical cutoff), tombstones the member's [Member] row + the owner-side roster, and then
 * rotates: [rotateAndRegrant] mints `Kr'`, retires the current key to this owner's
 * [RetainedRealmKey] keyring so prior content stays readable, and re-grants `Kr'` to every
 * remaining member. That is what closed the T-GR-02 residual which relay revocation alone
 * left at Medium — a removed member cannot read content written after the rotation.
 */
@OptIn(ExperimentalStdlibApi::class)
class SharedRealmGrantManager(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
    private val realmRegistry: RealmRegistry,
    /** Builds a [perRealmDeviceRelay] for a granted realm (per-realm key JWT). */
    private val grantedRelayFactory: (RealmContext) -> SyncRelay?,
    private val nowMs: () -> Long,
) {

    private val directory = GrantContactDirectory(executionContext)
    private val membershipRecords = MembershipRecords(executionContext, cryptoManager)

    /**
     * Grant [personId] membership of shared realm [realmId]. Returns the minted
     * member-authority **public key** (hex). [label] is the advisory folder name
     * shown at the accept prompt (no behaviour rides it). Works across sessions and
     * on any of the owner's devices via the durable [OwnedRealmMaster] custody (GR-6).
     */
    suspend fun grant(realmId: String, personId: String, label: String): String {
        val myEnc = syncManager.getRealmEncryptionKeyPair()
            ?: throw SharedRealmGrantException("realm locked — unlock this device to grant membership")

        // Master key (A1): in-memory this session, else rehydrated from the durable
        // OwnedRealmMaster row — which also re-registers the realm so the Member-row
        // write below routes into it (GR-6).
        val masterKeyPair = ownerMasterKeyPair(realmId, myEnc)
        // ⚠⚠ Never grant a retired key: the member would join on it, and no later re-key reaches them, because
        // their grant was issued with it, and nothing marks that key as one to replace. See [mustWaitForRotation].
        if (mustWaitForRotation(realmId, myEnc, masterKeyPair)) {
            throw SharedRealmGrantException(
                "this realm's key is still changing on another of your devices — try again once it has synced",
            )
        }

        // Raw Kr: unwrap our own OWNER membership row with our A13 (the realm's
        // RealmContext holds derivedKeys, not the raw A12 we must re-wrap for Bob).
        val ownMembership = readMembership(realmId)
            ?: throw SharedRealmGrantException("no membership row for this realm")
        val kr = cryptoManager.unwrapKeyForIdentity(ownMembership.wrappedMasterKey.get(), myEnc.privateKey)

        val contact = directory.grantableByPersonId(personId)
            ?: throw SharedRealmGrantException(
                "contact has no grant seed — re-do the contact handshake to share with them",
            )

        // Mint A26, sign its capability with the realm master (epoch 0 — the
        // device-admission convention; revocations are keyed per authority key, so a
        // fresh A26 per grant never collides with a prior member's revocation).
        val authority = cryptoManager.generateDeviceSigningKeyPair()
        val issuedAt = nowMs()
        val capabilitySignature = cryptoManager.signEd25519(
            masterKeyPair.privateKey,
            MembershipCapability.message(realmId, authority.publicKey, CAP_EPOCH, issuedAt),
        )

        val wrappedForGrantee = cryptoManager.wrapKeyForIdentity(kr, contact.a13PublicKey)
        val grantedGeneration = keyGenerationOf(realmId)
        val payload = GrantPayload(
            grantId = sha256(authority.publicKey).toHexString(),
            realmId = realmId,
            wrappedRealmKey = wrappedForGrantee,
            capability = GrantCapability(
                realmId = realmId,
                memberAuthorityPublicKey = authority.publicKey,
                epoch = CAP_EPOCH,
                issuedAt = issuedAt,
                signature = capabilitySignature,
            ),
            memberAuthorityPrivateKey = authority.privateKey,
            label = label,
            grantorFingerprint = sha256(myEnc.publicKey).toHexString(),
            // ⚠⚠ Signed on every grant, not only re-keys: a grantee already in the realm reads a grant as a re-key
            // ([applyRekey]), and only this signature says the realm master sent that key (the box is anonymous).
            masterPublicKey = masterKeyPair.publicKey,
            rekeySignature = cryptoManager.signEd25519(
                masterKeyPair.privateKey,
                org.tenshu.sync.common.RealmRekey.message(realmId, authority.publicKey, wrappedForGrantee, issuedAt, grantedGeneration),
            ),
            keyGeneration = grantedGeneration,
        )

        val address = deriveGrantAddress(myEnc.publicKey, contact.a13PublicKey, contact.grantSeed)
        val sealed = sealGrant(cryptoManager, payload, contact.a13PublicKey, address)
        relay.dropGrant(address.toHexString(), sealed).failing {
            throw SharedRealmGrantException("could not deliver the grant to the relay mailbox")
        }
        // MR-1: the same capability bytes, synced into the realm as its record of this member.
        membershipRecords.saveCapability(
            MembershipCapability(realmId, authority.publicKey, CAP_EPOCH, issuedAt, capabilitySignature),
            masterKeyPair.publicKey,
        )

        // Lazy backfill: ensure the owner's own Member row exists in the realm (the
        // roster's first row; pre-GR realms never wrote one).
        ensureOwnerMemberRow(realmId, sha256(masterKeyPair.publicKey).toHexString(), sha256(myEnc.publicKey).toHexString(), masterKeyPair)

        // GR-6: persist the granted member's A26 public key keyed by the in-realm
        // memberId, so the owner can revoke by member later (the Member row carries
        // only the hash; the owner discards the A26 private half).
        val memberId = sha256(authority.publicKey).toHexString()
        executionContext.saveData(
            GrantedMembership.create(
                context = executionContext.context,
                memberId = memberId,
                realmId = realmId,
                memberAuthorityPublicKey = authority.publicKey,
            ),
        )
        // RO D7: the owner writes this member's `Member` row once their acceptance arrives — note what to poll.
        val grantedKeyId = sha256(ownMembership.wrappedMasterKey.get()).toHexString()
        executionContext.saveData(
            AwaitedAcceptance.create(executionContext.context, memberId, realmId, contact.fingerprint, grantedKeyId),
        )
        // GR-7 catch-up: the grant itself delivered this key.
        recordDelivery(realmId, memberId, grantedKeyId, issuedAt)

        return authority.publicKey.toHexString()
    }

    /**
     * Accept a pending grant from the contact whose `TenshuIdentity` key is
     * [grantorFingerprint]: consume the sealed grant (a previously-polled
     * [PendingGrant] if present, else a live mailbox pickup — the GR-6/Option-A
     * paths), register the realm (per-realm device key), persist the A26 keypair +
     * our membership, admit this device, and write our [Member] record (the D4
     * acceptance signal). Returns the joined realm's id.
     */
    suspend fun accept(grantorFingerprint: String): String {
        val myEnc = syncManager.getRealmEncryptionKeyPair()
            ?: throw SharedRealmGrantException("realm locked — unlock this device to accept a grant")
        val grantor = directory.byFingerprint(grantorFingerprint)
            ?: throw SharedRealmGrantException("no contact with that fingerprint")
        if (!grantor.isGrantable) {
            throw SharedRealmGrantException("contact has no grant seed — cannot derive the grant mailbox")
        }

        val address = deriveGrantAddress(grantor.a13PublicKey, myEnc.publicKey, grantor.grantSeed)

        // GR-6: a poll may already have consumed the (single-pickup) mailbox copy and
        // stored it as a quarantined PendingGrant — prefer that; else do a live pickup
        // (the Option-A "accept from contact" path where no poll ran).
        val pending = readPendingGrant(grantorFingerprint)
        val sealedBytes = pending?.sealedGrant?.get()
            ?: (relay.pickupGrant(address.toHexString()) as? TResult.Success)?.value?.get()
            ?: throw SharedRealmGrantException("no pending grant from this contact")
        val payload = unsealGrant(cryptoManager, sealedBytes, myEnc.privateKey, address)

        // Quarantine: the sealed payload's grantor fingerprint must match the contact
        // we addressed — defence in depth over the address already binding them.
        if (payload.grantorFingerprint != grantorFingerprint) {
            throw SharedRealmGrantException("grant fingerprint mismatch — refusing")
        }

        val realmId = payload.realmId

        // GR-7: a grant for a realm we already belong to is a re-key (rotation), not a
        // new join — adopt the new Kr in place (retain the old, re-register) and skip
        // the join/admit path (the device is already admitted). Mirrors the silent poll
        // apply; here it covers a re-key the user explicitly accepts from a card.
        if (isExistingMember(realmId)) {
            if (!applyRekey(payload, myEnc, grantorFingerprint)) {
                if (pending != null) deletePendingGrant(grantorFingerprint)
                throw SharedRealmGrantException("this key change was not signed by the folder's owner — ignored")
            }
            if (pending != null) deletePendingGrant(grantorFingerprint)
            return realmId
        }

        val capability = MembershipCapability(
            realmId = realmId,
            memberAuthorityPublicKey = payload.capability.memberAuthorityPublicKey,
            epoch = payload.capability.epoch,
            issuedAt = payload.capability.issuedAt,
            signature = payload.capability.signature,
        )

        // Register the realm with a FRESH per-realm device key + realm-local device id
        // (D2), explicitly relayed with that key — before saving the membership, so the
        // personal-key auto-join observer sees it registered and skips.
        val kr = cryptoManager.unwrapKeyForIdentity(payload.wrappedRealmKey, myEnc.privateKey)
        val perRealmDeviceKey = cryptoManager.generateDeviceSigningKeyPair()
        val realmLocalDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(perRealmDeviceKey.publicKey)
        val realmContext = RealmContext(
            ownerId = realmId,
            deviceId = realmLocalDeviceId,
            derivedKeys = cryptoManager.deriveSharedRealmKeys(kr),
            deviceSigningKeyPair = perRealmDeviceKey,
            masterKeyPair = null,
            retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey),
        )
        if (!realmRegistry.isRegistered(realmId)) {
            realmRegistry.register(realmContext, grantedRelayFactory(realmContext))
        }

        // GR-7: where this realm's re-keys will arrive — the per-realm address of the contact that granted it.
        executionContext.saveData(
            org.tenshu.identity.RealmGrantor.create(executionContext.context, realmId, grantorFingerprint, payload.keyGeneration),
        )

        // Persist A26 (+ its capability) so this and every future device self-binds.
        executionContext.saveData(
            MemberAuthorityKey.create(
                context = executionContext.context,
                sharedRealmId = realmId,
                publicKey = capability.memberAuthorityPublicKey,
                privateKey = payload.memberAuthorityPrivateKey,
                capability = capability.toStoredBytes(),
            ),
        )

        // Save our membership row (Kr wrapped to our A13 — the auto-join form).
        executionContext.saveData(
            SharedRealmMembership.create(
                context = executionContext.context,
                sharedRealmId = realmId,
                wrappedMasterKey = payload.wrappedRealmKey,
                role = SharedRealmMembership.Role.MEMBER,
            ),
        )

        // Admit this device: bind our per-realm device key under A26 and present the
        // capability + binding to the relay.
        admitDevice(capability, payload.memberAuthorityPrivateKey, perRealmDeviceKey, realmLocalDeviceId)

        // ⭐ RO D7: the acceptance travels back to the OWNER, who writes our `Member` row — we no longer write
        // one into the realm ourselves (a viewer could not, and a self-written row is self-asserted). Queued in
        // a durable outbox first, so a failed drop is retried on the next poll rather than lost.
        queueAcceptance(realmId, capability.memberAuthorityPublicKey, payload.memberAuthorityPrivateKey, grantor, myEnc)
        runCatchingCancellable { deliverOutboundAcceptances() }

        // GR-6: the grant is accepted — clear the quarantined pending record (the
        // tombstone syncs, so the card disappears on the user's other devices too).
        if (pending != null) deletePendingGrant(grantorFingerprint)

        return realmId
    }

    /**
     * GR-6 inbound poll: sweep every grantable contact's derivable grant address,
     * pick up any sealed grant waiting there (the mailbox `GET` is destructive, so
     * we **store** it as a quarantined [PendingGrant] rather than accept inline —
     * D4), and return the full pending set for the *Shared with me* cards. A contact
     * we've already discovered a grant from is skipped (one pending per contact).
     */
    suspend fun pollPendingGrants(): List<PendingGrant> {
        val myEnc = syncManager.getRealmEncryptionKeyPair()
            ?: throw SharedRealmGrantException("realm locked — unlock this device to check for shared folders")
        // RO D7, both directions of the acceptance: deliver ours, and as an owner pick up our members'.
        // ⚠ Best-effort, each: a failure here must not stop the inbound grant poll below.
        runCatchingCancellable { deliverOutboundAcceptances() }
            .onFailure { println("SharedRealmGrantManager -> acceptance delivery failed (will retry): $it") }
        runCatchingCancellable { processAwaitedAcceptances(myEnc) }
            .onFailure { println("SharedRealmGrantManager -> acceptance pickup failed (will retry): $it") }
        runCatchingCancellable { catchUpRekeys(myEnc) }
            .onFailure { println("SharedRealmGrantManager -> re-key catch-up failed (will retry): $it") }
        runCatchingCancellable { pickUpRekeys(myEnc) }
            .onFailure { println("SharedRealmGrantManager -> re-key pickup failed (will retry): $it") }
        // GR-7 key requests: ask for the realms we are behind on, and answer our members' requests.
        runCatchingCancellable { sendKeyRequests(myEnc) }
            .onFailure { println("SharedRealmGrantManager -> key request failed (will retry): $it") }
        runCatchingCancellable { answerKeyRequests(myEnc) }
            .onFailure { println("SharedRealmGrantManager -> key request answer failed (will retry): $it") }
        for (contact in directory.all()) {
            if (!contact.isGrantable) continue
            // One pending card per contact: a quarantined card normally blocks a fresh
            // pickup until it's accepted/declined. But [PendingGrant] is a *synced* row,
            // so a card we already consumed on `accept` can be resurrected by a sync pull
            // that re-applies the create after our local delete tombstone (a create→delete
            // ordering race). A resurrected card is *stale* — we've already joined its
            // realm — and would wrongly block a waiting **re-key** (Kr rotation) at this
            // address (GR-7). So if the existing card is for a realm we already belong to,
            // drop it and fall through to the pickup; only a card whose realm we have *not*
            // joined is a genuine quarantine that should still block (one-per-contact, D4).
            val existingPending = readPendingGrant(contact.fingerprint)
            if (existingPending != null) {
                if (isStalePending(existingPending, myEnc)) {
                    deletePendingGrant(contact.fingerprint)
                } else {
                    continue
                }
            }
            val address = deriveGrantAddress(contact.a13PublicKey, myEnc.publicKey, contact.grantSeed)
            val sealedBytes = (relay.pickupGrant(address.toHexString()) as? TResult.Success)?.value?.get()
                ?: continue

            // GR-7: a drop for a realm we are *already* a member of is a **re-key**
            // (Kr rotation after some other member was removed) — apply it silently
            // here, no quarantine card. The gate (we already hold this realm's
            // membership) is what makes auto-apply safe: a re-key cannot inject a new
            // realm, and the blob is still sealed to our A13 + addressed via the
            // handshake secret. A brand-new realm falls through to quarantine (D4).
            val payload = try {
                unsealGrant(cryptoManager, sealedBytes, myEnc.privateKey, address)
            } catch (_: Exception) {
                continue // not for us / corrupt — leave it, don't quarantine garbage
            }
            if (isExistingMember(payload.realmId)) {
                applyRekey(payload, myEnc, contact.fingerprint)
                continue
            }
            executionContext.saveData(
                PendingGrant.create(
                    context = executionContext.context,
                    grantorFingerprint = contact.fingerprint,
                    sealedGrant = sealedBytes,
                    address = address.toHexString(),
                    discoveredAt = nowMs(),
                ),
            )
        }
        return readAllPendingGrants()
    }

    /** GR-6: discard a quarantined [PendingGrant] (decline). The blob is lost; the owner can re-grant. */
    fun declinePendingGrant(grantorFingerprint: String) {
        deletePendingGrant(grantorFingerprint)
    }

    /**
     * Remove the member with in-realm id [memberId] from shared realm [realmId]: look
     * up the member-authority public key from the owner-side [GrantedMembership]
     * roster (GR-6), post a master-signed revocation (the relay stops admitting +
     * purges that authority's devices), tombstone the member's [Member] row + the
     * roster row, then **rotate `Kr`** (GR-7, D6): mint `Kr′`, retire the current key
     * to this owner's keyring, re-wrap `Kr′` to the owner's own membership, and
     * re-grant `Kr′` to every *remaining* member via a re-key drop on the grant
     * mailbox. The removed member's `Kr` then buys no *new* content — closing the
     * T-GR-02 residual the relay-revocation alone left at Medium. Revoke-before-accept
     * is the same op (D6) — the sealed grant already exposed the old `Kr` to the
     * grantee's A13, so rotation is what actually cuts them off.
     */
    suspend fun removeMember(realmId: String, memberId: String) {
        val myEnc = syncManager.getRealmEncryptionKeyPair()
            ?: throw SharedRealmGrantException("realm locked — unlock this device to remove members")
        val masterKeyPair = ownerMasterKeyPair(realmId, myEnc)
        // A device behind another owner device's rotation must not rotate again: two concurrent rotations fork
        // the key (`shared-realm-membership-grant.md` §14.3). See [mustWaitForRotation].
        if (mustWaitForRotation(realmId, myEnc, masterKeyPair)) {
            throw SharedRealmGrantException(
                "this realm's key is still changing on another of your devices — try again once it has synced",
            )
        }

        val roster = readGrantedMembership(memberId)
            ?: throw SharedRealmGrantException(
                "no grant record for this member on this device — cannot revoke",
            )
        val authorityPublicKey = roster.memberAuthorityPublicKey.get()

        val revokedAt = nowMs()
        val signature = cryptoManager.signEd25519(
            masterKeyPair.privateKey,
            MembershipRevocation.message(realmId, authorityPublicKey, REVOKE_EPOCH, revokedAt),
        )
        val revocation = MembershipRevocation(realmId, authorityPublicKey, REVOKE_EPOCH, revokedAt, signature)
        relay.revokeMember(realmId, revocation).failing {
            throw SharedRealmGrantException("relay revocation failed")
        }
        // MR-1: the same revocation bytes, synced into the realm. ⭐ This, not the local delete below, is
        // what tells the owner's other devices and every remaining member: a `deleteData` emits no
        // tombstone, and shared realms apply none (`shared-realm-membership-records.md` §1).
        membershipRecords.saveRevocation(revocation, masterKeyPair.publicKey)

        // Tombstone the member's Member row (cross-member propagation of the removal
        // rides Kr rotation in GR-7; the relay revocation is the immediate cutoff).
        executionContext.deleteData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = Member.ID,
                keyValue = Member.compositeKey(realmId, memberId),
            ),
        )
        // Drop the owner-side roster row, and stop waiting for an acceptance (revoke-before-accept, D6).
        executionContext.deleteData(grantedMembershipDataId(memberId))
        if (executionContext.database.getData(awaitedAcceptanceDataId(memberId)) != null) {
            executionContext.deleteData(awaitedAcceptanceDataId(memberId))
        }

        // GR-7: rotate Kr and re-grant Kr′ to the remaining members. The Member-row
        // tombstone above already removed [memberId] from the MEMBERS relation, so the
        // re-grant sweep below never targets the removed member.
        // ⚠⚠ Pick up waiting acceptances FIRST (RO D7): a member who accepted but whose row the owner has not
        // written yet would otherwise be missed by the rotation below and left on the retired key.
        runCatchingCancellable { processAwaitedAcceptances(myEnc) }
            .onFailure { println("SharedRealmGrantManager -> acceptance pickup before rotation failed: $it") }
        rotateAndRegrant(realmId, memberId, myEnc, masterKeyPair)
    }

    /**
     * GR-7 Kr rotation: mint `Kr′`, [adoptNewRealmKey] on the owner side (retire the
     * current key into the keyring + re-wrap `Kr′` to the owner's own membership +
     * re-register), then re-key every remaining member by dropping a re-key
     * [GrantPayload] (the same sealed-mailbox shape, with an **empty**
     * `memberAuthorityPrivateKey` — the member already holds their A26) at that
     * member's grant address. The member's device picks it up via the GR-6 poll and
     * silently adopts `Kr′` ([applyRekey]). Re-grant drops are best-effort: an
     * unreachable member simply keeps the old key until the next removal or a manual
     * re-grant — they are never *wrongly* left able to read new content (only the
     * remaining members ever receive `Kr′`).
     */
    private suspend fun rotateAndRegrant(
        realmId: String,
        removedMemberId: String,
        myEnc: SyncCryptoManager.KeyPair,
        masterKeyPair: SyncCryptoManager.KeyPair,
    ) {
        // ⚠⚠ Seal what was saved before the rotation under the key it was saved under — above all `removeMember`'s
        // revocation record. A device that only holds the old key (an owner device that cannot re-register mid-session,
        // §14.3; a member yet to pick up the re-key) can read nothing sealed under the new one.
        drainOwnEmitters()
        val krPrime = cryptoManager.generateSharedRealmMasterKey()
        adoptNewRealmKey(realmId, krPrime, myEnc, masterKeyPair)
        // Captured now, with the key it names: what each delivery below records (see [dropRekey]).
        val krPrimeId = readMembership(realmId)?.let { sha256(it.wrappedMasterKey.get()).toHexString() }
        val krPrimeGeneration = keyGenerationOf(realmId)

        for (member in readRealmMembers(realmId)) {
            if (member.role.get() != Member.Role.MEMBER) continue
            val memberId = member.memberId.get()
            if (memberId == removedMemberId) continue
            // ⚠⚠ Never re-key a member the realm's signed records revoked. A rotation can run on an owner device
            // whose `Member` row for them is stale — notably [mustWaitForRotation]'s recovery, which fires
            // exactly when this device is behind.
            if (membershipRecords.isRevoked(realmId, memberId)) continue
            val contactFingerprint = member.contactFingerprint.get()
            if (contactFingerprint.isEmpty()) continue
            val contact = directory.byFingerprint(contactFingerprint)?.takeIf { it.isGrantable } ?: continue
            val roster = readGrantedMembership(memberId) ?: continue

            dropRekey(realmId, roster, contact, krPrime, krPrimeId, krPrimeGeneration, myEnc, masterKeyPair)
        }
        // MR-1 §3.5: re-send the realm's records under `Kr′` — a member granted from now on holds only
        // the new key, and could not read records sealed under the retired one.
        membershipRecords.reEmit(realmId)
    }

    /**
     * GR-7 re-key for one member: re-sign their capability (also recorded, MR-1) and drop a re-key payload — [kr]
     * wrapped to their A13, no A26 — at their grant address. Best-effort: a failed drop leaves that member on the
     * old key (recoverable).
     */
    private suspend fun dropRekey(
        realmId: String,
        roster: GrantedMembership,
        contact: GrantContact,
        kr: ByteArray,
        /**
         * `sha256` of the owner's wrapped [kr] — read by the caller together with [kr]. ⚠ Never re-read after the drop:
         * another owner device's rotation can sync in while it is in flight, and recording THAT key would mark the
         * member current on a key nobody sent them. Null records nothing.
         */
        keyId: String?,
        /** [kr]'s generation ([keyGenerationOf]), read by the caller together with [kr] — for the same reason as [keyId]. */
        keyGeneration: Long,
        myEnc: SyncCryptoManager.KeyPair,
        masterKeyPair: SyncCryptoManager.KeyPair,
    ): Boolean {
        val grantorFingerprint = sha256(myEnc.publicKey).toHexString()
        val authorityPub = roster.memberAuthorityPublicKey.get()
        // ⚠⚠ Strictly after the last one this member was sent, by any owner device: the member orders re-keys by this
        // signed value and refuses one that is not newer, so a slow clock here must not make a genuine re-key look old.
        val issuedAt = maxOf(nowMs(), (lastDelivery(realmId, roster.memberId.get())?.issuedAt?.toEpochMilliseconds() ?: 0L) + 1)
        val capabilitySignature = cryptoManager.signEd25519(
            masterKeyPair.privateKey,
            MembershipCapability.message(realmId, authorityPub, CAP_EPOCH, issuedAt),
        )
        // MR-1: the re-signed capability is a record too (same member, newer `issuedAt`).
        membershipRecords.saveCapability(
            MembershipCapability(realmId, authorityPub, CAP_EPOCH, issuedAt, capabilitySignature),
            masterKeyPair.publicKey,
        )
        val wrapped = cryptoManager.wrapKeyForIdentity(kr, contact.a13PublicKey)
        val payload = GrantPayload(
            grantId = sha256(wrapped).toHexString(),
            realmId = realmId,
            wrappedRealmKey = wrapped,
            capability = GrantCapability(
                realmId = realmId,
                memberAuthorityPublicKey = authorityPub,
                epoch = CAP_EPOCH,
                issuedAt = issuedAt,
                signature = capabilitySignature,
            ),
            // Re-key marker: the member already holds the A26 private key + is
            // already admitted, so a re-key ships no authority key and triggers no
            // re-admission — only the content key changes (D6).
            memberAuthorityPrivateKey = ByteArray(0),
            label = "",
            grantorFingerprint = grantorFingerprint,
            // ⚠⚠ What the member checks before adopting: the realm master authorises THIS key for THIS member.
            masterPublicKey = masterKeyPair.publicKey,
            rekeySignature = cryptoManager.signEd25519(
                masterKeyPair.privateKey,
                org.tenshu.sync.common.RealmRekey.message(realmId, authorityPub, wrapped, issuedAt, keyGeneration),
            ),
            keyGeneration = keyGeneration,
        )
        // ⚠⚠ The per-realm re-key address, never the contact-wide grant address: the mailbox keeps one blob per address,
        // so two realms' re-keys to one contact there would destroy the first while both were recorded as delivered.
        val address = deriveRekeyAddress(myEnc.publicKey, contact.a13PublicKey, contact.grantSeed, realmId)
        val sealed = sealGrant(cryptoManager, payload, contact.a13PublicKey, address)
        // Best-effort: a failed drop leaves that member on the old key — and, with no delivery recorded, the next
        // poll's [catchUpRekeys] on any owner device sends it again.
        if (relay.dropGrant(address.toHexString(), sealed) !is TResult.Success<*>) return false
        keyId?.let { recordDelivery(realmId, roster.memberId.get(), it, issuedAt) }
        return true
    }

    /**
     * Member side, GR-7: pick up each realm's re-key at its per-realm address ([deriveRekeyAddress]) — one address per
     * realm, from the contact that granted it ([org.tenshu.identity.RealmGrantor]); without that note (a membership from
     * before it), every grantable contact's. Adopted only through [applyRekey], so only what the realm master signed.
     * ⚠ Owned realms are skipped: an owner device rotates, it is never re-keyed by a drop.
     */
    private suspend fun pickUpRekeys(myEnc: SyncCryptoManager.KeyPair) {
        val memberships = executionContext.database
            .getArrayData(executionContext.relationReference(IdentityService.Relations.SHARED_REALM_MEMBERSHIPS))
            ?.toList().orEmpty().mapNotNull { it as? SharedRealmMembership }
        for (membership in memberships) {
            val realmId = membership.sharedRealmId.get()
            if (readOwnedRealmMaster(realmId) != null) continue
            val grantor = (
                executionContext.database.getData(
                    DataId.StringDataId.create(executionContext.context, org.tenshu.identity.RealmGrantor.ID, realmId),
                ) as? org.tenshu.identity.RealmGrantor
                )?.grantorFingerprint?.get()
            val contacts = grantor?.let { listOfNotNull(directory.byFingerprint(it)) } ?: directory.all()
            for (contact in contacts.filter { it.isGrantable }) {
                val address = deriveRekeyAddress(contact.a13PublicKey, myEnc.publicKey, contact.grantSeed, realmId)
                val sealed = (relay.pickupGrant(address.toHexString()) as? TResult.Success)?.value?.get() ?: continue
                val payload = try {
                    unsealGrant(cryptoManager, sealed, myEnc.privateKey, address)
                } catch (_: Exception) {
                    continue
                }
                // ⚠ The address names the realm, but the payload must too: nothing else binds them.
                if (payload.realmId != realmId) continue
                applyRekey(payload, myEnc, contact.fingerprint)
            }
        }
    }

    /**
     * Member side, GR-7: ask the granting contact for the current key of every realm this device is behind on — a
     * block in its stream opened under no key it holds ([KeyRequestTracker]). One request per contact names all its
     * realms (the address is per contact, last write wins). ⚠ Rate-limited, and only a hint: the trigger is forgeable,
     * and the answer is an ordinary re-key, adopted only through [applyRekey].
     */
    private suspend fun sendKeyRequests(myEnc: SyncCryptoManager.KeyPair) {
        val now = nowMs()
        val due = realmRegistry.keyRequests.due(now)
        if (due.isEmpty()) return
        // ⚠ Every outstanding realm, not only the due ones: the per-contact address keeps one request, so a request
        // for realm B alone would destroy an unread one for realm A. The due set decides WHEN to send, not WHAT.
        val outstanding = realmRegistry.keyRequests.behindRealms().filter { readOwnedRealmMaster(it) == null && isExistingMember(it) }
        val byGrantor = outstanding.mapNotNull { realmId ->
            val grantorRow = executionContext.database.getData(
                DataId.StringDataId.create(executionContext.context, org.tenshu.identity.RealmGrantor.ID, realmId),
            ) as? org.tenshu.identity.RealmGrantor ?: return@mapNotNull null
            // ⚠⚠ Only a block written after the key we hold came to be can need a newer one. Every pull from 0 (each
            // start, each re-key) meets the blocks from before we joined, sealed under keys we were never given —
            // asking about those would send a request, and cost the owner an answer, on every restart.
            val latest = realmRegistry.keyRequests.latestUnopenable(realmId) ?: return@mapNotNull null
            // ⚠ No skew allowance: two rotations inside it would leave a member that lost the second unable to ever ask.
            if (latest <= grantorRow.lastKeyGeneration.toEpochMilliseconds()) return@mapNotNull null
            val grantor = grantorRow.grantorFingerprint.get()
            val authority = executionContext.database.getData(
                DataId.StringDataId.create(executionContext.context, MemberAuthorityKey.ID, realmId),
            ) as? MemberAuthorityKey ?: return@mapNotNull null
            Triple(grantor, realmId, authority)
        }.groupBy({ it.first }, { it.second to it.third })
        for ((grantorFingerprint, realms) in byGrantor) {
            if (realms.none { (realmId, _) -> realmId in due }) continue
            val contact = directory.byFingerprint(grantorFingerprint)?.takeIf { it.isGrantable } ?: continue
            val entries = realms.map { (realmId, authority) ->
                org.tenshu.contact.common.KeyRequestEntry(
                    realmId = realmId,
                    memberAuthorityPublicKey = authority.publicKey.get(),
                    requestedAt = now,
                    signature = cryptoManager.signEd25519(
                        authority.privateKey.get(),
                        org.tenshu.sync.common.RealmKeyRequest.message(realmId, authority.publicKey.get(), now),
                    ),
                )
            }
            val address = deriveKeyRequestAddress(myEnc.publicKey, contact.a13PublicKey, contact.grantSeed)
            val sealed = org.tenshu.contact.common.sealKeyRequest(
                cryptoManager, org.tenshu.contact.common.KeyRequestPayload(entries = entries), contact.a13PublicKey, address,
            )
            if (relay.dropGrant(address.toHexString(), sealed) !is TResult.Success<*>) continue
            println("SharedRealmGrantManager -> asked for the current key of ${entries.size} realm(s)")
            realms.forEach { (realmId, _) -> realmRegistry.keyRequests.requested(realmId, now) }
        }
    }

    /**
     * Owner side, GR-7: answer each member's key request with the realm's current key, through the same signed,
     * ordered re-key as a rotation ([dropRekey]). An entry is answered only when it is for a realm we own, signed by
     * the A26 we granted that member, from the contact the member row names, for a member neither revoked nor still
     * invited — and at most once per member per realm per interval ([KeyRequestTracker.mayAnswer]).
     */
    private suspend fun answerKeyRequests(myEnc: SyncCryptoManager.KeyPair) {
        for (contact in directory.all().filter { it.isGrantable }) {
            val address = deriveKeyRequestAddress(contact.a13PublicKey, myEnc.publicKey, contact.grantSeed)
            val sealed = (relay.pickupGrant(address.toHexString()) as? TResult.Success)?.value?.get() ?: continue
            val request = try {
                org.tenshu.contact.common.unsealKeyRequest(cryptoManager, sealed, myEnc.privateKey, address)
            } catch (_: Exception) {
                continue
            }
            for (entry in request.entries) {
                val realmId = entry.realmId
                if (readOwnedRealmMaster(realmId) == null) continue
                val memberId = sha256(entry.memberAuthorityPublicKey).toHexString()
                val roster = readGrantedMembership(memberId) ?: continue
                if (roster.realmId.get() != realmId) continue
                if (!roster.memberAuthorityPublicKey.get().contentEquals(entry.memberAuthorityPublicKey)) continue
                val row = executionContext.database.getData(
                    DataId.StringDataId.create(executionContext.context, Member.ID, Member.compositeKey(realmId, memberId)),
                ) as? Member ?: continue
                if (!MemberRowAttestation.isValid(row, verifier) || row.contactFingerprint.get() != contact.fingerprint) continue
                if (membershipRecords.isRevoked(realmId, memberId)) continue
                // A relay can replay an old sealed request at will: answer only recent ones.
                if (nowMs() - entry.requestedAt > KEY_REQUEST_MAX_AGE_MS) continue
                if (!cryptoManager.verifyEd25519(
                        entry.memberAuthorityPublicKey,
                        org.tenshu.sync.common.RealmKeyRequest.message(realmId, entry.memberAuthorityPublicKey, entry.requestedAt),
                        entry.signature,
                    )
                ) {
                    println("SharedRealmGrantManager -> ignoring a key request for $realmId that does not verify")
                    continue
                }
                val masterKeyPair = runCatchingCancellable { ownerMasterKeyPair(realmId, myEnc) }.getOrNull() ?: continue
                if (runCatchingCancellable { mustWaitForRotation(realmId, myEnc, masterKeyPair) }.getOrDefault(true)) continue
                if (!realmRegistry.keyRequests.mayAnswer(realmId, memberId, nowMs())) continue
                val ownMembership = readMembership(realmId) ?: continue
                val currentKr = cryptoManager.unwrapKeyForIdentity(ownMembership.wrappedMasterKey.get(), myEnc.privateKey)
                val currentKeyId = sha256(ownMembership.wrappedMasterKey.get()).toHexString()
                val currentGeneration = keyGenerationOf(realmId)
                println("SharedRealmGrantManager -> $realmId: answering member $memberId's key request")
                val sent = runCatchingCancellable {
                    dropRekey(realmId, roster, contact, currentKr, currentKeyId, currentGeneration, myEnc, masterKeyPair)
                }.getOrDefault(false)
                if (sent) realmRegistry.keyRequests.answered(realmId, memberId, nowMs())
            }
        }
    }

    /**
     * The generation of this realm's current key, owner side ([org.tenshu.identity.RealmKeyGeneration]): written by the
     * device that created the key, strictly after the one it replaced, synced to every owner device. 0 for the realm's
     * first key. ⚠ An owner device still on an older key reads THAT key's generation — which is the point.
     */
    private fun keyGenerationOf(realmId: String): Long {
        val wrapped = readMembership(realmId)?.wrappedMasterKey?.get() ?: return 0L
        val recorded = executionContext.database.getData(
            DataId.StringDataId.create(
                executionContext.context,
                org.tenshu.identity.RealmKeyGeneration.ID,
                org.tenshu.identity.RealmKeyGeneration.compositeKey(realmId, sha256(wrapped).toHexString()),
            ),
        ) as? org.tenshu.identity.RealmKeyGeneration
        // A key with no record is the realm's first (never rotated): generation 0.
        return recorded?.generation?.toEpochMilliseconds() ?: 0L
    }

    /** GR-7 catch-up: note that [memberId] was sent the key [keyId] ([RealmKeyDelivery]). */
    private fun recordDelivery(realmId: String, memberId: String, keyId: String, issuedAt: Long) {
        executionContext.saveData(
            org.tenshu.identity.RealmKeyDelivery.create(executionContext.context, realmId, memberId, keyId, issuedAt),
        )
    }

    private fun lastDelivery(realmId: String, memberId: String): org.tenshu.identity.RealmKeyDelivery? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                executionContext.context,
                org.tenshu.identity.RealmKeyDelivery.ID,
                org.tenshu.identity.RealmKeyDelivery.compositeKey(realmId, memberId),
            ),
        ) as? org.tenshu.identity.RealmKeyDelivery

    private fun deliveredKeyId(realmId: String, memberId: String): String? = lastDelivery(realmId, memberId)?.keyId?.get()

    /**
     * GR-7 catch-up, owner side: send the realm's **current** key to every accepted, unrevoked member who was not
     * sent it ([RealmKeyDelivery]). A rotation re-keys only the members its device knows — one whose `Member` row
     * had not synced there yet is skipped — and a drop can fail; either way that member would stay on the old key,
     * joined but unable to read anything new, and nothing else would ever resend it.
     *
     * ⚠ Only once this device holds the current key ([mustWaitForRotation]), never a member still waiting on an
     * invitation (acceptance handles them, with the key their grant carried), and
     * idempotent on the member side: a key they already hold is not adopted twice.
     */
    private suspend fun catchUpRekeys(myEnc: SyncCryptoManager.KeyPair) {
        val awaiting = executionContext.database
            .getArrayData(executionContext.relationReference(IdentityService.Relations.AWAITED_ACCEPTANCES))
            ?.toList().orEmpty().mapNotNull { (it as? AwaitedAcceptance)?.memberId?.get() }.toSet()
        val byRealm = executionContext.database.getArrayData(executionContext.relationReference(IdentityService.Relations.MEMBERS))
            ?.toList().orEmpty().mapNotNull { it as? Member }.groupBy { it.realmId.get() }
        for ((realmId, members) in byRealm) {
            if (readOwnedRealmMaster(realmId) == null) continue
            val ownMembership = readMembership(realmId) ?: continue
            val masterKeyPair = runCatchingCancellable { ownerMasterKeyPair(realmId, myEnc) }.getOrNull() ?: continue
            if (runCatchingCancellable { mustWaitForRotation(realmId, myEnc, masterKeyPair) }.getOrDefault(true)) continue
            val currentKeyId = sha256(ownMembership.wrappedMasterKey.get()).toHexString()
            val owed = members.filter { member ->
                val memberId = member.memberId.get()
                member.role.get() == Member.Role.MEMBER &&
                    memberId !in awaiting &&
                    MemberRowAttestation.isValid(member, verifier) &&
                    !membershipRecords.isRevoked(realmId, memberId) &&
                    deliveredKeyId(realmId, memberId) != currentKeyId
            }
            if (owed.isEmpty()) continue
            val currentKr = cryptoManager.unwrapKeyForIdentity(ownMembership.wrappedMasterKey.get(), myEnc.privateKey)
            val currentGeneration = keyGenerationOf(realmId)
            for (member in owed) {
                val memberId = member.memberId.get()
                val contact = directory.byFingerprint(member.contactFingerprint.get())?.takeIf { it.isGrantable } ?: continue
                val roster = readGrantedMembership(memberId) ?: continue
                println("SharedRealmGrantManager -> $realmId: sending member $memberId the current key (catch-up)")
                runCatchingCancellable { dropRekey(realmId, roster, contact, currentKr, currentKeyId, currentGeneration, myEnc, masterKeyPair) }
            }
        }
    }

    /**
     * MR-1 §3.5: re-emit [realmId]'s valid membership records — called once per start by an owner
     * device, against relay body GC. Returns how many went out.
     */
    fun reEmitMembershipRecords(realmId: String): Int = membershipRecords.reEmit(realmId)

    /**
     * Adopt [newKr] as shared realm [realmId]'s current content key (GR-7): retire the
     * current `Kr` into the [RetainedRealmKey] keyring (so content under it stays
     * readable via trial-decrypt), re-wrap [newKr] to this device's own A13 in the
     * [SharedRealmMembership] row, and re-register the realm's [RealmContext] with the
     * new `DerivedKeys` + the (now larger) keyring. Used by both the owner
     * ([rotateAndRegrant], [masterKeyPair] non-null) and a remaining member adopting a
     * re-key ([applyRekey], [masterKeyPair] null). Idempotent: re-applying the same key
     * (a duplicate poll pickup) is a no-op.
     */
    private fun adoptNewRealmKey(
        realmId: String,
        newKr: ByteArray,
        myEnc: SyncCryptoManager.KeyPair,
        masterKeyPair: SyncCryptoManager.KeyPair?,
    ) {
        val current = readMembership(realmId) ?: return
        val oldWrapped = current.wrappedMasterKey.get()
        val oldKr = try {
            cryptoManager.unwrapKeyForIdentity(oldWrapped, myEnc.privateKey)
        } catch (_: Exception) {
            null
        }
        // Idempotent: the membership already carries this key — nothing to rotate.
        if (oldKr != null && oldKr.contentEquals(newKr)) return

        val wasRegistered = realmRegistry.isRegistered(realmId)

        // Retire the current key first, so the keyring (read at re-register / by the
        // join observer the membership save fires) already includes it.
        if (oldKr != null) {
            val keyId = sha256(oldWrapped).toHexString()
            // ⚠ A recovery re-retires a key that is already retired: keep the first `retiredAt`. A fresh one would
            // win LWW on every other owner device stuck on that key and restart their 24 h wait, chaining.
            val retiredAt = (
                executionContext.database.getData(
                    DataId.StringDataId.create(executionContext.context, RetainedRealmKey.ID, RetainedRealmKey.compositeKey(realmId, keyId)),
                ) as? RetainedRealmKey
                )?.retiredAt?.get() ?: nowMs()
            executionContext.saveData(
                RetainedRealmKey.create(
                    context = executionContext.context,
                    sharedRealmId = realmId,
                    keyId = keyId,
                    wrappedMasterKey = oldWrapped,
                    retiredAt = retiredAt,
                ),
            )
        }

        val newWrapped = cryptoManager.wrapKeyForIdentity(newKr, myEnc.publicKey)
        // ⚠⚠ Owner side: the new key's generation, strictly after the one it replaces — and saved BEFORE the membership,
        // so another owner device never holds the new key without its generation (it would sign generation 0, and every
        // member would refuse it). Same personal stream, earlier block.
        if (masterKeyPair != null) {
            executionContext.saveData(
                org.tenshu.identity.RealmKeyGeneration.create(
                    executionContext.context, realmId, sha256(newWrapped).toHexString(),
                    generation = maxOf(nowMs(), keyGenerationOf(realmId) + 1),
                ),
            )
        }

        // Swap the membership to the new key (wrapped to our own A13). For a member not
        // yet registered this session, this save fires SharedRealmJoinObserver, which
        // registers the realm with the new key + keyring; the owner (always registered)
        // and an already-registered member take the manual re-register below instead.
        executionContext.saveData(
            SharedRealmMembership.create(
                context = executionContext.context,
                sharedRealmId = realmId,
                wrappedMasterKey = newWrapped,
                role = current.role.get(),
            ),
        )

        // ⚠ The membership save above usually re-registered already (`SharedRealmJoinObserver` re-keys a realm whose
        // stored key moved); this covers a wiring without that observer.
        val newKeys = cryptoManager.deriveSharedRealmKeys(newKr)
        if (wasRegistered && realmRegistry.runtimeFor(realmId)?.context?.derivedKeys != newKeys) {
            // The join observer skipped (already registered), so re-register manually
            // with the new keys, reusing the existing per-realm device key + relay kind.
            // ⚠ Owner and member alike keep their per-realm key and relay (D10): the owner no
            // longer re-registers under the personal-key relay.
            val existing = realmRegistry.runtimeFor(realmId)?.context
            OwnerRealmDevice.register(
                cryptoManager = cryptoManager,
                realmRegistry = realmRegistry,
                realmId = realmId,
                derivedKeys = newKeys,
                masterKeyPair = masterKeyPair,
                retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey),
                personalPublicKey = syncManager.getDeviceSigningKeyPair().publicKey,
                reuse = existing,
            )
        }
    }

    private fun readRealmMembers(realmId: String): List<Member> {
        // Derived from the relation's declared spec (§9.2 / D4).
        val ref = executionContext.relationReference(IdentityService.Relations.MEMBERS)
        return (executionContext.database.getArrayData(ref)?.toList() ?: emptyList())
            .mapNotNull { it as? Member }
            .filter { it.realmId.get() == realmId }
    }

    private suspend fun admitDevice(
        capability: MembershipCapability,
        authorityPrivateKey: ByteArray,
        deviceKey: SyncCryptoManager.KeyPair,
        deviceId: String,
    ) {
        val boundAt = nowMs()
        val binding = MemberDeviceBinding(
            realmId = capability.realmId,
            deviceId = deviceId,
            perRealmDevicePublicKey = deviceKey.publicKey,
            boundAt = boundAt,
            signature = cryptoManager.signEd25519(
                authorityPrivateKey,
                MemberDeviceBinding.message(capability.realmId, deviceId, deviceKey.publicKey, boundAt),
            ),
        )
        relay.admitMember(
            capability.realmId, capability, binding,
            admittedAtHlc = realmRegistry.admissionStampFor(capability.realmId, executionContext.context),
        ).failing {
            throw SharedRealmGrantException("relay rejected the member device admission")
        }
    }

    /**
     * The realm master keypair (A1) for [realmId] — from the in-memory [RealmContext]
     * this session, else rehydrated from the durable [OwnedRealmMaster] row (GR-6). In
     * the durable path the realm is (re-)registered with the master key + Kr so the
     * owner's Member-row writes route into the shared realm. Throws if this device is
     * not the owner (no master row).
     */
    private suspend fun ownerMasterKeyPair(
        realmId: String,
        myEnc: SyncCryptoManager.KeyPair,
    ): SyncCryptoManager.KeyPair {
        realmRegistry.runtimeFor(realmId)?.context?.masterKeyPair?.let { return it }

        val owned = readOwnedRealmMaster(realmId)
            ?: throw SharedRealmGrantException(
                "this device does not hold the realm master key — only the owner can grant or remove members",
            )
        val masterKeyPair = SyncCryptoManager.KeyPair(owned.masterPublicKey.get(), owned.masterPrivateKey.get())

        val membership = readMembership(realmId)
            ?: throw SharedRealmGrantException("no membership row for this realm")
        val kr = cryptoManager.unwrapKeyForIdentity(membership.wrappedMasterKey.get(), myEnc.privateKey)
        // Register as OWNER — ⚠⚠ under a per-realm key and realm-local id (D10, threat model T-GR-05),
        // never the personal A4 — and admit that key with the master just read. Best-effort: a failed
        // admission leaves the grant itself working; the realm stream retries on the next registration.
        val realm = OwnerRealmDevice.register(
            cryptoManager = cryptoManager,
            realmRegistry = realmRegistry,
            realmId = realmId,
            derivedKeys = cryptoManager.deriveSharedRealmKeys(kr),
            masterKeyPair = masterKeyPair,
            retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey),
            personalPublicKey = syncManager.getDeviceSigningKeyPair().publicKey,
        )
        runCatchingCancellable {
            OwnerRealmDevice.admitSelf(
                cryptoManager, realm, relay,
                admittedAtHlc = realmRegistry.admissionStampFor(realmId, executionContext.context),
            )
        }
        // MR-1 §3.5: this device just registered the realm as an owner for this session — the
        // "start-up" of a shared realm, since nothing re-registers one at process start.
        runCatchingCancellable { membershipRecords.reEmit(realmId) }
        return masterKeyPair
    }

    private fun ensureOwnerMemberRow(
        realmId: String,
        ownerMemberId: String,
        ownerFingerprint: String,
        masterKeyPair: SyncCryptoManager.KeyPair,
    ) {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Member.ID,
            keyValue = Member.compositeKey(realmId, ownerMemberId),
        )
        // ⚠ An existing row is kept only if it verifies: one written before MR-3 is unsigned, and would
        // otherwise leave the owner unlisted for ever (`GetRealmMembers` lists signed rows only).
        val existing = executionContext.database.getData(dataId) as? Member
        if (existing != null && MemberRowAttestation.isValid(existing, verifier)) return
        executionContext.saveData(
            signedMemberRow(realmId, ownerMemberId, "", ownerFingerprint, Member.Role.OWNER, nowMs(), masterKeyPair),
        )
    }

    /**
     * Adopt the `Kr′` carried by a re-key [payload] (GR-7): unwrap it with our A13 and
     * [adoptNewRealmKey] (retire the old key, swap the membership, re-register). Used by
     * the silent poll path and the defensive accept branch. [masterKeyPair] is null — a
     * re-key recipient is a member, not the realm owner.
     */
    /**
     * Adopt a re-key — only once [isAuthorisedRekey] says the realm master sent it, and never onto a key this device
     * already retired. Returns whether it was adopted.
     */
    private fun applyRekey(
        payload: GrantPayload,
        myEnc: SyncCryptoManager.KeyPair,
        /** The contact whose address it arrived at — recorded as the realm's grantor when none is yet ([RealmGrantor]). */
        viaContactFingerprint: String? = null,
    ): Boolean {
        if (!isAuthorisedRekey(payload)) {
            println("SharedRealmGrantManager -> refusing a re-key for ${payload.realmId}: not signed by the realm master for this member")
            return false
        }
        // ⚠ A pure re-key (no A26 shipped) is for the member key we hold — never one lifted from another grant.
        val ownAuthority = executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, MemberAuthorityKey.ID, payload.realmId),
        ) as? MemberAuthorityKey
        if (payload.memberAuthorityPrivateKey.isEmpty() && ownAuthority != null &&
            !payload.capability.memberAuthorityPublicKey.contentEquals(ownAuthority.publicKey.get())
        ) {
            println("SharedRealmGrantManager -> refusing a re-key for ${payload.realmId}: it names another member key")
            return false
        }
        // ⚠⚠ Newer than the last one adopted, or refused: a skipped re-key replayed later is genuinely signed and names a
        // key this device never held, so its retired keyring cannot catch it ([RealmGrantor.lastKeyGeneration]).
        val grantor = executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, org.tenshu.identity.RealmGrantor.ID, payload.realmId),
        ) as? org.tenshu.identity.RealmGrantor
        val generation = payload.keyGeneration
        val lastGeneration = grantor?.lastKeyGeneration?.toEpochMilliseconds()
        if (lastGeneration != null && generation < lastGeneration) {
            println("SharedRealmGrantManager -> refusing a re-key for ${payload.realmId}: an older key generation than ours")
            return false
        }
        // ⚠ The relay can re-serve an older genuine drop from the same address: its key is one a later rotation
        // retired here, and adopting it would roll the realm back onto the key a removed member holds.
        val retired = DataId.StringDataId.create(
            executionContext.context,
            RetainedRealmKey.ID,
            RetainedRealmKey.compositeKey(payload.realmId, sha256(payload.wrappedRealmKey).toHexString()),
        )
        val newKr = cryptoManager.unwrapKeyForIdentity(payload.wrappedRealmKey, myEnc.privateKey)
        // ⚠ The same generation under a different key is a fork or a stale owner — never a reason to switch.
        val sameGenerationOtherKey = lastGeneration != null && generation == lastGeneration &&
            readMembership(payload.realmId)?.let { current ->
                !cryptoManager.unwrapKeyForIdentity(current.wrappedMasterKey.get(), myEnc.privateKey).contentEquals(newKr)
            } == true
        if (sameGenerationOtherKey) {
            println("SharedRealmGrantManager -> refusing a re-key for ${payload.realmId}: another key of the generation we hold")
            return false
        }
        if (executionContext.database.getData(retired) != null || isRetiredKey(payload.realmId, newKr, myEnc)) {
            println("SharedRealmGrantManager -> refusing a re-key for ${payload.realmId}: it names a key already retired here")
            return false
        }
        val keyChanged = readMembership(payload.realmId)?.let { current ->
            !cryptoManager.unwrapKeyForIdentity(current.wrappedMasterKey.get(), myEnc.privateKey).contentEquals(newKr)
        } ?: true
        adoptNewRealmKey(payload.realmId, newKr, myEnc, masterKeyPair = null)
        // ⚠ Backfilled from the delivering contact when absent (a membership from before the note), so it gains the
        // ordering check and its key requests from now on, not never.
        val grantorFingerprint = grantor?.grantorFingerprint?.get() ?: viaContactFingerprint
        if (grantorFingerprint != null) {
            executionContext.saveData(
                org.tenshu.identity.RealmGrantor.create(executionContext.context, payload.realmId, grantorFingerprint, generation),
            )
        }
        // ⚠ Only a key that actually changed settles a request: a lagging owner (or a late duplicate) repeating the key we
        // already hold leaves us exactly as behind as before. (A forged trigger therefore keeps asking — once per interval.)
        if (keyChanged) realmRegistry.keyRequests.adopted(payload.realmId)
        return true
    }

    /**
     * GR-7: the realm master signed exactly this wrapped key ([RealmRekey]) — so the realm's owner, and nobody else,
     * chose the key. The wrap is to our own A13 (we just unwrapped it), so the signature cannot be lifted from another
     * member's payload; the member key it names is the payload's own (a re-key names our stored A26, a re-grant to a
     * member a fresh one), and either way only the master could have signed it.
     * ⚠ A master-signed *capability* is not enough: a removed member holds their own, with its A26 private key, and
     * could pair it with a key of their choosing.
     */
    private fun isAuthorisedRekey(payload: GrantPayload): Boolean {
        val masterPublicKey = payload.masterPublicKey
        if (masterPublicKey.isEmpty() || payload.rekeySignature.isEmpty()) return false
        if (org.tenshu.core.common.security.ownerIdForMasterPublicKey(masterPublicKey) != payload.realmId) return false
        return cryptoManager.verifyEd25519(
            masterPublicKey,
            org.tenshu.sync.common.RealmRekey.message(
                payload.realmId,
                payload.capability.memberAuthorityPublicKey,
                payload.wrappedRealmKey,
                payload.capability.issuedAt,
                payload.keyGeneration,
            ),
            payload.rekeySignature,
        )
    }

    /** Whether [kr] is a key in this realm's retired keyring here (the wrapped bytes differ per wrap, so compare keys). */
    private fun isRetiredKey(realmId: String, kr: ByteArray, myEnc: SyncCryptoManager.KeyPair): Boolean {
        val derived = cryptoManager.deriveSharedRealmKeys(kr)
        return buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey).any { it.keys == derived }
    }

    /** True iff this device already holds a [SharedRealmMembership] for [realmId] (so an inbound grant is a re-key). */
    private fun isExistingMember(realmId: String): Boolean = readMembership(realmId) != null

    /**
     * True iff [pending] is an obsolete quarantine card: it unseals to a realm we are
     * already a member of (so it was already accepted — a resurrected synced create, or
     * a card superseded by a re-key). Such a card must not block a fresh pickup. A card
     * we cannot unseal is treated as *not* stale (leave it; don't silently discard
     * something we can't reason about).
     */
    private fun isStalePending(pending: PendingGrant, myEnc: SyncCryptoManager.KeyPair): Boolean {
        val realmId = try {
            unsealGrant(
                cryptoManager,
                pending.sealedGrant.get(),
                myEnc.privateKey,
                pending.address.get().hexToByteArray(),
            ).realmId
        } catch (_: Exception) {
            return false
        }
        return isExistingMember(realmId)
    }

    private fun readMembership(realmId: String): SharedRealmMembership? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = SharedRealmMembership.ID,
                keyValue = realmId,
            ),
        ) as? SharedRealmMembership

    private fun readOwnedRealmMaster(realmId: String): OwnedRealmMaster? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = OwnedRealmMaster.ID,
                keyValue = realmId,
            ),
        ) as? OwnedRealmMaster

    // ---- RO D7: acceptances ----------------------------------------------------------------------

    /** Member side: sign (A26) and seal our acceptance to the grantor, into the durable outbox. */
    private fun queueAcceptance(
        realmId: String,
        authorityPublicKey: ByteArray,
        authorityPrivateKey: ByteArray,
        grantor: GrantContact,
        myEnc: SyncCryptoManager.KeyPair,
    ) {
        val displayName = ""
        val contactFingerprint = sha256(myEnc.publicKey).toHexString()
        val acceptedAt = nowMs()
        val payload = AcceptancePayload(
            realmId = realmId,
            memberAuthorityPublicKey = authorityPublicKey,
            displayName = displayName,
            contactFingerprint = contactFingerprint,
            acceptedAt = acceptedAt,
            signature = cryptoManager.signEd25519(
                authorityPrivateKey,
                MembershipAcceptance.message(realmId, authorityPublicKey, displayName, contactFingerprint, acceptedAt),
            ),
        )
        val address = deriveAcceptanceAddress(grantor.a13PublicKey, myEnc.publicKey, grantor.grantSeed, realmId)
        executionContext.saveData(
            OutboundAcceptance.create(
                executionContext.context,
                realmId = realmId,
                address = address.toHexString(),
                sealedAcceptance = sealAcceptance(cryptoManager, payload, grantor.a13PublicKey, address),
            ),
        )
    }

    /**
     * Member side: drop every queued acceptance; a delivered one leaves the outbox.
     *
     * ⚠⚠ A refusal never drops it. Above all a `402` (`ENTITLEMENT_ENFORCE`, maintainer decision 2026-11-12):
     * the member must create an account — the free tier counts — before the owner can learn they joined. The
     * row stays queued and is retried on every poll, and is marked [OutboundAcceptance.needsAccount] so the app
     * says "Create a free account to join this folder" instead of leaving them silently "Invited".
     */
    private suspend fun deliverOutboundAcceptances() {
        val ref = executionContext.relationReference(IdentityService.Relations.OUTBOUND_ACCEPTANCES)
        val queued = executionContext.database.getArrayData(ref)?.toList().orEmpty().mapNotNull { it as? OutboundAcceptance }
        for (row in queued) {
            val result = relay.dropGrant(row.address.get(), row.sealedAcceptance.get())
            if (result is TResult.Failure<*>) {
                // ⚠ Only a 402 decides it. A network error, 5xx or 429 says nothing about the account, so it keeps
                // the flag as it was — else one flaky retry hides the notice while the member still needs an account.
                // A successful drop deletes the row, which is what clears it.
                val needsAccount = row.needsAccount.get() || TenshuException(result.error).isEntitlementRejection()
                if (needsAccount != row.needsAccount.get()) {
                    executionContext.saveData(
                        OutboundAcceptance.create(
                            executionContext.context, row.realmId.get(), row.address.get(), row.sealedAcceptance.get(),
                            needsAccount = needsAccount,
                        ),
                    )
                }
                continue
            }
            executionContext.deleteData(
                DataId.StringDataId.create(executionContext.context, OutboundAcceptance.ID, row.realmId.get()),
            )
        }
    }

    /**
     * Owner side: pick up each awaited member's acceptance, verify it, and write that member's `Member` row
     * (RO D7) — so the member list is owner-attested. An acceptance is honoured only if it is for the realm
     * and member we granted, from the contact we granted it to, signed by that member's A26, and the member
     * is not revoked.
     *
     * ⚠ A pickup is destructive: a blob that fails these checks is gone, and that member stays "invited"
     * until they accept again. ⚠ Idempotent: a `Member` row already present just retires the wait — another
     * owner device got there first.
     */
    private suspend fun processAwaitedAcceptances(myEnc: SyncCryptoManager.KeyPair) {
        val ref = executionContext.relationReference(IdentityService.Relations.AWAITED_ACCEPTANCES)
        val awaited = executionContext.database.getArrayData(ref)?.toList().orEmpty().mapNotNull { it as? AwaitedAcceptance }
        // First retire waits that are already over: the member was removed, or their row is written.
        val open = awaited.filter { wait ->
            val realmId = wait.realmId.get()
            val memberId = wait.memberId.get()
            val memberRowId = DataId.StringDataId.create(executionContext.context, Member.ID, Member.compositeKey(realmId, memberId))
            // ⚠ Only a row that verifies counts as "already written": a squatted or stale row must not retire the wait.
            val existingRow = executionContext.database.getData(memberRowId) as? Member
            val over = membershipRecords.isRevoked(realmId, memberId) ||
                (existingRow != null && MemberRowAttestation.isValid(existingRow, verifier))
            if (over) executionContext.deleteData(awaitedAcceptanceDataId(memberId))
            !over
        }
        // ⚠⚠ One pickup per ADDRESS, not per wait. Two grants of the same realm to the same contact (granted twice
        // before acceptance) share one acceptance address; picking it up per wait let the older wait take the
        // newer grant's acceptance, refuse it as the wrong key, and discard it — the member stayed "invited" for good.
        for ((key, waits) in open.groupBy { it.realmId.get() to it.granteeFingerprint.get() }) {
            val (realmId, granteeFingerprint) = key
            val contact = directory.byFingerprint(granteeFingerprint)?.takeIf { it.isGrantable } ?: continue
            // ⚠⚠ Resolve the realm master BEFORE the pickup: the pickup is destructive, and a throw after it (the
            // master or membership not synced yet) would lose a verified acceptance for good. Registers the realm
            // as owner, so the row below routes into it.
            val masterKeyPair = runCatchingCancellable { ownerMasterKeyPair(realmId, myEnc) }.getOrNull() ?: continue
            // ⚠⚠ And wait until this device holds the realm's CURRENT key: the re-key below would otherwise ship a
            // retired `Kr`, and the member would stay on it for good ([mustWaitForRotation]).
            if (runCatchingCancellable { mustWaitForRotation(realmId, myEnc, masterKeyPair) }.getOrDefault(true)) continue
            val address = deriveAcceptanceAddress(myEnc.publicKey, contact.a13PublicKey, contact.grantSeed, realmId)
            val sealed = (relay.pickupGrant(address.toHexString()) as? TResult.Success)?.value?.get() ?: continue
            val acceptance = try {
                unsealAcceptance(cryptoManager, sealed, myEnc.privateKey, address)
            } catch (_: Exception) {
                continue
            }
            // Route the acceptance to the grant it names.
            val memberId = MembershipRecordVerifier.memberIdOf(acceptance.memberAuthorityPublicKey)
            val wait = waits.firstOrNull { it.memberId.get() == memberId }
            val roster = wait?.let { readGrantedMembership(memberId) }
            val genuine = roster != null && isGenuineAcceptance(
                acceptance, realmId, memberId, roster.memberAuthorityPublicKey.get(), contact.fingerprint,
                cryptoManager::verifyEd25519,
            )
            if (!genuine) {
                println("SharedRealmGrantManager -> refusing an acceptance for $memberId that does not verify")
                continue
            }
            // The member's row, written and signed by us (RO D7, MR-3).
            ensureOwnerMemberRow(realmId, sha256(masterKeyPair.publicKey).toHexString(), sha256(myEnc.publicKey).toHexString(), masterKeyPair)
            executionContext.saveData(
                signedMemberRow(
                    realmId, memberId, acceptance.displayName, acceptance.contactFingerprint, Member.Role.MEMBER,
                    acceptance.acceptedAt, masterKeyPair,
                ),
            )
            // ⚠ If the key this grant carried has since been retired, deliver the current one now, or the member —
            // joined and unrevoked — cannot read anything written after the rotation. Decided by the grant's own key
            // id against the content-addressed retired-key rows (see [AwaitedAcceptance.grantedKeyId]).
            if (roster != null && wait != null && carriedARetiredKey(realmId, wait)) {
                val ownMembership = readMembership(realmId)
                if (ownMembership != null) {
                    val currentKr = cryptoManager.unwrapKeyForIdentity(ownMembership.wrappedMasterKey.get(), myEnc.privateKey)
                    val currentKeyId = sha256(ownMembership.wrappedMasterKey.get()).toHexString()
                    val currentGeneration = keyGenerationOf(realmId)
                    runCatchingCancellable { dropRekey(realmId, roster, contact, currentKr, currentKeyId, currentGeneration, myEnc, masterKeyPair) }
                }
            }
            // The accepted grant's wait ends — and so do this contact's other waits for this realm: a member joins a
            // realm once, so an older grant of the same realm to them can never be accepted (theirs was replaced in
            // the one-slot grant mailbox, or arrives as a re-key, which keeps no A26). ⚠ Its capability stays valid at
            // the relay, harmlessly: no device ever holds that A26's private key, so nothing can present it.
            for (other in waits) executionContext.deleteData(awaitedAcceptanceDataId(other.memberId.get()))
        }
    }

    /**
     * MR-3: a `Member` row signed by the realm master (`MemberRowAttestation`). ⭐ Only the owner writes rows
     * (RO D7), so every valid row carries this signature; a member cannot rename another or invent one.
     */
    private fun signedMemberRow(
        realmId: String,
        memberId: String,
        displayName: String,
        contactFingerprint: String,
        role: String,
        joinedAt: Long,
        masterKeyPair: SyncCryptoManager.KeyPair,
        // ⚠ Strictly after the row it replaces: peers drop a row older than the one they hold, so an owner device whose
        // clock is behind another's must not sign an edit that every peer then refuses.
        signedAt: Long = maxOf(
            nowMs(),
            (
                (executionContext.database.getData(
                    DataId.StringDataId.create(executionContext.context, Member.ID, Member.compositeKey(realmId, memberId)),
                ) as? Member)?.signedAt?.toEpochMilliseconds() ?: 0L
                ) + 1,
        ),
    ): Member = Member.create(
        context = executionContext.context,
        realmId = realmId,
        memberId = memberId,
        displayName = displayName,
        contactFingerprint = contactFingerprint,
        role = role,
        joinedAt = joinedAt,
        signerPublicKey = masterKeyPair.publicKey,
        signature = cryptoManager.signEd25519(
            masterKeyPair.privateKey,
            MemberRowAttestation.message(realmId, memberId, displayName, contactFingerprint, role, joinedAt, signedAt),
        ),
        signedAt = signedAt,
    )

    private val verifier = org.tenshu.sync.common.Ed25519SignatureVerifier(cryptoManager::verifyEd25519)

    /**
     * Whether an owner must hold off handing out [realmId]'s key, because this device's membership carries a key a
     * rotation already retired.
     *
     * A rotation saves two rows, the retired key and then the new membership, and they sync separately. So a device
     * between them is either *behind* another owner device's rotation (the new membership is on its way) or holds a
     * rotation *cut short* between its saves (it never comes). The two look the same. A recent retirement is
     * treated as behind: wait. One older than [ABANDONED_ROTATION_AFTER_MS] is treated as cut short: finish it here
     * with a fresh key ([rotateAndRegrant]), which also re-keys every member it knows is not revoked. It runs
     * lazily, on the next grant, removal or waiting acceptance — nothing schedules it.
     * ⚠ If the device was in fact only offline that long, the result is two concurrent rotations — the
     * `shared-realm-membership-grant.md` §14.3 limit.
     */
    private suspend fun mustWaitForRotation(
        realmId: String,
        myEnc: SyncCryptoManager.KeyPair,
        masterKeyPair: SyncCryptoManager.KeyPair,
    ): Boolean {
        val retired = retiredRowForCurrentKey(realmId)
        if (retired == null) {
            realmRegistry.clearStuck(realmId)
            return false
        }
        val age = nowMs() - retired.retiredAt.toEpochMilliseconds()
        if (age < ABANDONED_ROTATION_AFTER_MS) return true
        // ⚠⚠ And only once both streams are caught up since this device found the realm stuck: the revocation
        // records (so no removed member is re-keyed) and any newer membership. A device that has merely been
        // offline past the window is behind, not abandoned.
        // ⚠⚠ But never for good. A stream can fail to complete indefinitely (a schema fence, a backward walk
        // that will not descend, a registration backoff), and while it does, this owner can neither grant nor
        // REMOVE anyone — worse than the risk the gate exists for. Past twice the window the recovery goes
        // ahead on time alone.
        if (!realmRegistry.caughtUpSinceStuck(realmId, nowMs())) {
            // ⚠⚠ Both clocks: `retiredAt` is the rotating device's, so a device just back from days offline would
            // otherwise skip the gate on its first look, before any pull. It must also have watched the realm stuck
            // itself for a while.
            if (age < 2 * ABANDONED_ROTATION_AFTER_MS) return true
            if (realmRegistry.stuckForMillis(realmId, nowMs()) < VALVE_LOCAL_OBSERVATION_MS) return true
            println(
                "WARN SharedRealmGrantManager -> realm $realmId never caught up after its rotation stalled; " +
                    "finishing the rotation on time alone (a removed member may be re-keyed if its revocation " +
                    "has not arrived here)",
            )
        }
        realmRegistry.clearStuck(realmId)
        println("SharedRealmGrantManager -> realm $realmId has held a retired key since ${retired.retiredAt.toEpochMilliseconds()} ms; finishing the rotation here")
        rotateAndRegrant(realmId, removedMemberId = "", myEnc, masterKeyPair)
        return retiredRowForCurrentKey(realmId) != null
    }

    /** The [RetainedRealmKey] row for this device's current key of [realmId], if a rotation already retired it. */
    private fun retiredRowForCurrentKey(realmId: String): RetainedRealmKey? {
        val wrapped = readMembership(realmId)?.wrappedMasterKey?.get() ?: return null
        val retiredId = DataId.StringDataId.create(
            executionContext.context,
            RetainedRealmKey.ID,
            RetainedRealmKey.compositeKey(realmId, sha256(wrapped).toHexString()),
        )
        return executionContext.database.getData(retiredId) as? RetainedRealmKey
    }

    /**
     * Waits until every save this session has made is sealed into an outbound block (`SyncBlockEmitter.awaitEmitIdle`).
     * Blocks are sealed when the emitter reaches them, under the realm's key *at that moment*.
     * ⚠⚠ This session's own emitters, found on this [executionContext]'s observers: the saves went through them.
     * A process-wide holder names whichever client session registered last, so another app's empty queue would
     * pass while this one's revocation still waits. ⚠ Bounded: a dead consumer never completes the barrier, and a
     * removal must not hang on it. On a timeout it goes ahead, with a WARN.
     */
    private suspend fun drainOwnEmitters() {
        val emitters = executionContext.observerRegistry.ofType<org.tenshu.sync.common.SyncBlockEmitter>()
        val drained = withTimeoutOrNull(EMIT_DRAIN_TIMEOUT) { emitters.forEach { it.awaitEmitIdle() } }
        if (drained == null) {
            println("WARN SharedRealmGrantManager -> the emitter did not drain before a rotation; records saved just before it may be sealed under the new key")
        }
    }

    /** Whether the key [wait]'s grant carried has since been retired by a rotation of [realmId]. */
    private fun carriedARetiredKey(realmId: String, wait: AwaitedAcceptance): Boolean {
        val keyId = wait.grantedKeyId.get()
        val retiredId = DataId.StringDataId.create(
            executionContext.context,
            RetainedRealmKey.ID,
            RetainedRealmKey.compositeKey(realmId, keyId),
        )
        return executionContext.database.getData(retiredId) != null
    }

    private fun awaitedAcceptanceDataId(memberId: String): DataId.StringDataId =
        DataId.StringDataId.create(executionContext.context, AwaitedAcceptance.ID, memberId)

    private fun readGrantedMembership(memberId: String): GrantedMembership? =
        executionContext.database.getData(grantedMembershipDataId(memberId)) as? GrantedMembership

    private fun grantedMembershipDataId(memberId: String): DataId.StringDataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = GrantedMembership.ID,
            keyValue = memberId,
        )

    private fun readPendingGrant(grantorFingerprint: String): PendingGrant? =
        executionContext.database.getData(pendingGrantDataId(grantorFingerprint)) as? PendingGrant

    private fun deletePendingGrant(grantorFingerprint: String) {
        executionContext.deleteData(pendingGrantDataId(grantorFingerprint))
    }

    private fun pendingGrantDataId(grantorFingerprint: String): DataId.StringDataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = PendingGrant.ID,
            keyValue = grantorFingerprint,
        )

    private fun readAllPendingGrants(): List<PendingGrant> {
        val ref = executionContext.relationReference(IdentityService.Relations.PENDING_GRANTS)
        return (executionContext.database.getArrayData(ref)?.toList() ?: emptyList())
            .mapNotNull { it as? PendingGrant }
    }

    private inline fun TResult<*>.failing(onFailure: () -> Unit) {
        if (this is TResult.Failure) onFailure()
    }

    private companion object {
        /** Capability epoch — matches the device-admission convention (epoch 0 in v1). */
        const val CAP_EPOCH = 0L

        /** How long a retired-but-current key is taken as "behind" before the rotation counts as cut short (24 h). */
        const val ABANDONED_ROTATION_AFTER_MS = 24L * 60 * 60 * 1000

        /** A key request older than this is not answered: a relay can re-serve an old sealed one at will. */
        const val KEY_REQUEST_MAX_AGE_MS = 2 * KeyRequestTracker.MIN_INTERVAL_MS

        /** How long this device must itself have seen a realm stuck before the time-only valve may fire (1 h). */
        const val VALVE_LOCAL_OBSERVATION_MS = 60L * 60 * 1000

        /** How long a rotation waits for this session's emitter to drain before going ahead regardless. */
        val EMIT_DRAIN_TIMEOUT = 30.seconds

        /**
         * Revocation epoch — carried for wire compatibility only. ⚠ The verifier is
         * **key-scoped and ignores it**: `MembershipChainVerifier.isRevoked` matches on
         * `memberAuthorityPublicKey` alone, because the `>= CAP_EPOCH` conjunct this once
         * named was inert rather than protective (F4). Do not "finish" that comparison.
         */
        const val REVOKE_EPOCH = 0L
    }
}

/** Thrown by [SharedRealmGrantManager] when a grant/accept/remove can't proceed. */
class SharedRealmGrantException(message: String) : Exception(message)

/**
 * RO D7: whether [acceptance] is the one the owner is waiting for — for [realmId] and [memberId], from the
 * A26 key the owner kept at grant time ([grantedAuthorityPublicKey]), sent by the contact it granted to
 * ([granteeFingerprint]), and signed by that A26 over `MembershipAcceptance.message`. Pure, so the refusal
 * cases are testable without a relay.
 */
internal fun isGenuineAcceptance(
    acceptance: AcceptancePayload,
    realmId: String,
    memberId: String,
    grantedAuthorityPublicKey: ByteArray,
    granteeFingerprint: String,
    verify: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean,
): Boolean {
    val authorityPub = acceptance.memberAuthorityPublicKey
    return acceptance.realmId == realmId &&
        authorityPub.contentEquals(grantedAuthorityPublicKey) &&
        MembershipRecordVerifier.memberIdOf(authorityPub) == memberId &&
        acceptance.contactFingerprint == granteeFingerprint &&
        verify(
            authorityPub,
            MembershipAcceptance.message(realmId, authorityPub, acceptance.displayName, acceptance.contactFingerprint, acceptance.acceptedAt),
            acceptance.signature,
        )
}
