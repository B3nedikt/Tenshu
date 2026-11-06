package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.catalog.KotlinNativeTypeTestShapes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * See `IsOnADataObjectAfterAWhenTest`. Kotlin/Native 2.4.10 answered *true* for every value in
 * this shape; 2.4.20 answers right, as the JVM always did. Asserted on both targets so a
 * compiler that regresses goes red here rather than in the model download journal.
 */
class TheOriginalTypeTestShapeTest {
    @Test
    fun theOriginalShapeIsRight() =
        assertEquals(KotlinNativeTypeTestShapes.expected, KotlinNativeTypeTestShapes.originalShape())
}
