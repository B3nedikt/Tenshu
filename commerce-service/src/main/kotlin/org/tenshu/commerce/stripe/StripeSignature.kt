package org.tenshu.commerce.stripe

import org.tenshu.commerce.constantTimeEquals
import org.tenshu.commerce.hmacSha256Hex

/**
 * Stripe webhook-signature verification (`commerce-accounts-and-sessions.md` §5.3, §6). Stripe
 * signs every webhook with the endpoint's signing secret and sends the result in a
 * `Stripe-Signature` header of the form:
 *
 * ```
 * t=1680000000,v1=<hex-hmac>,v1=<hex-hmac-second-secret>
 * ```
 *
 * The signed payload is `"$t.$rawBody"` HMAC-SHA256'd under the signing secret. Verifying this is
 * **load-bearing** (T-CB-07): a forged `invoice.paid` would otherwise mint entitlements / fabricate
 * paid accounts. This is a from-scratch port of Stripe's documented scheme (no `stripe-java` SDK on
 * the classpath — consistent with the module's hand-rolled REST posture) and must read the **raw**
 * request bytes, before any content re-encoding, because a single altered byte changes the HMAC.
 */
object StripeSignature {

    /** Default replay window — Stripe's own recommended 5-minute tolerance. */
    const val DEFAULT_TOLERANCE_MS: Long = 5L * 60 * 1000

    /**
     * True iff [sigHeader] carries a `t` within [toleranceMs] of [nowMs] **and** a `v1` scheme
     * signature that matches `HMAC-SHA256("$t.$payload", secret)` (constant-time). A blank secret,
     * a missing/malformed header, a stale timestamp, or no matching `v1` all return false.
     */
    fun verify(
        payload: String,
        sigHeader: String?,
        secret: String,
        nowMs: Long,
        toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    ): Boolean {
        if (secret.isBlank() || sigHeader.isNullOrBlank()) return false

        val parts = sigHeader.split(",").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull() ?: return false
        // Reject stale (and future-dated) events — bounds a captured-signature replay.
        if (kotlin.math.abs(nowMs - timestamp * 1000) > toleranceMs) return false

        val expected = hmacSha256Hex(secret, "$timestamp.$payload")
        // Stripe may send several v1 signatures during a secret rotation — accept any match.
        return parts.any { it.first == "v1" && constantTimeEquals(it.second, expected) }
    }
}
