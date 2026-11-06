package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UnigramVocabulary.read] over bytes, on every platform: the packed format is little-endian and
 * UTF-8, and neither is a platform's default everywhere.
 */
class UnigramVocabularyTest {

    private fun packed(
        pieces: List<Pair<String, Float>>,
        magic: String = "TSHU",
        version: Int = 1,
        count: Int = pieces.size,
        byteFallback: Boolean = true,
        unkId: Int = 2,
    ): ByteArray = packedVocabulary(pieces, magic, version, count, byteFallback, unkId)

    private val pieces = listOf(
        "<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f,
        "▁sun" to -1.5f, "café" to -2.25f, "夕日" to -7f, "🌅" to -9.125f,
    )

    @Test
    fun aPackedVocabularyRoundTrips() {
        val vocabulary = assertNotNull(UnigramVocabulary.read(packed(pieces)))

        assertEquals(pieces.size, vocabulary.size)
        pieces.forEachIndexed { index, (piece, score) ->
            assertEquals(index, vocabulary.idOf(piece), piece)
            assertEquals(score, vocabulary.scoreOf(piece), piece)
        }
        assertEquals(2, vocabulary.unkId)
        assertTrue(vocabulary.byteFallback)
        // In UTF-16 units, which is what the Viterbi's window is measured in.
        assertEquals(5, vocabulary.longestPiece)
        assertNull(vocabulary.idOf("absent"))
    }

    @Test
    fun theHeaderFlagsAreRead() {
        val vocabulary = assertNotNull(UnigramVocabulary.read(packed(pieces, byteFallback = false, unkId = 0)))

        assertEquals(false, vocabulary.byteFallback)
        assertEquals(0, vocabulary.unkId)
    }

    /** ⚠ A half-copied file is "this device cannot embed text", never a crash. */
    @Test
    fun aTruncatedVocabularyIsNull() {
        val whole: ByteArray = packed(pieces)

        assertNull(UnigramVocabulary.read(whole.copyOf(whole.size - 1)))
        assertNull(UnigramVocabulary.read(whole.copyOf(20)))
        assertNull(UnigramVocabulary.read(whole.copyOf(3)))
        assertNull(UnigramVocabulary.read(ByteArray(0)))
    }

    @Test
    fun anotherFileIsNull() {
        assertNull(UnigramVocabulary.read(packed(pieces, magic = "ONNX")))
        assertNull(UnigramVocabulary.read(packed(pieces, version = 2)))
        assertNull(UnigramVocabulary.read("not a vocabulary at all, just some text".encodeToByteArray()))
    }

    /**
     * ⚠ The answer is `null` with or without the reader's count check: a refused allocation is
     * caught too. What the check saves is the allocation, which this cannot observe.
     */
    @Test
    fun anImplausibleCountIsNull() {
        assertNull(UnigramVocabulary.read(packed(pieces, count = Int.MAX_VALUE)))
        assertNull(UnigramVocabulary.read(packed(pieces, count = -1)))
    }
}
