package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The commerce→relay **account-closure** credential (`retention-quota-model.md` Face A / D4).
 *
 * The unit under test is the *framing* — segments, alg pinning, claim extraction, expiry — not the
 * signature primitive, so the signer here is a deterministic fake. Real Ed25519 lives where the
 * backend does; mixing them in would test BouncyCastle, not this codec.
 */
class AccountClosureTokenTest {

    private val signature = byteArrayOf(7, 7, 7, 7)
    private val sign: (ByteArray) -> ByteArray = { signature }
    private val accept: (String?, ByteArray, ByteArray) -> Boolean = { _, _, sig ->
        sig.contentEquals(signature)
    }
    private val reject: (String?, ByteArray, ByteArray) -> Boolean = { _, _, _ -> false }

    private fun claims(
        ownerId: String = "owner-1",
        closedAtSec: Long = 1_000,
        issuedAtSec: Long = 1_000,
        expiresAtSec: Long = 2_000,
        keyId: String? = null,
    ) = AccountClosureClaims(ownerId, closedAtSec, issuedAtSec, expiresAtSec, keyId)

    @Test
    fun round_trips_every_claim() {
        val original = claims(keyId = "k-2026")
        val decoded = decodeAccountClosureToken(
            token = encodeAccountClosureToken(original, sign),
            currentTimeSec = 1_500,
            verifier = accept,
        )
        assertEquals(original, decoded)
    }

    @Test
    fun rejects_a_bad_signature() {
        val token = encodeAccountClosureToken(claims(), sign)
        assertFailsWith<EntitlementTokenFailure.SignatureMismatch> {
            decodeAccountClosureToken(token, currentTimeSec = 1_500, verifier = reject)
        }
    }

    @Test
    fun rejects_an_expired_record() {
        // ⚠ This is what makes account *reopening* work: commerce stops re-issuing, and the last
        // record stops authorising anything. Without expiry a leaked record would be a permanent
        // delete-this-owner capability that fires against a reopened account.
        val token = encodeAccountClosureToken(claims(expiresAtSec = 2_000), sign)
        assertFailsWith<EntitlementTokenFailure.Expired> {
            decodeAccountClosureToken(token, currentTimeSec = 2_000, verifier = accept)
        }
    }

    @Test
    fun rejects_a_record_from_the_future() {
        val token = encodeAccountClosureToken(claims(issuedAtSec = 5_000, expiresAtSec = 6_000), sign)
        assertFailsWith<EntitlementTokenFailure.NotYetValid> {
            decodeAccountClosureToken(token, currentTimeSec = 4_999, verifier = accept)
        }
    }

    @Test
    fun refuses_to_mint_a_record_without_an_owner() {
        // ⚠ A closure record with no owner is not a weaker credential, it is an unusable one — and
        // a blank owner reaching `reclaimOwner` is exactly the input that primitive refuses.
        assertFailsWith<IllegalArgumentException> {
            encodeAccountClosureToken(claims(ownerId = ""), sign)
        }
        assertFailsWith<IllegalArgumentException> {
            encodeAccountClosureToken(claims(ownerId = "  "), sign)
        }
    }

    @Test
    fun rejects_a_signed_record_whose_owner_claim_is_missing_or_blank() {
        // The encoder guards the mint side; the decoder must not trust that a record it receives
        // was minted by our encoder. A *validly signed* payload with no `sub` still fails.
        val header = """{"alg":"EdDSA","typ":"JWT"}"""
        listOf("""{"closed":1000,"iat":1000,"exp":2000}""", """{"sub":"","closed":1000,"iat":1000,"exp":2000}""")
            .forEach { payload ->
                val token = fakeToken(header, payload)
                assertFailsWith<EntitlementTokenFailure.ClaimMissing> {
                    decodeAccountClosureToken(token, currentTimeSec = 1_500, verifier = accept)
                }
            }
    }

    @Test
    fun rejects_a_non_EdDSA_algorithm_including_none() {
        // Alg-pinning: the classic JWT `alg: none` downgrade must not reach the signature check.
        listOf("none", "HS256").forEach { alg ->
            val token = fakeToken("""{"alg":"$alg","typ":"JWT"}""", """{"sub":"o","closed":1,"iat":1,"exp":9}""")
            assertFailsWith<EntitlementTokenFailure.WrongAlgorithm> {
                decodeAccountClosureToken(token, currentTimeSec = 5, verifier = accept)
            }
        }
    }

    @Test
    fun rejects_a_malformed_token() {
        assertFailsWith<EntitlementTokenFailure.Malformed> {
            decodeAccountClosureToken("not.a", currentTimeSec = 1, verifier = accept)
        }
    }

    @Test
    fun an_absent_keyId_decodes_as_the_default_key() {
        val decoded = decodeAccountClosureToken(
            token = encodeAccountClosureToken(claims(keyId = null), sign),
            currentTimeSec = 1_500,
            verifier = accept,
        )
        assertNull(decoded.keyId, "null selects the issuer's default key, matching the entitlement token")
    }

    /** Builds a token from raw header/payload JSON so malformed-input cases are expressible. */
    private fun fakeToken(headerJson: String, payloadJson: String): String {
        val h = b64(headerJson.encodeToByteArray())
        val p = b64(payloadJson.encodeToByteArray())
        return "$h.$p.${b64(signature)}"
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun b64(bytes: ByteArray): String =
        kotlin.io.encoding.Base64.UrlSafe
            .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode(bytes)
}
