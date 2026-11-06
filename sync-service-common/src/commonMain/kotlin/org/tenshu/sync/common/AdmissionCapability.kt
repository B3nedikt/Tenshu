package org.tenshu.sync.common

import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.common.util.toInt
import org.tenshu.core.common.util.toLong

/**
 * The delegated device-admission capability chain for **personal** realms
 * (`docs/proposals/delegated-device-admission-authority.md` §4, Option B).
 *
 * Each realm device holds its own **admission keypair** (asset A33, the
 * personal-realm analogue of GR's A26) plus a certificate chain rooting its
 * authority in the realm master key (A1):
 *
 * ```
 * cert       = LP("tenshu-admit-cap-v2")    ‖ LP(ownerId) ‖ LP(deviceA33Pub) ‖ LP(deviceId) ‖ LP(mintedAtEpoch)
 *          — Ed25519(parent), parent = A1 (owner-minted) or the admitter's A33
 * revocation = LP("tenshu-admit-revoke-v3") ‖ LP(ownerId) ‖ LP(deviceId) ‖ LP(epoch) ‖ LP(revokedAt)
 *                                           ‖ LP(deviceA33Pub)
 *          — Ed25519(A1)
 * ```
 *
 * A device admits the next by signing the new-device delegation
 * (`MasterDelegationVerifier.pairingResponseMessage`) with its A33 key and
 * presenting the chain; [AdmissionChainVerifier] walks the chain to the realm's
 * stored `masterPublicKey`. The A33 private key is never relay-visible — the
 * relay sees only public keys + signatures it can verify but not mint (House
 * Rule #1).
 *
 * The byte layout is [lengthPrefixedSignedMessage] (§7d): every field, the domain
 * tag included, is 4-byte-BE length-prefixed, so field boundaries are unambiguous.
 * Client and relay must produce identical bytes, so the `message()` builders are the
 * single source of truth for the wire shape.
 *
 * ⚠ The tag suffixes moved with the encoding (protocol-1 re-cut, 2026-10-28): a tag
 * names one byte layout and is never reused for another, so a signature from a build
 * that predates the prefixes fails as a plain mismatch. `tenshu-admit-revoke-v1`
 * (device-scoped) and `-v2` (key-scoped, unprefixed) are retired — never reuse them.
 */

/**
 * One link of the admission chain: the [signature] (by the parent authority —
 * A1 for the root link, the admitting device's A33 otherwise) vouches that
 * [deviceA33PublicKey] is the admission key of [deviceId] in [ownerId]'s realm.
 *
 * [mintedAtEpoch] is the realm epoch the capability was issued under. ⚠ Signed but
 * **unread** by revocation since F5b (§7g): a revocation names the capability *key*,
 * never an epoch. It stays in the message only because the epoch concept itself is
 * F5c's open question (§7e.3) — do not wire a comparison back onto it.
 */
class AdmissionCapabilityCert(
    val ownerId: String,
    val deviceA33PublicKey: ByteArray,
    val deviceId: String,
    val mintedAtEpoch: Long,
    val signature: ByteArray,
) {
    /** The exact bytes [signature] is over — re-built for verification. */
    fun signedMessage(): ByteArray = message(ownerId, deviceA33PublicKey, deviceId, mintedAtEpoch)

    companion object {
        /** Domain-separation tag — a cert signature can never double as any other message. */
        const val DOMAIN_TAG = "tenshu-admit-cap-v2"

        fun message(
            ownerId: String,
            deviceA33PublicKey: ByteArray,
            deviceId: String,
            mintedAtEpoch: Long,
        ): ByteArray = lengthPrefixedSignedMessage(
            DOMAIN_TAG,
            signedField(ownerId),
            deviceA33PublicKey,
            signedField(deviceId),
            signedField(mintedAtEpoch),
        )
    }
}

/**
 * Master-signed revocation of the admission capability [deviceA33PublicKey] held by
 * [deviceId] in [ownerId]'s realm (proposal §5, F1′ §7b.3). Strips exactly the cert
 * that names that key — a re-mint issues a new key, which this revocation does not
 * name. Only an A1 signature counts — a revocation signed by anything else cannot
 * strip an admitter ([AdmissionChainVerifier.isRevoked] ignores it).
 *
 * ⚠⚠ F5b (§7g): the key is **required**. The keyless device-at-an-epoch form and its
 * epoch-rule fallback are gone — both failure directions §7g.2 measured (fail-open on
 * a keyless revocation, fail-closed on set membership) needed a realm with keyless
 * revocations in it, and the protocol-1 re-cut started every realm without any.
 */
class AdmissionRevocation(
    val ownerId: String,
    val deviceId: String,
    /**
     * ⚠ Signed and stored, but **unread**: since F5b nothing compares it. Reserved, like
     * `MembershipCapability.epoch` (§7f.3) — wiring a comparison onto it would re-import the
     * §7b.1 lagging-counter failure.
     */
    val epoch: Long,
    val revokedAt: Long,
    val signature: ByteArray,
    /** The **A33 capability key** this revocation strips (F1′). Required since F5b. */
    val deviceA33PublicKey: ByteArray,
) {
    fun signedMessage(): ByteArray = message(ownerId, deviceId, epoch, revokedAt, deviceA33PublicKey)

    companion object {
        /**
         * ⚠ `-v3`: `-v1` was the keyless device-scoped form and `-v2` the key-scoped form under
         * the old unprefixed concatenation (where a distinct tag was what kept a v1 message for
         * one device from colliding with a v2 message for another). Both are retired and must
         * not be reused; the length prefixes now close that class for every message, not just
         * this pair (§7d, pinned by `SignedMessageEncodingTest`).
         */
        const val DOMAIN_TAG = "tenshu-admit-revoke-v3"

        fun message(
            ownerId: String,
            deviceId: String,
            epoch: Long,
            revokedAt: Long,
            deviceA33PublicKey: ByteArray,
        ): ByteArray = lengthPrefixedSignedMessage(
            DOMAIN_TAG,
            signedField(ownerId),
            signedField(deviceId),
            signedField(epoch),
            signedField(revokedAt),
            deviceA33PublicKey,
        )
    }
}

/**
 * Canonical bytes an [AdmissionKeyAnnouncement]
 * (`tenshu/identity/admissionKeyAnnouncement`, P4 migration) signature is over
 * — signed by the announcing device's **A4** signing key, verified by the
 * A1-holder against the `Device` row's registered public key before minting a
 * cert for the announced A33 key. Domain-tagged so an A4 signature over this
 * can never double as a block signature or JWT.
 */
fun admissionKeyAnnouncementMessage(deviceId: String, a33PublicKey: ByteArray): ByteArray =
    lengthPrefixedSignedMessage(ADMISSION_ANNOUNCE_TAG, signedField(deviceId), a33PublicKey)

const val ADMISSION_ANNOUNCE_TAG = "tenshu-admit-announce-v2"

/**
 * Encode a cert chain (ordered leaf → A1-signed root) for transport — the
 * pairing key-blob's v2 tail (proposal §4.2) and, in P2, the relay admission
 * POST. Layout: 4-byte BE cert count, then per cert each variable-length field
 * 4-byte-BE-length-prefixed:
 *
 * ```
 * count ‖ [ len(ownerId) ‖ ownerId ‖ len(a33Pub) ‖ a33Pub ‖
 *           len(deviceId) ‖ deviceId ‖ mintedAtEpoch(8) ‖ len(sig) ‖ sig ]*
 * ```
 */
fun encodeAdmissionCertChain(chain: List<AdmissionCapabilityCert>): ByteArray {
    var out = chain.size.toByteArray()
    for (cert in chain) {
        val ownerId = cert.ownerId.encodeToByteArray()
        val deviceId = cert.deviceId.encodeToByteArray()
        out = out +
            ownerId.size.toByteArray() + ownerId +
            cert.deviceA33PublicKey.size.toByteArray() + cert.deviceA33PublicKey +
            deviceId.size.toByteArray() + deviceId +
            cert.mintedAtEpoch.toByteArray() +
            cert.signature.size.toByteArray() + cert.signature
    }
    return out
}

/**
 * Inverse of [encodeAdmissionCertChain]. Throws [IllegalArgumentException] on a
 * malformed blob — callers that must degrade gracefully (the pairing key-blob
 * parse) wrap the call rather than this function guessing.
 */
fun decodeAdmissionCertChain(bytes: ByteArray): List<AdmissionCapabilityCert> {
    var pos = 0

    fun take(n: Int): ByteArray {
        require(n >= 0 && pos + n <= bytes.size) { "admission cert chain truncated at $pos (+$n of ${bytes.size})" }
        return bytes.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun takeLenPrefixed(): ByteArray = take(take(4).toInt())

    val count = take(4).toInt()
    // Bound the count by what the remaining bytes could physically hold (a cert is
    // ≥ 24 bytes even with empty variable fields) BEFORE allocating — a garbage or
    // hostile count must fail cheaply, never drive a huge List allocation. The
    // relay decodes attacker-supplied bytes on the admission path, so this is
    // load-bearing DoS hygiene, not just robustness.
    require(count in 0..(bytes.size - pos) / MIN_ENCODED_CERT_BYTES) {
        "implausible cert count: $count for ${bytes.size - pos} bytes"
    }
    val chain = List(count) {
        AdmissionCapabilityCert(
            ownerId = takeLenPrefixed().decodeToString(),
            deviceA33PublicKey = takeLenPrefixed(),
            deviceId = takeLenPrefixed().decodeToString(),
            mintedAtEpoch = take(8).toLong(),
            signature = takeLenPrefixed(),
        )
    }
    require(pos == bytes.size) { "trailing bytes after admission cert chain: ${bytes.size - pos}" }
    return chain
}

/** Four length prefixes (4 B each) + the 8-byte epoch — a cert with empty variable fields. */
private const val MIN_ENCODED_CERT_BYTES = 4 * 4 + 8
