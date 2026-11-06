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
