package app.tenshu.macos

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FFM route to ImageIO and Vision, end to end on the bundled probe face
 * (`scripts/inference/face-probe-photo.jpg`, CC0, `ios-face-grouping.md` §14.2).
 *
 * ⚠ The reference is the calibration harness's own recording of macOS Vision on this photograph
 * (`FaceProbePhotoMacVision` in `:inference-service-proxy`'s iosTest, made by `vision_detect.swift`
 * over a PNG of PIL's decode). This test pins that **this bridge asks Vision the same question the
 * harness did**, not Vision's answer: the tolerance is generous (0.02 inter-ocular distances), because
 * the decoders differ by ±1 in a channel and Vision may move between macOS builds (FD9).
 */
class MacFaceVisionTest {

    private val probe: ByteArray = File("../scripts/inference/face-probe-photo.jpg").readBytes()

    @Test
    fun decodesTheProbeAtItsOwnSizeUpright() {
        if (!MacFaceVision.isAvailable) return
        val pixels = assertNotNull(MacFaceVision.decodeUpright(probe, applyExif = true) { w, h -> maxOf(w, h) })
        assertEquals(254 to 256, pixels.width to pixels.height)
        assertEquals(254 * 256, pixels.argb.size)
        assertTrue(pixels.argb.all { it ushr 24 == 0xFF }, "every pixel opaque")
    }

    @Test
    fun boundsTheLongEdge() {
        if (!MacFaceVision.isAvailable) return
        val pixels = assertNotNull(MacFaceVision.decodeUpright(probe, applyExif = true) { _, _ -> 128 })
        assertEquals(128, maxOf(pixels.width, pixels.height))
    }

    @Test
    fun refusesBytesThatAreNotAnImage() {
        if (!MacFaceVision.isAvailable) return
        assertNull(MacFaceVision.decodeUpright("not an image".encodeToByteArray(), applyExif = true) { w, h -> maxOf(w, h) })
        assertNull(MacFaceVision.decodeUpright(ByteArray(0), applyExif = true) { w, h -> maxOf(w, h) })
    }

    @Test
    fun findsTheProbeFaceWhereTheHarnessDid() {
        if (!MacFaceVision.isAvailable) return
        val pixels = assertNotNull(MacFaceVision.decodeUpright(probe, applyExif = true) { w, h -> maxOf(w, h) })
        val detection = MacFaceVision.detectFaces(pixels.width, pixels.height, pixels.argb, revision = 3)
        assertEquals(3, detection.revisionRun)
        val face = detection.faces.single()
        // Recorded box, bottom-left origin: x 0.223884, y = 1 - 0.969672, w = 0.581074, h = 0.576534.
        assertEquals(0.223884, face.x, 0.01)
        assertEquals(1 - 0.969672, face.y, 0.01)
        assertEquals(0.581074, face.width, 0.01)
        val regions = assertNotNull(face.regions)
        assertEquals(MacFaceVision.REGIONS.toSet(), regions.keys)
        val iod = hypot(157.7673 - 96.1580, 115.8523 - 119.1054)
        fun near(name: String, x: Double, y: Double) {
            val p = assertNotNull(regions[name])
            val error = hypot(p[0] - x, p[1] - y) / iod
            assertTrue(error < 0.02, "$name (${p[0]}, ${p[1]}) is $error IOD from the recording ($x, $y)")
        }
        near("leftPupil", 96.1580, 119.1054)
        near("rightPupil", 157.7673, 115.8523)
        near("nose", 124.3922, 101.0724)
        near("outerLips", 110.0542, 50.7890)
    }

    @Test
    fun aPictureWithNoFaceIsAnEmptyDetectionNotAFailure() {
        if (!MacFaceVision.isAvailable) return
        val grey = IntArray(64 * 64) { (0xFF shl 24) or 0x808080 }
        val detection = MacFaceVision.detectFaces(64, 64, grey, revision = 3)
        assertEquals(0, detection.faces.size)
    }

    @Test
    fun refusesPixelsThatAreNotTheirSize() {
        if (!MacFaceVision.isAvailable) return
        assertFailsWith<IllegalArgumentException> { MacFaceVision.detectFaces(10, 10, IntArray(99), revision = 3) }
    }

    @Test
    fun theRecordedBoxIsConsistentWithItself() {
        // Guard on the constants above: the recording's box is 0.581074 wide and 0.576534 tall.
        assertTrue(abs((0.804958 - 0.223884) - 0.581074) < 1e-6)
        assertTrue(abs((0.969672 - 0.393138) - 0.576534) < 1e-6)
    }
}
