package org.tenshu.inference.proxy

import kotlin.math.sqrt

/**
 * Apple Vision's face landmarks reduced to the five points `alignFace` wants — the iOS twin of
 * [FaceMesh], pure so the JVM gate tests it (`ios-face-grouping.md` §3.5, §10.3; IN-6 F3).
 *
 * ⭐ **The mapping is F1a's *corners-mid*, measured against FaceMesh's own definitions** (§10.3):
 * - **eye** = the midpoint of the `leftEye` / `rightEye` contour's two extreme points along the
 *   pupil axis — FaceMesh's corner midpoint (33/133, 362/263). ⚠ Not the pupils: they sit ~0.05
 *   inter-ocular distances (IOD) higher, a definitional offset that moves every crop.
 * - **nose** = midway between `noseCrest`'s lowest point and `nose`'s lowest point (the subnasale),
 *   "lowest" meaning furthest towards the chin across the eye axis. ⚠ The crest bottom alone sits
 *   0.07 IOD above FaceMesh's nose tip; the region's centroid is worse.
 * - **mouth** = `outerLips`' two extreme points along the eye axis.
 * - ⚠ **No fitted correction.** F1a chose this on the public set and it held on the library with
 *   ~0 nose bias; a fixed offset fitted there did not transfer (§10.3). Adding one is a
 *   re-measurement, not a tweak.
 *
 * ⚠⚠ **Vision's origin is the BOTTOM-left; every other face point in this codebase is top-left.**
 * The regions come in as Vision gives them — `VNFaceLandmarkRegion2D.pointsInImage(imageSize:)`,
 * pixels, y up — and the flip happens here, once, so no caller can forget it or do it twice. A
 * face mapped without it lands upside down, which `alignFace` would then happily "correct" by
 * rotating 180°: a plausible crop of the wrong orientation, never an error.
 *
 * ⚠ **Image-left first, with no per-face sorting**: `leftPupil` and `leftEye` are the eye on the
 * image's left of an upright face (§10.3), the order [FaceMesh] names its points in. The axes are
 * taken from the face's own points, so a rolled or upside-down face maps the same way.
 *
 * ⚠ Vision types never reach common code: the iOS backend (F4) hands over plain numbers.
 */
object VisionFaceLandmarks {

    /**
     * The seven Vision regions the mapping reads, each as `pointsInImage` returned them: flattened
     * `x0, y0, x1, y1, …` in image pixels, origin bottom-left. `leftPupil` and `rightPupil` are one
     * point each.
     */
    class Regions(
        val leftEye: FloatArray,
        val rightEye: FloatArray,
        val leftPupil: FloatArray,
        val rightPupil: FloatArray,
        val nose: FloatArray,
        val noseCrest: FloatArray,
        val outerLips: FloatArray,
    )

    /**
     * The five points in top-left image FRACTIONS — [FaceLandmarks]' contract, which `alignFace`
     * converts back to pixels with the same [imageWidth] and [imageHeight].
     *
     * @param imageWidth the size Vision was given as `imageSize`: the upright image the detector
     *   ran on.
     * @return `null` when the regions cannot carry the mapping — a region missing or odd-length, a
     *   non-finite point, or eyes that coincide. ⚠ A guess here would be a face aligned on nothing.
     */
    fun landmarks(regions: Regions, imageWidth: Int, imageHeight: Int): FaceLandmarks? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val h = imageHeight.toDouble()
        val leftEye = topLeft(regions.leftEye, h) ?: return null
        val rightEye = topLeft(regions.rightEye, h) ?: return null
        val leftPupil = topLeft(regions.leftPupil, h)?.first() ?: return null
        val rightPupil = topLeft(regions.rightPupil, h)?.first() ?: return null
        val nose = topLeft(regions.nose, h) ?: return null
        val noseCrest = topLeft(regions.noseCrest, h) ?: return null
        val outerLips = topLeft(regions.outerLips, h) ?: return null

        // The pupils only give the axis the eye contours' corners are found along.
        val pupilAxis = (rightPupil - leftPupil).unit() ?: return null
        val leftEyeCentre = leftEye.extremesAlong(pupilAxis).let { (a, b) -> (a + b) / 2.0 }
        val rightEyeCentre = rightEye.extremesAlong(pupilAxis).let { (a, b) -> (a + b) / 2.0 }

        val eyeAxis = (rightEyeCentre - leftEyeCentre).unit() ?: return null
        // 90° clockwise in a y-DOWN frame: from the eyes towards the chin.
        val towardsChin = Vec(-eyeAxis.y, eyeAxis.x)
        val (mouthLeft, mouthRight) = outerLips.extremesAlong(eyeAxis)
        val crestBottom = noseCrest.extremesAlong(towardsChin).second
        val subnasale = nose.extremesAlong(towardsChin).second
        val noseTip = (crestBottom + subnasale) / 2.0

        val w = imageWidth.toDouble()
        fun Vec.fraction() = FacePoint((x / w).toFloat(), (y / h).toFloat())
        return FaceLandmarks(
            leftEye = leftEyeCentre.fraction(),
            rightEye = rightEyeCentre.fraction(),
            noseTip = noseTip.fraction(),
            mouthLeft = mouthLeft.fraction(),
            mouthRight = mouthRight.fraction(),
        )
    }

    /** Vision's bottom-left pixels → top-left pixels. `null` for a malformed or non-finite region. */
    private fun topLeft(flat: FloatArray, height: Double): List<Vec>? {
        if (flat.isEmpty() || flat.size % 2 != 0) return null
        if (flat.any { !it.isFinite() }) return null
        return List(flat.size / 2) { Vec(flat[2 * it].toDouble(), height - flat[2 * it + 1].toDouble()) }
    }

    /**
     * The points with the smallest and the largest projection on [axis]. ⚠ Ties go to the FIRST
     * point, as numpy's `argmin` / `argmax` do in F1a's reference.
     */
    private fun List<Vec>.extremesAlong(axis: Vec): Pair<Vec, Vec> {
        var low = first()
        var high = first()
        var lowAt = low.dot(axis)
        var highAt = lowAt
        for (i in 1 until size) {
            val at = this[i].dot(axis)
            if (at < lowAt) { low = this[i]; lowAt = at }
            if (at > highAt) { high = this[i]; highAt = at }
        }
        return low to high
    }

    private class Vec(val x: Double, val y: Double) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
        operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
        operator fun div(d: Double) = Vec(x / d, y / d)
        fun dot(o: Vec) = x * o.x + y * o.y
        fun unit(): Vec? {
            val n = sqrt(x * x + y * y)
            return if (n == 0.0 || !n.isFinite()) null else Vec(x / n, y / n)
        }
    }
}
