package org.tenshu.core.common.security

import org.tenshu.core.security.stubEd25519Signature
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JwtTest {

    private val key = "test-device-private-key".encodeToByteArray()

    private fun sign(message: ByteArray): ByteArray = stubEd25519Signature(key, message)
    private fun verify(message: ByteArray, signature: ByteArray): Boolean =
        signature.contentEquals(stubEd25519Signature(key, message))

    @Test
    fun roundTripWithoutNonce() {
        val claims = JwtClaims(
            ownerId = "owner-1",
            deviceId = "device-1",
            issuedAtSec = 1_000L,
            expiresAtSec = 1_300L
        )
        val token = encodeJwt(claims, ::sign)
        val decoded = decodeJwt(token, currentTimeSec = 1_100L, verifier = ::verify)

        assertEquals("owner-1", decoded.ownerId)
        assertEquals("device-1", decoded.deviceId)
        assertEquals(1_000L, decoded.issuedAtSec)
        assertEquals(1_300L, decoded.expiresAtSec)
        assertNull(decoded.nonce)
    }

    @Test
    fun roundTripWithNonce() {
        val claims = JwtClaims(
            ownerId = "owner-1",
            deviceId = "device-1",
            issuedAtSec = 1_000L,
            expiresAtSec = 1_300L,
            nonce = "nonce-abc-123"
        )
        val token = encodeJwt(claims, ::sign)
        val decoded = decodeJwt(token, currentTimeSec = 1_100L, verifier = ::verify)
        assertEquals("nonce-abc-123", decoded.nonce)
    }

    @Test
    fun rejectsExpiredToken() {
        val token = encodeJwt(
            JwtClaims("o", "d", 100L, 200L),
            ::sign
        )
        assertFailsWith<JwtVerificationFailure.Expired> {
            decodeJwt(token, currentTimeSec = 200L, verifier = ::verify)
        }
        assertFailsWith<JwtVerificationFailure.Expired> {
            decodeJwt(token, currentTimeSec = 999L, verifier = ::verify)
        }
    }

    @Test
    fun rejectsTokenIssuedInTheFuture() {
        val token = encodeJwt(
            JwtClaims("o", "d", 1_000L, 1_300L),
            ::sign
        )
        assertFailsWith<JwtVerificationFailure.NotYetValid> {
            decodeJwt(token, currentTimeSec = 999L, verifier = ::verify)
        }
    }

    @Test
    fun rejectsMalformedToken_wrongSegmentCount() {
        assertFailsWith<JwtVerificationFailure.Malformed> {
            decodeJwt("not.a.valid.jwt", currentTimeSec = 0L) { _, _ -> true }
        }
        assertFailsWith<JwtVerificationFailure.Malformed> {
            decodeJwt("only-one-segment", currentTimeSec = 0L) { _, _ -> true }
        }
    }

    @Test
    fun rejectsMalformedToken_invalidBase64() {
        assertFailsWith<JwtVerificationFailure.Malformed> {
            decodeJwt("!!!.@@@.###", currentTimeSec = 0L) { _, _ -> true }
        }
    }

    @Test
    fun rejectsAlgNoneAttack() {
        // Hand-craft a token with `alg=none`. This is the canonical algorithm-confusion
        // vector: many JWT libraries accept `alg=none` by default and skip signature
        // verification entirely. Tenshu's decoder must reject before even calling the
        // verifier — i.e. we never get a chance to mis-implement verification for "none".
        val noneToken = handCraftToken(
            headerJson = """{"alg":"none","typ":"JWT"}""",
            payloadJson = """{"iss":"attacker","sub":"x","iat":1000,"exp":2000}""",
            signature = ByteArray(0)
        )
        val ex = assertFailsWith<JwtVerificationFailure.WrongAlgorithm> {
            decodeJwt(noneToken, currentTimeSec = 1_500L) { _, _ ->
                error("Verifier must NOT be called for alg=none")
            }
        }
        assertEquals("none", ex.alg)
    }

    @Test
    fun rejectsWrongAlgorithm_HS256() {
        // Algorithm-confusion via a non-EdDSA alg the decoder doesn't know how to verify.
        val hsToken = handCraftToken(
            headerJson = """{"alg":"HS256","typ":"JWT"}""",
            payloadJson = """{"iss":"attacker","sub":"x","iat":1000,"exp":2000}""",
            signature = "fake-signature".encodeToByteArray()
        )
        assertFailsWith<JwtVerificationFailure.WrongAlgorithm> {
            decodeJwt(hsToken, currentTimeSec = 1_500L) { _, _ ->
                error("Verifier must NOT be called for non-EdDSA alg")
            }
        }
    }

    @Test
    fun rejectsTamperedPayload() {
        val original = encodeJwt(
            JwtClaims("alice", "device-1", 1_000L, 1_300L),
            ::sign
        )
        val parts = original.split(".")
        // Swap in a payload claiming to be Mallory; signature was over the original input.
        val tamperedPayload =
            base64UrlEncode("""{"iss":"mallory","sub":"d","iat":1000,"exp":1300}""")
        val tampered = "${parts[0]}.$tamperedPayload.${parts[2]}"

        assertFailsWith<JwtVerificationFailure.SignatureMismatch> {
            decodeJwt(tampered, currentTimeSec = 1_100L, verifier = ::verify)
        }
    }

    @Test
    fun rejectsSignatureFromDifferentKey() {
        val token = encodeJwt(JwtClaims("o", "d", 1_000L, 1_300L), ::sign)
        val wrongKey = "different-private-key".encodeToByteArray()
        assertFailsWith<JwtVerificationFailure.SignatureMismatch> {
            decodeJwt(token, currentTimeSec = 1_100L) { msg, sig ->
                sig.contentEquals(stubEd25519Signature(wrongKey, msg))
            }
        }
    }

    @Test
    fun rejectsMissingClaims() {
        val noIss = handCraftSignedToken("""{"sub":"d","iat":1000,"exp":2000}""")
        val ex1 = assertFailsWith<JwtVerificationFailure.ClaimMissing> {
            decodeJwt(noIss, currentTimeSec = 1_500L, verifier = ::verify)
        }
        assertEquals("iss", ex1.name)

        val noExp = handCraftSignedToken("""{"iss":"o","sub":"d","iat":1000}""")
        val ex2 = assertFailsWith<JwtVerificationFailure.ClaimMissing> {
            decodeJwt(noExp, currentTimeSec = 1_500L, verifier = ::verify)
        }
        assertEquals("exp", ex2.name)
    }

    @Test
    fun escapesSpecialCharactersInClaims() {
        // A pathological ownerId that contains JSON-special chars must round-trip cleanly.
        val claims = JwtClaims(
            ownerId = """user-with-"quotes"-and-\backslashes""",
            deviceId = "device\nwith\nnewlines",
            issuedAtSec = 1_000L,
            expiresAtSec = 1_300L
        )
        val token = encodeJwt(claims, ::sign)
        val decoded = decodeJwt(token, currentTimeSec = 1_100L, verifier = ::verify)
        assertEquals(claims.ownerId, decoded.ownerId)
        assertEquals(claims.deviceId, decoded.deviceId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handCraftToken(
        headerJson: String,
        payloadJson: String,
        signature: ByteArray
    ): String {
        val h = base64UrlEncode(headerJson.encodeToByteArray())
        val p = base64UrlEncode(payloadJson.encodeToByteArray())
        val s = base64UrlEncode(signature)
        return "$h.$p.$s"
    }

    private fun handCraftSignedToken(payloadJson: String): String {
        val header = base64UrlEncode("""{"alg":"EdDSA","typ":"JWT"}""".encodeToByteArray())
        val payload = base64UrlEncode(payloadJson.encodeToByteArray())
        val signingInput = "$header.$payload".encodeToByteArray()
        val sig = base64UrlEncode(stubEd25519Signature(key, signingInput))
        return "$header.$payload.$sig"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.UrlSafe.encode(bytes).trimEnd('=')

    private fun base64UrlEncode(s: String): String = base64UrlEncode(s.encodeToByteArray())
}
