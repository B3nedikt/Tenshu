package org.tenshu.core.common.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The commerce↔relay bridge credential (`commerce-billing-plane.md` §4 / D2): a short-lived,
 * signed assertion *"the bearer is entitled to tier T (quota Q) until time E"*. The relay verifies
 * it as **one more signed credential it doesn't issue** — the same EdDSA-over-Ed25519, alg-pinned
 * JWT shape as the device-key [JwtClaims] (`Jwt.kt`), so the relay's verifier-only, stateless-auth
 * posture is unchanged and no plaintext-identity surface is added to the data plane.
 *
 * **Blinding-ready (D3).** [ownerId] is **optional**. The v1 minimal variant binds the token to a
 * relay `ownerId` (so the relay can reject a token presented under a different owner); the strong
 * variant issues a *blinded* token carrying no `ownerId`, so neither plane alone — nor both
 * colluding — can link person → realm. The verifier here checks only signature + expiry + reads
 * the tier; it never *requires* the `ownerId` claim. The minimal-variant owner-binding check is a
 * separate, caller-side step (the relay does it; the contract does not assume it). Upgrading to
 * blinded tokens is therefore a token-issuance change, not a verifier rewrite.
 *
 * **Key rotation (A29).** [keyId] tags which issuer key signed the token, so the relay can hold
 * current + previous public keys during a signing-key rollover and dispatch on it. `null` selects
 * the issuer's unkeyed/default key.
 *
 * **Algorithm pinning.** Header is fixed at `{"alg":"EdDSA","typ":"JWT"}`; the decoder rejects any
 * other algorithm — including `none` — exactly as [decodeJwt].
 */
data class EntitlementTokenClaims(
    /** Relay `ownerId` the token is bound to (minimal variant). `null` in the blinded variant. */
    val ownerId: String?,
    /** Entitlement tier, e.g. `tenshu.drive.pro` / `tenshu.drive.free`. */
    val tier: String,
    /** Storage quota the tier grants, in bytes. */
    val quotaBytes: Long,
    val issuedAtSec: Long,
    val expiresAtSec: Long,
    /** Issuer signing-key id for rotation; `null` = the issuer's default key. */
    val keyId: String? = null,
)

/**
 * How far into the future an entitlement token's `iat` may sit and still verify.
 *
 * ⚠⚠ **The issuer and the verifier are different machines, and the token is at its youngest exactly
 * when it is first presented.** Commerce stamps `iat` from its own clock; the relay checks it
 * against the relay's, one HTTP round trip later. Measured 2026-08-08: commerce ran **1.21 s ahead
 * of the relay**, which is the same order as the gap between minting and presenting — so a freshly
 * fetched token could be refused as [EntitlementTokenFailure.NotYetValid] and the user would get a
 * `402` on the first write after signing in, healing a second later. That intermittency is the
 * whole reason to fix it here rather than chase it in the field.
 *
 * ⚠ Start bound only, never `exp` — being early is a clock artefact on a token that becomes valid
 * regardless; being late is expiry, and honouring an expired credential is a free grant. Sibling of
 * the record-path tolerance in `EntitlementStorageProvider.kt` and `RelayEntitlementVerifier.kt`.
 *
 * ⚠⚠ Deliberately **not** applied to [decodeAccountClosureToken] — see its KDoc.
 */
const val ENTITLEMENT_TOKEN_SKEW_LEEWAY_SEC: Long = 60L

sealed class EntitlementTokenFailure(message: String) : RuntimeException(message) {
    class Malformed(reason: String) : EntitlementTokenFailure("Malformed entitlement token: $reason")
    class WrongAlgorithm(val alg: String) :
        EntitlementTokenFailure("Unsupported alg '$alg' (only EdDSA is accepted)")

    class Expired : EntitlementTokenFailure("Entitlement token has expired")
    class NotYetValid : EntitlementTokenFailure("Entitlement token iat is in the future")
    class SignatureMismatch : EntitlementTokenFailure("Entitlement token signature does not verify")
    class ClaimMissing(val name: String) : EntitlementTokenFailure("Required claim '$name' missing")
    class UnknownKey(val keyId: String?) :
        EntitlementTokenFailure("No configured issuer key for keyId '$keyId'")
}

/**
 * Encode and sign [claims] into an entitlement-token string. [signer] receives the bytes-to-sign
 * and returns an Ed25519 signature. Header fields are fixed (alg-pinning); not caller-exposed.
 */
@OptIn(ExperimentalEncodingApi::class)
fun encodeEntitlementToken(claims: EntitlementTokenClaims, signer: (ByteArray) -> ByteArray): String {
    val headerJson = """{"alg":"EdDSA","typ":"JWT"}"""
    val payloadJson = buildEntitlementPayloadJson(claims)
    val signingInput = base64UrlNoPad(headerJson.encodeToByteArray()) +
        "." +
        base64UrlNoPad(payloadJson.encodeToByteArray())
    val signature = signer(signingInput.encodeToByteArray())
    return "$signingInput.${base64UrlNoPad(signature)}"
}

/**
 * Verify [token]'s signature with [verifier] and return its [EntitlementTokenClaims] if all checks
 * pass. Throws an [EntitlementTokenFailure] otherwise. Checks, in order: three base64url segments;
 * header is exactly `{"alg":"EdDSA","typ":"JWT"}`; signature verifies over `header.payload`;
 * `iat <= now < exp`, with [ENTITLEMENT_TOKEN_SKEW_LEEWAY_SEC] of tolerance on `iat` alone.
 *
 * ⚠ This used to say *"Clock-skew tolerance is the caller's responsibility"* — and no caller ever
 * took it up, including the relay, which passes the wall clock straight through. Tolerance lives
 * here now precisely because leaving it to the caller meant it did not exist.
 *
 * The [verifier] is handed the parsed [keyId] so a multi-key relay can pick the matching public
 * key (returning `false` for an unknown key surfaces as a [EntitlementTokenFailure.SignatureMismatch]
 * unless the caller pre-checks the key — see [EntitlementTokenVerifier]).
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeEntitlementToken(
    token: String,
    currentTimeSec: Long,
    verifier: (keyId: String?, message: ByteArray, signature: ByteArray) -> Boolean,
): EntitlementTokenClaims {
    val parts = token.split(".")
    if (parts.size != 3) throw EntitlementTokenFailure.Malformed("expected 3 segments, got ${parts.size}")
    val (headerB64, payloadB64, signatureB64) = parts

    val headerJson = runCatching { decodeB64UrlNoPad(headerB64).decodeToString() }
        .getOrElse { throw EntitlementTokenFailure.Malformed("header not base64url") }
    val alg = extractStringClaim(headerJson, "alg")
        ?: throw EntitlementTokenFailure.Malformed("header missing alg")
    if (alg != "EdDSA") throw EntitlementTokenFailure.WrongAlgorithm(alg)

    val payloadJson = runCatching { decodeB64UrlNoPad(payloadB64).decodeToString() }
        .getOrElse { throw EntitlementTokenFailure.Malformed("payload not base64url") }
    val signature = runCatching { decodeB64UrlNoPad(signatureB64) }
        .getOrElse { throw EntitlementTokenFailure.Malformed("signature not base64url") }

    val keyId = extractStringClaim(payloadJson, "kid")
    val signingInput = "$headerB64.$payloadB64".encodeToByteArray()
    if (!verifier(keyId, signingInput, signature)) throw EntitlementTokenFailure.SignatureMismatch()

    val tier = extractStringClaim(payloadJson, "tier")
        ?: throw EntitlementTokenFailure.ClaimMissing("tier")
    val quotaBytes = extractLongClaim(payloadJson, "quota")
        ?: throw EntitlementTokenFailure.ClaimMissing("quota")
    val iat = extractLongClaim(payloadJson, "iat")
        ?: throw EntitlementTokenFailure.ClaimMissing("iat")
    val exp = extractLongClaim(payloadJson, "exp")
        ?: throw EntitlementTokenFailure.ClaimMissing("exp")

    if (currentTimeSec < iat - ENTITLEMENT_TOKEN_SKEW_LEEWAY_SEC) throw EntitlementTokenFailure.NotYetValid()
    if (currentTimeSec >= exp) throw EntitlementTokenFailure.Expired()

    return EntitlementTokenClaims(
        ownerId = extractStringClaim(payloadJson, "sub"),
        tier = tier,
        quotaBytes = quotaBytes,
        issuedAtSec = iat,
        expiresAtSec = exp,
        keyId = keyId,
    )
}

/**
 * Read a token's `exp` (seconds-since-epoch) **without verifying its signature** — for a client that
 * holds a token but not the issuer public key and needs to know when to refresh it
 * (`commerce-billing-plane.md` §8 Phase 2c client cache). This is **not** an authorization check: the
 * value is used only to schedule a re-fetch, never to trust the token. Returns `null` if the token is
 * not three base64url segments or carries no numeric `exp`. The relay still verifies the signature +
 * expiry authoritatively on every presented write.
 */
@OptIn(ExperimentalEncodingApi::class)
fun peekEntitlementTokenExpiresAtSec(token: String): Long? {
    val parts = token.split(".")
    if (parts.size != 3) return null
    val payloadJson = runCatching { decodeB64UrlNoPad(parts[1]).decodeToString() }.getOrNull() ?: return null
    return extractLongClaim(payloadJson, "exp")
}

// ---------------------------------------------------------------------------------------------
// Account-closure credential (retention-quota-model.md Face A / D4).
//
// Deliberately in THIS file rather than a sibling: it is the same commerce→relay credential family
// (alg-pinned EdDSA JWT, `kid` rotation, verifier-only relay) and it reuses the private base64url +
// claim-reading helpers below. Duplicating a JWT codec into a second security file to avoid a
// filename mismatch would be the worse trade.
// ---------------------------------------------------------------------------------------------

/**
 * The commerce→relay **account-closure** assertion: *"owner O's account was closed at time C"*.
 * The relay verifies it exactly like [EntitlementTokenClaims] — one more signed credential it does
 * not issue — and, once [closedAtSec] is older than its configured grace window, reclaims that
 * owner's content keyspace (`ContentBlobRegistry.reclaimOwner`).
 *
 * ⚠⚠ **[ownerId] is REQUIRED here, unlike the entitlement token's optional/blinded `sub`.**
 * Reclamation cannot be blinded: the relay must know *whose* keyspace to drop, and the keyspace is
 * the `ownerId`. So a closure assertion inherently discloses person→realm linkage to the relay, in
 * the one flow where D3's blinding cannot apply. That is a real, recorded exception, not an
 * oversight — see the T-R-09 amendment.
 *
 * ⚠ **Short-lived and re-issued, exactly like the entitlement token.** [expiresAtSec] is not a
 * property of the closure (a closure is durable) — it is what makes *reopening* work: commerce
 * simply stops re-issuing, and a stale record can no longer authorise anything. Without it, a
 * leaked record would remain a permanent delete-this-owner capability that would fire against a
 * *reopened* account. "Stop reissuing = revocation" is the same model the entitlement token uses.
 *
 * ⚠ [closedAtSec] is the **grace clock's origin** and is chosen by commerce, not the relay: the
 * operational retention window has to be measured from the real closure, not from whenever a
 * record happened to be delivered.
 */
data class AccountClosureClaims(
    /** The relay `ownerId` whose content is to be reclaimed. Never blinded — see the KDoc. */
    val ownerId: String,
    /** When commerce closed the account. The grace window is measured from here. */
    val closedAtSec: Long,
    val issuedAtSec: Long,
    /** Short — the record is re-issued while the account stays closed. */
    val expiresAtSec: Long,
    /** Issuer signing-key id for rotation; `null` = the issuer's default key. */
    val keyId: String? = null,
)

/** Encode and sign [claims]. Header is fixed at `{"alg":"EdDSA","typ":"JWT"}` (alg-pinning). */
@OptIn(ExperimentalEncodingApi::class)
fun encodeAccountClosureToken(
    claims: AccountClosureClaims,
    signer: (ByteArray) -> ByteArray,
): String {
    require(claims.ownerId.isNotBlank()) { "An account-closure token requires a non-blank ownerId." }
    val headerJson = """{"alg":"EdDSA","typ":"JWT"}"""
    val parts = mutableListOf(
        "\"sub\":\"${escapeJson(claims.ownerId)}\"",
        "\"closed\":${claims.closedAtSec}",
        "\"iat\":${claims.issuedAtSec}",
        "\"exp\":${claims.expiresAtSec}",
    )
    claims.keyId?.let { parts += "\"kid\":\"${escapeJson(it)}\"" }
    val signingInput = base64UrlNoPad(headerJson.encodeToByteArray()) +
        "." +
        base64UrlNoPad("{${parts.joinToString(",")}}".encodeToByteArray())
    return "$signingInput.${base64UrlNoPad(signer(signingInput.encodeToByteArray()))}"
}

/**
 * Verify [token] and return its [AccountClosureClaims]. Throws an [EntitlementTokenFailure]
 * otherwise — the failure taxonomy is shared, since a caller handles both credentials the same way.
 *
 * Checks mirror [decodeEntitlementToken] in the same order (3 segments → alg pin → signature →
 * `iat <= now < exp`), plus: **`sub` is mandatory** and must be non-blank. A closure record without
 * an owner is not a weaker credential, it is an unusable one — and a blank `sub` reaching
 * `reclaimOwner` is the input mistake that primitive refuses on purpose.
 *
 * ⚠⚠ **One deliberate divergence: [ENTITLEMENT_TOKEN_SKEW_LEEWAY_SEC] is NOT applied here.** This
 * credential authorises deleting an owner's entire content keyspace, and the two paths fail very
 * differently. A skew-refused *entitlement* token is a user-visible `402` on a write the user is
 * making right now, with nothing to retry it. A *closure* record is delivered by a repeating sweep,
 * but re-posting heals only a one-off skew: a commerce clock that runs *persistently* fast would fail
 * every record forever. So the tolerance lives on the minting side instead — commerce backdates
 * `iat` (`AccountClosureMinter.IAT_BACKDATE_SEC`) — and the verifier that guards a delete stays strict.
 *
 * ⚠ This is not the grace gate. The retention window runs from the `closed` claim, never from
 * `iat`, so `iat` tolerance could not have advanced a deletion even if it were applied.
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeAccountClosureToken(
    token: String,
    currentTimeSec: Long,
    verifier: (keyId: String?, message: ByteArray, signature: ByteArray) -> Boolean,
): AccountClosureClaims {
    val parts = token.split(".")
    if (parts.size != 3) throw EntitlementTokenFailure.Malformed("expected 3 segments, got ${parts.size}")
    val (headerB64, payloadB64, signatureB64) = parts

    val headerJson = runCatching { decodeB64UrlNoPad(headerB64).decodeToString() }
        .getOrElse { throw EntitlementTokenFailure.Malformed("header not base64url") }
    val alg = extractStringClaim(headerJson, "alg")
        ?: throw EntitlementTokenFailure.Malformed("header missing alg")
    if (alg != "EdDSA") throw EntitlementTokenFailure.WrongAlgorithm(alg)

    val payloadJson = runCatching { decodeB64UrlNoPad(payloadB64).decodeToString() }
        .getOrElse { throw EntitlementTokenFailure.Malformed("payload not base64url") }
    val signature = runCatching { decodeB64UrlNoPad(signatureB64) }
        .getOrElse { throw EntitlementTokenFailure.Malformed("signature not base64url") }

    val keyId = extractStringClaim(payloadJson, "kid")
    val signingInput = "$headerB64.$payloadB64".encodeToByteArray()
    if (!verifier(keyId, signingInput, signature)) throw EntitlementTokenFailure.SignatureMismatch()

    val ownerId = extractStringClaim(payloadJson, "sub")?.takeIf { it.isNotBlank() }
        ?: throw EntitlementTokenFailure.ClaimMissing("sub")
    val closedAt = extractLongClaim(payloadJson, "closed")
        ?: throw EntitlementTokenFailure.ClaimMissing("closed")
    val iat = extractLongClaim(payloadJson, "iat")
        ?: throw EntitlementTokenFailure.ClaimMissing("iat")
    val exp = extractLongClaim(payloadJson, "exp")
        ?: throw EntitlementTokenFailure.ClaimMissing("exp")

    if (currentTimeSec < iat) throw EntitlementTokenFailure.NotYetValid()
    if (currentTimeSec >= exp) throw EntitlementTokenFailure.Expired()

    return AccountClosureClaims(
        ownerId = ownerId,
        closedAtSec = closedAt,
        issuedAtSec = iat,
        expiresAtSec = exp,
        keyId = keyId,
    )
}

/** Payload uses short JWT-style names: `sub` (ownerId, omitted when blinded), `tier`, `quota`, `iat`, `exp`, `kid`. */
private fun buildEntitlementPayloadJson(claims: EntitlementTokenClaims): String {
    val parts = mutableListOf<String>()
    claims.ownerId?.let { parts += "\"sub\":\"${escapeJson(it)}\"" }
    parts += "\"tier\":\"${escapeJson(claims.tier)}\""
    parts += "\"quota\":${claims.quotaBytes}"
    parts += "\"iat\":${claims.issuedAtSec}"
    parts += "\"exp\":${claims.expiresAtSec}"
    claims.keyId?.let { parts += "\"kid\":\"${escapeJson(it)}\"" }
    return "{${parts.joinToString(",")}}"
}

@OptIn(ExperimentalEncodingApi::class)
private val B64_URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@OptIn(ExperimentalEncodingApi::class)
private fun base64UrlNoPad(bytes: ByteArray): String = B64_URL_NO_PAD.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeB64UrlNoPad(s: String): ByteArray = B64_URL_NO_PAD.decode(s)

private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Minimal scalar-claim reader for the flat, machine-generated payloads this module produces — not
 * a general JSON parser (matching `Jwt.kt`'s deliberately small claim extraction). Reads
 * `"name":"value"` (string) ignoring surrounding whitespace.
 */
private fun extractStringClaim(json: String, name: String): String? {
    val key = "\"$name\""
    var i = json.indexOf(key)
    if (i < 0) return null
    i += key.length
    while (i < json.length && json[i] != ':') i++
    i++
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++
    val sb = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        when {
            c == '\\' && i + 1 < json.length -> { sb.append(json[i + 1]); i += 2 }
            c == '"' -> return sb.toString()
            else -> { sb.append(c); i++ }
        }
    }
    return null
}

/** Reads `"name":<digits>` (long). */
private fun extractLongClaim(json: String, name: String): Long? {
    val key = "\"$name\""
    var i = json.indexOf(key)
    if (i < 0) return null
    i += key.length
    while (i < json.length && json[i] != ':') i++
    i++
    while (i < json.length && json[i].isWhitespace()) i++
    val start = i
    if (i < json.length && (json[i] == '-' || json[i] == '+')) i++
    while (i < json.length && json[i].isDigit()) i++
    return json.substring(start, i).toLongOrNull()
}
