package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Contact-SAS derivation (`contact-invitation-handshake.md` §5.3).
 *
 * The pinned vector follows the pairing-SAS convention: the first
 * implementation pins it, every platform asserts it — a silent change to the
 * derivation (sort order, info tag, truncation) breaks the vector before it
 * breaks two users' matching screens.
 */
class ContactSasTest {

    // Fixed "keypairs" for the pinned vector — values, not validity, matter
    // to the derivation (it never does curve math).
    private val pubA = ByteArray(32) { 0x01 }
    private val pubB = ByteArray(32) { 0x02 }

    @Test
    fun pinnedCrossPlatformVector() {
        // Pinned 2026-06-11 by the first (desktop/BouncyCastle-free, pure
        // common HKDF) implementation; all platforms must reproduce it.
        assertEquals("744595", deriveContactSas(pubA, pubB))
    }

    @Test
    fun argumentOrderDoesNotMatter() {
        // Lexicographic sort inside the derivation — both parties derive the
        // same digits regardless of who passes which key first.
        assertEquals(deriveContactSas(pubA, pubB), deriveContactSas(pubB, pubA))

        val randomA = nextSecureRandomBytes(32)
        val randomB = nextSecureRandomBytes(32)
        assertEquals(deriveContactSas(randomA, randomB), deriveContactSas(randomB, randomA))
    }

    @Test
    fun bitFlipInEitherKeyChangesTheSas() {
        val base = deriveContactSas(pubA, pubB)

        val flippedA = pubA.copyOf().also { it[7] = (it[7].toInt() xor 0x40).toByte() }
        assertNotEquals(base, deriveContactSas(flippedA, pubB))

        val flippedB = pubB.copyOf().also { it[31] = (it[31].toInt() xor 0x01).toByte() }
        assertNotEquals(base, deriveContactSas(pubA, flippedB))
    }

    @Test
    fun outputIsSixZeroPaddedDigits() {
        repeat(16) {
            val sas = deriveContactSas(nextSecureRandomBytes(32), nextSecureRandomBytes(32))
            assertEquals(6, sas.length)
            assertTrue(sas.all { it.isDigit() }, "non-digit in SAS '$sas'")
        }
    }
}
