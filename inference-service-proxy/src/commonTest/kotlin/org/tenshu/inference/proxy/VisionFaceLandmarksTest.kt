package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.VisionFaceLandmarksFixtures.Fixture
import org.tenshu.inference.proxy.onnx.FaceModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VisionFaceLandmarks] — the maths of the mapping, never Vision's own output (`ios-face-grouping.md`
 * §11.3: iOS Vision's points are not macOS Vision's, so a recorded Vision answer is no golden).
 */
class VisionFaceLandmarksTest {

    private fun Fixture.regions() = VisionFaceLandmarks.Regions(
        leftEye = leftEye, rightEye = rightEye, leftPupil = leftPupil, rightPupil = rightPupil,
        nose = nose, noseCrest = noseCrest, outerLips = outerLips,
    )

    private fun FaceLandmarks.points() = listOf(leftEye, rightEye, noseTip, mouthLeft, mouthRight)

    private fun map(fixture: Fixture): FaceLandmarks =
        assertNotNull(VisionFaceLandmarks.landmarks(fixture.regions(), fixture.width, fixture.height), fixture.name)

    // ---- the reference -------------------------------------------------------------------------

    /**
     * ⭐ Every fixture face maps to what F1a's own `vision_five(nose="mid", eyes="corners")` gave on
     * the same points — the mapping §10.3 measured, re-implemented here.
     */
    @Test
    fun theFixturesMapToF1asCornersMidReference() {
        for (fixture in VisionFaceLandmarksFixtures.faces) {
            map(fixture).points().zip(fixture.expected).forEachIndexed { i, (got, want) ->
                // ⚠ Fractions; 5e-5 is 0.02 px on a 336 px tile. The inputs are float32-rounded.
                assertTrue(
                    abs(got.x - want.x) < 5e-5f && abs(got.y - want.y) < 5e-5f,
                    "${fixture.name} point $i: $got, F1a $want",
                )
            }
        }
    }

    /**
     * The same faces against the MediaPipe five F1a matched them to: the right regions became the
     * right points. A swapped eye, a missing flip or a mouth read off the wrong region is ≥ 0.5 IOD
     * out; §10.3's public-set p90s are 0.03 (eyes, mouth) and 0.12 (nose).
     */
    @Test
    fun theFixturesLandOnMediaPipesPoints() {
        for (fixture in VisionFaceLandmarksFixtures.faces) {
            val w = fixture.width.toFloat()
            val h = fixture.height.toFloat()
            val mp = fixture.mediaPipe.map { FacePoint(it.x * w, it.y * h) }
            val iod = hypot(mp[1].x - mp[0].x, mp[1].y - mp[0].y)
            map(fixture).points().map { FacePoint(it.x * w, it.y * h) }.zip(mp).forEachIndexed { i, (got, want) ->
                val error = hypot(got.x - want.x, got.y - want.y) / iod
                val bound = if (i == 2) 0.2f else 0.08f
                assertTrue(error < bound, "${fixture.name} point $i is $error IOD from MediaPipe's")
            }
        }
    }

    // ---- the y-flip ----------------------------------------------------------------------------

    /**
     * A hand-built upright face on a NON-square 200×100 image, in Vision's bottom-left pixels: the
     * eyes at Vision y = 70 are near the TOP, so they must come out at top-left y = 30. Every
     * answer is worked by hand.
     */
    @Test
    fun visionsBottomLeftOriginIsFlippedToTopLeft() {
        val face = assertNotNull(VisionFaceLandmarks.landmarks(UPRIGHT, 200, 100))
        assertNear(FacePoint(70f / 200, 30f / 100), face.leftEye, "left eye: corners (60,70)–(80,70)")
        assertNear(FacePoint(130f / 200, 30f / 100), face.rightEye, "right eye: corners (120,70)–(140,70)")
        // Crest bottom (100, 50) → top-left y 50; subnasale (100, 44) → 56; midway 53.
        assertNear(FacePoint(100f / 200, 53f / 100), face.noseTip, "nose")
        assertNear(FacePoint(80f / 200, 70f / 100), face.mouthLeft, "mouth left")
        assertNear(FacePoint(120f / 200, 70f / 100), face.mouthRight, "mouth right")
        assertTrue(face.leftEye.y < face.noseTip.y && face.noseTip.y < face.mouthLeft.y, "eyes above nose above mouth")
    }

    /**
     * ⚠ Which crest point is "lowest" depends on the flip: in Vision's y-up frame the crest's
     * smallest y is the chin end. A mapping that skipped the flip would pick the crest's TOP (Vision
     * y = 66) for the nose and put the eyes under the mouth.
     */
    @Test
    fun theNoseUsesTheChinEndsOfCrestAndNoseInVisionsFrame() {
        val face = assertNotNull(VisionFaceLandmarks.landmarks(UPRIGHT, 200, 100))
        // From the crest's top (100, 66 → 34) the nose would be at (34 + 56) / 2 = 45, not 53.
        assertTrue(abs(face.noseTip.y * 100 - 53f) < 1e-3f, "nose y ${face.noseTip.y * 100}")
    }

    // ---- rotated faces -------------------------------------------------------------------------

    /**
     * ⭐ Rolled and upside-down faces: every region rotated in Vision's frame maps to the upright
     * answer rotated the same way (the axes come from the face, not the image), and `alignFace`
     * then lands the five points on the same template positions — the crop does not care how the
     * face was turned.
     */
    @Test
    fun aRotatedFaceMapsToTheRotatedPointsAndAlignsTheSame() {
        for (fixture in VisionFaceLandmarksFixtures.faces) {
            val upright = map(fixture)
            val w = fixture.width.toFloat()
            val h = fixture.height.toFloat()
            val uprightAligned = aligned(upright, fixture.width, fixture.height)
            for (degrees in listOf(23.0, 90.0, 180.0, -120.0, 271.0)) {
                val turn = Rotation(degrees, cx = w / 2, cy = h / 2)
                val rotated = assertNotNull(
                    VisionFaceLandmarks.landmarks(turn.regions(fixture.regions()), fixture.width, fixture.height),
                    "${fixture.name} at $degrees°",
                )
                rotated.points().zip(upright.points()).forEachIndexed { i, (got, base) ->
                    // The upright answer, unflipped into Vision's frame, turned, flipped back.
                    val vision = turn.apply(FacePoint(base.x * w, h - base.y * h))
                    val want = FacePoint(vision.x / w, (h - vision.y) / h)
                    assertNear(want, got, "${fixture.name} at $degrees°, point $i", tolerance = 2e-5f)
                }
                aligned(rotated, fixture.width, fixture.height).zip(uprightAligned).forEachIndexed { i, (got, want) ->
                    assertTrue(
                        hypot(got.x - want.x, got.y - want.y) < 0.01f,
                        "${fixture.name} at $degrees°: template point $i at $got, upright $want",
                    )
                }
            }
        }
    }

    private fun aligned(face: FaceLandmarks, width: Int, height: Int): List<FacePoint> {
        val crop = alignFace(face, FaceModel.ArcFaceTemplate, width, height)
        return face.points().map { crop.map(FacePoint(it.x * width, it.y * height)) }
    }

    /** A rotation by [degrees] counter-clockwise in Vision's y-up frame, about (cx, cy). */
    private class Rotation(degrees: Double, private val cx: Float, private val cy: Float) {
        private val c = cos(degrees * PI / 180).toFloat()
        private val s = sin(degrees * PI / 180).toFloat()

        fun apply(p: FacePoint): FacePoint {
            val dx = p.x - cx
            val dy = p.y - cy
            return FacePoint(cx + c * dx - s * dy, cy + s * dx + c * dy)
        }

        fun points(flat: FloatArray): FloatArray {
            val out = FloatArray(flat.size)
            for (i in 0 until flat.size / 2) {
                val p = apply(FacePoint(flat[2 * i], flat[2 * i + 1]))
                out[2 * i] = p.x
                out[2 * i + 1] = p.y
            }
            return out
        }

        fun regions(r: VisionFaceLandmarks.Regions) = VisionFaceLandmarks.Regions(
            leftEye = points(r.leftEye), rightEye = points(r.rightEye),
            leftPupil = points(r.leftPupil), rightPupil = points(r.rightPupil),
            nose = points(r.nose), noseCrest = points(r.noseCrest), outerLips = points(r.outerLips),
        )
    }

    // ---- refusals ------------------------------------------------------------------------------

    @Test
    fun regionsThatCannotCarryTheMappingAreRefused() {
        fun with(
            leftEye: FloatArray = UPRIGHT.leftEye,
            leftPupil: FloatArray = UPRIGHT.leftPupil,
            rightPupil: FloatArray = UPRIGHT.rightPupil,
            outerLips: FloatArray = UPRIGHT.outerLips,
        ) = VisionFaceLandmarks.Regions(
            leftEye = leftEye, rightEye = UPRIGHT.rightEye, leftPupil = leftPupil, rightPupil = rightPupil,
            nose = UPRIGHT.nose, noseCrest = UPRIGHT.noseCrest, outerLips = outerLips,
        )
        assertNull(VisionFaceLandmarks.landmarks(with(outerLips = floatArrayOf()), 200, 100), "a missing region")
        assertNull(VisionFaceLandmarks.landmarks(with(leftEye = floatArrayOf(60f, 70f, 80f)), 200, 100), "an odd length")
        assertNull(VisionFaceLandmarks.landmarks(with(leftPupil = floatArrayOf(Float.NaN, 70f)), 200, 100), "a NaN point")
        assertNull(
            VisionFaceLandmarks.landmarks(with(leftPupil = floatArrayOf(130f, 70f), rightPupil = floatArrayOf(130f, 70f)), 200, 100),
            "coincident pupils give no axis",
        )
        assertNull(VisionFaceLandmarks.landmarks(UPRIGHT, 0, 100), "an image with no size")
    }

    private fun assertNear(want: FacePoint, got: FacePoint, message: String, tolerance: Float = 1e-6f) {
        assertTrue(abs(want.x - got.x) <= tolerance && abs(want.y - got.y) <= tolerance, "$message: want $want, got $got")
    }

    private companion object {
        /**
         * An upright face in Vision's bottom-left pixels on a 200×100 image. The eye contours are
         * diamonds whose corners lie on the pupil axis; the crest runs down the nose from y 66 to 50,
         * the nose region's lowest point is y 44, the lips span x 80–120 at y 30.
         */
        val UPRIGHT = VisionFaceLandmarks.Regions(
            leftEye = floatArrayOf(60f, 70f, 70f, 73f, 80f, 70f, 70f, 67f),
            rightEye = floatArrayOf(140f, 70f, 130f, 73f, 120f, 70f, 130f, 67f),
            leftPupil = floatArrayOf(70f, 71f),
            rightPupil = floatArrayOf(130f, 71f),
            nose = floatArrayOf(92f, 52f, 96f, 46f, 100f, 44f, 104f, 46f, 108f, 52f),
            noseCrest = floatArrayOf(100f, 66f, 100f, 58f, 100f, 50f),
            outerLips = floatArrayOf(80f, 30f, 90f, 34f, 100f, 35f, 110f, 34f, 120f, 30f, 110f, 26f, 100f, 25f, 90f, 26f),
        )
    }
}
