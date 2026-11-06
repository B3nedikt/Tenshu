package org.tenshu.sync.relay.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.JwtVerificationFailure
import org.tenshu.core.common.security.decodeJwt

/**
 * Per-call JWT verification. The standard happy path on a relay endpoint:
 *
 * ```kotlin
 * val claims = jwtAuthenticator.require(call) ?: return@post
 * // ... use claims.ownerId / claims.deviceId / claims.nonce
 * ```
 *
 * On failure, [require] writes the appropriate `4xx` and returns null. The endpoint code
 * just `return`s; no manual response handling.
 *
 * Algorithm pinning is enforced inside [decodeJwt] — the verifier callback is never
 * called for `alg=none` or anything other than `EdDSA`. Cf. `Jwt.kt` KDoc.
 *
 * `nowEpochSec` is injected so tests use deterministic time and production uses
 * `System.currentTimeMillis() / 1000`.
 */
class JwtAuthenticator(
    private val knownDevices: DeviceRegistry,
    private val ed25519: Ed25519Backend,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val authFailureLimiter: AuthFailureLimiter = AuthFailureLimiter(limitPerHour = 0),
) {

    /**
     * The client this call is attributed to for failure accounting. Behind Caddy the socket peer is
     * always the proxy, so this is only meaningful with `XForwardedHeaders` installed — see
     * [AuthFailureLimiter]. Falls back to the socket peer on a direct/dev bind.
     */
    private fun clientKey(call: ApplicationCall): String = call.request.origin.remoteAddress

    /** Advisory only; the client's own backoff is what actually paces it. */
    private val retryAfterSeconds = "60"

    /**
     * Answer a failed authentication, charging it to the caller's budget.
     *
     * ⚠ Every `401` path goes through here rather than calling `respond` directly: the budget is
     * only meaningful if it sees *all* the failures, and a new failure branch added later would
     * otherwise silently escape accounting.
     */
    private suspend fun rejectUnauthorized(call: ApplicationCall, reason: String): JwtClaims? {
        authFailureLimiter.recordFailure(clientKey(call))
        call.respond(HttpStatusCode.Unauthorized, reason)
        return null
    }

    /**
     * Verify the call's `Authorization: Bearer <token>` header. Writes a `4xx` response
     * and returns null on any failure path. Returns [JwtClaims] on success.
     *
     * Failure mapping:
     *   - missing/malformed `Authorization` → 401
     *   - unknown `(iss, sub)` device → 401 (don't leak whether ownerId or deviceId is wrong)
     *   - bad signature / tampered payload → 401
     *   - expired / not-yet-valid → 401
     *   - wrong alg / malformed token → 400
     */
    suspend fun require(call: ApplicationCall): JwtClaims? {
        // ⚠⚠ Before any parsing or signature verification: past its budget this caller gets the
        // same honest 401 without the relay doing the Ed25519 work. The status is deliberately
        // NOT 429 — the client keys its backoff, its "device not recognised" state and its
        // self-repair off a 401, so answering anything else would silence all three and re-create
        // the storm this bounds. See [AuthFailureLimiter].
        if (authFailureLimiter.isOverBudget(clientKey(call))) {
            call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds)
            call.respond(HttpStatusCode.Unauthorized, "Invalid token")
            return null
        }
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return rejectUnauthorized(call, "Missing or malformed Authorization header")
        }
        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isEmpty()) {
            return rejectUnauthorized(call, "Empty bearer token")
        }

        // We don't know the device's public key until the JWT's claims are parsed, so we
        // need a two-pass approach: parse-only first, then verify against the looked-up
        // public key. To stay within the single-pass `decodeJwt` API, we capture the
        // claims-shaped state inside the verifier callback. The callback is only invoked
        // for EdDSA-tagged tokens (`decodeJwt` enforces alg pinning before signing-input
        // construction).
        var resolvedKey: ByteArray? = null
        var unknownDevice = false

        return try {
            decodeJwt(
                token = token,
                currentTimeSec = nowEpochSec(),
                verifier = { message, signature ->
                    // First we have to peek at iss/sub to look up the key. Pull them from
                    // the second segment of the token directly — the JWT spec says the
                    // signing input is exactly `header.payload`, so we can recover the
                    // payload bytes from `message`.
                    val signingInput = message.decodeToString()
                    val payloadStart = signingInput.indexOf('.') + 1
                    if (payloadStart == 0) return@decodeJwt false
                    val payloadJson = decodeBase64UrlSegment(signingInput.substring(payloadStart))
                    val ownerId = quickField(payloadJson, "iss") ?: return@decodeJwt false
                    val deviceId = quickField(payloadJson, "sub") ?: return@decodeJwt false

                    val publicKey = knownDevices.getPublicKey(ownerId, deviceId)
                    if (publicKey == null) {
                        unknownDevice = true
                        return@decodeJwt false
                    }
                    resolvedKey = publicKey
                    ed25519.verify(publicKey, message, signature)
                }
            )
        } catch (e: JwtVerificationFailure.WrongAlgorithm) {
            call.respond(HttpStatusCode.BadRequest, "Unsupported algorithm: ${e.alg}")
            null
        } catch (e: JwtVerificationFailure.Malformed) {
            call.respond(HttpStatusCode.BadRequest, "Malformed token")
            null
        } catch (e: JwtVerificationFailure.SignatureMismatch) {
            // Don't distinguish "unknown device" from "bad signature" in the response —
            // both look the same to a probing attacker.
            call.respond(HttpStatusCode.Unauthorized, "Invalid token")
            null
        } catch (e: JwtVerificationFailure.Expired) {
            call.respond(HttpStatusCode.Unauthorized, "Token expired")
            null
        } catch (e: JwtVerificationFailure.NotYetValid) {
            call.respond(HttpStatusCode.Unauthorized, "Token not yet valid")
            null
        } catch (e: JwtVerificationFailure.ClaimMissing) {
            call.respond(HttpStatusCode.BadRequest, "Required claim missing: ${e.name}")
            null
        }
    }
}

private fun decodeBase64UrlSegment(s: String): String {
    val padded = s + "=".repeat((4 - s.length % 4) % 4)
    return java.util.Base64.getUrlDecoder().decode(padded).decodeToString()
}

/** Tiny field extractor matching `Jwt.kt`'s shape. Returns the string value or null. */
private fun quickField(json: String, name: String): String? {
    val key = "\"$name\""
    val idx = json.indexOf(key)
    if (idx < 0) return null
    val openQuote = json.indexOf('"', json.indexOf(':', idx + key.length) + 1)
    if (openQuote < 0) return null
    val closeQuote = json.indexOf('"', openQuote + 1)
    if (closeQuote < 0) return null
    return json.substring(openQuote + 1, closeQuote)

}
