package org.tenshu.inference.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FaceMesh] — the harness's mesh reduction, now shipped (IN-6 F2).
 *
 * ⚠⚠ The indices ARE the contract: they are what §12's measurement ran on, and a swapped pair
 * mirrors every crop without any arithmetic failing. So these pin which mesh points each landmark
 * reads, using a mesh where every point's coordinates encode its own index.
 */
class FaceMeshTest {

    /** Point `i` sits at (i / 1000, i / 2000) — so any landmark says which index produced it. */
    private val mesh = List(FaceMesh.POINTS) { FacePoint(it / 1000f, it / 2000f) }

    private fun at(i: Int) = FacePoint(i / 1000f, i / 2000f)
    private fun mid(a: Int, b: Int) = FacePoint((at(a).x + at(b).x) / 2f, (at(a).y + at(b).y) / 2f)

    @Test
    fun eyesAreCornerMidpointsNamedByImagePosition() {
        val landmarks = FaceMesh.landmarks(mesh)!!

        assertEquals(mid(33, 133), landmarks.leftEye, "the image-LEFT eye is corners 33/133")
        assertEquals(mid(362, 263), landmarks.rightEye, "the image-RIGHT eye is corners 362/263")
    }

    @Test
    fun noseAndMouthReadTheMeasuredIndices() {
        val landmarks = FaceMesh.landmarks(mesh)!!

        assertEquals(at(1), landmarks.noseTip)
        assertEquals(at(61), landmarks.mouthLeft)
        assertEquals(at(291), landmarks.mouthRight)
    }

    @Test
    fun theImageLeftEyeIsLeftOfTheImageRightEyeOnAnUprightFace() {
        // ⚠ On MediaPipe's canonical mesh 33/133 lie at smaller x than 362/263 for an upright face —
        // the order `alignFace`'s template expects. This mesh preserves that ordering by index.
        val landmarks = FaceMesh.landmarks(mesh)!!
        assertTrue(landmarks.leftEye.x < landmarks.rightEye.x)
    }

    /** ⚠ A mesh from some other model is refused rather than guessed around. */
    @Test
    fun aShortMeshIsRefused() {
        assertNull(FaceMesh.landmarks(mesh.take(FaceMesh.POINTS - 1)))
    }

    @Test
    fun theIrisExtendedMeshIsAccepted() {
        // The Face Landmarker actually reports 478 points (468 + 10 iris); the first 468 are the mesh.
        val withIris = mesh + List(10) { FacePoint(0.5f, 0.5f) }
        assertEquals(FaceMesh.landmarks(mesh), FaceMesh.landmarks(withIris))
    }

    @Test
    fun theBoxIsTheMeshExtent() {
        val box = FaceMesh.box(listOf(FacePoint(0.2f, 0.4f), FacePoint(0.6f, 0.1f), FacePoint(0.3f, 0.9f)))

        assertEquals(0.2f, box.left)
        assertEquals(0.1f, box.top)
        assertEquals(0.6f, box.right)
        assertEquals(0.9f, box.bottom)
    }
}
