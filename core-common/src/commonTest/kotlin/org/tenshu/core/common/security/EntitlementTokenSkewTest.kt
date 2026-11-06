package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Issuer-vs-verifier clock skew on the bearer **entitlement token** (`iat`).
 *
 * Same fixture posture as [AccountClosureTokenTest]: the unit under test is the framing and the
 * time gates, so the signer is a deterministic fake — real Ed25519 belongs where the backend is
 * tested.
 *
 * ⚠⚠ Why this exists: commerce stamps `iat` from its clock and the relay checks it against its
 * own, one HTTP round trip later — and on 2026-08-08 commerce measured **1.21 s ahead of the
 * relay**, the same order as the mint→present gap. So the freshest, most obviously legitimate
 * token there is could be refused, surfacing as a `402` on the first write after signing in and
 * then healing a second later. The record path had the identical defect (fixed in the same
 * series); this is the token half.
 */
class EntitlementTokenSkewTest {

    private val signature = byteArrayOf(3, 1, 4, 1)
    private val sign: (ByteArray) -> ByteArray = { signature }
    private val accept: (String?, ByteArray, ByteArray) -> Boolean = { _, _, sig ->
        sig.contentEquals(signature)
    }

    private fun claims(issuedAtSec: Long = 1_000, expiresAtSec: Long = 2_000) =
        EntitlementTokenClaims(
            ownerId = "owner-1",
            tier = "tenshu.drive.premium",
            quotaBytes = 2L * 1024 * 1024 * 1024 * 1024,
            issuedAtSec = issuedAtSec,
            expiresAtSec = expiresAtSec,
        )

    @Test
    fun tokenStampedSecondsAheadOfTheVerifier_stillVerifies() {
        val token = encodeEntitlementToken(claims(issuedAtSec = 1_000), sign)

        // The verifier's clock trails the issuer's by 2s — inside the leeway.
        val decoded = decodeEntitlementToken(token, currentTimeSec = 998, verifier = accept)

        assertEquals("tenshu.drive.premium", decoded.tier)
    }

    /** The boundary itself, so the constant cannot be quietly narrowed to nothing. */
    @Test
    fun theWholeLeewayIsHonoured_atItsEdge() {
        val token = encodeEntitlementToken(claims(issuedAtSec = 1_000), sign)
        val edge = 1_000 - ENTITLEMENT_TOKEN_SKEW_LEEWAY_SEC

        decodeEntitlementToken(token, currentTimeSec = edge, verifier = accept)

        assertFailsWith<EntitlementTokenFailure.NotYetValid>(
            "one second beyond the leeway is no longer clock skew",
        ) {
            decodeEntitlementToken(token, currentTimeSec = edge - 1, verifier = accept)
        }
    }

    /** ⚠ One-sided: tolerating a late token would extend a grant past what was issued. */
    @Test
    fun expiryGetsNoLeeway() {
        val token = encodeEntitlementToken(claims(expiresAtSec = 2_000), sign)

        assertFailsWith<EntitlementTokenFailure.Expired> {
            decodeEntitlementToken(token, currentTimeSec = 2_000, verifier = accept)
        }
        assertFailsWith<EntitlementTokenFailure.Expired>(
            "an expired token must not be revived by the start-bound tolerance",
        ) {
            decodeEntitlementToken(
                token,
                currentTimeSec = 2_000 + ENTITLEMENT_TOKEN_SKEW_LEEWAY_SEC,
                verifier = accept,
            )
        }
    }

    /**
     * ⚠⚠ Pins the **deliberate** divergence between the two credentials, which would otherwise
     * read as an oversight and get "fixed".
     *
     * The closure record authorises deleting an owner's entire content keyspace, and the two fail
     * very differently under skew: a refused entitlement token is a user-visible `402` on a write
     * happening right now, with nothing to retry it; a refused closure record costs nothing,
     * because a repeating sweep re-posts a freshly-stamped one. Loosening a delete capability to
     * fix a problem that already heals itself is a bad trade.
     */
    @Test
    fun theClosureCredentialDeliberatelyGetsNoLeeway() {
        val closure = encodeAccountClosureToken(
            AccountClosureClaims(
                ownerId = "owner-1",
                closedAtSec = 1_000,
                issuedAtSec = 1_000,
                expiresAtSec = 2_000,
            ),
            sign,
        )

        assertFailsWith<EntitlementTokenFailure.NotYetValid>(
            "a delete capability stays strict — its skew failures are self-healing",
        ) {
            decodeAccountClosureToken(closure, currentTimeSec = 999, verifier = accept)
        }
    }
}
