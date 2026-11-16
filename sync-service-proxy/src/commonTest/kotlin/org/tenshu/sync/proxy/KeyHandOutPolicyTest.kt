package org.tenshu.sync.proxy

import org.tenshu.identity.RealmKeyGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-GR-02 revocation window: the rule every owner-side key hand-out obeys. The window is an owner device holding the
 * new key but neither revocation record, because the records' block failed to push while the rotation's went through.
 */
class KeyHandOutPolicyTest {

    private val bob = "b0b".padEnd(64, '0')
    private val carol = "ca7".padEnd(64, '0')

    @Test
    fun aMemberTheGenerationRowNames_getsNothing_evenWithNoRevocationRecord() {
        assertFalse(KeyHandOutPolicy.mayReceive(bob, revokedByRecords = false, revokedAtGeneration = setOf(bob)))
    }

    @Test
    fun aMemberTheRecordsRevoke_getsNothing_evenIfTheRowDoesNotNameThem() {
        assertFalse(KeyHandOutPolicy.mayReceive(bob, revokedByRecords = true, revokedAtGeneration = emptySet()))
    }

    @Test
    fun withNoGenerationRowForTheKey_nobodyIsSentIt() {
        // The row may still be in flight; its absence never reads as "nobody was revoked".
        assertFalse(KeyHandOutPolicy.mayReceive(carol, revokedByRecords = false, revokedAtGeneration = null))
    }

    @Test
    fun aCurrentMember_isSentTheKey_whenTheRowIsHere() {
        assertTrue(KeyHandOutPolicy.mayReceive(carol, revokedByRecords = false, revokedAtGeneration = setOf(bob)))
    }

    @Test
    fun theRevokedSet_roundTripsThroughTheRowsField_inAStableOrder() {
        val encoded = RealmKeyGeneration.encodeMemberIds(setOf(carol, bob))
        assertEquals("$bob,$carol", encoded)
        assertEquals(setOf(bob, carol), RealmKeyGeneration.decodeMemberIds(encoded))
        assertEquals(emptySet(), RealmKeyGeneration.decodeMemberIds(RealmKeyGeneration.encodeMemberIds(emptySet())))
    }
}
