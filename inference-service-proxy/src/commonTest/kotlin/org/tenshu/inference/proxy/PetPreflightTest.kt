package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Pets' preflight on iOS (`photos-pets.md` PT-4b2): the probe found by key, the output before the speed. */
class PetPreflightTest {

    private val probe = PetPreflightProbes.COREML_00C9CD86_ED3DF779

    private fun key(embedder: String = probe.embedderArchiveSha256, detector: String = probe.detectorArchiveSha256) = PreflightKey(
        runtime = RuntimeFingerprint(
            visionArchiveSha256 = petArchivePair(embedder, detector),
            computeUnits = "CPU_AND_NEURAL_ENGINE",
            osBuild = "os-1",
            preprocessingRevision = 1,
            detectorRevision = PixelPetBackend.PET_PIPELINE_VERSION,
        ),
        vocabularySha256 = null,
        appBuild = "1",
        preflightRevision = PET_PREFLIGHT_REVISION,
    )

    private val device = object : PreflightDevice {
        override fun thermalState(): Int = 0
        override fun memoryWarnings(): Long = 0
        override fun footprintBytes(): Long? = null
        override fun availableBytes(): Long? = null
        override fun nowEpochMs(): Long = 1_000_000_000L
    }

    private val context = object : PreflightRunContext {
        override fun isRetired(): Boolean = false
        override fun holdingSessions() {}
    }

    /** An embedding whose unit CLS half starts with [first8]; the patch mean is anything non-zero. */
    private fun embedding(first8: FloatArray): FloatArray {
        val known = first8.sumOf { it.toDouble() * it }
        val rest = sqrt((1.0 - known) / (PetEmbedderModel.TEMPLATE - 8)).toFloat()
        return FloatArray(PetEmbedderModel.WIDTH) { i -> if (i < 8) first8[i] else if (i < PetEmbedderModel.TEMPLATE) rest else 1f }
    }

    private fun dets(first8: FloatArray) = FloatArray(AnimalDetectorModel.QUERIES * 4) { if (it < 8) first8[it] else 0.5f }

    private inner class Models(
        val embedderOut: FloatArray = embedding(probe.embedderReference),
        val detsOut: FloatArray = dets(probe.detectorReferences[0]),
        val time: TestTimeSource? = null,
        val predictMs: Long = 5,
    ) : PetPreflightModels {
        var closed = 0
        override fun openEmbedder(compiledPath: String): PreflightTower = object : PreflightTower {
            override fun predict(floats: FloatArray): FloatArray = embedderOut.also { time?.plusAssign(predictMs.milliseconds) }
            override fun predict(ids: IntArray): FloatArray = error("pixels")
            override fun close() { closed++ }
        }
        override fun openDetector(compiledPath: String): PetPreflightDetector = object : PetPreflightDetector {
            override fun predictAll(floats: FloatArray): Map<String, FloatArray> =
                mapOf("dets" to detsOut, "labels" to FloatArray(AnimalDetectorModel.QUERIES * AnimalDetectorModel.CLASSES))
                    .also { time?.plusAssign(predictMs.milliseconds) }
            override fun close() { closed++ }
        }
    }

    private val target = PreparedModel(mapOf(ModelArtifactRole.ANIMAL_DETECTOR to "/d", ModelArtifactRole.PET_EMBEDDER to "/e"))

    private fun run(models: Models, time: TestTimeSource = TestTimeSource()): PreflightFinding {
        val prober = PetPreflightProber(models, device, time = time)
        val run = assertNotNull(prober.runFor(key()))
        return run.judge(assertNotNull(run.measure(target, context)))
    }

    @Test
    fun theProbeIsFoundOnlyForThePinnedPair() {
        val prober = PetPreflightProber(Models(), device)
        assertNotNull(prober.runFor(key()))
        assertNull(prober.runFor(key(embedder = "another")), "a new export has no probe, so no verdict")
        assertNull(prober.runFor(key(detector = "another")))
    }

    @Test
    fun theExportsOwnAnswersPass_onEitherComputePath_andBothSessionsClose() {
        val models = Models()
        assertIs<PreflightFinding.Passed>(run(models))
        assertEquals(2, models.closed, "a probe never leaves a session resident")
        assertIs<PreflightFinding.Passed>(run(Models(detsOut = dets(probe.detectorReferences[1]))), "the Neural Engine's answer")
    }

    /**
     * ⚠⚠ Measured on an iPhone 17e (`photos-pets.md` §10.18): its Neural Engine put one noise-box
     * coordinate 0.0185 from the Mac's. At the old ±0.01 that failed twice in a row, one probe short
     * of latching the install as failed for good.
     */
    @Test
    fun anotherChipsNeuralEngineDriftPasses() {
        val iPhone = floatArrayOf(0.4846191f, 0.4257813f, 0.9814453f, 0.7954102f, 0.4902344f, 0.7358398f, 0.9824219f, 0.4833984f)
        assertIs<PreflightFinding.Passed>(run(Models(detsOut = dets(iPhone))), "the iPhone's logged answer")
        // Another chip will not drift the same coordinate: one no reference covers, by the measured amount.
        val drifted = probe.detectorReferences[1].copyOf().also { it[5] += 0.0185f }
        assertIs<PreflightFinding.Passed>(run(Models(detsOut = dets(drifted))), "the Mac NE's answer, drifted as measured")
    }

    @Test
    fun anotherModelsNumbersFail_beforeAnyTiming() {
        val wrongEmbedder = probe.embedderReference.map { -it }.toFloatArray()
        assertIs<PreflightFinding.Failed>(run(Models(embedderOut = embedding(wrongEmbedder))))
        assertIs<PreflightFinding.Failed>(run(Models(detsOut = dets(FloatArray(8) { 0.1f }))))
        assertIs<PreflightFinding.Failed>(run(Models(detsOut = dets(FloatArray(8) { Float.NaN }))))
    }

    @Test
    fun aClsHalfWithNoLengthFails_neverPassesOnNaN() {
        val zeroCls = FloatArray(PetEmbedderModel.WIDTH) { if (it < PetEmbedderModel.TEMPLATE) 0f else 1f }
        assertIs<PreflightFinding.Failed>(run(Models(embedderOut = zeroCls)))
    }

    @Test
    fun memoryIsReadWithBothModelsOpen_andTooLittleIsNotAPass() {
        var open = 0
        var openAtReading = -1
        val tight = object : PreflightDevice {
            override fun thermalState(): Int = 0
            override fun memoryWarnings(): Long = 0
            override fun footprintBytes(): Long? = null
            override fun availableBytes(): Long? { openAtReading = open; return 100L * 1024 * 1024 }
            override fun nowEpochMs(): Long = 1_000_000_000L
        }
        val models = object : PetPreflightModels {
            val inner = Models()
            override fun openEmbedder(compiledPath: String): PreflightTower {
                open++
                val tower = inner.openEmbedder(compiledPath)
                return object : PreflightTower by tower { override fun close() { open--; tower.close() } }
            }
            override fun openDetector(compiledPath: String): PetPreflightDetector {
                open++
                val detector = inner.openDetector(compiledPath)
                return object : PetPreflightDetector by detector { override fun close() { open--; detector.close() } }
            }
        }
        val run = assertNotNull(PetPreflightProber(models, tight).runFor(key()))
        val finding = run.judge(assertNotNull(run.measure(target, context)))
        assertEquals(2, openAtReading, "read with the detector AND the embedder resident")
        assertEquals(0, open, "both closed before the verdict")
        assertTrue(finding is PreflightFinding.Slow, "$finding")
    }

    @Test
    fun rightButSlowIsSlow() {
        val time = TestTimeSource()
        val finding = run(Models(time = time, predictMs = 900), time)
        assertTrue(finding is PreflightFinding.Slow, "$finding")
    }

    @Test
    fun anInstallMissingAModelFailsWithoutOpeningAnything() {
        val models = Models()
        val prober = PetPreflightProber(models, device)
        val run = assertNotNull(prober.runFor(key()))
        val measured = assertNotNull(run.measure(PreparedModel(mapOf(ModelArtifactRole.PET_EMBEDDER to "/e")), context))
        assertIs<PreflightFinding.Failed>(run.judge(measured))
        assertEquals(0, models.closed)
    }

    @Test
    fun thePetsSlotHasItsOwnFiles() {
        val others = listOf(ReadinessSlot.SIGLIP, ReadinessSlot.FACES)
        val pets = ReadinessSlot.PETS
        others.forEach { slot ->
            assertTrue(slot.verdictPath != pets.verdictPath && slot.scenePath != pets.scenePath && slot.fingerprintPath != pets.fingerprintPath)
        }
        assertTrue(pets in ReadinessSlot.ALL)
    }
}
