package org.tenshu.core.common.util

/**
 * Derives the **grant seed** from the contact handshake's link secret
 * (`shared-realm-membership-grant.md` §9.1, D8).
 *
 * ```
 * grantSeed = HKDF-SHA-256(ikm = K_link, salt = empty,
 *                          info = "tenshu/grant/seed/v1", length = 32)
 * ```
 *
 * The grant mailbox address must be derivable by both grantor and grantee yet
 * secret to everyone else (Q-c). But the as-built CI layer retains no shared
 * secret long-term — the inviter persists only `SHA-256(K_link)` (asset A25) and
 * the invitee's `PendingRedemption` clears `K_link` within ≤72 h. So each side
 * derives this seed **during the handshake**, while it still transiently holds
 * `K_link`, and persists it on its copy of the other party's `TenshuIdentity`
 * (`TenshuIdentity.grantSeed`). The seed is a one-way HKDF of `K_link` — knowing
 * the seed never recovers `K_link`, and it never travels the wire.
 *
 * Feed the result as the `sharedSecret` of [deriveGrantAddress]; only the two
 * parties hold it, so the resulting address is secret, not a public identifier.
 */
fun deriveGrantSeed(linkSecret: ByteArray): ByteArray =
    hkdfSha256(
        ikm = linkSecret,
        salt = ByteArray(0),
        info = GRANT_SEED_INFO,
        length = GRANT_SEED_LENGTH,
    )

/** Byte length of a grant seed — a full-entropy 32-byte HKDF output. */
const val GRANT_SEED_LENGTH = 32

private val GRANT_SEED_INFO: ByteArray = "tenshu/grant/seed/v1".encodeToByteArray()
