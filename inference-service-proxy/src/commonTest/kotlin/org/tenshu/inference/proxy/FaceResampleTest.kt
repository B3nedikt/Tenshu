package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.onnx.FaceModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ⭐ The shared [resampleFace] against the numbers Android's own resample produced BEFORE it moved
 * here (`MediaPipeFaceResampleGoldenTest`, recorded on the Android call path; `ios-face-grouping.md`
 * §13). ⚠⚠ Do not re-record these from this function: that would test the function against itself.
 *
 * Runs on every lane — JVM, Android host, JS and iOS — so a platform whose float arithmetic or
 * `toInt()` differed would fail here rather than silently crop differently.
 */
class FaceResampleTest {

    private fun encode(x: Int, y: Int): Int = (0xFF shl 24) or (x shl 12) or y

    private fun coordinateImage(width: Int, height: Int): IntArray =
        IntArray(width * height) { encode(it % width, it / width) }

    /** FNV-1a 64 over the ints, little-endian bytes — the golden test's digest, byte for byte. */
    private fun digest(pixels: IntArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for (p in pixels) {
            for (shift in 0 until 32 step 8) {
                hash = hash xor ((p ushr shift) and 0xFF).toLong()
                hash *= 0x100000001b3L
            }
        }
        return hash
    }

    private fun aligned(width: Int, height: Int, face: FaceLandmarks): IntArray =
        resampleFace(coordinateImage(width, height), width, height, 112, alignFace(face, FaceModel.ArcFaceTemplate, width, height))

    @Test
    fun aRolledFaceInALandscapeFrame() {
        val out = aligned(640, 480, LANDSCAPE_FACE)
        assertEquals(LANDSCAPE_SPOTS.toList(), SPOTS.map { (x, y) -> out[y * 112 + x] })
        assertEquals(LANDSCAPE_DIGEST, digest(out))
    }

    @Test
    fun aPortraitFrameIsNotStretched() {
        val out = aligned(300, 500, PORTRAIT_FACE)
        assertEquals(PORTRAIT_SPOTS.toList(), SPOTS.map { (x, y) -> out[y * 112 + x] })
        assertEquals(PORTRAIT_DIGEST, digest(out))
    }

    @Test
    fun aCropPastTheBorderClampsToTheEdgePixels() {
        val out = aligned(200, 150, CORNER_FACE)
        assertEquals(CORNER_SPOTS.toList(), SPOTS.map { (x, y) -> out[y * 112 + x] })
        assertEquals(CORNER_DIGEST, digest(out))
    }

    @Test
    fun aSmallRotatedCropInFull() {
        val crop = FaceCrop(scale = 1.5f, cos = 0.8660254f, sin = 0.5f, translateX = -2.25f, translateY = -6.5f, side = 8)
        assertContentEquals(SMALL_FULL, resampleFace(coordinateImage(20, 13), 20, 13, 8, crop))
    }

    @Test
    fun aDegenerateCropReadsTheOrigin() {
        val crop = FaceCrop(scale = 0f, cos = 1f, sin = 0f, translateX = 56f, translateY = 56f, side = 112)
        assertContentEquals(IntArray(16) { encode(0, 0) }, resampleFace(coordinateImage(16, 16), 16, 16, 4, crop))
    }

    /** ⚠ A buffer that is not the stated image would read the wrong rows, not fail. */
    @Test
    fun aBufferThatIsNotTheStatedImageIsRefused() {
        val crop = FaceCrop(scale = 1f, cos = 1f, sin = 0f, translateX = 0f, translateY = 0f, side = 4)
        assertFailsWith<IllegalArgumentException> { resampleFace(IntArray(10), 4, 4, 4, crop) }
    }

    private companion object {
        val SPOTS = listOf(0 to 0, 111 to 0, 0 to 111, 111 to 111, 56 to 56, 38 to 51, 70 to 92)

        val LANDSCAPE_FACE = FaceLandmarks(
            leftEye = FacePoint(0.4120f, 0.3870f),
            rightEye = FacePoint(0.5310f, 0.4190f),
            noseTip = FacePoint(0.4690f, 0.4980f),
            mouthLeft = FacePoint(0.4110f, 0.5720f),
            mouthRight = FacePoint(0.5020f, 0.5960f),
        )
        val PORTRAIT_FACE = FaceLandmarks(
            leftEye = FacePoint(0.3530f, 0.4110f),
            rightEye = FacePoint(0.6420f, 0.3980f),
            noseTip = FacePoint(0.5110f, 0.4870f),
            mouthLeft = FacePoint(0.3910f, 0.5520f),
            mouthRight = FacePoint(0.6170f, 0.5430f),
        )
        val CORNER_FACE = FaceLandmarks(
            leftEye = FacePoint(0.0400f, 0.0700f),
            rightEye = FacePoint(0.2300f, 0.0500f),
            noseTip = FacePoint(0.1350f, 0.2000f),
            mouthLeft = FacePoint(0.0600f, 0.3100f),
            mouthRight = FacePoint(0.2100f, 0.3000f),
        )

        // ---- copied from MediaPipeFaceResampleGoldenTest, recorded on Android before the move ----
        const val LANDSCAPE_DIGEST = -1219745928050650034L
        val LANDSCAPE_SPOTS = intArrayOf(
            0xFF0CA043.toInt(), 0xFF1B6067.toInt(), 0xFF0A612F.toInt(), 0xFF192153.toInt(),
            0xFF12F0CC.toInt(), 0xFF10A0BC.toInt(), 0xFF14111D.toInt(),
        )
        const val PORTRAIT_DIGEST = 6913615674969161670L
        val PORTRAIT_SPOTS = intArrayOf(
            0xFF01D060.toInt(), 0xFF103056.toInt(), 0xFF027145.toInt(), 0xFF10C13C.toInt(),
            0xFF0960CF.toInt(), 0xFF0700C6.toInt(), 0xFF0B6118.toInt(),
        )
        const val CORNER_DIGEST = 4871248919460903765L
        val CORNER_SPOTS = intArrayOf(
            0xFF000000.toInt(), 0xFF04F000.toInt(), 0xFF000043.toInt(), 0xFF051041.toInt(),
            0xFF01B00C.toInt(), 0xFF009008.toInt(), 0xFF02902F.toInt(),
        )
        val SMALL_FULL = intArrayOf(
            0xFF003003.toInt(), 0xFF004002.toInt(), 0xFF005002.toInt(), 0xFF005002.toInt(), 0xFF006001.toInt(), 0xFF006001.toInt(), 0xFF007001.toInt(), 0xFF007000.toInt(),
            0xFF004003.toInt(), 0xFF004003.toInt(), 0xFF005003.toInt(), 0xFF005002.toInt(), 0xFF006002.toInt(), 0xFF007002.toInt(), 0xFF007001.toInt(), 0xFF008001.toInt(),
            0xFF004004.toInt(), 0xFF005003.toInt(), 0xFF005003.toInt(), 0xFF006003.toInt(), 0xFF006002.toInt(), 0xFF007002.toInt(), 0xFF008002.toInt(), 0xFF008001.toInt(),
            0xFF004004.toInt(), 0xFF005004.toInt(), 0xFF006004.toInt(), 0xFF006003.toInt(), 0xFF007003.toInt(), 0xFF007003.toInt(), 0xFF008002.toInt(), 0xFF008002.toInt(),
            0xFF005005.toInt(), 0xFF005005.toInt(), 0xFF006004.toInt(), 0xFF006004.toInt(), 0xFF007004.toInt(), 0xFF008003.toInt(), 0xFF008003.toInt(), 0xFF009003.toInt(),
            0xFF005006.toInt(), 0xFF006005.toInt(), 0xFF006005.toInt(), 0xFF007005.toInt(), 0xFF007004.toInt(), 0xFF008004.toInt(), 0xFF009004.toInt(), 0xFF009003.toInt(),
            0xFF005006.toInt(), 0xFF006006.toInt(), 0xFF007005.toInt(), 0xFF007005.toInt(), 0xFF008005.toInt(), 0xFF008004.toInt(), 0xFF009004.toInt(), 0xFF009004.toInt(),
            0xFF006007.toInt(), 0xFF006006.toInt(), 0xFF007006.toInt(), 0xFF007006.toInt(), 0xFF008005.toInt(), 0xFF009005.toInt(), 0xFF009005.toInt(), 0xFF00A004.toInt(),
        )
    }
}
