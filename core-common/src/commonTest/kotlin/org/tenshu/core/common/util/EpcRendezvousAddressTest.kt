package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Phase 3a of `docs/proposals/ios-proxy-architecture.md` — the rendezvous
 * session-id derivation. Pure HKDF, so it runs on every target including the iOS
 * simulator gate.
 */
class EpcRendezvousAddressTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun derivationIsDeterministicAnd16Bytes() {
        val a = deriveEpcRendezvousSessionId(secret)
        val b = deriveEpcRendezvousSessionId(secret)
        assertEquals(EPC_RENDEZVOUS_SESSION_ID_LENGTH, a.size)
        assertContentEquals(a, b, "same pairing secret → same session id on both ends")
    }

    @Test
    fun differentSecretsYieldDifferentIds() {
        val other = ByteArray(32) { (it + 1).toByte() }
        assertFalse(deriveEpcRendezvousSessionId(secret).contentEquals(deriveEpcRendezvousSessionId(other)))
    }

    @Test
    fun isDistinctFromTheGrantAddressDomain() {
        // Same input bytes, different HKDF info → independent of deriveGrantAddress.
        val grant = deriveGrantAddress(
            grantorPublicKey = ByteArray(0),
            granteePublicKey = ByteArray(0),
            sharedSecret = secret,
        )
        assertFalse(deriveEpcRendezvousSessionId(secret).contentEquals(grant))
    }
}
