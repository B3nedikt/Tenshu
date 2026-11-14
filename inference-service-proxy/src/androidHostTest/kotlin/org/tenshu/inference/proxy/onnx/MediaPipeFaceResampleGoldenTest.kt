package org.tenshu.inference.proxy.onnx

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tenshu.inference.proxy.FaceCrop
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.FacePoint
import org.tenshu.inference.proxy.alignFace

/**
 * ⭐ The golden of Android's face resample, bit for bit (`ios-face-grouping.md` §4, §13).
 *
 * ⚠⚠ **The expected values were recorded from `MediaPipeFaceBackend`'s own resample BEFORE it moved
 * to `commonMain`**, and this file is not to be edited to match a later implementation. Android's
 * clustering calibration (`FaceClustering.Measured`) was measured on exactly this arithmetic —
 * pixel centres, `FaceCrop.unmap`, `toInt()` truncation, a border clamp — so a resample that moves
 * one pixel moves every cosine without any test of the maths noticing. `FaceResampleTest`
 * (commonTest) holds the shared function to the same numbers; this one holds the Android call path.
 *
 * Every source pixel encodes its own coordinates (`0xFF` alpha, x in bits 12–23, y in bits 0–11),
 * so an output pixel says exactly which source pixel was read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaPipeFaceResampleGoldenTest {

    private val backend = MediaPipeFaceBackend(
        context = ApplicationProvider.getApplicationContext(),
        locateDetector = { null },
        locateEmbedder = { null },
    )

    private fun coordinateImage(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, encode(x, y))
        return bitmap
    }

    private fun encode(x: Int, y: Int): Int = (0xFF shl 24) or (x shl 12) or y

    /** FNV-1a 64 over the ints, little-endian bytes — a digest, so a 112² golden fits in a line. */
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

    @Test
    fun aRolledFaceInALandscapeFrame() {
        val image = coordinateImage(640, 480)
        val crop = alignFace(LANDSCAPE_FACE, FaceModel.ArcFaceTemplate, 640, 480)
        val out = backend.resample(image, 112, crop)
        assertEquals(112 * 112, out.size)
        assertEquals(LANDSCAPE_DIGEST, digest(out))
        // Spot pixels, readable: the corners and the centre of the aligned square.
        assertEquals(LANDSCAPE_SPOTS.toList(), SPOTS.map { (x, y) -> out[y * 112 + x] })
    }

    @Test
    fun aPortraitFrameIsNotStretched() {
        val image = coordinateImage(300, 500)
        val crop = alignFace(PORTRAIT_FACE, FaceModel.ArcFaceTemplate, 300, 500)
        val out = backend.resample(image, 112, crop)
        assertEquals(PORTRAIT_DIGEST, digest(out))
        assertEquals(PORTRAIT_SPOTS.toList(), SPOTS.map { (x, y) -> out[y * 112 + x] })
    }

    /** A face at the corner: the crop reads past two borders and the clamp decides those pixels. */
    @Test
    fun aCropPastTheBorderClampsToTheEdgePixels() {
        val image = coordinateImage(200, 150)
        val crop = alignFace(CORNER_FACE, FaceModel.ArcFaceTemplate, 200, 150)
        val out = backend.resample(image, 112, crop)
        assertEquals(CORNER_DIGEST, digest(out))
        assertEquals(CORNER_SPOTS.toList(), SPOTS.map { (x, y) -> out[y * 112 + x] })
    }

    /** Small enough to spell out: a 30° roll at scale 1.5 into an 8×8 square, on a 20×13 image. */
    @Test
    fun aSmallRotatedCropInFull() {
        val image = coordinateImage(20, 13)
        val crop = FaceCrop(scale = 1.5f, cos = 0.8660254f, sin = 0.5f, translateX = -2.25f, translateY = -6.5f, side = 8)
        val out = backend.resample(image, 8, crop)
        assertArrayEquals(SMALL_FULL, out)
    }

    /** `alignFace`'s degenerate answer: every destination pixel reads the source origin. */
    @Test
    fun aDegenerateCropReadsTheOrigin() {
        val image = coordinateImage(16, 16)
        val crop = FaceCrop(scale = 0f, cos = 1f, sin = 0f, translateX = 56f, translateY = 56f, side = 112)
        val out = backend.resample(image, 4, crop)
        assertArrayEquals(IntArray(16) { encode(0, 0) }, out)
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

        // ---- recorded from MediaPipeFaceBackend.resample before the move — do not edit -----------
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
