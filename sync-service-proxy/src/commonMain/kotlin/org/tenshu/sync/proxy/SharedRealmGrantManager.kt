package org.tenshu.sync.proxy

import org.tenshu.contact.common.GrantCapability
import org.tenshu.contact.common.GrantPayload
import org.tenshu.contact.common.sealGrant
import org.tenshu.contact.common.unsealGrant
import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.util.deriveGrantAddress
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
 * explicitly so the personal-key [sharedRealmRelayFactory] doesn't shadow it).
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

        val payload = GrantPayload(
            grantId = sha256(authority.publicKey).toHexString(),
            realmId = realmId,
            wrappedRealmKey = cryptoManager.wrapKeyForIdentity(kr, contact.a13PublicKey),
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
        )

        val address = deriveGrantAddress(myEnc.publicKey, contact.a13PublicKey, contact.grantSeed)
        val sealed = sealGrant(cryptoManager, payload, contact.a13PublicKey, address)
        relay.dropGrant(address.toHexString(), sealed).failing {
            throw SharedRealmGrantException("could not deliver the grant to the relay mailbox")
        }

        // Lazy backfill: ensure the owner's own Member row exists in the realm (the
        // roster's first row; pre-GR realms never wrote one).
        ensureOwnerMemberRow(realmId, sha256(masterKeyPair.publicKey).toHexString(), sha256(myEnc.publicKey).toHexString())

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
            applyRekey(payload, myEnc)
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

        // Write our Member record into the realm — the D4 acceptance signal Alice sees.
        executionContext.saveData(
            Member.create(
                context = executionContext.context,
                realmId = realmId,
                memberId = sha256(capability.memberAuthorityPublicKey).toHexString(),
                displayName = "",
                contactFingerprint = sha256(myEnc.publicKey).toHexString(),
                role = Member.Role.MEMBER,
                joinedAt = nowMs(),
            ),
        )

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
                applyRekey(payload, myEnc)
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
        relay.revokeMember(
            realmId,
            MembershipRevocation(realmId, authorityPublicKey, REVOKE_EPOCH, revokedAt, signature),
        ).failing {
            throw SharedRealmGrantException("relay revocation failed")
        }

        // Tombstone the member's Member row (cross-member propagation of the removal
        // rides Kr rotation in GR-7; the relay revocation is the immediate cutoff).
        executionContext.deleteData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = Member.ID,
                keyValue = Member.compositeKey(realmId, memberId),
            ),
        )
        // Drop the owner-side roster row.
        executionContext.deleteData(grantedMembershipDataId(memberId))

        // GR-7: rotate Kr and re-grant Kr′ to the remaining members. The Member-row
        // tombstone above already removed [memberId] from the MEMBERS relation, so the
        // re-grant sweep below never targets the removed member.
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
        val krPrime = cryptoManager.generateSharedRealmMasterKey()
        adoptNewRealmKey(realmId, krPrime, myEnc, masterKeyPair)

        val grantorFingerprint = sha256(myEnc.publicKey).toHexString()
        for (member in readRealmMembers(realmId)) {
            if (member.role.get() != Member.Role.MEMBER) continue
            val memberId = member.memberId.get()
            if (memberId == removedMemberId) continue
            val contactFingerprint = member.contactFingerprint.get()
            if (contactFingerprint.isEmpty()) continue
            val contact = directory.byFingerprint(contactFingerprint)?.takeIf { it.isGrantable } ?: continue
            val roster = readGrantedMembership(memberId) ?: continue

            val authorityPub = roster.memberAuthorityPublicKey.get()
            val issuedAt = nowMs()
            val capabilitySignature = cryptoManager.signEd25519(
                masterKeyPair.privateKey,
                MembershipCapability.message(realmId, authorityPub, CAP_EPOCH, issuedAt),
            )
            val wrapped = cryptoManager.wrapKeyForIdentity(krPrime, contact.a13PublicKey)
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
            )
            val address = deriveGrantAddress(myEnc.publicKey, contact.a13PublicKey, contact.grantSeed)
            val sealed = sealGrant(cryptoManager, payload, contact.a13PublicKey, address)
            // Best-effort: a failed drop leaves that member on the old key (recoverable).
            relay.dropGrant(address.toHexString(), sealed)
        }
    }

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
            executionContext.saveData(
                RetainedRealmKey.create(
                    context = executionContext.context,
                    sharedRealmId = realmId,
                    keyId = sha256(oldWrapped).toHexString(),
                    wrappedMasterKey = oldWrapped,
                    retiredAt = nowMs(),
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
                wrappedMasterKey = cryptoManager.wrapKeyForIdentity(newKr, myEnc.publicKey),
                role = current.role.get(),
            ),
        )

        if (wasRegistered) {
            // The join observer skipped (already registered), so re-register manually
            // with the new keys, reusing the existing per-realm device key + relay kind.
            val existing = realmRegistry.runtimeFor(realmId)?.context
            // ⚠ The pair travels together: the existing context's (a per-realm key), else one
            // snapshot of this device's — never an id from one and a key from the other.
            val identity = existing?.let { SyncManager.DeviceKeyIdentity(it.deviceId, it.deviceSigningKeyPair) }
                ?: syncManager.currentDeviceIdentity()
            val newContext = RealmContext(
                ownerId = realmId,
                deviceId = identity.deviceId,
                derivedKeys = cryptoManager.deriveSharedRealmKeys(newKr),
                deviceSigningKeyPair = identity.signingKeyPair,
                masterKeyPair = masterKeyPair,
                retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey),
            )
            // Owner → default relay factory (personal-key JWT); member → per-realm key.
            if (masterKeyPair != null) {
                realmRegistry.register(newContext)
            } else {
                realmRegistry.register(newContext, grantedRelayFactory(newContext))
            }
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
        relay.admitMember(capability.realmId, capability, binding).failing {
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
    private fun ownerMasterKeyPair(
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
        // Register as OWNER (default relay factory → the owner's A4-JWT relay; the
        // per-realm grantedRelayFactory is for cross-user members, not the owner).
        realmRegistry.register(
            RealmContext(
                ownerId = realmId,
                deviceId = syncManager.currentDeviceId,
                derivedKeys = cryptoManager.deriveSharedRealmKeys(kr),
                deviceSigningKeyPair = syncManager.getDeviceSigningKeyPair(),
                masterKeyPair = masterKeyPair,
                retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey),
            ),
        )
        return masterKeyPair
    }

    private fun ensureOwnerMemberRow(realmId: String, ownerMemberId: String, ownerFingerprint: String) {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = Member.ID,
            keyValue = Member.compositeKey(realmId, ownerMemberId),
        )
        if (executionContext.database.getData(dataId) != null) return
        executionContext.saveData(
            Member.create(
                context = executionContext.context,
                realmId = realmId,
                memberId = ownerMemberId,
                displayName = "",
                contactFingerprint = ownerFingerprint,
                role = Member.Role.OWNER,
                joinedAt = nowMs(),
            ),
        )
    }

    /**
     * Adopt the `Kr′` carried by a re-key [payload] (GR-7): unwrap it with our A13 and
     * [adoptNewRealmKey] (retire the old key, swap the membership, re-register). Used by
     * the silent poll path and the defensive accept branch. [masterKeyPair] is null — a
     * re-key recipient is a member, not the realm owner.
     */
    private fun applyRekey(payload: GrantPayload, myEnc: SyncCryptoManager.KeyPair) {
        val newKr = cryptoManager.unwrapKeyForIdentity(payload.wrappedRealmKey, myEnc.privateKey)
        adoptNewRealmKey(payload.realmId, newKr, myEnc, masterKeyPair = null)
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
