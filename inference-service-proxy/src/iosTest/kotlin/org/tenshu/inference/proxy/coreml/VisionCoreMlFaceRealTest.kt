package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.FacePreflightJudge
import org.tenshu.inference.proxy.VisionFaceLandmarks
import org.tenshu.inference.proxy.FacePreflightMeasurement
import org.tenshu.inference.proxy.FacePreflightProbe
import org.tenshu.inference.proxy.FacePreflightProbes
import org.tenshu.inference.proxy.FacePreflightProber
import org.tenshu.inference.proxy.FaceProbeImages
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.FingerprintOutcome
import org.tenshu.inference.proxy.Preflight
import org.tenshu.inference.proxy.PreflightJudge
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.ReadinessSlot
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The face path over **real** Vision and, when handed one, the **real** F2 archive, on the simulator
 * (`ios-face-grouping.md` §14.4).
 *
 * ```
 * ./gradlew :inference-service-proxy:iosSimulatorArm64Test \
 *     -Dtenshu.inference.coremlFaceExport=$HOME/tenshu-models/coreml-face-export-984e39d3d798
 * ```
 *
 * ⚠⚠ The simulator is not a phone: every timing here is the build Mac's, and Vision's points on
 * iOS differ from macOS's (§11.3), so nothing here compares Vision output with a recorded one.
 * ⚠ No face image is used (FD12): Vision is asked to run, not to find someone.
 */
@OptIn(ExperimentalForeignApi::class)
class VisionCoreMlFaceRealTest {

    private val fixtures = CoreMlFixtures()
    private val model = FaceModel.AuraFaceCoreMl

    @AfterTest
    fun cleanUp() = fixtures.cleanUp()

    private fun ms(block: () -> Unit): Double {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
    }

    /**
     * §6.2's detector probe on the simulator's real Vision: the bundled face is found at revision 3,
     * where macOS Vision finds it.
     *
     * ⚠⚠ Its five points are NOT asserted: the simulator's landmarks are not the face's (§14.4), so
     * the five-points rule is the phone's to pass (F8). They are printed.
     */
    @Test
    fun visionFindsTheBundledFaceAtThePinnedRevision() {
        val photo = CoreMlFacePreflightModels().faceProbe()
        val image = FaceImage(photo.width, photo.height, photo.argb)
        lateinit var first: VisionDetection
        val warmUp: Double = ms { first = SystemVisionFaceDetector.detect(image) }
        val after: List<Double> = List(5) { ms { SystemVisionFaceDetector.detect(image) } }
        println("VISION-REAL revision ${first.revisionRun} on the ${photo.width}x${photo.height} face probe: first request $warmUp ms, then ${after.sorted()} ms; faces: ${first.faces.size}, boxes ${first.faces.map { it.box }}")
        val probe = CoreMlFacePreflightModels().detect(photo.argb, photo.width, photo.height)
        assertEquals(FaceModel.VISION_LANDMARKS_REVISION, probe.revision)
        val face = assertNotNull(probe.faces.singleOrNull(), "Vision did not find the one face in the bundled face probe: ${probe.faces.size}")
        println("VISION-REAL simulator five points ${face.landmarks}; the judge says ${FacePreflightJudge.faceRejection(face) ?: "usable"}")
        val mac = FaceProbePhotoMacVision.box
        val iw = minOf(face.box.right, mac.right) - maxOf(face.box.left, mac.left)
        val ih = minOf(face.box.bottom, mac.bottom) - maxOf(face.box.top, mac.top)
        val inter = maxOf(0f, iw) * maxOf(0f, ih)
        val union = (face.box.right - face.box.left) * (face.box.bottom - face.box.top) + (mac.right - mac.left) * (mac.bottom - mac.top) - inter
        assertTrue(inter / union > 0.8f, "the simulator's box is not macOS Vision's: IoU ${inter / union}")
    }

    /** ⭐ The five-points rule on macOS Vision's recorded answer for the bundled face (§14.4). */
    @Test
    fun macOsVisionsPointsForTheBundledFacePassTheFivePointsRule() {
        val photo = CoreMlFacePreflightModels().faceProbe()
        assertEquals(FaceProbePhotoMacVision.WIDTH to FaceProbePhotoMacVision.HEIGHT, photo.width to photo.height)
        val landmarks = assertNotNull(VisionFaceLandmarks.landmarks(FaceProbePhotoMacVision.regions, photo.width, photo.height))
        assertNull(FacePreflightJudge.faceRejection(org.tenshu.inference.proxy.FaceProbeFace(FaceProbePhotoMacVision.box, landmarks)))
    }

    /** A detector that answers macOS Vision's recording: the simulator's own landmarks are not the face's. */
    private val macVision = object : VisionFaceDetector {
        override fun detect(image: FaceImage): VisionDetection =
            VisionDetection(FaceModel.VISION_LANDMARKS_REVISION, listOf(VisionFaceObservation(FaceProbePhotoMacVision.box, FaceProbePhotoMacVision.regions)))
    }

    /** The bundled bytes are the committed file's: `scripts/inference/face-probe-photo.jpg`. */
    @Test
    fun theBundledFaceIsTheCommittedFile() {
        val digest: String = org.tenshu.core.common.util.Sha256Digest().apply { update(FaceProbePhoto.jpeg, FaceProbePhoto.jpeg.size) }.digest()
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(FaceProbePhoto.SHA256, digest)
        assertEquals("3c49a0b75ed61d862b2b7f3d44dab36f78f26fa94c3b1ba63b0b9f1c5e095772", digest)
        assertEquals(19_933, FaceProbePhoto.jpeg.size)
    }

    @Test
    fun aPhotographWithNoFaceIsDetectedEmptyThroughTheWholeDecode() = runBlocking<Unit> {
        val jpeg: ByteArray = testJpeg(1600, 1200, orientation = 6) { x, y -> ((x * 255 / 1600) shl 16) or ((y * 255 / 1200) shl 8) or 0x40 }
        val backend = VisionCoreMlFaceBackend(locate = { "/nowhere" }, open = { _, _ -> error("not opened by a detection") })
        val outcome = backend.detectFaces(jpeg)
        println("VISION-REAL a 1600x1200 EXIF-6 JPEG: $outcome")
        assertEquals(FaceDetectionOutcome.Detected(emptyList()), outcome)
    }

    private fun exportDirectory(): String? {
        val directory: String? = NSProcessInfo.processInfo.environment["TENSHU_COREML_FACE_EXPORT"] as? String
        if (directory == null) println("SKIPPED: no -Dtenshu.inference.coremlFaceExport=<export directory>, see the KDoc")
        return directory
    }

    /** The real archive compiled through the cache, as the prepare step would. */
    private fun compiled(directory: String): String {
        val name = "auraface_fp16.mlpackage.tar"
        NSFileManager.defaultManager.createDirectoryAtPath("${fixtures.root}/archives", true, null, null)
        check(NSFileManager.defaultManager.copyItemAtPath("$directory/$name", "${fixtures.root}/archives/$name", null)) { "no $name in $directory" }
        val digest: String = FacePreflightProbes.AURAFACE_984E39D3D798.embedderArchiveSha256
        val cache: CoreMlModelCache = fixtures.cache()
        runBlocking { cache.prepare(CoreMlTowerSpec.faceEmbedder(model), "archives/$name", digest) }
        return assertNotNull(cache.compiledPath(ModelArtifactRole.FACE_EMBEDDER, digest))
    }

    /**
     * ⭐ The host figures the shipped [FacePreflightProbe] carries: the probe images through the
     * real archive on the simulator's Core ML. Printed for the record, and held to it.
     */
    @Test
    fun theRealEmbedderRanksTheProbeImagesAsTheRecordSays() {
        val directory: String = exportDirectory() ?: return
        val session: CoreMlSession = CoreMlSession.open(compiled(directory), CoreMlTowerSpec.faceEmbedder(model))
        try {
            fun embed(argb: IntArray): FloatArray = session.predict(ImagePreprocessing.preprocessPixels(argb, FaceProbeImages.SIDE, model.profile))
            val reference = embed(FaceProbeImages.reference())
            val brighter = embed(FaceProbeImages.brighter())
            val shifted = embed(FaceProbeImages.shifted())
            val other = embed(FaceProbeImages.other())
            val toBrighter = PreflightJudge.cosine(reference, brighter)
            val toShifted = PreflightJudge.cosine(reference, shifted)
            val toOther = PreflightJudge.cosine(reference, other)
            val margin = minOf(toBrighter, toShifted) - toOther
            // The bundled face, through the very detector, mapping, align and crop the probe uses.
            val photo = CoreMlFacePreflightModels().faceProbe()
            val detection = CoreMlFacePreflightModels(detector = macVision).detect(photo.argb, photo.width, photo.height)
            val found = assertNotNull(detection.faces.filter { it.landmarks != null }.maxByOrNull { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }, "Vision found no face in the bundled face")
            val side = model.template.side
            val crop = org.tenshu.inference.proxy.resampleFace(photo.argb, photo.width, photo.height, side, org.tenshu.inference.proxy.alignFace(found.landmarks!!, model.template, photo.width, photo.height))
            val face = embed(crop)
            val mirror = embed(IntArray(crop.size) { crop[(it / side) * side + (side - 1 - it % side)] })
            val faceMargin = PreflightJudge.cosine(face, mirror) - PreflightJudge.cosine(face, other)
            println(
                "FACE-PROBE-HOST-FACE ${photo.width}x${photo.height}, ${detection.faces.size} face(s), box ${found.box}; norm ${PreflightJudge.norm(face)}; " +
                    "cos mirror ${PreflightJudge.cosine(face, mirror)} other ${PreflightJudge.cosine(face, other)}; margin $faceMargin",
            )
            println(
                "FACE-PROBE-HOST norm ${PreflightJudge.norm(reference)} (brighter ${PreflightJudge.norm(brighter)}, shifted ${PreflightJudge.norm(shifted)}, other ${PreflightJudge.norm(other)}); " +
                    "cos brighter $toBrighter shifted $toShifted other $toOther; margin $margin",
            )
            val record: FacePreflightProbe = FacePreflightProbes.AURAFACE_984E39D3D798
            assertTrue(margin >= record.minMargin, "the host margin $margin is under the record's floor ${record.minMargin}")
            assertTrue(faceMargin >= record.faceMinMargin, "the host face margin $faceMargin is under the record's floor ${record.faceMinMargin}")
            println("FACE-HOST-REFERENCE " + reference.joinToString(","))
            val agreement = PreflightJudge.cosine(reference, record.hostReference)
            println("FACE-HOST-AGREEMENT $agreement")
            assertTrue(agreement >= record.minHostCosine, "this host's field vector is at cos $agreement to the record's")
        } finally {
            session.close()
        }
    }

    /** ⭐ The whole face preflight over the real archive and real Vision, on a real face readiness. */
    @Test
    fun theShippedFaceProbePassesOnTheRealArchive() {
        val directory: String = exportDirectory() ?: return
        val prepared = PreparedModel.of(ModelArtifactRole.FACE_EMBEDDER to compiled(directory))
        val key = iosFaceRuntimeFingerprint(FacePreflightProbes.AURAFACE_984E39D3D798.embedderArchiveSha256, "simulator").let {
            org.tenshu.inference.proxy.PreflightKey(it, null, "test", org.tenshu.inference.proxy.FACE_PREFLIGHT_REVISION)
        }
        val readiness = AiReadiness(
            files = { fixtures.files },
            facts = { AiModelFacts(installed = true, prepared = prepared) },
            currentKey = { key },
            storesUsable = { true },
            applyFingerprint = { FingerprintOutcome.Unchanged },
            cancelPass = {},
            closeSessions = {},
            slot = ReadinessSlot.FACES,
        )
        val preflight = Preflight.over(readiness, FacePreflightProber(CoreMlFacePreflightModels(detector = macVision), IosPreflightDevice), IosPreflightDevice, beforeLoad = {})

        val outcome: PreflightOutcome = runBlocking { preflight.foregrounded(1) }

        val passed: PreflightOutcome.Passed = assertIs(outcome, "the shipped face probe did not pass on the real archive: $outcome")
        println("FACE-PREFLIGHT-REAL ${(passed.measurement as FacePreflightMeasurement).summary()}")
        assertEquals(AiReadinessState.Ready, readiness.current())

        val backend = faceBackendOver(readiness)
        assertTrue(backend.supports(InferenceCapability.FACE_CLUSTERING))
    }
}
