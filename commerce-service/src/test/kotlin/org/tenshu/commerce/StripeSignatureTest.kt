package org.tenshu.commerce

import org.tenshu.commerce.stripe.StripeSignature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for Stripe webhook-signature verification (§5.3, T-CB-07) — the load-bearing guard
 * that makes a forged `invoice.paid` non-mintable.
 */
class StripeSignatureTest {

    private val secret = "whsec_test_secret"
    private val payload = """{"id":"evt_1","type":"invoice.paid"}"""

    private fun header(timestampSec: Long, sig: String) = "t=$timestampSec,v1=$sig"

    private fun validHeader(timestampSec: Long): String =
        header(timestampSec, hmacSha256Hex(secret, "$timestampSec.$payload"))

    @Test
    fun validSignature_verifies() {
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000
        assertTrue(StripeSignature.verify(payload, validHeader(ts), secret, nowMs))
    }

    @Test
    fun tamperedPayload_fails() {
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000
        val hdr = validHeader(ts)
        assertFalse(StripeSignature.verify("""{"id":"evt_1","type":"invoice.paid","x":1}""", hdr, secret, nowMs))
    }

    @Test
    fun wrongSecret_fails() {
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000
        assertFalse(StripeSignature.verify(payload, validHeader(ts), "whsec_other", nowMs))
    }

    @Test
    fun staleTimestamp_fails() {
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000
        // Header signed 10 minutes ago, default tolerance 5 minutes → replay-rejected.
        assertFalse(StripeSignature.verify(payload, validHeader(ts), secret, nowMs + 10 * 60 * 1000))
    }

    @Test
    fun missingOrMalformedHeader_fails() {
        val nowMs = 1_700_000_000_000L
        assertFalse(StripeSignature.verify(payload, null, secret, nowMs))
        assertFalse(StripeSignature.verify(payload, "garbage", secret, nowMs))
        assertFalse(StripeSignature.verify(payload, "t=abc,v1=def", secret, nowMs))
    }

    @Test
    fun blankSecret_fails() {
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000
        assertFalse(StripeSignature.verify(payload, validHeader(ts), "", nowMs))
    }

    @Test
    fun multipleV1_anyMatchVerifies() {
        val nowMs = 1_700_000_000_000L
        val ts = nowMs / 1000
        val good = hmacSha256Hex(secret, "$ts.$payload")
        // Simulates a signing-secret rotation: an old (bogus) v1 alongside the current one.
        val hdr = "t=$ts,v1=deadbeef,v1=$good"
        assertTrue(StripeSignature.verify(payload, hdr, secret, nowMs))
    }
}
