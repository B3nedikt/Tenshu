package app.tenshu.control.inference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.vision.facesOf

/**
 * The macOS face pair's platform halves on the bundled probe face (`ios-face-grouping.md` §14.2,
 * §10.8): the decode, Vision, and what they hand the shared mapping, through the real FFM path.
 *
 * ⚠⚠ Here and not in `:control-shared-proxy`: that module's tests run on JDK 17, which has no FFM, so
 * [MacFaces.runsHere] is false there and every case would pass without running. This module tests on
 * the JDK 25 the app ships with, and [theFacePairRunsOnEveryMac] fails if that ever stops being true.
 */
class MacFacesRealTest {

    private val probe: ByteArray = File("../scripts/inference/face-probe-photo.jpg").readBytes()

    @Test
    fun theProbeBecomesOneAlignableFaceThroughTheRealPath() {
        if (!MacFaces.runsHere) return
        val image = assertNotNull(MacFaces.Decoder.decodeUpright(probe, null))
        assertEquals(254 to 256, image.width to image.height)
        val detection = MacFaces.Detector.detect(image)
        assertEquals(FaceModel.VISION_LANDMARKS_REVISION, detection.revisionRun)
        val face = facesOf(detection.faces, image.width, image.height).single()
        // The five points in top-left pixels: eyes above the nose above the mouth, all inside.
        val l = face.landmarks
        for (p in listOf(l.leftEye, l.rightEye, l.noseTip, l.mouthLeft, l.mouthRight)) {
            assertTrue(p.x in 0f..254f && p.y in 0f..256f, "$p")
        }
        assertTrue(l.leftEye.x < l.rightEye.x)
        assertTrue(maxOf(l.leftEye.y, l.rightEye.y) < l.noseTip.y)
        assertTrue(l.noseTip.y < minOf(l.mouthLeft.y, l.mouthRight.y))
    }

    @Test
    fun aNamedOrientationIsAppliedOverTheStoredPixels() {
        if (!MacFaces.runsHere) return
        // 6 = rotate 90° clockwise: width and height swap.
        val rotated = assertNotNull(MacFaces.Decoder.decodeUpright(probe, 6))
        assertEquals(256 to 254, rotated.width to rotated.height)
        assertNull(MacFaces.Decoder.decodeUpright(probe, 9))
    }

    @Test
    fun theFacePairRunsOnEveryMac() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) return
        assertTrue(MacFaces.runsHere, "macOS with FFM must run the face pair, or every case here is vacuous")
    }

    @Test
    fun theRunningMacReportsABuild() {
        if (!MacFaces.runsHere) return
        assertTrue(MacFaces.osBuild != "unknown" && MacFaces.osBuild.isNotBlank(), MacFaces.osBuild)
    }
}
