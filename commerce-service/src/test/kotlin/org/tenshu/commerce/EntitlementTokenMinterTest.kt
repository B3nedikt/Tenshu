package org.tenshu.commerce

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.EntitlementTokenFailure
import org.tenshu.core.common.security.EntitlementTokenVerifier
import org.tenshu.core.common.security.decodeEntitlementToken
import org.tenshu.identity.EntitlementSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The bearer entitlement token round-trips: a token the minter signs verifies under the pinned
 * commerce public key via [EntitlementTokenVerifier], carries the right tier/quota/owner, and the
 * decoder rejects expired / tampered / blinded-but-bound edge cases
 * (`commerce-billing-plane.md` §4 / §8 Phase 1).
 */
class EntitlementTokenMinterTest {

    private val ed = BouncyCastleEd25519Backend()
    private val config = CommerceConfig.fromEnv { null } // defaults: dev seed, tenshu.commerce, 72h, 50 GiB
    private val minter = EntitlementTokenMinter(config, IssuerKeypair(config.issuerSeed, ed))

    // The relay's view: it holds only the issuer public key (the dev seed's public half, pinned).
    private fun verifier(nowSec: Long) = EntitlementTokenVerifier(
        publicKeysByKeyId = mapOf(config.signingKeyId to EntitlementSignature.COMMERCE_PUBLIC_KEY),
        ed25519 = ed,
        nowSec = { nowSec },
    )

    @Test
    fun mintedToken_verifiesAndCarriesTierQuotaOwnerAndTtl() {
        val nowMs = 1_700_000_000_000L
        val token = minter.mint(ownerId = "owner-1", tier = minter.topTier, quotaBytes = minter.topTierQuotaBytes, nowMs = nowMs)

        val result = verifier(nowMs / 1000).verify(token)
        val valid = assertIs<EntitlementTokenVerifier.Result.Valid>(result)
        assertEquals("owner-1", valid.claims.ownerId)
        assertEquals(CommerceConfig.DEFAULT_TOP_TIER_PRODUCT_ID, valid.claims.tier)
        assertEquals(CommerceConfig.DEFAULT_TOP_TIER_QUOTA_BYTES, valid.claims.quotaBytes)
        assertEquals(nowMs / 1000, valid.claims.issuedAtSec)
        assertEquals(nowMs / 1000 + config.tokenTtlHours * 3600, valid.claims.expiresAtSec)
    }

    @Test
    fun expiredToken_isRejected() {
        val nowMs = 1_700_000_000_000L
        val token = minter.mint(ownerId = "owner-1", tier = minter.topTier, quotaBytes = 1L, nowMs = nowMs)
        // Verify well past expiry.
        val result = verifier(nowMs / 1000 + config.tokenTtlHours * 3600 + 1).verify(token)
        val invalid = assertIs<EntitlementTokenVerifier.Result.Invalid>(result)
        assertIs<EntitlementTokenFailure.Expired>(invalid.failure)
    }

    @Test
    fun tamperedToken_failsSignature() {
        val nowMs = 1_700_000_000_000L
        val token = minter.mint(ownerId = "owner-1", tier = minter.topTier, quotaBytes = 1L, nowMs = nowMs)
        // Flip a character in the payload segment.
        val parts = token.split(".")
        val tampered = parts[0] + "." + parts[1].dropLast(2) + "AA" + "." + parts[2]
        val result = verifier(nowMs / 1000).verify(tampered)
        assertIs<EntitlementTokenVerifier.Result.Invalid>(result)
    }

    @Test
    fun unknownKeyId_reportedDistinctly() {
        val nowMs = 1_700_000_000_000L
        val token = minter.mint(ownerId = "owner-1", tier = minter.topTier, quotaBytes = 1L, nowMs = nowMs)
        // Relay configured with a DIFFERENT keyId than the token carries (null) → UnknownKey.
        val mismatched = EntitlementTokenVerifier(
            publicKeysByKeyId = mapOf("some-other-kid" to EntitlementSignature.COMMERCE_PUBLIC_KEY),
            ed25519 = ed,
            nowSec = { nowMs / 1000 },
        )
        val invalid = assertIs<EntitlementTokenVerifier.Result.Invalid>(mismatched.verify(token))
        assertIs<EntitlementTokenFailure.UnknownKey>(invalid.failure)
    }

    @Test
    fun blindedToken_hasNoOwnerId() {
        val nowMs = 1_700_000_000_000L
        val token = minter.mint(ownerId = null, tier = minter.topTier, quotaBytes = 1L, nowMs = nowMs)
        // Decode directly (verifier-agnostic of ownerId): sub claim absent → null.
        val claims = decodeEntitlementToken(token, nowMs / 1000) { _, msg, sig ->
            ed.verify(EntitlementSignature.COMMERCE_PUBLIC_KEY, msg, sig)
        }
        assertNull(claims.ownerId)
    }

    @Test
    fun decoder_rejectsAlgNone() {
        // A hand-built alg:none token must be rejected (algorithm-confusion defence).
        val none = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJ0aWVyIjoieCIsInF1b3RhIjoxLCJpYXQiOjAsImV4cCI6OTk5OTk5OTk5OX0."
        assertFailsWith<EntitlementTokenFailure.WrongAlgorithm> {
            decodeEntitlementToken(none, 1L) { _, _, _ -> true }
        }
    }
}
