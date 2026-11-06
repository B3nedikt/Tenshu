package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * The incremental digest must agree with the one-shot [sha256] for every chunking — it is what gates a
 * downloaded region against the signed catalog (`maps-region-delivery.md` §7.1 track 3e / R4), so a
 * digest that depended on how the bytes happened to arrive off the socket would reject good bundles.
 */
class Sha256DigestTest {

    private val payload = ByteArray(10_000) { (it * 31 % 251).toByte() }

    private fun streamed(chunk: Int): ByteArray {
        val digest = Sha256Digest()
        var offset = 0
        val buffer = ByteArray(chunk)
        while (offset < payload.size) {
            val n = minOf(chunk, payload.size - offset)
            payload.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + n)
            // Feed a partially-filled buffer on the last chunk — exactly what a socket read does.
            digest.update(buffer, n)
            offset += n
        }
        return digest.digest()
    }

    @Test
    fun anyChunkingHashesTheSameAsTheOneShot() {
        val expected = sha256(payload)
        // 1 byte at a time, an odd size that never aligns, a size larger than the payload, and an exact
        // divisor — the boundary cases a real socket produces.
        listOf(1, 7, 1024, 3333, 10_000, 16_384).forEach { chunk ->
            assertContentEquals(expected, streamed(chunk), "chunk size $chunk")
        }
    }

    @Test
    fun emptyInputMatchesTheOneShot() {
        assertContentEquals(sha256(ByteArray(0)), Sha256Digest().digest())
    }

    @Test
    fun zeroLengthUpdatesAreIgnored() {
        val digest = Sha256Digest()
        digest.update(ByteArray(0))
        digest.update(payload, payload.size)
        digest.update(ByteArray(64), 0) // a read that returned nothing must not change the hash
        assertContentEquals(sha256(payload), digest.digest())
    }
}
