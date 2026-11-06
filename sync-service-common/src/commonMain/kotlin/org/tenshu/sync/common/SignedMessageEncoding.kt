package org.tenshu.sync.common

import org.tenshu.core.common.util.toByteArray

/**
 * The one canonical encoding for every signed admission / membership message
 * (`admission-revocation-names-the-capability.md` §7d, threat model §8.15):
 *
 * ```
 * LP(x)   = u32be(|x|) ‖ x
 * message = LP(domainTag) ‖ LP(field₁) ‖ … ‖ LP(fieldₙ)
 * ```
 *
 * Strings are UTF-8, `Long`s their 8-byte big-endian form — and **every** field is
 * prefixed, the fixed-width ones included, so the rule has no exceptions to get wrong.
 *
 * ⚠⚠ The property this buys is that the encoding is **injective**: the bytes decode to
 * exactly one `(tag, field₁ … fieldₙ)`. The bare concatenation it replaces was not —
 * with variable-length `ownerId` / `deviceId` / `realmId` and no prefixes, two different
 * field tuples could produce identical bytes, and `deviceId` is chosen by the joining
 * device. Domain tags kept *shapes* apart; only prefixes keep *fields* apart. The tag is
 * itself prefixed, so no tag can be a byte-prefix of another tag's message.
 *
 * ⚠ Client and relay must produce identical bytes, so every message builder in
 * [AdmissionCapabilityCert], [AdmissionRevocation], [admissionKeyAnnouncementMessage],
 * [MembershipCapability], [MemberDeviceBinding] and [MembershipRevocation] goes through
 * this function. A new signed message in this family should too — a hand-rolled `+`
 * chain is how the ambiguity would come back.
 */
fun lengthPrefixedSignedMessage(domainTag: String, vararg fields: ByteArray): ByteArray {
    val tag = domainTag.encodeToByteArray()
    var out = tag.size.toByteArray() + tag
    for (field in fields) {
        out = out + field.size.toByteArray() + field
    }
    return out
}

/** A `String` field — UTF-8. */
internal fun signedField(value: String): ByteArray = value.encodeToByteArray()

/** A `Long` field — 8-byte big-endian. */
internal fun signedField(value: Long): ByteArray = value.toByteArray()

// ── The pairing / realm family (B1, 2026-10-28) ─────────────────────────────────────────────────
//
// ⚠⚠ These used to be untagged bare concatenations, hand-copied at every client call site and on the
// relay. Their signers — A1, an admitter's A33, a shared-realm master, a device's A4 — are the same
// keys that sign the tagged messages above, so a bare `K ‖ deviceId ‖ epoch(8)` could be the very
// bytes of an `AdmissionCapabilityCert` (choose K = cert[0 : |cert|−12], deviceId = its 4-byte length
// prefix, epoch = its last 8 bytes) and a signature over one verified as the other. Every message an
// A1/A33/A4/shared-master key signs now starts with LP(a fixed, unique tag), so no two can share
// bytes. The other things an A4 key signs are not LP but start with a printable byte, which no LP
// message can: JWS inputs (`eyJ…`) and the hub offer (`tenshu/pairing/hub-offer/v1` ‖ LP fields,
// `HubOfferSignature.kt`). Pinned by `A4SignedFamiliesTest` — see the threat model's §7d/B1 amendment.

/** Tag of [pairingDelegationMessage]. */
const val PAIRING_DELEGATION_TAG = "tenshu-admit-delegation-v1"

/** Tag of [realmBootstrapMessage]. */
const val REALM_BOOTSTRAP_TAG = "tenshu-realm-bootstrap-v1"

/** Tag of [recoveryClaimMessage]. */
const val RECOVERY_CLAIM_TAG = "tenshu-recovery-claim-v1"

/** Tag of [offerResponseMessage]. */
const val OFFER_RESPONSE_TAG = "tenshu-offer-response-v1"

/** Tag of `hlcTipCanonicalMessage`. */
const val HLC_TIP_TAG = "tenshu-hlc-tip-v1"

/**
 * The new-device delegation (`POST /sync/pairing/response`, `/sync/admit-device`): the admitter —
 * A1, its A33, or a shared-realm master — vouches that [newDevicePublicKey] is [newDeviceId]'s A4
 * key, admitted at [epoch].
 */
fun pairingDelegationMessage(newDevicePublicKey: ByteArray, newDeviceId: String, epoch: Long): ByteArray {
    // ⚠ A delegation vouches for an Ed25519 key, so it is only ever built over one. Every signer
    // and the relay go through here, so no path can be talked into signing over an arbitrary blob.
    require(newDevicePublicKey.size == ED25519_PUBLIC_KEY_BYTES) {
        "a delegation is over a $ED25519_PUBLIC_KEY_BYTES-byte Ed25519 key, got ${newDevicePublicKey.size} bytes"
    }
    return lengthPrefixedSignedMessage(PAIRING_DELEGATION_TAG, newDevicePublicKey, signedField(newDeviceId), signedField(epoch))
}

/** The master self-signature on `POST /sync/realm` (genesis, and its idempotent re-assertions). */
fun realmBootstrapMessage(
    ownerId: String,
    masterPublicKey: ByteArray,
    firstDevicePublicKey: ByteArray,
    firstDeviceId: String,
): ByteArray = lengthPrefixedSignedMessage(
    REALM_BOOTSTRAP_TAG,
    signedField(ownerId),
    masterPublicKey,
    firstDevicePublicKey,
    signedField(firstDeviceId),
)

/** The A1 signature on `POST /sync/recovery-claim` over the relay-issued [challenge]. */
fun recoveryClaimMessage(newDevicePublicKey: ByteArray, newDeviceId: String, challenge: ByteArray): ByteArray =
    lengthPrefixedSignedMessage(RECOVERY_CLAIM_TAG, newDevicePublicKey, signedField(newDeviceId), challenge)

/**
 * The responder's A4 signature on `POST /sync/pairing/offer-response`. A missing [payload] (a signed
 * denial) is encoded as an empty field; `status` already distinguishes a denial from an approval.
 */
fun offerResponseMessage(status: String, pairingSecretHash: String, publicKeyA: ByteArray, payload: ByteArray?): ByteArray =
    lengthPrefixedSignedMessage(
        OFFER_RESPONSE_TAG,
        signedField(status),
        signedField(pairingSecretHash),
        publicKeyA,
        payload ?: ByteArray(0),
    )

/** Ed25519 public keys are exactly this long; every key a relay route registers is checked against it. */
const val ED25519_PUBLIC_KEY_BYTES = 32
