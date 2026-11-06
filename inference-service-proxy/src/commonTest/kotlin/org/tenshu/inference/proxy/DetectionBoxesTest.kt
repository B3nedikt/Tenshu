package org.tenshu.inference.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** One box per face across the whole-image pass and the tiles (IN-6 recall, §12.14). */
class DetectionBoxesTest {

    /**
     * ⚠⚠ The seam case from the maintainer's library: a large face across the tile seam, found whole
     * on the full image and as a fragment by a tile — and the fragment scored HIGHER. Keeping the best
     * score handed the landmarker half a face and lost it.
     */
    @Test
    fun aHigherScoringFragmentOfAFaceNeverReplacesTheWholeFace() {
        val whole = DetectionBox(380f, 220f, 620f, 560f, score = 0.71f)
        val fragmentFromATile = DetectionBox(500f, 230f, 620f, 550f, score = 0.93f)

        val kept = selectFaceBoxes(listOf(fragmentFromATile, whole), sameFaceOverlap = 0.5f)

        assertSame(whole, kept.single())
    }

    @Test
    fun twoSeparateFacesAreBothKept() {
        val left = DetectionBox(100f, 100f, 200f, 200f, score = 0.9f)
        val right = DetectionBox(400f, 100f, 500f, 200f, score = 0.6f)

        assertEquals(2, selectFaceBoxes(listOf(left, right), sameFaceOverlap = 0.5f).size)
    }

    /** A small distant face beside a large one is a second face, not a fragment of the first. */
    @Test
    fun aSmallFaceBesideALargeOneIsNotSwallowed() {
        val large = DetectionBox(0f, 0f, 400f, 400f, score = 0.8f)
        val distant = DetectionBox(380f, 380f, 440f, 440f, score = 0.6f)

        assertEquals(2, selectFaceBoxes(listOf(large, distant), sameFaceOverlap = 0.5f).size)
    }
}
