package org.tenshu.inference.proxy

/**
 * A detector box in pixels of the decoded image, with its detector score.
 *
 * ⚠ Pixels, not [FaceBox]'s fractions: tiles are cut and offset in pixels, and the overlap tests
 * compare boxes from different tiles of one image.
 */
class DetectionBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/** Intersection over the SMALLER area — a whole face and a part of it overlap fully by this measure. */
fun overlapOverSmaller(a: DetectionBox, b: DetectionBox): Float {
    val iw = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
    val ih = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    val smaller = minOf(a.area, b.area)
    return if (smaller <= 0f) 0f else iw * ih / smaller
}

/**
 * One box per face, from the whole-image pass and every tile together (IN-6 recall, §12.14).
 *
 * ⚠⚠ **The LARGEST box of an overlapping set wins, never the highest-scoring one** (2026-09-30).
 * A tile holds only part of a face that crosses its seam, and the detector can still fire on that
 * part with a higher score than the whole face scored on the full image; keeping the best score would
 * hand the landmarker a crop of half a face. Overlap is measured over the smaller box, so a fragment
 * inside a face is always recognised as the same face. ⚠ Measured on the maintainer's library this
 * recovered none of the faces the tiled pipeline loses (§12.14) — it removes a failure mode, it is not
 * the explanation for those.
 */
fun selectFaceBoxes(boxes: List<DetectionBox>, sameFaceOverlap: Float): List<DetectionBox> {
    val kept = mutableListOf<DetectionBox>()
    boxes.sortedByDescending { it.area }.forEach { box ->
        if (kept.none { overlapOverSmaller(it, box) >= sameFaceOverlap }) kept += box
    }
    return kept
}
