package org.tenshu.contact.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.security.SyncCryptoManager

/**
 * The sealed payload of a shared-realm membership grant
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D3) — the cross-user
 * analogue of [InviteResponsePayload]/[InviteAckPayload].
 *
 * Alice drops one of these onto the relay grant mailbox at the grant address
 * ([deriveGrantAddress][org.tenshu.core.common.util.deriveGrantAddress]); the
 * relay sees ciphertext + routing id only (House Rule #1). The payload is
 * **sealed to the grantee's A13** ([sealGrant]) so only Bob's devices open it —
 * a leaked mailbox address yields nothing without his A13 private key.
 *
 * Serialised as JSON bytes for the same reasons as the invite payloads (the
 * project's control-plane codec; tiny; the security binding is the AEAD's
 * `info` + grant-address AAD, not the codec). [version] bumps with the shape;
 * the sealed-box `info` ([GRANT_INFO]) carries the matching version segment.
 *
 * The embedded [GrantCapability] is the master-signed Q30 capability flattened
 * to serialisable fields — the orchestration layer maps it to/from
 * `MembershipCapability` in `:sync-service-common`, which this module does not
 * depend on.
 */
@Serializable
data class GrantPayload(
    val version: Int = 1,
    /** Opaque grant id — lets the grantee dedupe a re-grant (Kr rotation) from the original. */
    val grantId: String,
    /** The shared realm being granted — its `ownerId` (`= hash(realm masterPub)`). */
    val realmId: String,
    /**
     * The realm key (Kr / A12) wrapped to the grantee's A13 —
     * `SyncCryptoManager.wrapKeyForIdentity` output. Unwrapped by the grantee's
     * A13 private key into the `SharedRealmMembership.wrappedMasterKey` his
     * existing auto-join path already understands (D3).
     */
    val wrappedRealmKey: ByteArray,
    /** The master-signed capability admitting this member's authority key (D1/D2). */
    val capability: GrantCapability,
    /**
     * The **member-authority private key** (A26) the [capability]'s public key
     * binds — the owner mints the A26 keypair at grant time (Option A: the
     * pre-signed [capability] requires the owner to know the authority public
     * key at grant time, so it mints the pair) and ships the private half
     * here. It is safe inline: the whole payload is already sealed to the
     * grantee's A13 ([sealGrant]), so only the grantee's devices ever see it, and
     * the owner — who holds the realm master — could admit any device to the
     * realm regardless, so transiently minting A26 is no escalation. The grantee
     * persists it as a `MemberAuthorityKey` so every device of theirs self-signs
     * its own `MemberDeviceBinding` without the owner online (D2). The owner
     * discards it after sealing — it keeps only the public half, to revoke (D6).
     */
    val memberAuthorityPrivateKey: ByteArray,
    /** Advisory folder name for the accept prompt — no behaviour rides it (D7). */
    val label: String = "",
    /**
     * The grantor's A13 fingerprint, shown at the accept prompt and matched
     * against the grantee's `TenshuIdentity` for the grantor so a grant from an
     * unknown fingerprint is displayed loudly as such (D4 quarantine).
     */
    val grantorFingerprint: String,
)

/**
 * The Q30 membership capability as serialisable fields (mirror of
 * `MembershipCapability`): `realmId ‖ memberAuthorityPublicKey ‖ epoch ‖
 * issuedAt`, signed by the realm master.
 */
@Serializable
data class GrantCapability(
    val realmId: String,
    val memberAuthorityPublicKey: ByteArray,
    val epoch: Long,
    val issuedAt: Long,
    val signature: ByteArray,
)

/** Sealed-box HKDF/AAD domain for the grant payload (§4, D3). */
val GRANT_INFO: ByteArray = "tenshu/grant/payload/v1".encodeToByteArray()

private val json = Json { ignoreUnknownKeys = true }

fun GrantPayload.encode(): ByteArray =
    json.encodeToString(GrantPayload.serializer(), this).encodeToByteArray()

fun decodeGrantPayload(bytes: ByteArray): GrantPayload =
    json.decodeFromString(GrantPayload.serializer(), bytes.decodeToString())

/**
 * Seal [payload] to the grantee's A13 ([recipientEncPublicKey]) for the grant
 * mailbox, binding [grantAddress] (the raw 16-byte address from
 * [deriveGrantAddress][org.tenshu.core.common.util.deriveGrantAddress]) as AAD
 * so a blob cannot be cross-spliced onto a different address — structurally the
 * invite payloads' `inviteId` binding.
 */
fun sealGrant(
    crypto: SyncCryptoManager,
    payload: GrantPayload,
    recipientEncPublicKey: ByteArray,
    grantAddress: ByteArray,
): ByteArray =
    crypto.sealToIdentity(
        plaintext = payload.encode(),
        recipientEncPublicKey = recipientEncPublicKey,
        info = GRANT_INFO,
        aad = grantAddress,
    )

/**
 * Inverse of [sealGrant]. Throws (AEAD tag / AAD failure) when [myEncPrivateKey]
 * is not the grantee's A13 private key or [grantAddress] differs from the one
 * the blob was sealed under.
 */
fun unsealGrant(
    crypto: SyncCryptoManager,
    sealed: ByteArray,
    myEncPrivateKey: ByteArray,
    grantAddress: ByteArray,
): GrantPayload =
    decodeGrantPayload(
        crypto.unsealFromIdentity(
            sealed = sealed,
            myEncPrivateKey = myEncPrivateKey,
            info = GRANT_INFO,
            aad = grantAddress,
        ),
    )
