package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two new `InferenceBackend` members (IN-6 F1) — detection and face identity as **separate
 * calls**, because on Apple they are separate providers (IN-D13: Vision detects for free and exposes
 * no faceprint; the identity vector is always ours).
 */
class FaceDetectionSeamTest {

    private fun backend(
        capabilities: Set<InferenceCapability> = setOf(InferenceCapability.FACE_CLUSTERING),
    ) = FakeInferenceBackend(capabilities = capabilities)

    private fun photo(content: String) = content.encodeToByteArray()

    @Test
    fun aPhotographWithTwoFacesDetectsTwoBoxes() = runTest {
        val outcome = backend().detectFaces(photo("anna,bob"))

        val detected = assertIsDetected(outcome)
        assertEquals(2, detected.faces.size)
    }

    /**
     * ⚠⚠ **"No faces" is a fact and must be [FaceDetectionOutcome.Detected] with an empty list** —
     * not a failure. Most photographs in a real library land here, and a pass that could not record
     * it would re-read the whole library for ever.
     */
    @Test
    fun aPhotographWithNoFacesIsADetectionWithNoBoxes() = runTest {
        val outcome = backend().detectFaces(photo(""))

        assertEquals(emptyList(), assertIsDetected(outcome).faces)
    }

    /**
     * ⚠⚠ And a backend that CANNOT look must say [FaceDetectionOutcome.Failed], never "no faces".
     * The default on the interface is `Failed` for exactly this reason: a backend that has not
     * implemented detection has not looked, and answering "none" would let a caller record that
     * about every photograph it owns.
     */
    @Test
    fun aBackendWithoutTheCapabilityFailsRatherThanReportingNoFaces() = runTest {
        val outcome = backend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
            .detectFaces(photo("anna"))

        assertEquals(FaceDetectionOutcome.Failed, outcome)
    }

    /** ⚠ And bytes that are not an image are a fact about the file, distinct from a failure. */
    @Test
    fun bytesThatAreNotAnImageAreUnreadable() = runTest {
        assertEquals(FaceDetectionOutcome.Unreadable, backend().detectFaces(photo("not-an-image")))
    }

    @Test
    fun theSamePersonEmbedsToTheSameVectorInEveryPhotograph() = runTest {
        val backend = backend()
        val first = photo("anna")
        val second = photo("bob,anna")

        val fromFirst = backend.embedFace(first, assertIsDetected(backend.detectFaces(first)).faces[0])
        val fromSecond = backend.embedFace(second, assertIsDetected(backend.detectFaces(second)).faces[1])

        assertEquals(fromFirst, fromSecond)
    }

    @Test
    fun twoPeopleEmbedDifferently() = runTest {
        val backend = backend()
        val bytes = photo("anna,bob")
        val boxes = assertIsDetected(backend.detectFaces(bytes)).faces

        assertNotEquals(backend.embedFace(bytes, boxes[0]), backend.embedFace(bytes, boxes[1]))
    }

    /**
     * ⚠⚠ Face vectors and image vectors are **different spaces**. The arithmetic succeeds across
     * them and means nothing, which is why they are stamped and stored separately — the check cannot
     * live in a type.
     */
    @Test
    fun aFaceVectorIsNotAnImageVector() = runTest {
        val backend = FakeInferenceBackend()
        val bytes = photo("anna")
        val box = assertIsDetected(backend.detectFaces(bytes)).faces.single()

        assertNotEquals(backend.embedImage(bytes), backend.embedFace(bytes, box))
    }

    /** ⚠ A detection no detector produced has no face behind it — `null`, not a vector of zeros. */
    @Test
    fun aBoxThatNamesNoFaceEmbedsToNothing() = runTest {
        val nowhere = DetectedFace(
            box = FaceBox(0.9f, 0f, 1f, 0.1f),
            landmarks = FaceLandmarks(
                leftEye = FacePoint(0.92f, 0.03f),
                rightEye = FacePoint(0.98f, 0.03f),
                noseTip = FacePoint(0.95f, 0.05f),
                mouthLeft = FacePoint(0.93f, 0.08f),
                mouthRight = FacePoint(0.97f, 0.08f),
            ),
        )
        assertNull(backend().embedFace(photo("anna"), nowhere))
    }

    /**
     * ⚠⚠ A detection carries the five points the crop is ALIGNED by, not just the box it is bounded
     * by. Without them every platform crops to its own detector's framing and the same model puts
     * the same face in a different region of its space — see [FaceLandmarks].
     */
    @Test
    fun aDetectionCarriesTheAlignmentPoints() = runTest {
        val face = assertIsDetected(backend().detectFaces(photo("anna"))).faces.single()

        assertTrue(face.landmarks.leftEye.x < face.landmarks.rightEye.x, "eyes are ordered")
        assertTrue(face.landmarks.noseTip.y > face.landmarks.leftEye.y, "the nose sits below the eyes")
        assertTrue(
            face.landmarks.mouthLeft.y > face.landmarks.noseTip.y,
            "and the mouth below the nose",
        )
    }

    @Test
    fun aBackendWithoutTheCapabilityEmbedsNoFace() = runTest {
        val bytes = photo("anna")
        val box = assertIsDetected(backend().detectFaces(bytes)).faces.single()

        assertNull(
            backend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
                .embedFace(bytes, box),
        )
    }

    /** ⚠ Unit vectors, because `clusterFaces` treats a dot product as a cosine. */
    @Test
    fun faceVectorsAreUnitLength() = runTest {
        val backend = backend()
        val bytes = photo("anna")
        val box = assertIsDetected(backend.detectFaces(bytes)).faces.single()

        val vector = backend.embedFace(bytes, box)!!.vector
        val length = kotlin.math.sqrt(vector.fold(0f) { sum, v -> sum + v * v })

        assertTrue(kotlin.math.abs(length - 1f) < 1e-5f, "length was $length")
    }

    private fun assertIsDetected(outcome: FaceDetectionOutcome): FaceDetectionOutcome.Detected {
        assertTrue(outcome is FaceDetectionOutcome.Detected, "expected a detection, got $outcome")
        return outcome
    }
}
