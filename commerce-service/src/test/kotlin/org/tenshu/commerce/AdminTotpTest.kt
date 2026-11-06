package org.tenshu.commerce

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The admin console's second factor (WC-5, §5.3). */
class AdminTotpTest {

    private val secretBase32 = "JBSWY3DPEHPK3PXP" // the RFC-style test vector seed
    private val secret = assertNotNull(AdminTotp.decodeBase32(secretBase32))

    private fun msFor(step: Long) = step * AdminTotp.STEP_SECONDS * 1000

    // --- the algorithm --------------------------------------------------------------------------

    @Test
    fun codesAreSixDigitsAndChangeWithTheStep() {
        val a = AdminTotp.code(secret, 1_000L)
        val b = AdminTotp.code(secret, 1_001L)
        assertEquals(6, a.length)
        assertTrue(a.all { it.isDigit() })
        assertTrue(a != b, "a new step must produce a new code")
        assertEquals(a, AdminTotp.code(secret, 1_000L), "…and be deterministic within one")
    }

    @Test
    fun base32DecodingToleratesPaddingSpacingAndCase() {
        val canonical = AdminTotp.decodeBase32(secretBase32)!!.toList()
        assertEquals(canonical, AdminTotp.decodeBase32("jbswy3dp ehpk3pxp")!!.toList())
        assertEquals(canonical, AdminTotp.decodeBase32("JBSWY3DP-EHPK3PXP===")!!.toList())
        assertNull(AdminTotp.decodeBase32("not!base32"), "an invalid alphabet is refused")
        assertNull(AdminTotp.decodeBase32("   "))
    }

    // --- verification ---------------------------------------------------------------------------

    @Test
    fun theCurrentCodeVerifies() {
        val verifier = AdminTotpVerifier(secretBase32)
        val step = 2_000L
        assertTrue(verifier.verify(AdminTotp.code(secret, step), msFor(step)))
    }

    @Test
    fun oneStepOfClockSkewIsToleratedInBothDirections() {
        val step = 3_000L
        assertTrue(AdminTotpVerifier(secretBase32).verify(AdminTotp.code(secret, step - 1), msFor(step)))
        assertTrue(AdminTotpVerifier(secretBase32).verify(AdminTotp.code(secret, step + 1), msFor(step)))
        // …but no further: a wider window is a wider replay window.
        assertFalse(AdminTotpVerifier(secretBase32).verify(AdminTotp.code(secret, step - 2), msFor(step)))
        assertFalse(AdminTotpVerifier(secretBase32).verify(AdminTotp.code(secret, step + 2), msFor(step)))
    }

    /**
     * ⚠⚠ The property that makes this a real second factor rather than a delay. A TOTP code stays
     * arithmetically valid for up to ~90 s here, so an intercepted request could otherwise simply be
     * replayed inside that window — leaving MFA to raise the cost of a captured request from
     * "forever" only to "a minute and a half".
     */
    @Test
    fun anAcceptedCodeCannotBePresentedTwice() {
        val verifier = AdminTotpVerifier(secretBase32)
        val step = 4_000L
        val code = AdminTotp.code(secret, step)

        assertTrue(verifier.verify(code, msFor(step)), "first use is accepted")
        assertFalse(verifier.verify(code, msFor(step)), "the same code is burned")
        // Still refused a step later, while it would otherwise remain inside the skew window.
        assertFalse(verifier.verify(code, msFor(step) + AdminTotp.STEP_SECONDS * 1000))
    }

    @Test
    fun aDifferentCodeStillWorksAfterOneIsBurned() {
        val verifier = AdminTotpVerifier(secretBase32)
        assertTrue(verifier.verify(AdminTotp.code(secret, 5_000L), msFor(5_000L)))
        assertTrue(verifier.verify(AdminTotp.code(secret, 5_001L), msFor(5_001L)), "the next step is fine")
    }

    @Test
    fun malformedAndWrongCodesAreRefused() {
        val verifier = AdminTotpVerifier(secretBase32)
        val now = msFor(6_000L)
        assertFalse(verifier.verify(null, now))
        assertFalse(verifier.verify("", now))
        assertFalse(verifier.verify("12345", now), "too short")
        assertFalse(verifier.verify("1234567", now), "too long")
        assertFalse(verifier.verify("abcdef", now), "not digits")
        assertFalse(verifier.verify("000000", now) && AdminTotp.code(secret, 6_000L) != "000000")
    }

    /** ⚠ Locked by default, like `TokenAdminAuth`: an unconfigured verifier admits nobody. */
    @Test
    fun anUnconfiguredVerifierIsNotConfigured_andVerifiesNothing() {
        listOf(null, "", "   ", "!!!not-base32!!!").forEach { seed ->
            val verifier = AdminTotpVerifier(seed)
            assertFalse(verifier.isConfigured, "seed=$seed")
            assertFalse(verifier.verify("123456", msFor(7_000L)), "seed=$seed")
        }
    }

    @Test
    fun burnedCodesDoNotAccumulateForever() {
        val verifier = AdminTotpVerifier(secretBase32)
        // Walk far enough that early entries can no longer be presented at all.
        (8_000L..8_050L).forEach { step ->
            assertTrue(verifier.verify(AdminTotp.code(secret, step), msFor(step)), "step $step")
        }
        // The oldest code is long out of window; re-presenting it now fails for that reason, and the
        // set holding it has been pruned rather than grown across the whole walk.
        assertFalse(verifier.verify(AdminTotp.code(secret, 8_000L), msFor(8_050L)))
    }
}
