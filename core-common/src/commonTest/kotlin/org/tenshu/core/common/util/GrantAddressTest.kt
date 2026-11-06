package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Grant-address derivation (`shared-realm-membership-grant.md` §4, D3).
 *
 * Like the contact SAS, the address is a pure cross-platform HKDF: the first
 * implementation pins the vector and every platform reproduces it, so a silent
 * change to the derivation (info tag, input order, length) is caught before it
 * breaks the grantor's drop and the grantee's pickup deriving different slots.
 * Unlike the SAS, the derivation is **directional** — grantor and grantee enter
 * in order, not sorted — so a mutual pair gets two distinct mailbox slots.
 */
class GrantAddressTest {

    private val alice = ByteArray(32) { 0x0A }
    private val bob = ByteArray(32) { 0x0B }
    private val secret = ByteArray(32) { 0x0C }

    @Test
    fun pinnedCrossPlatformVector() {
        // Pinned 2026-06-13 by the first (pure-common HKDF) implementation.
        assertEquals("3b492e4043cb2acd3f71bdfebeddd2f2", deriveGrantAddress(alice, bob, secret).toHexString())
    }

    @Test
    fun isSixteenBytes() {
        assertEquals(GRANT_ADDRESS_LENGTH, deriveGrantAddress(alice, bob, secret).size)
    }

    @Test
    fun deterministic() {
        // Grantor and grantee derive the same slot from the same inputs.
        assertContentEquals(
            deriveGrantAddress(alice, bob, secret),
            deriveGrantAddress(alice, bob, secret),
        )
    }

    @Test
    fun directional_grantorGranteeOrderMatters() {
        // Alice→Bob and Bob→Alice are distinct mailbox slots.
        assertFalse(
            deriveGrantAddress(alice, bob, secret)
                .contentEquals(deriveGrantAddress(bob, alice, secret)),
        )
    }

    @Test
    fun differentSecret_differentAddress() {
        val other = ByteArray(32) { 0x0D }
        assertFalse(
            deriveGrantAddress(alice, bob, secret)
                .contentEquals(deriveGrantAddress(alice, bob, other)),
        )
    }
}
