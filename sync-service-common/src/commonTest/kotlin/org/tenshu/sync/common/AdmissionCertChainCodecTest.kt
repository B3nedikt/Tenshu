package org.tenshu.sync.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-trip + malformed-input contract of [encodeAdmissionCertChain] /
 * [decodeAdmissionCertChain] — the framing the pairing key-blob's v2 tail and
 * the P2 relay POST both ride.
 */
class AdmissionCertChainCodecTest {

    private val chain = listOf(
        AdmissionCapabilityCert(
            ownerId = "owner-1",
            deviceA33PublicKey = ByteArray(32) { (it + 10).toByte() },
            deviceId = "device-child",
            mintedAtEpoch = 7L,
            signature = ByteArray(64) { (it + 20).toByte() },
        ),
        AdmissionCapabilityCert(
            ownerId = "owner-1",
            deviceA33PublicKey = ByteArray(32) { (it + 30).toByte() },
            deviceId = "device-parent",
            mintedAtEpoch = 3L,
            signature = ByteArray(64) { (it + 40).toByte() },
        ),
    )

    @Test
    fun roundTrip_preservesEveryFieldAndOrder() {
        val decoded = decodeAdmissionCertChain(encodeAdmissionCertChain(chain))
        assertEquals(chain.size, decoded.size)
        chain.zip(decoded).forEach { (original, back) ->
            assertEquals(original.ownerId, back.ownerId)
            assertContentEquals(original.deviceA33PublicKey, back.deviceA33PublicKey)
            assertEquals(original.deviceId, back.deviceId)
            assertEquals(original.mintedAtEpoch, back.mintedAtEpoch)
            assertContentEquals(original.signature, back.signature)
        }
    }

    @Test
    fun emptyChain_roundTrips() {
        assertTrue(decodeAdmissionCertChain(encodeAdmissionCertChain(emptyList())).isEmpty())
    }

    @Test
    fun truncatedBytes_throw() {
        val encoded = encodeAdmissionCertChain(chain)
        assertFailsWith<IllegalArgumentException> {
            decodeAdmissionCertChain(encoded.copyOfRange(0, encoded.size - 5))
        }
    }

    @Test
    fun trailingGarbage_throws() {
        assertFailsWith<IllegalArgumentException> {
            decodeAdmissionCertChain(encodeAdmissionCertChain(chain) + byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun hostileCount_failsCheaply_withoutAllocating() {
        // A huge claimed count over a few bytes must be rejected by the plausibility
        // bound, not attempted (a naive List(count) would try to allocate it). The
        // relay decodes attacker-supplied bytes on the admission path — DoS hygiene.
        val hostile = byteArrayOf(0x7F, -1, -1, -1) + ByteArray(16)
        assertFailsWith<IllegalArgumentException> { decodeAdmissionCertChain(hostile) }
    }
}
