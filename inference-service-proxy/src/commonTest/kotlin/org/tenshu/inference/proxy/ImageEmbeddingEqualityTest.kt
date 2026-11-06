package org.tenshu.inference.proxy

import org.tenshu.inference.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A changed "not a face" verdict must be visible to equality. */
class ImageEmbeddingEqualityTest {

    private val vector = floatArrayOf(0.6f, 0.8f)

    @Test
    fun theSameVectorWithOppositeVerdictsIsNotEqual() {
        assertNotEquals(
            ImageEmbedding(vector, ModelProvenance.Fake, notAFace = false),
            ImageEmbedding(vector.copyOf(), ModelProvenance.Fake, notAFace = true),
        )
    }

    @Test
    fun equalEmbeddingsStillHashAlike() {
        val a = ImageEmbedding(vector, ModelProvenance.Fake, notAFace = true)
        val b = ImageEmbedding(vector.copyOf(), ModelProvenance.Fake, notAFace = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
