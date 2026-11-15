package org.tenshu.inference.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Readiness **per model** (`ios-face-grouping.md` FD7, §6.2) and the face preflight on fakes:
 * SigLIP and the face pair over ONE disk, each ready, unsuitable or deleted on its own, and the face
 * pair under D12's rules through the very [AiReadiness] and [Preflight] SigLIP uses.
 *
 * ⚠ The readinesses are the real ones over an in-memory disk; a new [AttemptLedger] is a new process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceReadinessTest {

    private val disk = InMemoryFileDatabase()
    private val time = TestTimeSource()
    private val width: Int = FaceModel.AuraFaceCoreMl.dimensions
    private var scene = 0L

    // ─── the two models ─────────────────────────────────────────────────────────────────────────

    private val siglipModel = PreparedModel("/coreml/a/vision.mlmodelc", "/coreml/a/text.mlmodelc", "/models/a.vocabulary")
    private val faceModel = PreparedModel.of(ModelArtifactRole.FACE_EMBEDDER to "/coreml/f/faceEmbedder.mlmodelc")

    private val siglipKey = PreflightKey(
        runtime = RuntimeFingerprint("vision-a", "text-a", computeUnits = "cpuAndNeuralEngine", osBuild = "os-1", preprocessingRevision = 1),
        vocabularySha256 = "vocabulary-a",
        appBuild = "1.0 (1)",
        preflightRevision = PREFLIGHT_REVISION,
    )
    private var faceKey = faceKey()

    private fun faceKey(embedder: String = "embedder-a", osBuild: String = "os-1", revision: Int = FaceModel.VISION_LANDMARKS_REVISION) = PreflightKey(
        runtime = RuntimeFingerprint(embedder, null, computeUnits = "cpuAndNeuralEngine", osBuild = osBuild, preprocessingRevision = 1, detectorRevision = revision),
        vocabularySha256 = null,
        appBuild = "1.0 (1)",
        preflightRevision = FACE_PREFLIGHT_REVISION,
    )

    private var faceInstalled = true
    private var facePrepared: PreparedModel? = faceModel
    private val steps = mutableListOf<String>()

    private class Killed : Error("the process was killed")

    /** A process whose disk takes no write from the moment [kill] is called. */
    private inner class Process(val ledger: AttemptLedger = AttemptLedger()) {
        var dead = false
        val files = object : FileDatabase by disk {
            override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
                if (!dead) disk.writeRawByteArray(filePath, bytes)
            }
        }

        fun kill(): Nothing {
            dead = true
            throw Killed()
        }
    }

    private fun siglip(process: Process = Process()): AiReadiness = AiReadiness(
        files = { process.files },
        facts = { AiModelFacts(true, siglipModel) },
        currentKey = { siglipKey },
        storesUsable = { true },
        applyFingerprint = { FingerprintOutcome.Unchanged },
        cancelPass = { steps += "siglip cancel" },
        closeSessions = { steps += "siglip close" },
        ledger = process.ledger,
    )

    private fun faces(process: Process = Process()): AiReadiness = AiReadiness(
        files = { process.files },
        facts = { AiModelFacts(faceInstalled, facePrepared) },
        currentKey = { faceKey },
        storesUsable = { true },
        applyFingerprint = { FingerprintOutcome.Unchanged },
        cancelPass = { steps += "faces cancel" },
        closeSessions = { steps += "faces close" },
        ledger = process.ledger,
        slot = ReadinessSlot.FACES,
    )

    private fun bytesOf(path: String): String? = disk.readBytes(path)?.get()?.decodeToString()

    // ─── FD7: per model ─────────────────────────────────────────────────────────────────────────

    @Test
    fun everySlotHasItsOwnFilesAndSiglipsAreTheOnesItAlwaysWrote() {
        val paths: List<String> = ReadinessSlot.ALL.flatMap { slot -> listOf(slot.verdictPath, slot.scenePath) + slot.openRoles.map { "${slot.verdictPath}.open.$it" } }
        assertEquals(paths.size, paths.toSet().size, "two slots share a file: $paths")
        assertEquals(ReadinessSlot.ALL.size, ReadinessSlot.ALL.map { it.name }.toSet().size)
        // ⚠⚠ Every phone already holds SigLIP's verdict at these paths: a move would void every pass.
        assertEquals("/inference/preflight.verdict", ReadinessSlot.SIGLIP.verdictPath)
        assertEquals("/inference/preflight.scene", ReadinessSlot.SIGLIP.scenePath)
        assertEquals(listOf("vision", "text"), ReadinessSlot.SIGLIP.openRoles)
        assertEquals(listOf(ModelArtifactRole.FACE_EMBEDDER), ReadinessSlot.FACES.openRoles)
    }

    @Test
    fun aFaceKeyCanNeverBeASiglipKey() {
        assertNotEquals(siglipKey, faceKey)
        assertNull(faceKey.runtime.textArchiveSha256)
        assertNull(faceKey.vocabularySha256)
        assertEquals(FaceModel.VISION_LANDMARKS_REVISION, faceKey.runtime.detectorRevision)
        assertNull(siglipKey.runtime.detectorRevision, "SigLIP's key gained a field every verdict on disk lacks")
    }

    @Test
    fun aFaceFailureLeavesSearchReadyAndItsFileUntouched() = runTest {
        assertEquals(AiReadinessState.Ready, siglip().recordVerdict(passed = true, probed = siglipKey))
        val searchBefore: String? = bytesOf(ReadinessSlot.SIGLIP.verdictPath)

        // Two face embedder opens Photos did not survive, each in its own process.
        repeat(2) { round ->
            val process = Process()
            val graph = faces(process)
            if (round == 0) assertEquals(AiReadinessState.Ready, graph.recordVerdict(passed = true, probed = faceKey)) else graph.fingerprintChecked()
            runCatching { graph.servingOpen(ModelArtifactRole.FACE_EMBEDDER, foreground = 1L, atEpochMs = 5L) { process.kill() } }
        }

        val facesNow = faces()
        assertEquals(AiReadinessState.Unsuitable, facesNow.fingerprintChecked(), "two face kills did not stop faces")
        assertTrue((facesNow.standing.value as ReadinessStanding.For).stoppedLoading)
        val searchNow = siglip()
        assertEquals(AiReadinessState.Ready, searchNow.fingerprintChecked(), "a face kill stopped search")
        assertTrue(searchNow.serves())
        assertEquals(searchBefore, bytesOf(ReadinessSlot.SIGLIP.verdictPath), "the face path wrote search's verdict")
        assertFalse(searchNow.loadAgain(faceKey), "search withdrew a face fail")
        assertTrue(facesNow.loadAgain(faceKey))
    }

    @Test
    fun aSearchFailureLeavesFacesReadyAndItsFileUntouched() = runTest {
        assertEquals(AiReadinessState.Ready, faces().recordVerdict(passed = true, probed = faceKey))
        val facesBefore: String? = bytesOf(ReadinessSlot.FACES.verdictPath)

        repeat(2) { round ->
            val process = Process()
            val graph = siglip(process)
            if (round == 0) assertEquals(AiReadinessState.Ready, graph.recordVerdict(passed = true, probed = siglipKey)) else graph.fingerprintChecked()
            runCatching { graph.servingOpen("vision", foreground = 1L, atEpochMs = 5L) { process.kill() } }
        }

        assertEquals(AiReadinessState.Unsuitable, siglip().fingerprintChecked(), "two search kills did not stop search")
        val facesNow = faces()
        assertEquals(AiReadinessState.Ready, facesNow.fingerprintChecked(), "a search kill stopped faces")
        assertTrue(facesNow.serves())
        assertEquals(facesBefore, bytesOf(ReadinessSlot.FACES.verdictPath), "the search path wrote the face verdict")
    }

    @Test
    fun deletingTheFaceModelLeavesSearchServing() = runTest {
        val search = siglip()
        val facesGraph = faces()
        assertEquals(AiReadinessState.Ready, search.recordVerdict(passed = true, probed = siglipKey))
        assertEquals(AiReadinessState.Ready, facesGraph.recordVerdict(passed = true, probed = faceKey))
        steps.clear()

        facesGraph.storeChanging {
            faceInstalled = false
            facePrepared = null
        }

        assertEquals(AiReadinessState.NoModel, facesGraph.current())
        assertFalse(facesGraph.serves())
        assertEquals(listOf("faces cancel", "faces close"), steps, "leaving faces touched search")
        assertEquals(AiReadinessState.Ready, search.fingerprintChecked())
        assertTrue(search.serves())
    }

    // ─── D12 for the face pair ──────────────────────────────────────────────────────────────────

    @Test
    fun theFacePairClaimsNothingUntilReady() = runTest {
        val graph = faces()
        val served = mutableMapOf<AiReadinessState, Boolean>()
        fun note() {
            served[graph.current()] = graph.serves()
        }
        faceInstalled = false
        facePrepared = null
        graph.fingerprintChecked(); note()
        faceInstalled = true
        graph.fingerprintChecked(); note()
        facePrepared = faceModel
        graph.markerWritten(); note()
        graph.recordVerdict(passed = true, probed = faceKey); note()
        assertEquals(faceModel, graph.serving())
        graph.recordVerdict(passed = false, probed = faceKey); note()

        assertEquals(
            mapOf(
                AiReadinessState.NoModel to false,
                AiReadinessState.Preparing to false,
                AiReadinessState.AwaitingVerdict to false,
                AiReadinessState.Ready to true,
                AiReadinessState.Unsuitable to false,
            ),
            served,
        )
    }

    @Test
    fun aNewOsBuildOrVisionRevisionVoidsTheFacePass() = runTest {
        val graph = faces()
        assertEquals(AiReadinessState.Ready, graph.recordVerdict(passed = true, probed = faceKey))
        faceKey = faceKey(osBuild = "os-2")
        assertEquals(AiReadinessState.AwaitingVerdict, graph.fingerprintChecked(), "an OS update kept the face pass (FD9)")
        assertEquals(AiReadinessState.Ready, graph.recordVerdict(passed = true, probed = faceKey))
        faceKey = faceKey(osBuild = "os-2", revision = 4)
        assertEquals(AiReadinessState.AwaitingVerdict, graph.fingerprintChecked(), "another Vision revision kept the face pass (FD9)")
    }

    // ─── the face preflight ─────────────────────────────────────────────────────────────────────

    private val probe = FacePreflightProbe(
        id = "test",
        embedderArchiveSha256 = "embedder-a",
        imagesSha256 = "",
        hostMargin = 0.7,
        minMargin = 0.35,
        hostNorm = 20.0,
        normBand = 2.0,
        faceHostMargin = 0.6,
        faceMinMargin = 0.3,
        hostReference = FloatArray(FaceModel.AuraFaceCoreMl.dimensions).also { it[0] = 20f },
        minHostCosine = 0.99,
    )

    private fun vector(length: Float, along: Float = 1f, axis: Int = 1): FloatArray = FloatArray(width).also {
        it[0] = length * along
        if (along != 1f) it[axis] = length * sqrt(1f - along * along)
    }

    private val profile = FaceModel.AuraFaceCoreMl.profile
    private val referenceTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.reference(), FaceProbeImages.SIDE, profile)
    private val brighterTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.brighter(), FaceProbeImages.SIDE, profile)
    private val shiftedTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.shifted(), FaceProbeImages.SIDE, profile)

    private var thermal = 0
    private var revision = FaceModel.VISION_LANDMARKS_REVISION
    private var detectCost: (call: Int) -> Duration = { 4.milliseconds }
    private var embedCost: Duration = 3.milliseconds
    private var detectFailure: Throwable? = null
    private val goodFace = FaceProbeFace(
        FaceBox(0.2f, 0.2f, 0.8f, 0.8f),
        FaceLandmarks(FacePoint(0.35f, 0.4f), FacePoint(0.65f, 0.4f), FacePoint(0.5f, 0.55f), FacePoint(0.38f, 0.7f), FacePoint(0.62f, 0.7f)),
    )
    private var probeFaces: List<FaceProbeFace> = listOf(goodFace)
    private val photo = FaceProbeImage(64, 64, IntArray(64 * 64) { (0xFF shl 24) or (it * 977 and 0xFFFFFF) })
    private val otherTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.other(), FaceProbeImages.SIDE, profile)
    private var faceAnswer: (tensor: FloatArray) -> FloatArray = { vector(20f, along = 0f, axis = 5) }
    private var answer: (tensor: FloatArray) -> FloatArray = { tensor ->
        when {
            tensor.contentEquals(referenceTensor) -> vector(20f)
            tensor.contentEquals(brighterTensor) -> vector(20f, along = 0.95f, axis = 1)
            tensor.contentEquals(shiftedTensor) -> vector(20f, along = 0.9f, axis = 2)
            tensor.contentEquals(otherTensor) -> vector(20f, along = 0.2f, axis = 3)
            else -> faceAnswer(tensor)
        }
    }
    private var openingEmbedder: () -> Unit = {}
    private var detections = 0
    private var open = 0
    private val opened = mutableListOf<String>()

    private val device = object : PreflightDevice {
        override fun thermalState(): Int = thermal
        override fun memoryWarnings(): Long = 0
        override fun footprintBytes(): Long? = null
        override fun availableBytes(): Long? = null
        override fun nowEpochMs(): Long = 1_000_000_000L
    }

    private val models = object : FacePreflightModels {
        override fun faceProbe(): FaceProbeImage = photo

        override fun openEmbedder(compiledPath: String): PreflightTower {
            openingEmbedder()
            open++
            opened += compiledPath
            return object : PreflightTower {
                override fun predict(floats: FloatArray): FloatArray {
                    time += embedCost
                    return answer(floats)
                }

                override fun predict(ids: IntArray): FloatArray = error("pixels only")

                override fun close() {
                    open--
                }
            }
        }

        override fun detect(argb: IntArray, width: Int, height: Int): FaceProbeDetection {
            assertTrue(argb.contentEquals(photo.argb) && width == photo.width && height == photo.height, "the detector was not shown the bundled face")
            time += detectCost(detections++)
            detectFailure?.let { throw it }
            return FaceProbeDetection(revision, probeFaces)
        }
    }

    private fun TestScope.preflight(readiness: AiReadiness): Preflight = Preflight.over(
        readiness = readiness,
        prober = FacePreflightProber(models, device, probes = { key -> probe.takeIf { it.isFor(key) } }, time = time),
        device = device,
        beforeLoad = {},
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun aFacePassMakesTheFacePairReadyAndClosesTheEmbedderFirst() = runTest {
        val graph = faces()
        val outcome = preflight(graph).foregrounded(++scene)

        val passed = assertIs<PreflightOutcome.Passed>(outcome)
        assertEquals(AiReadinessState.Ready, graph.current())
        assertEquals(faceModel, graph.serving())
        assertEquals(listOf(faceModel.path(ModelArtifactRole.FACE_EMBEDDER)), opened)
        assertEquals(0, open, "the probe left the embedder open")
        val measured = assertIs<FacePreflightMeasurement>(passed.measurement)
        assertEquals(FaceModel.VISION_LANDMARKS_REVISION, measured.detection?.revision)
        assertEquals(PreflightBudget.PREDICTIONS_PER_TOWER, measured.reference.size)
    }

    @Test
    fun theDetectorAndTheEmbedderAreTimedAfterAWarmUp() = runTest {
        // F1b: the first Vision request in a process took up to 104 ms; here a first request
        // over the whole budget, which only a warm-up keeps out of the judged median.
        detectCost = { call -> if (call == 0) (PreflightBudget.MAX_MEDIAN_PREDICT_MS * 2).milliseconds else 4.milliseconds }

        val passed = assertIs<PreflightOutcome.Passed>(preflight(faces()).foregrounded(++scene))
        val measured = passed.measurement as FacePreflightMeasurement
        assertTrue(measured.detectMs.all { it == 4.0 }, "the warm-up was judged: ${measured.detectMs}")
        assertEquals(PreflightBudget.MAX_MEDIAN_PREDICT_MS * 2, measured.detectWarmUpMs)
        assertTrue(measured.embedMs.all { it == 3.0 })
    }

    @Test
    fun aNonFiniteVectorIsAFailureNeverAPass() = runTest {
        answer = { FloatArray(width).also { it[0] = Float.NaN } }
        val graph = faces()

        val retry = assertIs<PreflightOutcome.Retry>(preflight(graph).foregrounded(++scene))
        assertIs<PreflightFinding.Failed>(retry.finding)
        assertEquals(1, retry.attempts.failed)
        assertEquals(AiReadinessState.AwaitingVerdict, graph.current())
        assertFalse(graph.serves())
    }

    @Test
    fun aZeroVectorIsAFailure() = runTest {
        answer = { FloatArray(width) }
        assertIs<PreflightFinding.Failed>(assertIs<PreflightOutcome.Retry>(preflight(faces()).foregrounded(++scene)).finding)
    }

    @Test
    fun anEmbedderThatDoesNotRankTheProbeImagesFails() = runTest {
        answer = { vector(20f) }
        val retry = assertIs<PreflightOutcome.Retry>(preflight(faces()).foregrounded(++scene))
        assertTrue((retry.finding as PreflightFinding.Failed).why.contains("ranked"), "${retry.finding}")
    }

    @Test
    fun aDetectorErrorOrAnotherRevisionIsAFailureNeverUnsuitable() = runTest {
        val graph = faces()
        detectFailure = IllegalStateException("Vision error 9")
        assertIs<PreflightFinding.Failed>(assertIs<PreflightOutcome.Retry>(preflight(graph).foregrounded(++scene)).finding)
        assertEquals(emptyList(), opened, "the embedder was loaded after the detector failed")

        detectFailure = null
        revision = 2
        time += 2.milliseconds
        val retry = preflight(graph).recompiled(++scene)
        assertTrue(retry is PreflightOutcome.Retry || retry is PreflightOutcome.InstallFailed, "$retry")
        assertEquals(AiReadinessState.AwaitingVerdict, graph.current())
    }

    @Test
    fun aDetectedFaceMustCarryFivePointsInsideItsBox() = runTest {
        val box = FaceBox(0.2f, 0.2f, 0.6f, 0.6f)
        val inside = FaceLandmarks(FacePoint(0.3f, 0.3f), FacePoint(0.5f, 0.3f), FacePoint(0.4f, 0.4f), FacePoint(0.32f, 0.55f), FacePoint(0.48f, 0.55f))
        assertNull(FacePreflightJudge.faceRejection(FaceProbeFace(box, inside)))
        assertTrue(FacePreflightJudge.faceRejection(FaceProbeFace(box, null))!!.contains("five points"))
        assertTrue(FacePreflightJudge.faceRejection(FaceProbeFace(box, inside.copy(noseTip = FacePoint(0.95f, 0.4f))))!!.contains("outside"))
        assertTrue(FacePreflightJudge.faceRejection(FaceProbeFace(box, inside.copy(leftEye = FacePoint(Float.NaN, 0.3f))))!!.contains("point"))

        probeFaces = listOf(FaceProbeFace(box, inside.copy(noseTip = FacePoint(0.95f, 0.4f))))
        assertIs<PreflightFinding.Failed>(assertIs<PreflightOutcome.Retry>(preflight(faces()).foregrounded(++scene)).finding)
    }

    @Test
    fun aFieldVectorTurnedAwayFromTheHostsFailsThoughItStillRanks() = runTest {
        // Every vector turned by the same 10°: the ranking and the norms are untouched, the space is not.
        val turn = { v: FloatArray -> FloatArray(v.size) { i -> when (i) { 0 -> v[0] * 0.985f - v[7] * 0.174f; 7 -> v[0] * 0.174f + v[7] * 0.985f; else -> v[i] } } }
        val plain = answer
        answer = { turn(plain(it)) }
        val retry = assertIs<PreflightOutcome.Retry>(preflight(faces()).foregrounded(++scene))
        assertTrue((retry.finding as PreflightFinding.Failed).why.contains("host"), "${retry.finding}")
    }

    @Test
    fun aDetectorThatFindsNoFaceInTheBundledFaceFails() = runTest {
        probeFaces = emptyList()
        val retry = assertIs<PreflightOutcome.Retry>(preflight(faces()).foregrounded(++scene))
        assertTrue((retry.finding as PreflightFinding.Failed).why.contains("no face"), "${retry.finding}")
    }

    @Test
    fun theFaceAndItsMirrorAreEmbeddedAndMustRankAboveThePattern() = runTest {
        val graph = faces()
        // A face embedded like the pattern: the face no longer sits closer to its mirror.
        faceAnswer = { vector(20f, along = 0.2f, axis = 3) }
        val retry = assertIs<PreflightOutcome.Retry>(preflight(graph).foregrounded(++scene))
        assertTrue((retry.finding as PreflightFinding.Failed).why.contains("mirror"), "${retry.finding}")

        faceAnswer = { vector(20f, along = 0f, axis = 5) }
        val passed = assertIs<PreflightOutcome.Passed>(preflight(graph).recompiled(++scene))
        val measured = passed.measurement as FacePreflightMeasurement
        assertTrue(measured.face != null && measured.faceMirrored != null, "the face pair was not embedded")
    }

    @Test
    fun threeSlowFaceProbesOnThreeForegroundsAreUnsuitableAndSearchIsUntouched() = runTest {
        assertEquals(AiReadinessState.Ready, siglip().recordVerdict(passed = true, probed = siglipKey))
        embedCost = (PreflightBudget.MAX_MEDIAN_PREDICT_MS + 1).milliseconds
        val graph = faces()
        val preflight = preflight(graph)

        repeat(PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE - 1) {
            assertIs<PreflightOutcome.Retry>(preflight.recompiled(++scene))
            assertIs<PreflightOutcome.Waiting>(preflight.foregrounded(scene), "a second slow probe in one foreground")
        }
        assertIs<PreflightOutcome.Unsuitable>(preflight.recompiled(++scene))
        assertEquals(AiReadinessState.Unsuitable, faces().fingerprintChecked())
        assertEquals(AiReadinessState.Ready, siglip().fingerprintChecked(), "a slow face phone stopped search")
    }

    @Test
    fun aHotPhonesSlowFaceProbeDecidesNothing() = runTest {
        thermal = PreflightBudget.THERMAL_SERIOUS
        embedCost = (PreflightBudget.MAX_MEDIAN_PREDICT_MS + 1).milliseconds
        val graph = faces()
        repeat(PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE + 1) {
            assertIs<PreflightFinding.Transient>(assertIs<PreflightOutcome.Retry>(preflight(graph).recompiled(++scene)).finding)
        }
        assertEquals(AiReadinessState.AwaitingVerdict, graph.current())
    }

    @Test
    fun twoFaceProbesPhotosDidNotSurviveAreUnsuitableWithOneTryAgainAndAPassResetsTheCount() = runTest {
        repeat(PreflightPolicy.INTERRUPTED_LOADS_FOR_UNSUITABLE) {
            val process = Process()
            openingEmbedder = { process.kill() }
            runCatching { preflight(faces(process)).recompiled(++scene) }
        }
        openingEmbedder = {}

        val relaunched = faces()
        assertEquals(AiReadinessState.Unsuitable, relaunched.fingerprintChecked())
        val standing = relaunched.standing.value as ReadinessStanding.For
        assertTrue(standing.stoppedLoading, "a fail of interrupted loads is not offered its Try again")
        assertTrue(relaunched.loadAgain(faceKey), "the one Try again was refused")
        assertFalse(relaunched.loadAgain(faceKey), "a second Try again on one key")

        assertIs<PreflightOutcome.Passed>(preflight(relaunched).recompiled(++scene))
        val attempts = PreflightJsonPeek(bytesOf(ReadinessSlot.FACES.verdictPath))
        assertEquals(0, attempts.interrupted, "a pass kept the interrupted count")
        assertEquals(1, attempts.loadedAgain, "a pass gave the spent press back")
    }

    @Test
    fun anInstallThisBuildHasNoFaceProbeForIsNeverProbed() = runTest {
        faceKey = faceKey(embedder = "embedder-b")
        val graph = faces()
        assertIs<PreflightOutcome.NoProbe>(preflight(graph).foregrounded(++scene))
        assertEquals(emptyList(), opened)
        assertEquals(AiReadinessState.AwaitingVerdict, graph.current())
    }

    @Test
    fun theShippedFaceProbeIsTheF2ExportsAndIsMeasured() {
        val record: FacePreflightProbe = FacePreflightProbes.AURAFACE_984E39D3D798
        assertEquals("d503f893c01153ae9fbc241973c797578b537d9dd4e6962d3326ec71a5c3b80c", record.embedderArchiveSha256)
        assertTrue(record.isFor(faceKey(embedder = record.embedderArchiveSha256)))
        assertFalse(record.isFor(faceKey(embedder = record.embedderArchiveSha256).let { it.copy(runtime = it.runtime.copy(textArchiveSha256 = "text-a")) }), "a key with a text tower matched the face probe")
        assertFalse(record.isFor(faceKey(embedder = record.embedderArchiveSha256).copy(vocabularySha256 = "vocabulary-a")), "a key with a vocabulary matched the face probe")
        assertTrue(record.hostMargin > 0 && record.minMargin > 0 && record.minMargin < record.hostMargin, "the record carries no measured margin")
        assertTrue(record.hostNorm > 0, "the record carries no measured norm")
        assertEquals(width, record.hostReference.size)
        assertTrue(record.hostReference.any { it != 0f }, "the record carries no host reference vector")
        assertTrue(record.faceHostMargin > 0 && record.faceMinMargin > 0 && record.faceMinMargin < record.faceHostMargin, "the record carries no measured face margin")
        assertEquals(FaceProbeImages.SHA256, record.imagesSha256)
    }

    @Test
    fun theProbeImagesAreTheOnesTheRecordWasMeasuredOn() {
        val digest: String = Sha256Digest().apply { FaceProbeImages.bytes().let { update(it, it.size) } }.digest()
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(FaceProbeImages.SHA256, digest)
    }

    @Test
    fun theHostReferenceIsTheRecordedVector() {
        val bytes = ByteArray(FacePreflightHostReference_984e39d3d798.VECTOR.size * 4)
        FacePreflightHostReference_984e39d3d798.VECTOR.forEachIndexed { i, v ->
            val bits = v.toRawBits()
            for (b in 0 until 4) bytes[4 * i + b] = (bits ushr (24 - 8 * b)).toByte()
        }
        val digest: String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(FacePreflightHostReference_984e39d3d798.SHA256, digest)
        assertTrue(FacePreflightProbes.AURAFACE_984E39D3D798.hostReference === FacePreflightHostReference_984e39d3d798.VECTOR)
        assertEquals(0.99, FacePreflightProbes.AURAFACE_984E39D3D798.minHostCosine)
    }

    /** The two counts a face verdict file holds, read the way a relaunch would. */
    private class PreflightJsonPeek(text: String?) {
        private fun count(name: String, text: String?): Int =
            text?.substringAfter("\"$name\":", "")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

        val interrupted: Int = count("interrupted", text)
        val loadedAgain: Int = count("loadedAgain", text)
    }
}
