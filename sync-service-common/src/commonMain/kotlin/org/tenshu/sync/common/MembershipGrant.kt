package org.tenshu.sync.common

import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.common.util.toLong

/**
 * The Q30 capability chain for cross-user shared-realm membership
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D1/D2/D6).
 *
 * Three Ed25519-signed records make a foreign user's devices admissible at the
 * relay without the relay ever holding a membership roster (D1):
 *
 * ```
 * capability    = LP("tenshu-member-cap-v2")    ‖ LP(realmId) ‖ LP(memberAuthorityPub) ‖ LP(epoch) ‖ LP(issuedAt)
 *                  — Ed25519(realm master)
 * deviceBinding = LP("tenshu-member-bind-v2")   ‖ LP(realmId) ‖ LP(deviceId') ‖ LP(perRealmDevicePub) ‖ LP(boundAt)
 *                  — Ed25519(member authority)
 * revocation    = LP("tenshu-member-revoke-v2") ‖ LP(realmId) ‖ LP(memberAuthorityPub) ‖ LP(epoch') ‖ LP(revokedAt)
 *                  — Ed25519(realm master)
 * ```
 *
 * The two-link chain — master signs the **member authority** key (D2's fresh
 * per-(member, realm) A26), the member authority signs each of the member's
 * **per-realm device** keys — lets the relay verify admission against only the
 * `realm → masterPub` binding it already stores from genesis. [MembershipChainVerifier]
 * performs the verification; these classes carry the fields + signature and
 * rebuild their canonical signed bytes.
 *
 * The byte layout is [lengthPrefixedSignedMessage] (`admission-revocation-names-the-capability.md`
 * §7d): every field, the domain tag included, 4-byte-BE length-prefixed, so field
 * boundaries are unambiguous. The tags moved `-v1` → `-v2` with the encoding (protocol-1
 * re-cut, 2026-10-28) so a pre-prefix signature fails as a plain mismatch. Client and
 * relay must produce identical bytes, so the `message()` builders are the single source
 * of truth for the wire shape.
 */

/**
 * `capability = realmId ‖ memberAuthorityPublicKey ‖ epoch ‖ issuedAt`, signed
 * by the realm master key (asset A1).
 *
 * ⚠⚠ [epoch] does **not** carry the realm's admission epoch, whatever D6/Q-d intended: every mint
 * site passes the constant `SharedRealmGrantManager.CAP_EPOCH = 0`. It is a reserved field, and
 * [MembershipChainVerifier.isRevoked] deliberately does not read it — revocation is decided by an
 * exact match on [memberAuthorityPublicKey] (F4, mirroring F1′). Wiring a live epoch in here would
 * re-import the failure it was removed for: a client whose epoch counter lagged would mint *below*
 * an existing revocation and be permanently, silently evicted
 * (`admission-revocation-names-the-capability.md` §7b.1).
 */
class MembershipCapability(
    val realmId: String,
    val memberAuthorityPublicKey: ByteArray,
    val epoch: Long,
    val issuedAt: Long,
    val signature: ByteArray,
) {
    /** The exact bytes [signature] is over — re-built for verification. */
    fun signedMessage(): ByteArray = message(realmId, memberAuthorityPublicKey, epoch, issuedAt)

    companion object {
        /**
         * Domain-separation tag (F4). ⚠⚠ Without it this message is **byte-identical** to a
         * [MembershipRevocation] for the same realm and authority key whose `revokedAt` equals this
         * `issuedAt`: same fields, same order, same signer (the realm master A1), and `epoch` pinned
         * to 0 on both sides by `SharedRealmGrantManager`. A capability's master signature then
         * verifies as a *revocation of its own holder* — an eviction primitive for anyone who holds
         * a capability, including the relay, which receives one on every
         * `POST /sync/realm-member/admit` and is supposed to be able to **verify** master signatures,
         * never mint one (House Rule #1). Pinned by `MembershipMessageConfusionTest`.
         */
        const val DOMAIN_TAG = "tenshu-member-cap-v2"

        fun message(
            realmId: String,
            memberAuthorityPublicKey: ByteArray,
            epoch: Long,
            issuedAt: Long,
        ): ByteArray = lengthPrefixedSignedMessage(
            DOMAIN_TAG,
            signedField(realmId),
            memberAuthorityPublicKey,
            signedField(epoch),
            signedField(issuedAt),
        )
    }
}

/**
 * `deviceBinding = realmId ‖ deviceId ‖ perRealmDevicePublicKey ‖ boundAt`,
 * signed by the **member authority** private key (A26). [deviceId] is the
 * member's fresh realm-local device id (`deviceId'` in the design — reusing the
 * personal device id would re-link what the per-realm key just unlinked, D2),
 * and [perRealmDevicePublicKey] is the per-realm device signing key whose JWTs
 * the relay will then accept for the shared `ownerId`.
 */
class MemberDeviceBinding(
    val realmId: String,
    val deviceId: String,
    val perRealmDevicePublicKey: ByteArray,
    val boundAt: Long,
    val signature: ByteArray,
) {
    fun signedMessage(): ByteArray = message(realmId, deviceId, perRealmDevicePublicKey, boundAt)

    companion object {
        /**
         * Domain-separation tag (F4) — tagged alongside its two siblings so no message in this
         * file is a bare concatenation. This one is signed by the member authority rather than A1,
         * so it was not part of the capability/revocation confusion, but leaving one message
         * untagged is how the next one gets written untagged.
         */
        const val DOMAIN_TAG = "tenshu-member-bind-v2"

        fun message(
            realmId: String,
            deviceId: String,
            perRealmDevicePublicKey: ByteArray,
            boundAt: Long,
        ): ByteArray = lengthPrefixedSignedMessage(
            DOMAIN_TAG,
            signedField(realmId),
            signedField(deviceId),
            perRealmDevicePublicKey,
            signedField(boundAt),
        )
    }
}

/**
 * `revocation = realmId ‖ memberAuthorityPublicKey ‖ epoch ‖ revokedAt`, signed
 * by the realm master key. Posting it stops the relay admitting any device
 * bound by that member authority (D6 step a); the matching per-realm device
 * registrations are purged, and Kr is rotated as the cryptographic backstop
 * (step b — out of GR-1 scope).
 *
 * ⚠ [epoch] is reserved and unread — see [MembershipCapability]. What makes this revocation bite is
 * that it names [memberAuthorityPublicKey], and a **fresh A26 is minted per grant**
 * (`SharedRealmGrantManager.grant`), so re-sharing with the same person mints a key no prior
 * revocation names. Revoking is therefore permanent *for that key* while never blocking a
 * re-grant — which is why dropping the epoch comparison is safe here.
 */
class MembershipRevocation(
    val realmId: String,
    val memberAuthorityPublicKey: ByteArray,
    val epoch: Long,
    val revokedAt: Long,
    val signature: ByteArray,
) {
    fun signedMessage(): ByteArray = message(realmId, memberAuthorityPublicKey, epoch, revokedAt)

    companion object {
        /** Domain-separation tag (F4) — the counterpart to [MembershipCapability.DOMAIN_TAG]. */
        const val DOMAIN_TAG = "tenshu-member-revoke-v2"

        fun message(
            realmId: String,
            memberAuthorityPublicKey: ByteArray,
            epoch: Long,
            revokedAt: Long,
        ): ByteArray = lengthPrefixedSignedMessage(
            DOMAIN_TAG,
            signedField(realmId),
            memberAuthorityPublicKey,
            signedField(epoch),
            signedField(revokedAt),
        )
    }
}

/**
 * Encode this capability for storage on `MemberAuthorityKey.capability`
 * (`shared-realm-membership-grant.md` §9.4, D9-b): `epoch ‖ issuedAt ‖ signature`
 * (8-byte BE `epoch`, 8-byte BE `issuedAt`, then the raw signature). The realm id
 * and member-authority public key already live on the row, so only the three
 * remaining signed fields ride here. Synced so every member device can rebuild
 * the master-signed capability ([membershipCapabilityFromStored]) it needs to
 * self-bind without the owner online.
 */
fun MembershipCapability.toStoredBytes(): ByteArray =
    epoch.toByteArray() + issuedAt.toByteArray() + signature

/**
 * Inverse of [toStoredBytes]: rebuild a [MembershipCapability] from a row's
 * [realmId] + [memberAuthorityPublicKey] + the [stored] `epoch ‖ issuedAt ‖
 * signature` blob.
 */
fun membershipCapabilityFromStored(
    realmId: String,
    memberAuthorityPublicKey: ByteArray,
    stored: ByteArray,
): MembershipCapability = MembershipCapability(
    realmId = realmId,
    memberAuthorityPublicKey = memberAuthorityPublicKey,
    epoch = stored.copyOfRange(0, 8).toLong(),
    issuedAt = stored.copyOfRange(8, 16).toLong(),
    signature = stored.copyOfRange(16, stored.size),
)

/**
 * A grantee's acceptance of a membership (`shared-realm-read-only-members.md` D7), signed by its **member
 * authority** (A26) and carried sealed to the grantor (`AcceptancePayload`). The owner verifies it against
 * the A26 public key the capability bound, then writes the member's `Member` row itself.
 *
 * ```
 * message = LP("tenshu-member-accept-v1") ‖ LP(realmId) ‖ LP(memberAuthorityPub) ‖ LP(displayName)
 *         ‖ LP(contactFingerprint) ‖ LP(acceptedAt)
 * ```
 * ⚠ A distinct tag, as F4 requires of every membership message: A26 also signs device bindings
 * (`tenshu-member-bind-v2`), and neither may verify as the other.
 */
object MembershipAcceptance {
    /** Never reuse for another layout; a new layout gets `-v2`. */
    const val DOMAIN_TAG = "tenshu-member-accept-v1"

    fun message(
        realmId: String,
        memberAuthorityPublicKey: ByteArray,
        displayName: String,
        contactFingerprint: String,
        acceptedAt: Long,
    ): ByteArray = lengthPrefixedSignedMessage(
        DOMAIN_TAG,
        signedField(realmId),
        memberAuthorityPublicKey,
        signedField(displayName),
        signedField(contactFingerprint),
        signedField(acceptedAt),
    )
}

/**
 * The realm master's attestation of one `Member` row (`shared-realm-membership-records.md` MR-3): since RO
 * D7 only the owner writes a member's row, and it signs it, so no member can rename another or invent one.
 *
 * ```
 * message = LP("tenshu-member-row-v1") ‖ LP(realmId) ‖ LP(memberId) ‖ LP(displayName)
 *         ‖ LP(contactFingerprint) ‖ LP(role) ‖ LP(joinedAt)
 * ```
 * A row is valid iff its signer's key binds to the realm id (`ownerIdForMasterPublicKey`) and the signature
 * verifies — self-certifying, so it can be checked on arrival, in any order.
 */
object MemberRowAttestation {
    /** Never reuse for another layout; a new layout gets `-v2`. */
    const val DOMAIN_TAG = "tenshu-member-row-v2"

    /**
     * ⚠ [signedAt] is signed so a row can be ordered by its owner, not by the block that carried it: anyone with the
     * realm key can re-push an older owner-signed row as a new block, which LWW would otherwise let win.
     */
    fun message(
        realmId: String,
        memberId: String,
        displayName: String,
        contactFingerprint: String,
        role: String,
        joinedAt: Long,
        signedAt: Long,
    ): ByteArray = lengthPrefixedSignedMessage(
        DOMAIN_TAG,
        signedField(realmId),
        signedField(memberId),
        signedField(displayName),
        signedField(contactFingerprint),
        signedField(role),
        signedField(joinedAt),
        signedField(signedAt),
    )

    /**
     * Whether [incoming] — already [isValid] — is older than a valid [stored] row at the same key, and so a replay
     * that must not replace it. Equal is the same signing (a re-emit of the identical row), and is let through.
     */
    fun isOlderThanStored(
        incoming: org.tenshu.identity.Member,
        stored: org.tenshu.identity.Member?,
        verify: Ed25519SignatureVerifier,
    ): Boolean {
        if (stored == null || !isValid(stored, verify)) return false
        val incomingAt = incoming.signedAt?.toEpochMilliseconds() ?: return true
        val storedAt = stored.signedAt?.toEpochMilliseconds() ?: return false
        return incomingAt < storedAt
    }

    /** Whether [row] carries a valid master signature for its own realm. Unsigned ⇒ false. */
    fun isValid(row: org.tenshu.identity.Member, verify: Ed25519SignatureVerifier): Boolean {
        val signer = row.signerPublicKey?.get()?.takeIf { it.isNotEmpty() } ?: return false
        val signature = row.signature?.get()?.takeIf { it.isNotEmpty() } ?: return false
        val signedAt = row.signedAt?.toEpochMilliseconds() ?: return false
        val realmId = row.realmId.get()
        // ⚠⚠ The row's KEY must be the one its signed content names. The key is not itself signed, so without
        // this a member could copy its own validly signed row to `"$realmId::<victim>"` and, by last-writer-wins,
        // hide the victim's row — or pre-claim a new member's key before the owner writes it.
        if (row.compositeKey.get() != org.tenshu.identity.Member.compositeKey(realmId, row.memberId.get())) return false
        if (org.tenshu.core.common.security.ownerIdForMasterPublicKey(signer) != realmId) return false
        return verify.verify(
            signer,
            message(
                realmId, row.memberId.get(), row.displayName.get(), row.contactFingerprint.get(), row.role.get(),
                row.joinedAt.toEpochMilliseconds(), signedAt,
            ),
            signature,
        )
    }
}

/**
 * The realm master's authorisation of a **re-key** (GR-7): "this member may switch this realm to the key
 * wrapped here". Signed by the owner device that rotates, carried in the grant payload, checked by the member
 * before it adopts anything.
 *
 * ```
 * message = LP("tenshu-realm-rekey-v1") ‖ LP(realmId) ‖ LP(memberAuthorityPub) ‖ LP(wrappedRealmKey) ‖ LP(issuedAt)
 * ```
 * ⚠⚠ Why the contact channel is not enough: a grant drop is an **anonymous** sealed box at an address the relay
 * serves, so its `grantorFingerprint` is self-asserted. A removed member colluding with the relay could otherwise
 * drop `{realmId, wrap(Kx, victimA13)}` and switch the victim to a key the attacker chose (T-GR-02). Only the realm
 * master can sign this, it names the member (their A26) and the exact wrapped key, so it cannot be redirected.
 * ⚠ A replay of an older genuine re-key is refused by the member for another reason: its key is retired there.
 */
object RealmRekey {
    /** Never reuse for another layout; a new layout gets `-v2`. (`-v1` carried no generation and never shipped.) */
    const val DOMAIN_TAG = "tenshu-realm-rekey-v2"

    /**
     * ⚠⚠ [keyGeneration] orders keys, not sends: the time this key replaced the one before it (the realm's newest
     * retirement, strictly increasing across owner devices). A member refuses a key of an older generation, so an
     * owner device that is behind — still on the key a removed member holds — can never roll it back, however fresh
     * the moment it answers. Ordering by when a re-key was *sent* let exactly that through.
     */
    fun message(
        realmId: String,
        memberAuthorityPublicKey: ByteArray,
        wrappedRealmKey: ByteArray,
        issuedAt: Long,
        keyGeneration: Long,
    ): ByteArray = lengthPrefixedSignedMessage(
        DOMAIN_TAG,
        signedField(realmId),
        memberAuthorityPublicKey,
        wrappedRealmKey,
        signedField(issuedAt),
        signedField(keyGeneration),
    )
}

/**
 * A member's request for a realm's **current** key (GR-7), signed by its member authority (A26), so an owner answers
 * only a member it granted — never a stranger asking it to send re-keys at someone.
 *
 * ```
 * message = LP("tenshu-realm-key-request-v1") ‖ LP(realmId) ‖ LP(memberAuthorityPub) ‖ LP(requestedAt)
 * ```
 * ⚠ Carries no authority over the key: the answer is the owner's ordinary signed re-key, checked like any other.
 */
object RealmKeyRequest {
    /** Never reuse for another layout; a new layout gets `-v2`. */
    const val DOMAIN_TAG = "tenshu-realm-key-request-v1"

    fun message(realmId: String, memberAuthorityPublicKey: ByteArray, requestedAt: Long): ByteArray =
        lengthPrefixedSignedMessage(DOMAIN_TAG, signedField(realmId), memberAuthorityPublicKey, signedField(requestedAt))
}
