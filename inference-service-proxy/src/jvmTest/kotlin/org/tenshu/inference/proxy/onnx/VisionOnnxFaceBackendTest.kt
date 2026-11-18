package org.tenshu.inference.proxy.onnx

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.DetectedFace
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.ModelBackedBackend
import org.tenshu.inference.proxy.VisionFaceLandmarks
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import org.tenshu.inference.proxy.vision.MAX_FACES_PER_PHOTO
import org.tenshu.inference.proxy.vision.VisionDetection
import org.tenshu.inference.proxy.vision.VisionFaceDetector
import org.tenshu.inference.proxy.vision.VisionFaceObservation
import org.tenshu.inference.proxy.vision.VisionRequestException
import org.tenshu.inference.proxy.vision.visionFaceBox

/**
 * [VisionOnnxFaceBackend]'s own guards over a fake decoder, a fake Vision and a fake embedder
 * (`ios-face-grouping.md` §10.8). Nothing is loaded: what is under test is what the backend does
 * around them, case for case with `VisionCoreMlFaceBackendTest` on iOS.
 */
class VisionOnnxFaceBackendTest {

    private val model = FaceModel.AuraFaceOnnxMac
    private val embedderFile: File = File.createTempFile("auraface", ".onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    private var located: String? = embedderFile.absolutePath
    private var answer: () -> FloatArray = { FloatArray(model.dimensions) { if (it == 0) 3f else 0f } }
    private var detectorFails: Throwable? = null
    private var revisionRun: Int = FaceModel.VISION_LANDMARKS_REVISION
    private var observations: (FaceImage) -> List<VisionFaceObservation> = { image -> listOf(observation(image)) }
    private var rejecting = false
    private val opened = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val fed = mutableListOf<FloatArray>()

    @AfterTest
    fun cleanUp() {
        embedderFile.delete()
    }

    /** Each image is one flat colour, named by its first byte: a crop shows whose pixels it read. */
    private val decoder = FaceImageDecoder { bytes, _ ->
        if (bytes.isEmpty() || bytes[0] == 0.toByte()) return@FaceImageDecoder null
        val grey: Int = bytes[0].toInt() and 0xFF
        FaceImage(64, 48, IntArray(64 * 48) { (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey })
    }

    private val detector = object : VisionFaceDetector {
        override fun detect(image: FaceImage): VisionDetection {
            detectorFails?.let { throw it }
            return VisionDetection(revisionRun, observations(image))
        }
    }

    private fun backend(osBuild: String = "26B123") = VisionOnnxFaceBackend(
        locateEmbedder = { located },
        decoder = decoder,
        detector = detector,
        osBuild = osBuild,
        lane = Dispatchers.Default,
        openEmbedder = { path ->
            opened += path
            if (rejecting) throw RejectedFaceEmbedderException("not the embedder")
            object : FaceEmbedderSession {
                override fun embed(input: FloatArray): FloatArray {
                    fed += input.copyOf()
                    return answer()
                }
                override fun close() {
                    closed += path
                }
            }
        },
    )

    /** An upright face in a `w × h` image, as Vision gives it: bottom-left pixels. */
    private fun observation(image: FaceImage, scale: Double = 0.5): VisionFaceObservation {
        val h: Float = image.height.toFloat()
        fun up(vararg xy: Float): FloatArray = FloatArray(xy.size) { if (it % 2 == 0) xy[it] else h - xy[it] }
        return VisionFaceObservation(
            box = visionFaceBox(0.25, 0.25, scale, scale, 1f),
            regions = VisionFaceLandmarks.Regions(
                leftEye = up(20f, 18f, 26f, 18f),
                rightEye = up(38f, 18f, 44f, 18f),
                leftPupil = up(23f, 18f),
                rightPupil = up(41f, 18f),
                nose = up(30f, 22f, 32f, 28f, 34f, 22f),
                noseCrest = up(32f, 20f, 32f, 26f),
                outerLips = up(24f, 34f, 32f, 36f, 40f, 34f),
            ),
        )
    }

    private val red = byteArrayOf(0x40, 1, 2)
    private val blue = byteArrayOf(0x70, 1, 2)

    private suspend fun VisionOnnxFaceBackend.oneFace(bytes: ByteArray): DetectedFace =
        assertIs<FaceDetectionOutcome.Detected>(detectFaces(bytes)).faces.single()

    // ─── what it claims ────────────────────────────────────────────────────────────────────────

    @Test
    fun claimsFacesOnlyAndOnlyWithAnEmbedder() {
        val backend = backend()
        assertTrue(backend.supports(InferenceCapability.FACE_CLUSTERING))
        assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        located = null
        assertFalse(backend.supports(InferenceCapability.FACE_CLUSTERING))
    }

    @Test
    fun withNoEmbedderDetectionFailsRatherThanRecordingFacesNothingCanUse() = runBlocking<Unit> {
        located = null
        assertIs<FaceDetectionOutcome.Failed>(backend().detectFaces(red))
    }

    // ─── detection guards ──────────────────────────────────────────────────────────────────────

    @Test
    fun aVisionErrorIsAFailureNotAnEmptyPhotograph() = runBlocking<Unit> {
        detectorFails = VisionRequestException("Vision failed")
        assertIs<FaceDetectionOutcome.Failed>(backend().detectFaces(red))
    }

    @Test
    fun aRunAtAnotherRevisionIsAFailure() = runBlocking<Unit> {
        revisionRun = FaceModel.VISION_LANDMARKS_REVISION - 1
        assertIs<FaceDetectionOutcome.Failed>(backend().detectFaces(red))
    }

    @Test
    fun bytesThatDoNotDecodeAreUnreadable() = runBlocking<Unit> {
        assertIs<FaceDetectionOutcome.Unreadable>(backend().detectFaces(byteArrayOf(0, 1)))
    }

    @Test
    fun aPhotographWithNobodyIsAnEmptyDetection() = runBlocking<Unit> {
        observations = { emptyList() }
        assertEquals(emptyList(), assertIs<FaceDetectionOutcome.Detected>(backend().detectFaces(red)).faces)
    }

    @Test
    fun keepsTheLargestTenFaces() = runBlocking<Unit> {
        observations = { image -> (1..12).map { i -> observation(image, scale = i / 24.0) } }
        val faces = assertIs<FaceDetectionOutcome.Detected>(backend().detectFaces(red)).faces
        assertEquals(MAX_FACES_PER_PHOTO, faces.size)
        val areas = faces.map { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }
        assertEquals(areas.sortedDescending(), areas)
    }

    @Test
    fun aFaceWithoutLandmarksIsLeftOut() = runBlocking<Unit> {
        observations = { image -> listOf(VisionFaceObservation(visionFaceBox(0.1, 0.1, 0.2, 0.2, 1f), null), observation(image)) }
        assertEquals(1, assertIs<FaceDetectionOutcome.Detected>(backend().detectFaces(red)).faces.size)
    }

    // ─── embedding ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun embedsANormalisedVectorStampedWithTheMacPipeline() = runBlocking<Unit> {
        val backend = backend()
        val embedding = assertNotNull(backend.embedFace(red, backend.oneFace(red)))
        assertEquals(1f, embedding.vector[0], 1e-6f)
        assertEquals(backend.provenance, embedding.producedBy)
        assertEquals(model.provenance.copy(labelSetVersion = FaceModel.macVisionStamp("26B123")), embedding.producedBy)
        assertEquals(3 * 112 * 112, fed.single().size)
    }

    @Test
    fun theIndexerReadsTheStampFromTheBackendItself() {
        // ⚠⚠ Without ModelBackedBackend the indexer falls back to the backend id, which never moves,
        // and an OS update would append another Vision's vectors to the store.
        val backend: Any = backend()
        assertIs<ModelBackedBackend>(backend)
    }

    @Test
    fun anotherOsBuildIsAnotherStamp() {
        assertNotEquals(backend("26B123").provenance, backend("26C55").provenance)
        assertEquals(backend("26B123").provenance, backend("26B123").provenance)
    }

    @Test
    fun aNanOrZeroVectorIsRefused() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        answer = { FloatArray(model.dimensions) { Float.NaN } }
        assertNull(backend.embedFace(red, face))
        answer = { FloatArray(model.dimensions) }
        assertNull(backend.embedFace(red, face))
        answer = { FloatArray(model.dimensions - 1) { 1f } }
        assertNull(backend.embedFace(red, face))
    }

    @Test
    fun decodesAPhotographOnceForItsFaces() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        backend.embedFace(red, face)
        backend.embedFace(red, face)
        assertEquals(1, backend.decodes)
        // ⚠ Other bytes are decoded afresh, even with the same content: identity, not equality.
        backend.embedFace(red.copyOf(), face)
        assertEquals(2, backend.decodes)
    }

    @Test
    fun aFaceIsCroppedFromThePhotographItWasAskedAbout() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        backend.embedFace(red, face)
        backend.embedFace(blue, face)
        // SIGLIP profile: (x / 255 − 0.5) / 0.5 per channel.
        assertEquals((0x40 / 255f - 0.5f) / 0.5f, fed[0][0], 1e-5f)
        assertEquals((0x70 / 255f - 0.5f) / 0.5f, fed[1][0], 1e-5f)
    }

    @Test
    fun anEmbedderThatIsNotTheModelIsRefusedOnceAndForAll() = runBlocking<Unit> {
        rejecting = true
        val backend = backend()
        val face = backend.oneFace(red)
        assertNull(backend.embedFace(red, face))
        assertFalse(backend.supports(InferenceCapability.FACE_CLUSTERING))
        assertNull(backend.embedFace(red, face))
        assertEquals(1, opened.size)
    }

    @Test
    fun aReplacedEmbedderIsReopened() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        backend.embedFace(red, face)
        embedderFile.writeBytes(byteArrayOf(9, 9, 9, 9))
        backend.embedFace(red, face)
        assertEquals(2, opened.size)
        assertEquals(1, closed.size)
    }

    @Test
    fun theIndexerTypeStillCompilesAgainstIt() {
        // A compile-time tie to the indexer the stamp feeds, so a rename of either is seen here.
        assertNotNull(MediaIndexer::class)
    }
}
