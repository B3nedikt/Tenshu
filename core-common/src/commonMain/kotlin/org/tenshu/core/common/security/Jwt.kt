package org.tenshu.core.common.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Tenshu's relay-side JWT format. EdDSA over Ed25519, claims pinned to the
 * device-key-signed-session shape from `docs/proposals/sync-backend-relay-plan.md` §4.1
 * and `docs/proposals/sync-relay-api.md` §2.
 *
 * **Algorithm pinning.** Header is fixed at `{"alg":"EdDSA","typ":"JWT"}`. Decoder rejects
 * any other algorithm — explicitly including `none`, the algorithm-confusion vector that
 * routinely sinks JWT implementations.
 *
 * **Claim shape.** Required: `iss` (ownerId), `sub` (deviceId), `iat`, `exp`. Optional:
 * `nonce` (state-changing endpoints; defends against intra-`exp`-window replay).
 *
 * **Time.** Encoder/decoder take `currentTimeSec` rather than reading a clock; the caller
 * sources time from `org.tenshu.core.time.Clock`. Tests pass deterministic values.
 */
data class JwtClaims(
    val ownerId: String,
    val deviceId: String,
    val issuedAtSec: Long,
    val expiresAtSec: Long,
    val nonce: String? = null
)

sealed class JwtVerificationFailure(message: String) : RuntimeException(message) {
    class Malformed(reason: String) : JwtVerificationFailure("Malformed JWT: $reason")
    class WrongAlgorithm(val alg: String) :
        JwtVerificationFailure("Unsupported alg '$alg' (only EdDSA is accepted)")

    class Expired : JwtVerificationFailure("JWT has expired")
    class NotYetValid : JwtVerificationFailure("JWT iat is in the future")
    class SignatureMismatch : JwtVerificationFailure("JWT signature does not verify")
    class ClaimMissing(val name: String) : JwtVerificationFailure("Required claim '$name' missing")
}

/**
 * Encode and sign [claims] into a JWT string. [signer] receives the bytes-to-sign and must
 * return a 32-byte (or platform-equivalent) Ed25519 signature.
 *
 * The two header fields are fixed; supplying a different alg / typ here would defeat the
 * decoder's algorithm pinning and is intentionally not exposed.
 */
@OptIn(ExperimentalEncodingApi::class)
fun encodeJwt(claims: JwtClaims, signer: (ByteArray) -> ByteArray): String {
    val headerJson = """{"alg":"EdDSA","typ":"JWT"}"""
    val payloadJson = buildPayloadJson(claims)
    val signingInput = base64UrlEncode(headerJson.encodeToByteArray()) +
            "." +
            base64UrlEncode(payloadJson.encodeToByteArray())
    val signature = signer(signingInput.encodeToByteArray())
    return "$signingInput.${base64UrlEncode(signature)}"
}

/**
 * Verify [token]'s signature with [verifier] and return its [JwtClaims] if all checks pass.
 *
 * Checks (in order):
 *   1. Three dot-separated base64url segments (else [JwtVerificationFailure.Malformed]).
 *   2. Header is exactly `{"alg":"EdDSA","typ":"JWT"}` (modulo key order).
 *      Anything else — including `alg:"none"` — fails [JwtVerificationFailure.WrongAlgorithm].
 *   3. Signature verifies against `header.payload` (else [JwtVerificationFailure.SignatureMismatch]).
 *   4. `iat <= currentTimeSec` and `currentTimeSec < exp` — clock-skew tolerance is the
 *      caller's responsibility.
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeJwt(
    token: String,
    currentTimeSec: Long,
    verifier: (message: ByteArray, signature: ByteArray) -> Boolean
): JwtClaims {
    val parts = token.split(".")
    if (parts.size != 3) throw JwtVerificationFailure.Malformed("expected 3 segments, got ${parts.size}")

    val headerBytes = decodeBase64UrlOrThrow(parts[0], "header")
    val payloadBytes = decodeBase64UrlOrThrow(parts[1], "payload")
    val signature = decodeBase64UrlOrThrow(parts[2], "signature")

    // Header must declare alg=EdDSA and typ=JWT, nothing else. Defends against the
    // `alg:"none"` algorithm-confusion vector by refusing to even *parse* a header
    // with any other algorithm.
    val headerJson = headerBytes.decodeToString()
    val alg = parseHeaderField(headerJson, "alg")
        ?: throw JwtVerificationFailure.Malformed("header missing alg")
    if (alg != "EdDSA") throw JwtVerificationFailure.WrongAlgorithm(alg)
    val typ = parseHeaderField(headerJson, "typ")
    if (typ != "JWT") throw JwtVerificationFailure.Malformed("header typ must be 'JWT', got '$typ'")

    val signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray()
    if (!verifier(signingInput, signature)) throw JwtVerificationFailure.SignatureMismatch()

    val payloadJson = payloadBytes.decodeToString()
    val ownerId = parsePayloadString(payloadJson, "iss")
        ?: throw JwtVerificationFailure.ClaimMissing("iss")
    val deviceId = parsePayloadString(payloadJson, "sub")
        ?: throw JwtVerificationFailure.ClaimMissing("sub")
    val iat = parsePayloadLong(payloadJson, "iat")
        ?: throw JwtVerificationFailure.ClaimMissing("iat")
    val exp = parsePayloadLong(payloadJson, "exp")
        ?: throw JwtVerificationFailure.ClaimMissing("exp")
    val nonce = parsePayloadString(payloadJson, "nonce")

    if (currentTimeSec < iat) throw JwtVerificationFailure.NotYetValid()
    if (currentTimeSec >= exp) throw JwtVerificationFailure.Expired()

    return JwtClaims(
        ownerId = ownerId,
        deviceId = deviceId,
        issuedAtSec = iat,
        expiresAtSec = exp,
        nonce = nonce
    )
}

private fun buildPayloadJson(c: JwtClaims): String = buildString {
    append("{")
    append("\"iss\":\"").append(escapeJsonString(c.ownerId)).append("\",")
    append("\"sub\":\"").append(escapeJsonString(c.deviceId)).append("\",")
    append("\"iat\":").append(c.issuedAtSec).append(",")
    append("\"exp\":").append(c.expiresAtSec)
    if (c.nonce != null) {
        append(",\"nonce\":\"").append(escapeJsonString(c.nonce)).append("\"")
    }
    append("}")
}

private fun escapeJsonString(s: String): String = buildString(s.length) {
    for (ch in s) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else append(ch)
        }
    }
}

/**
 * Parse a top-level string field from a flat JSON object. Tenshu's JWT header/payload have
 * a fixed shape; we don't need a general JSON parser. Returns null if the field isn't
 * present or isn't a string.
 */
private fun parseHeaderField(json: String, name: String): String? = parsePayloadString(json, name)

private fun parsePayloadString(json: String, name: String): String? {
    val key = "\"$name\""
    val keyIdx = json.indexOf(key)
    if (keyIdx < 0) return null
    val colonIdx = json.indexOf(':', keyIdx + key.length)
    if (colonIdx < 0) return null
    val openQuote = json.indexOf('"', colonIdx + 1)
    if (openQuote < 0) return null
    val sb = StringBuilder()
    var i = openQuote + 1
    while (i < json.length) {
        when (val ch = json[i]) {
            '\\' -> {
                if (i + 1 >= json.length) return null
                when (json[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 >= json.length) return null
                        val hex = json.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }

                    else -> return null
                }
                i += 2
            }

            '"' -> return sb.toString()
            else -> {
                sb.append(ch)
                i++
            }
        }
    }
    return null
}

private fun parsePayloadLong(json: String, name: String): Long? {
    val key = "\"$name\""
    val keyIdx = json.indexOf(key)
    if (keyIdx < 0) return null
    val colonIdx = json.indexOf(':', keyIdx + key.length)
    if (colonIdx < 0) return null
    var i = colonIdx + 1
    while (i < json.length && json[i].isWhitespace()) i++
    val start = i
    while (i < json.length && (json[i].isDigit() || (i == start && json[i] == '-'))) i++
    if (i == start) return null
    return json.substring(start, i).toLongOrNull()
}

@OptIn(ExperimentalEncodingApi::class)
private fun base64UrlEncode(bytes: ByteArray): String =
    Base64.UrlSafe.encode(bytes).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64UrlOrThrow(s: String, segment: String): ByteArray {
    return try {
        // Pad to a multiple of 4 — RFC 7515 omits padding from JWT segments.
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        Base64.UrlSafe.decode(padded)
    } catch (e: IllegalArgumentException) {
        throw JwtVerificationFailure.Malformed("$segment is not valid base64url")
    }
}
