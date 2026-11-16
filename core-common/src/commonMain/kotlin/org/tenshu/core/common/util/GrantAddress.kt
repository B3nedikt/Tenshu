package org.tenshu.core.common.util

/**
 * Derives the **grant address** that keys the relay grant mailbox
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D3; Q-c resolved
 * 2026-06-13).
 *
 * ```
 * address = HKDF-SHA-256(ikm = sharedSecret || grantorPublicKey || granteePublicKey,
 *                        salt = empty, info = "tenshu/grant/address/v1", length = 16)
 * ```
 *
 * The address is an HKDF of the completed contact handshake's shared context —
 * the handshake secret (`K_link`) both sides hold, bound to the two A13 public
 * keys — so it is **derivable only by the grantor and grantee, not a public
 * identifier**. The relay learns neither party's personal `ownerId` from
 * routing, and a third party cannot fetch the grant unless it already observed
 * the handshake secret (and even then the payload is sealed to the grantee's
 * A13, so a leaked address yields denial, never disclosure).
 *
 * **Directional.** The grantor and grantee keys enter in order, not sorted (the
 * deliberate contrast with [deriveContactSas], which sorts because a SAS is a
 * symmetric fingerprint). Alice→Bob and Bob→Alice therefore derive **distinct**
 * addresses, so a mutual pair can grant each other without colliding on one
 * mailbox slot. Bob derives the address Alice posts to from the same inputs and
 * polls it on Shared-screen init.
 *
 * Returns the raw 16-byte address. Callers hex-encode it for the relay route
 * (`/sync/grant/{address}`) and pass the raw bytes as the sealed-box AAD, the
 * way the invite mailbox uses the 16-byte `inviteId`.
 */
fun deriveGrantAddress(
    grantorPublicKey: ByteArray,
    granteePublicKey: ByteArray,
    sharedSecret: ByteArray,
): ByteArray =
    hkdfSha256(
        ikm = sharedSecret + grantorPublicKey + granteePublicKey,
        salt = ByteArray(0),
        info = GRANT_ADDRESS_INFO,
        length = GRANT_ADDRESS_LENGTH,
    )

/** Raw byte length of a grant address — matches the 16-byte `inviteId`. */
const val GRANT_ADDRESS_LENGTH = 16

private val GRANT_ADDRESS_INFO: ByteArray = "tenshu/grant/address/v1".encodeToByteArray()

/**
 * The **acceptance address** — where a grantee drops its sealed acceptance back to the grantor
 * (`shared-realm-read-only-members.md` D7; `shared-realm-membership-records.md` §3.3): the same handshake
 * context as [deriveGrantAddress], under a distinct HKDF `info`, and bound to [realmId].
 *
 * ```
 * address = HKDF-SHA-256(ikm = sharedSecret || grantorPublicKey || granteePublicKey || LP(realmId),
 *                        salt = empty, info = "tenshu/grant/acceptance/v1", length = 16)
 * ```
 *
 * ⚠⚠ **Per realm**, unlike the grant address: the mailbox keeps one blob per address, last write wins, so
 * a grantee accepting two of the same grantor's shares before the grantor polled would otherwise overwrite
 * the first acceptance — and that member would never appear. ⚠ The distinct `info` keeps it from ever
 * equalling a grant address, so an acceptance can never be picked up as a grant or the reverse.
 * Same directionality as [deriveGrantAddress]: `grantorPublicKey` is the one the grant came from.
 */
fun deriveAcceptanceAddress(
    grantorPublicKey: ByteArray,
    granteePublicKey: ByteArray,
    sharedSecret: ByteArray,
    realmId: String,
): ByteArray {
    val realm = realmId.encodeToByteArray()
    val lengthPrefix = byteArrayOf(
        (realm.size ushr 24).toByte(), (realm.size ushr 16).toByte(), (realm.size ushr 8).toByte(), realm.size.toByte(),
    )
    return hkdfSha256(
        ikm = sharedSecret + grantorPublicKey + granteePublicKey + lengthPrefix + realm,
        salt = ByteArray(0),
        info = ACCEPTANCE_ADDRESS_INFO,
        length = GRANT_ADDRESS_LENGTH,
    )
}

private val ACCEPTANCE_ADDRESS_INFO: ByteArray = "tenshu/grant/acceptance/v1".encodeToByteArray()

/**
 * The **re-key address** — where an owner drops a member's new realm key (GR-7): the same handshake context as
 * [deriveGrantAddress], bound to [realmId], under its own HKDF `info`.
 *
 * ```
 * address = HKDF-SHA-256(ikm = sharedSecret || grantorPublicKey || granteePublicKey || LP(realmId),
 *                        salt = empty, info = "tenshu/grant/rekey/v1", length = 16)
 * ```
 *
 * ⚠⚠ **Per realm.** The mailbox keeps one blob per address, last write wins: two re-keys to one contact for two
 * realms (two rotations, or a catch-up for each) at the contact-wide grant address would destroy the first while
 * both were recorded as delivered — that member stranded on the old key for good. ⚠ Per realm, too, so a re-key
 * can never overwrite an unread *invitation*, which keeps the grant address. ⚠ Contact-derived rather than
 * key-derived: a removed member holding an old realm key could compute a key-derived slot and overwrite it.
 */
fun deriveRekeyAddress(
    grantorPublicKey: ByteArray,
    granteePublicKey: ByteArray,
    sharedSecret: ByteArray,
    realmId: String,
): ByteArray {
    val realm = realmId.encodeToByteArray()
    val lengthPrefix = byteArrayOf(
        (realm.size ushr 24).toByte(), (realm.size ushr 16).toByte(), (realm.size ushr 8).toByte(), realm.size.toByte(),
    )
    return hkdfSha256(
        ikm = sharedSecret + grantorPublicKey + granteePublicKey + lengthPrefix + realm,
        salt = ByteArray(0),
        info = REKEY_ADDRESS_INFO,
        length = GRANT_ADDRESS_LENGTH,
    )
}

private val REKEY_ADDRESS_INFO: ByteArray = "tenshu/grant/rekey/v1".encodeToByteArray()

/**
 * The **key-request address** — where a member asks the contact that granted it realms for their current keys (GR-7):
 * the handshake context of [deriveGrantAddress], **member first** (the requester), under its own HKDF `info`.
 *
 * ```
 * address = HKDF-SHA-256(ikm = sharedSecret || requesterPublicKey || ownerPublicKey,
 *                        salt = empty, info = "tenshu/grant/key-request/v1", length = 16)
 * ```
 *
 * ⚠ Per contact, not per realm: an owner polls one such address per contact on every poll, which keeps its cost
 * proportional to contacts rather than to members × realms. One request therefore names **every** realm the member
 * wants keys for, so a second request never loses the first's realms. ⚠ Its own `info`, so it never equals a grant
 * address in the other direction — a request can never be picked up as an invitation, nor overwrite one.
 */
fun deriveKeyRequestAddress(
    requesterPublicKey: ByteArray,
    ownerPublicKey: ByteArray,
    sharedSecret: ByteArray,
): ByteArray =
    hkdfSha256(
        ikm = sharedSecret + requesterPublicKey + ownerPublicKey,
        salt = ByteArray(0),
        info = KEY_REQUEST_ADDRESS_INFO,
        length = GRANT_ADDRESS_LENGTH,
    )

private val KEY_REQUEST_ADDRESS_INFO: ByteArray = "tenshu/grant/key-request/v1".encodeToByteArray()
