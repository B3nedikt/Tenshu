package org.tenshu.core.common.epc

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The out-of-band EPC pairing payload (`docs/proposals/ios-proxy-architecture.md`
 * §10.2). It is the *only* thing the OOB channel (QR / manual string) carries, and
 * it answers exactly one question: **which proxy** the app should reach.
 *
 * - [relayBaseUrl] — where the `/sync/epc` rendezvous mailbox lives.
 * - [pairingSecret] — fresh, high-entropy (≥16 bytes); HKDF'd to the rendezvous
 *   session id via [org.tenshu.core.common.util.deriveEpcRendezvousSessionId]. The
 *   proxy that `provision()`s that session is the peer the app reaches by deriving
 *   the same id (§10.1). Per-pairing and short-lived.
 * - [expiresAtMillis] — advisory display TTL only; the relay's session cap/TTL is
 *   authoritative (§10.2).
 *
 * **No proxy identity / public key rides here.** The EPC handshake is *ephemeral*
 * X25519 (`EpcHandshake`); the **SAS** authenticates the exchange (§10.1). So the
 * payload's confidentiality reduces to the QR's OOB confidentiality, and the SAS
 * confirmation is the load-bearing defence against a relay-level MITM (§10.5 / A31).
 *
 * Carried base64url-encoded inside a `tenshu://epc/pair#<payload>` URI by
 * [EpcPairingUri], mirroring the contact-invite link convention (`K_link` in the
 * fragment).
 */
data class EpcPairingPayload(
    val relayBaseUrl: String,
    val pairingSecret: ByteArray,
    val expiresAtMillis: Long? = null,
) {
    init {
        require(pairingSecret.size in MIN_SECRET_BYTES..MAX_SECRET_BYTES) {
            "pairingSecret must be $MIN_SECRET_BYTES..$MAX_SECRET_BYTES bytes, was ${pairingSecret.size}"
        }
    }

    // ByteArray breaks data-class structural equality — compare contents (mirrors
    // the InvitePayloads convention).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpcPairingPayload) return false
        return relayBaseUrl == other.relayBaseUrl &&
            pairingSecret.contentEquals(other.pairingSecret) &&
            expiresAtMillis == other.expiresAtMillis
    }

    override fun hashCode(): Int {
        var result = relayBaseUrl.hashCode()
        result = 31 * result + pairingSecret.contentHashCode()
        result = 31 * result + (expiresAtMillis?.hashCode() ?: 0)
        return result
    }

    companion object {
        /** Floor matching the §10.2 "≥16 bytes" rule and the 16-byte rendezvous session id. */
        const val MIN_SECRET_BYTES = 16

        /** Sanity ceiling — a pairing secret is per-pairing and tiny; one length byte encodes it. */
        const val MAX_SECRET_BYTES = 255
    }
}

/**
 * Codec for the `tenshu://epc/pair#<base64url(blob)>` pairing URI (§10.2). Lives in
 * `:core-common` so both ends share it: Control (proxy) encodes + renders the QR /
 * manual string, the iOS client (scanner / manual entry) decodes it.
 *
 * The blob is a small, version-tagged binary record (not JSON — `:core-common`
 * carries no serialization plugin, and a TLV is the most compact, escaping-free
 * "base64url blob" §10.2 asks for):
 *
 * ```
 * [version u8][secretLen u8][secret …][relayLen u16-be][relay utf-8][hasExpiry u8][expiry i64-be?]
 * ```
 *
 * An unknown leading version byte is a hard decode error (the "update the app"
 * rule the EPC handshake already uses for its `ClientHello` version).
 */
object EpcPairingUri {

    /** URI scheme + path; the payload rides in the fragment after `#`. */
    const val PREFIX = "tenshu://epc/pair#"

    /** Current blob version. */
    const val VERSION = 1

    /** Encode [payload] to a `tenshu://epc/pair#…` URI. */
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(payload: EpcPairingPayload): String =
        PREFIX + Base64.UrlSafe.encode(encodeBlob(payload)).trimEnd('=')

    /**
     * Decode a `tenshu://epc/pair#…` URI back to an [EpcPairingPayload]. Throws
     * [EpcPairingUriException] on a wrong scheme, malformed base64url, a truncated /
     * malformed blob, an unknown version, or a too-short secret.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(uri: String): EpcPairingPayload {
        val fragment = uri.removePrefix(PREFIX)
        if (fragment === uri || fragment.isEmpty()) {
            throw EpcPairingUriException("not an EPC pairing URI: expected '$PREFIX…'")
        }
        val blob = try {
            // base64url without padding (mirrors the JWT-segment convention).
            Base64.UrlSafe.decode(fragment + "=".repeat((4 - fragment.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            throw EpcPairingUriException("payload is not valid base64url", e)
        }
        return decodeBlob(blob)
    }

    private fun encodeBlob(payload: EpcPairingPayload): ByteArray {
        val relay = payload.relayBaseUrl.encodeToByteArray()
        require(relay.size <= 0xFFFF) { "relayBaseUrl too long: ${relay.size} bytes" }
        val expiry = payload.expiresAtMillis
        val out = ArrayList<Byte>(8 + payload.pairingSecret.size + relay.size)
        out.add(VERSION.toByte())
        out.add(payload.pairingSecret.size.toByte())
        payload.pairingSecret.forEach(out::add)
        out.add((relay.size ushr 8).toByte())
        out.add(relay.size.toByte())
        relay.forEach(out::add)
        if (expiry == null) {
            out.add(0)
        } else {
            out.add(1)
            for (shift in 56 downTo 0 step 8) out.add((expiry ushr shift).toByte())
        }
        return out.toByteArray()
    }

    private fun decodeBlob(blob: ByteArray): EpcPairingPayload {
        val reader = BlobReader(blob)
        val version = reader.u8()
        if (version != VERSION) {
            throw EpcPairingUriException(
                "unsupported EPC pairing version $version (this app supports $VERSION) — update the app"
            )
        }
        val secret = reader.bytes(reader.u8())
        val relay = reader.bytes((reader.u8() shl 8) or reader.u8()).decodeToString()
        val expiry = when (val hasExpiry = reader.u8()) {
            0 -> null
            1 -> reader.i64()
            else -> throw EpcPairingUriException("malformed expiry flag: $hasExpiry")
        }
        reader.requireEnd()
        return try {
            EpcPairingPayload(relayBaseUrl = relay, pairingSecret = secret, expiresAtMillis = expiry)
        } catch (e: IllegalArgumentException) {
            throw EpcPairingUriException(e.message ?: "invalid pairing payload", e)
        }
    }
}

/** Thrown by [EpcPairingUri.decode] for any malformed / unsupported pairing URI. */
class EpcPairingUriException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Bounds-checked sequential reader over the pairing blob — any underflow is a decode error. */
private class BlobReader(private val bytes: ByteArray) {
    private var pos = 0

    fun u8(): Int {
        if (pos >= bytes.size) fail()
        return bytes[pos++].toInt() and 0xFF
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > bytes.size) fail()
        return bytes.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun i64(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun requireEnd() {
        if (pos != bytes.size) throw EpcPairingUriException("trailing bytes in pairing blob")
    }

    private fun fail(): Nothing = throw EpcPairingUriException("truncated pairing blob")
}
