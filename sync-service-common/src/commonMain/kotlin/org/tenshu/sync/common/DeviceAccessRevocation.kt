package org.tenshu.sync.common

import org.tenshu.core.common.util.HlcStamp

/**
 * The signed request that cuts one device's **relay access** — its JWT key and, permanently, its
 * right to re-join through the pairing flows (`POST /sync/device-access/revoke`;
 * `sync-entity-identity.md` §5b precondition 0, threat model T-K-02 / T-GR-08).
 *
 * One rule, for every realm kind: **removing another device needs the realm master (A1); removing
 * yourself needs your own device key.** Membership is not permission, so a shared-realm member and a
 * non-owner personal device are the same case — each can only sign the [Authority.SELF] form.
 *
 * ```
 * message = LP(tag) ‖ LP(ownerId) ‖ LP(deviceId)
 *         ‖ LP(authored.physicalTime) ‖ LP(authored.logicalCounter) ‖ LP(authored.deviceId)
 * tag     = "tenshu-device-access-revoke-v1"   — Ed25519(A1, realm.masterPublicKey)
 *         | "tenshu-device-self-revoke-v1"     — Ed25519(the named device's registered key)
 * ```
 *
 * ⚠⚠ **Two tags, deliberately.** The self form is signed by a device key, which also signs block
 * HLC tips, JWS inputs and pairing messages (`A4SignedFamiliesTest`); the owner form by A1, which
 * signs certs and revocations (`CrossFamilySignatureTest`). A distinct tag per form keeps each
 * signature from being re-presented as the other, or as anything else those keys sign.
 *
 * [authoredAt] is the HLC the revoking client **authored** the removal at — not when it was
 * delivered. The relay applies it only when it is not older than the device's admission on record
 * (`DeviceRegistry.revokeIfNotStale`), the same rule a `Device` tombstone gets, so a removal that
 * waited in a queue cannot undo a later re-admission. It is inside the signature so nobody on the
 * path can make a stale request look fresh.
 *
 * ⚠⚠ **Names exactly one device.** This is not `/sync/admission/revoke`, which is posted once per
 * member of an admission subtree and strips only minting power. Cutting access for every
 * descendant would fence devices that are still realm members (§5b, "A NEW route").
 *
 * ⚠ The stamp is three fields, not its `encode()` string, so the encoding stays injective
 * (`lengthPrefixedSignedMessage`).
 */
class DeviceAccessRevocation(
    val authority: Authority,
    val ownerId: String,
    val deviceId: String,
    val authoredAt: HlcStamp,
    val signature: ByteArray,
) {
    fun signedMessage(): ByteArray = message(authority, ownerId, deviceId, authoredAt)

    /** Who signs, and therefore whom it may name. [wire] is the request body's `authority`. */
    enum class Authority(val wire: String, val domainTag: String) {
        /** A1 over [MASTER_DOMAIN_TAG]; may name any device of the realm. */
        MASTER("master", MASTER_DOMAIN_TAG),

        /** The named device's own registered key over [SELF_DOMAIN_TAG]; may name only itself. */
        SELF("self", SELF_DOMAIN_TAG);

        companion object {
            fun fromWire(value: String): Authority? = entries.firstOrNull { it.wire == value }
        }
    }

    companion object {
        /** Never reuse for another layout; a new layout gets `-v2`. */
        const val MASTER_DOMAIN_TAG = "tenshu-device-access-revoke-v1"

        /** Never reuse for another layout; a new layout gets `-v2`. */
        const val SELF_DOMAIN_TAG = "tenshu-device-self-revoke-v1"

        fun message(authority: Authority, ownerId: String, deviceId: String, authoredAt: HlcStamp): ByteArray =
            lengthPrefixedSignedMessage(
                authority.domainTag,
                signedField(ownerId),
                signedField(deviceId),
                signedField(authoredAt.physicalTime),
                signedField(authoredAt.logicalCounter),
                signedField(authoredAt.deviceId),
            )
    }
}
