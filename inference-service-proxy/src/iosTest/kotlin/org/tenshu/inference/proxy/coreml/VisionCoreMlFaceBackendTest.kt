package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.DetectedFace
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.FacePoint
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.VisionFaceLandmarks
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.FaceModel
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VisionCoreMlFaceBackend]'s own guards on a fixture backend: a fake decoder, a fake Vision and a
 * fake Core ML session (`ios-face-grouping.md` §7 F4, §14). Nothing is loaded; what is under test is
 * what the backend does around them.
 *
 * ⚠ Each guard was removed once and its case seen red (`ios-face-grouping.md` §14.3).
 */
@OptIn(ExperimentalForeignApi::class)
class VisionCoreMlFaceBackendTest {

    private val model = FaceModel.AuraFaceCoreMl
    private val path = "/coreml/f/faceEmbedder.mlmodelc"

    private var ready = true
    private var located: String? = path
    private var answer: () -> FloatArray = { FloatArray(model.dimensions) { if (it == 0) 3f else 0f } }
    private var detectorFails: Throwable? = null
    private var whileDetecting: () -> Unit = {}
    private var observations: (FaceImage) -> List<VisionFaceObservation> = { image -> listOf(observation(image)) }
    private var rejecting = false
    private val opened = mutableListOf<String>()
    private val decoded = mutableListOf<ByteArray>()
    private val fed = mutableListOf<FloatArray>()

    /** Each image is one flat colour, named by its first byte: a crop shows whose pixels it read. */
    private val decoder = FaceImageDecoder { bytes, _ ->
        if (bytes.isEmpty() || bytes[0] == 0.toByte()) return@FaceImageDecoder null
        decoded += bytes
        val grey: Int = bytes[0].toInt() and 0xFF
        FaceImage(64, 48, IntArray(64 * 48) { (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey })
    }

    private var revisionRun: Int = FaceModel.VISION_LANDMARKS_REVISION

    private val detector = object : VisionFaceDetector {
        override fun detect(image: FaceImage): VisionDetection {
            detectorFails?.let { throw it }
            whileDetecting()
            return VisionDetection(revisionRun, observations(image))
        }
    }

    private inner class Session(override val compiledPath: String) : CoreMlSessionPort {
        override fun predict(floats: FloatArray): FloatArray {
            fed += floats.copyOf()
            return answer()
        }
        override fun predict(ids: IntArray): FloatArray = error("pixels only")
        override fun close() {}
    }

    private fun backend() = VisionCoreMlFaceBackend(
        locate = { located },
        ready = { ready },
        decoder = decoder,
        detector = detector,
        open = { compiledPath, spec ->
            assertEquals(CoreMlTowerSpec.faceEmbedder(model), spec)
            opened += compiledPath
            if (rejecting) throw CoreMlRejectedModelException("not the embedder")
            Session(compiledPath)
        },
        lane = Dispatchers.Default,
    )

    /** An upright face in a `w × h` image, as Vision gives it: bottom-left pixels. */
    private fun observation(image: FaceImage): VisionFaceObservation {
        val h: Float = image.height.toFloat()
        fun up(vararg xy: Float): FloatArray = FloatArray(xy.size) { if (it % 2 == 0) xy[it] else h - xy[it] }
        return VisionFaceObservation(
            box = visionFaceBox(0.25, 0.25, 0.5, 0.5, 1f),
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

    private suspend fun VisionCoreMlFaceBackend.oneFace(bytes: ByteArray): DetectedFace =
        assertIs<FaceDetectionOutcome.Detected>(detectFaces(bytes)).faces.single()

    // ─── claims nothing until Ready ─────────────────────────────────────────────────────────────

    @Test
    fun itClaimsFacesOnlyWhileReadyAndWithAnEmbedder() {
        val backend = backend()
        assertTrue(backend.supports(InferenceCapability.FACE_CLUSTERING))
        assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        ready = false
        assertFalse(backend.supports(InferenceCapability.FACE_CLUSTERING), "claimed while not Ready")
        ready = true
        located = null
        assertFalse(backend.supports(InferenceCapability.FACE_CLUSTERING), "claimed with no embedder")
    }

    @Test
    fun aBackendThatIsNotReadyLooksAtNothingAndEmbedsNothing() = runBlocking<Unit> {
        val backend = backend()
        val face: DetectedFace = backend.oneFace(red)
        ready = false
        val decodesBefore: Int = backend.decodes
        detectorFails = AssertionError("Vision was run while not Ready")
        assertIs<FaceDetectionOutcome.Failed>(backend.detectFaces(blue), "not Ready, and detected")
        assertEquals(decodesBefore, backend.decodes, "a photograph was decoded while not Ready")
        detectorFails = null
        assertIs<FaceDetectionOutcome.Failed>(backend.detectFaces(red), "not Ready, and detected")
        assertNull(backend.embedFace(red, face), "not Ready, and embedded")
        assertTrue(opened.isEmpty(), "an embedder was opened while not Ready")
    }

    @Test
    fun aTeardownUnderTheDetectorRecordsNothing() = runBlocking<Unit> {
        whileDetecting = { ready = false }
        assertIs<FaceDetectionOutcome.Failed>(backend().detectFaces(red), "a detection that outlived its readiness was handed on")
    }

    @Test
    fun aTeardownUnderThePredictionReturnsNoVector() = runBlocking<Unit> {
        val backend = backend()
        val face: DetectedFace = backend.oneFace(red)
        answer = { ready = false; FloatArray(model.dimensions) { 1f } }
        assertNull(backend.embedFace(red, face), "a vector that outlived its readiness was handed on")
    }

    // ─── Vision errors ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aVisionErrorIsFailedNeverDetectedEmpty() = runBlocking<Unit> {
        detectorFails = VisionRequestException("Vision error 9")
        assertIs<FaceDetectionOutcome.Failed>(backend().detectFaces(red))
    }

    @Test
    fun aVisionThatRanAnotherRevisionIsFailed() = runBlocking<Unit> {
        revisionRun = FaceModel.VISION_LANDMARKS_REVISION + 1
        assertIs<FaceDetectionOutcome.Failed>(backend().detectFaces(red), "faces from another Vision revision were handed on as this pipeline's")
    }

    @Test
    fun noFaceIsDetectedEmptyAndUndecodableBytesAreUnreadable() = runBlocking<Unit> {
        observations = { emptyList() }
        assertEquals(FaceDetectionOutcome.Detected(emptyList()), backend().detectFaces(red))
        assertIs<FaceDetectionOutcome.Unreadable>(backend().detectFaces(byteArrayOf(0)))
    }

    @Test
    fun aFaceWithoutLandmarksIsLeftOutAndTheLargestTenAreKept() = runBlocking<Unit> {
        observations = { image ->
            val mapped = observation(image)
            listOf(VisionFaceObservation(mapped.box, null)) +
                (1..12).map { i -> VisionFaceObservation(visionFaceBox(0.0, 0.0, 0.02 * i, 0.02 * i, 1f), mapped.regions) }
        }
        val faces = assertIs<FaceDetectionOutcome.Detected>(backend().detectFaces(red)).faces
        assertEquals(MAX_FACES_PER_PHOTO, faces.size)
        assertEquals(faces.sortedByDescending { it.box.right - it.box.left }, faces, "not largest first")
        assertTrue(faces.none { it.box.right - it.box.left < 0.02f * 3 - 1e-6f }, "a small face was kept over a larger one")
    }

    @Test
    fun visionsBottomLeftBoxIsFlipped() {
        val box: FaceBox = visionFaceBox(0.1, 0.2, 0.3, 0.4, 0.9f)
        assertEquals(0.1f, box.left, 1e-6f)
        assertEquals(0.4f, box.top, 1e-6f)
        assertEquals(0.4f, box.right, 1e-6f)
        assertEquals(0.8f, box.bottom, 1e-6f)
        assertEquals(0.9f, box.confidence)
    }

    // ─── the vector ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aVectorIsUnitLengthAndStampedWithTheIosPipeline() = runBlocking<Unit> {
        val backend = backend()
        val embedding: ImageEmbedding = assertNotNull(backend.embedFace(red, backend.oneFace(red)))
        assertEquals(model.dimensions, embedding.vector.size)
        assertTrue(abs(sqrt(embedding.vector.fold(0f) { s, v -> s + v * v }) - 1f) < 1e-5f)
        assertEquals(model.provenance.copy(labelSetVersion = FaceModel.VISION_PIPELINE_VERSION), embedding.producedBy)
        assertEquals(backend.provenance, embedding.producedBy)
        assertEquals(3 * model.template.side * model.template.side, fed.single().size)
    }

    @Test
    fun aNonFiniteOrZeroOrWrongWidthVectorIsRefused() = runBlocking<Unit> {
        val backend = backend()
        val face: DetectedFace = backend.oneFace(red)
        answer = { FloatArray(model.dimensions) { if (it == 7) Float.NaN else 1f } }
        assertNull(backend.embedFace(red, face), "a NaN vector was returned")
        answer = { FloatArray(model.dimensions) { if (it == 7) Float.POSITIVE_INFINITY else 1f } }
        assertNull(backend.embedFace(red, face), "an infinite vector was returned")
        answer = { FloatArray(model.dimensions) }
        assertNull(backend.embedFace(red, face), "a zero vector was returned")
        answer = { FloatArray(model.dimensions - 1) { 1f } }
        assertNull(backend.embedFace(red, face), "a vector of another width was returned")
    }

    @Test
    fun aPredictionThatThrowsIsNoVector() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        answer = { throw CoreMlPredictionException("ANE fault") }
        assertNull(backend.embedFace(red, face))
    }

    @Test
    fun anArchiveThatIsNotTheEmbedderIsLatchedForItsPathAlone() = runBlocking<Unit> {
        rejecting = true
        val backend = backend()
        val face = backend.oneFace(red)
        assertNull(backend.embedFace(red, face))
        assertFalse(backend.supports(InferenceCapability.FACE_CLUSTERING), "a rejected archive is still claimed")
        assertNull(backend.embedFace(red, face))
        assertEquals(listOf(path), opened, "a rejected archive was opened again")
        rejecting = false
        located = "/coreml/g/faceEmbedder.mlmodelc"
        assertTrue(backend.supports(InferenceCapability.FACE_CLUSTERING), "the latch refused another archive")
        assertNotNull(backend.embedFace(red, face))
    }

    // ─── the bytes cache ────────────────────────────────────────────────────────────────────────

    @Test
    fun detectThenEmbedDecodesOnce() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        assertNotNull(backend.embedFace(red, face))
        assertNotNull(backend.embedFace(red, face))
        assertEquals(1, backend.decodes, "the same bytes were decoded again between detect and embed")
    }

    @Test
    fun aFaceIsNeverCroppedFromAnotherPhotographsPixels() = runBlocking<Unit> {
        val backend = backend()
        val redFace = backend.oneFace(red)
        backend.oneFace(blue)
        assertNotNull(backend.embedFace(red, redFace))
        // The red decode's grey, through the SigLIP profile: (0x40 / 255 - 0.5) / 0.5.
        val expected: Float = (0x40 / 255f - 0.5f) / 0.5f
        assertTrue(fed.single().all { abs(it - expected) < 1e-5f }, "the crop read another photograph's pixels")
        assertEquals(3, backend.decodes)
    }

    @Test
    fun equalBytesInAnotherArrayAreDecodedAfresh() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        assertNotNull(backend.embedFace(red.copyOf(), face))
        assertEquals(2, backend.decodes, "the cache was keyed on content, not identity")
    }

    @Test
    fun closingDropsTheCachedImage() = runBlocking<Unit> {
        val backend = backend()
        val face = backend.oneFace(red)
        backend.closeSessions()
        assertNotNull(backend.embedFace(red, face))
        assertEquals(2, backend.decodes)
    }

    // ─── the decode ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDecodeEdgeHalvesAsAndroidsSampleSizeDoes() {
        assertEquals(2048, faceDecodeEdge(2048, 1536))
        assertEquals(2000, faceDecodeEdge(4000, 3000))
        assertEquals(2016, faceDecodeEdge(3024, 4032))
        assertEquals(1025, faceDecodeEdge(4100, 300))
        assertEquals(1500, faceDecodeEdge(6000, 4000))
        assertEquals(640, faceDecodeEdge(640, 480))
    }

    @Test
    fun everyExifOrientationIsAppliedAsDisplayed() {
        // A 3×2 stored image, pixel value = its stored index.
        val stored = FaceImage(3, 2, IntArray(6) { it })
        val expected: Map<Int, Pair<Pair<Int, Int>, List<Int>>> = mapOf(
            1 to ((3 to 2) to listOf(0, 1, 2, 3, 4, 5)),
            2 to ((3 to 2) to listOf(2, 1, 0, 5, 4, 3)),
            3 to ((3 to 2) to listOf(5, 4, 3, 2, 1, 0)),
            4 to ((3 to 2) to listOf(3, 4, 5, 0, 1, 2)),
            5 to ((2 to 3) to listOf(0, 3, 1, 4, 2, 5)),
            6 to ((2 to 3) to listOf(3, 0, 4, 1, 5, 2)),
            7 to ((2 to 3) to listOf(5, 2, 4, 1, 3, 0)),
            8 to ((2 to 3) to listOf(2, 5, 1, 4, 0, 3)),
        )
        for ((orientation, want) in expected) {
            val shown = orient(stored, orientation)
            assertEquals(want.first, shown.width to shown.height, "orientation $orientation's size")
            assertContentEquals(want.second.toIntArray(), shown.argb, "orientation $orientation")
        }
    }

    @Test
    fun theRealDecoderIsBoundedUprightAndHonoursAnOverride() {
        // Stored 300×200, left half red, tagged 6 (rotate 90° clockwise to display).
        val jpeg: ByteArray = testJpeg(300, 200, orientation = 6) { x, _ -> if (x < 150) 0xE01010 else 0x1010E0 }
        val upright: FaceImage = assertNotNull(IosFaceImageDecoder.decodeUpright(jpeg, null))
        assertEquals(200 to 300, upright.width to upright.height, "EXIF was not applied")
        fun redAt(image: FaceImage, x: Int, y: Int): Boolean = ((image.argb[y * image.width + x] shr 16) and 0xFF) > 0x80
        assertTrue(redAt(upright, 100, 30), "the stored left half is not on top once rotated clockwise")
        assertFalse(redAt(upright, 100, 270))

        val raw: FaceImage = assertNotNull(IosFaceImageDecoder.decodeUpright(jpeg, 1))
        assertEquals(300 to 200, raw.width to raw.height, "an override of 1 still applied the bytes' EXIF")
        val overridden: FaceImage = assertNotNull(IosFaceImageDecoder.decodeUpright(jpeg, 8))
        assertEquals(200 to 300, overridden.width to overridden.height)
        assertTrue(redAt(overridden, 100, 270), "orientation 8 did not rotate counter-clockwise")
        assertNull(IosFaceImageDecoder.decodeUpright(jpeg, 9))
        assertNull(IosFaceImageDecoder.decodeUpright(byteArrayOf(1, 2, 3), null))

        val big: ByteArray = testJpeg(4000, 300) { _, _ -> 0x808080 }
        val bounded: FaceImage = assertNotNull(IosFaceImageDecoder.decodeUpright(big, null))
        assertEquals(2000, bounded.width, "the decode was not bounded by Android's rule")
    }

    private fun readinessOf(slot: org.tenshu.inference.proxy.ReadinessSlot) = org.tenshu.inference.proxy.AiReadiness(
        files = { org.tenshu.core.common.proxy.InMemoryFileDatabase() },
        facts = { org.tenshu.inference.proxy.AiModelFacts(false, null) },
        currentKey = { error("not read") },
        storesUsable = { true },
        applyFingerprint = { org.tenshu.inference.proxy.FingerprintOutcome.Unchanged },
        cancelPass = {},
        closeSessions = {},
        slot = slot,
    )

    /** ⚠⚠ The F5 trap: a face backend over search's readiness (and so search's custody) is refused. */
    @Test
    fun theFaceBackendRefusesSearchsReadiness() {
        assertFalse(faceBackendOver(readinessOf(org.tenshu.inference.proxy.ReadinessSlot.FACES)).supports(InferenceCapability.FACE_CLUSTERING))
        val refused = kotlin.runCatching { faceBackendOver(readinessOf(org.tenshu.inference.proxy.ReadinessSlot.SIGLIP)) }
        assertIs<IllegalArgumentException>(refused.exceptionOrNull(), "a face backend was wired over search's readiness")
    }

    @Test
    fun faceBackendOverServesOnlyWhatReadinessServes() {
        // The locator reads the face role from readiness's snapshot, and nothing else.
        val prepared = org.tenshu.inference.proxy.PreparedModel.of(ModelArtifactRole.FACE_EMBEDDER to path)
        assertEquals(path, prepared.paths[ModelArtifactRole.FACE_EMBEDDER])
        assertNull(org.tenshu.inference.proxy.PreparedModel("v", "t", "w").paths[ModelArtifactRole.FACE_EMBEDDER], "SigLIP's generation names a face embedder")
    }
}
