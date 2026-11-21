package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import org.tenshu.inference.proxy.onnx.TowerGuards
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Pets' half of a [Preflight] on iOS (`photos-pets.md` PT-4b2): both Core ML models, opened outside the
 * backend, run on fixed tensors and judged against what Core ML answered for the very archives on the
 * build Mac — the face preflight's shape (`FacePreflight.kt`), for two models.
 *
 * ⚠⚠ Readiness only reaches `Ready` after a verdict, and an install whose key has no probe gets none: a
 * new export needs its own [PetPreflightProbe], recorded by `CoreMlPetModelsRealTest`'s references.
 */
const val PET_PREFLIGHT_REVISION: Int = 1

/**
 * The two archives as one identity, in the slot of a fingerprint's vision archive: a new export of
 * EITHER model is another key, so `pets.log` starts over (`FaceTemplateCustody.vectorsSurvive`).
 * `null` until both are installed.
 */
fun petArchivePair(embedderSha256: String?, detectorSha256: String?): String? =
    if (embedderSha256 == null || detectorSha256 == null) null else "$embedderSha256+$detectorSha256"

/**
 * One measured export pair.
 *
 * @param embedderReference the first eight values of the unit-length CLS half for [PetProbeTensors.embedder].
 * @param detectorReferences the first eight `dets` for [PetProbeTensors.detector], one per Core ML compute
 *   path: ⚠ on noise no query is an animal, and the CPU and Neural Engine paths place those meaningless
 *   boxes up to 0.07 apart (§10.14). Matching either proves the model; a wrong one matches neither.
 */
class PetPreflightProbe(
    val id: String,
    val embedderArchiveSha256: String,
    val detectorArchiveSha256: String,
    val embedderReference: FloatArray,
    val embedderTolerance: Float,
    val detectorReferences: List<FloatArray>,
    val detectorTolerance: Float,
) {
    fun isFor(key: PreflightKey): Boolean =
        key.runtime.visionArchiveSha256 == petArchivePair(embedderArchiveSha256, detectorArchiveSha256) &&
            key.runtime.textArchiveSha256 == null &&
            key.vocabularySha256 == null
}

/** Which pets probe, if any, was measured on the export pair a key names. */
fun interface PetPreflightProbeLookup {
    fun forKey(key: PreflightKey): PetPreflightProbe?
}

/** The pets probes this build carries: one per export pair a phone may still hold. */
internal object PetPreflightProbes {
    /** `export-coreml-pets.py`, 2026-11-19, Float32 IO (§10.13). [M] coremltools on the M4 Pro build Mac. */
    val COREML_00C9CD86_ED3DF779 = PetPreflightProbe(
        id = "00c9cd86-ed3df779",
        embedderArchiveSha256 = PetEmbedderModel.DinoV2SmallZeroShotCoreMl.sha256,
        detectorArchiveSha256 = AnimalDetectorModel.RfDetrNanoCoreMl.sha256,
        embedderReference = floatArrayOf(-0.04122f, 0.02591f, 0.01230f, 0.00085f, 0.03360f, -0.01969f, 0.05529f, -0.02652f),
        embedderTolerance = 0.004f,
        detectorReferences = listOf(
            floatArrayOf(0.49731f, 0.35913f, 1.01465f, 0.70166f, 0.49780f, 0.73682f, 1.00879f, 0.48315f),
            floatArrayOf(0.48438f, 0.41772f, 0.98340f, 0.77686f, 0.49023f, 0.73535f, 0.98242f, 0.48340f),
            // [M] iPhone 17e's Neural Engine, twice, identical (`photos-pets.md` §10.18): the same detection
            // as the M4 Pro's NE row above, one coordinate 0.0185 away from it.
            floatArrayOf(0.48462f, 0.42578f, 0.98145f, 0.79541f, 0.49023f, 0.73584f, 0.98242f, 0.48340f),
        ),
        // ⚠⚠ Not 0.01: that failed a real iPhone, whose Neural Engine places the meaningless noise boxes
        // 0.0185 from the Mac's — and three failed probes latch the install as failed for good. 0.03 covers
        // chip-to-chip drift and still sits far inside the 0.07 between compute paths; the archive's
        // SHA-256 is what proves the model's identity, this proves the compute path answers sanely.
        detectorTolerance = 0.03f,
    )

    val records: List<PetPreflightProbe> = listOf(COREML_00C9CD86_ED3DF779)

    fun forKey(key: PreflightKey): PetPreflightProbe? = records.firstOrNull { it.isFor(key) }
}

/** The fixed inputs: `sin(0.001 i) × 1.5`, CHW, the tensors `CoreMlPetModelsRealTest` pins. */
object PetProbeTensors {
    fun embedder(): FloatArray = of(PetEmbedderModel.SIDE)

    fun detector(): FloatArray = of(AnimalDetectorModel.SIDE)

    private fun of(side: Int): FloatArray = FloatArray(3 * side * side) { (sin(it * 0.001) * 1.5).toFloat() }
}

/** The detector as the preflight opens it: every output by name. */
interface PetPreflightDetector {
    fun predictAll(floats: FloatArray): Map<String, FloatArray>

    fun close()
}

/** What the pets preflight opens **outside the backend**, so it runs while nothing is claimed. */
interface PetPreflightModels {
    /** @throws Throwable when the model will not load, or loads and is not the pet embedder. */
    fun openEmbedder(compiledPath: String): PreflightTower

    /** @throws Throwable when the model will not load, or loads and is not RF-DETR. */
    fun openDetector(compiledPath: String): PetPreflightDetector
}

/** One pets probe's measurements. */
class PetPreflightMeasurement(
    thermalAtStart: Int,
    memoryWarned: Boolean,
    failure: String? = null,
    val embeddings: List<FloatArray> = emptyList(),
    val dets: List<FloatArray> = emptyList(),
    val embedMs: List<Double> = emptyList(),
    val detectMs: List<Double> = emptyList(),
    val embedderOpenMs: Double = 0.0,
    val detectorOpenMs: Double = 0.0,
    footprintBeforeBytes: Long? = null,
    footprintBytes: Long? = null,
    availableBytes: Long? = null,
    thermalAtEnd: Int = thermalAtStart,
) : PreflightMeasurement(
    thermalAtStart = thermalAtStart,
    memoryWarned = memoryWarned,
    failure = failure,
    footprintBeforeBytes = footprintBeforeBytes,
    footprintBytes = footprintBytes,
    availableBytes = availableBytes,
    thermalAtEnd = thermalAtEnd,
) {
    override fun summary(): String =
        "pets thermal $thermalAtStart->$thermalAtEnd warned=$memoryWarned open=${detectorOpenMs}/${embedderOpenMs}ms " +
            "predict=${PreflightJudge.median(detectMs)}/${PreflightJudge.median(embedMs)}ms footprint=$footprintBeforeBytes->$footprintBytes"
}

/**
 * The detector, then the embedder beside it — each opened, warmed once, predicted
 * [PreflightBudget.PREDICTIONS_PER_TOWER] times; memory read with both resident; both **closed before the
 * verdict** (the face prober's rule: a probe never leaves a session resident).
 */
class PetPreflightProber(
    private val models: PetPreflightModels,
    private val device: PreflightDevice,
    private val probes: PetPreflightProbeLookup = PetPreflightProbeLookup { key -> PetPreflightProbes.forKey(key) },
    private val time: TimeSource = TimeSource.Monotonic,
) : PreflightProber {

    override fun runFor(key: PreflightKey): PreflightRun? = probes.forKey(key)?.let { Run(it) }

    private inner class Run(private val probe: PetPreflightProbe) : PreflightRun {

        override fun judge(measurement: PreflightMeasurement): PreflightFinding = when (measurement) {
            is PetPreflightMeasurement -> PetPreflightJudge.judge(measurement, probe)
            else -> PreflightFinding.Failed("not a pets measurement")
        }

        override fun measure(target: PreparedModel, context: PreflightRunContext): PreflightMeasurement? {
            val thermalAtStart: Int = device.thermalState()
            val warningsAtStart: Long = device.memoryWarnings()
            val footprintBefore: Long? = device.footprintBytes()
            fun failed(why: String) = PetPreflightMeasurement(
                thermalAtStart = thermalAtStart,
                memoryWarned = device.memoryWarnings() != warningsAtStart,
                failure = why,
                thermalAtEnd = device.thermalState(),
            )
            val detectorPath: String = target.paths[ModelArtifactRole.ANIMAL_DETECTOR] ?: return failed("the install has no detector")
            val embedderPath: String = target.paths[ModelArtifactRole.PET_EMBEDDER] ?: return failed("the install has no pet embedder")
            if (context.isRetired()) return null
            context.holdingSessions()

            var mark: TimeMark = time.markNow()
            val detector: PetPreflightDetector = runCatching { models.openDetector(detectorPath) }
                .getOrElse { return failed("the detector did not load: $it") }
            val detectorOpenMs: Double = mark.elapsedMs()
            // ⚠⚠ Both models stay open together, as `CoreMlPetBackend` holds them in a pass: memory is
            // read with BOTH resident, never one after the other (a phone that fits each alone but not
            // the pair would otherwise pass).
            var embedder: PreflightTower? = null
            try {
                val dets = ArrayList<FloatArray>()
                val detectMs = ArrayList<Double>()
                try {
                    val input: FloatArray = PetProbeTensors.detector()
                    detector.predictAll(input)
                    repeat(PreflightBudget.PREDICTIONS_PER_TOWER) {
                        if (context.isRetired()) return null
                        mark = time.markNow()
                        dets += detector.predictAll(input)["dets"] ?: return failed("the detector answered no 'dets'")
                        detectMs += mark.elapsedMs()
                    }
                } catch (failure: Throwable) {
                    return failed("a detector prediction failed: $failure")
                }

                if (context.isRetired()) return null
                mark = time.markNow()
                val opened: PreflightTower = runCatching { models.openEmbedder(embedderPath) }
                    .getOrElse { return failed("the pet embedder did not load: $it") }
                embedder = opened
                val embedderOpenMs: Double = mark.elapsedMs()
                val embeddings = ArrayList<FloatArray>()
                val embedMs = ArrayList<Double>()
                try {
                    val input: FloatArray = PetProbeTensors.embedder()
                    opened.predict(input)
                    repeat(PreflightBudget.PREDICTIONS_PER_TOWER) {
                        if (context.isRetired()) return null
                        mark = time.markNow()
                        embeddings += opened.predict(input)
                        embedMs += mark.elapsedMs()
                    }
                    // One more detection with the embedder resident: the pair's own working set.
                    detector.predictAll(PetProbeTensors.detector())
                } catch (failure: Throwable) {
                    return failed("a prediction with both models open failed: $failure")
                }
                return PetPreflightMeasurement(
                    thermalAtStart = thermalAtStart,
                    memoryWarned = device.memoryWarnings() != warningsAtStart,
                    embeddings = embeddings,
                    dets = dets,
                    embedMs = embedMs,
                    detectMs = detectMs,
                    embedderOpenMs = embedderOpenMs,
                    detectorOpenMs = detectorOpenMs,
                    footprintBeforeBytes = footprintBefore,
                    // ⚠ Read while both are still open.
                    footprintBytes = device.footprintBytes(),
                    availableBytes = device.availableBytes(),
                    thermalAtEnd = device.thermalState(),
                )
            } finally {
                runCatching { embedder?.close() }
                runCatching { detector.close() }
            }
        }
    }
}

/** The rule, as arithmetic over a [PetPreflightMeasurement]: the output first, then the speed. */
internal object PetPreflightJudge {

    fun judge(measurement: PetPreflightMeasurement, probe: PetPreflightProbe): PreflightFinding =
        PreflightJudge.conditioned(measurement, unconditioned(measurement, probe))

    private fun unconditioned(measurement: PetPreflightMeasurement, probe: PetPreflightProbe): PreflightFinding {
        measurement.failure?.let { return PreflightFinding.Failed(it) }
        if (measurement.embeddings.isEmpty() || measurement.dets.isEmpty()) return PreflightFinding.Failed("no output")
        // ⚠⚠ The output before the speed: a model that answers quickly with the wrong numbers is not slow.
        for (vector in measurement.embeddings) {
            TowerGuards.unusableReason(vector, PetEmbedderModel.WIDTH)?.let { return PreflightFinding.Failed("embedder: $it") }
            val cls = vector.copyOf(PetEmbedderModel.TEMPLATE)
            val norm = sqrt(cls.sumOf { it.toDouble() * it })
            // ⚠⚠ A CLS half with no length normalises to NaN, and a NaN comparison never fails: the
            // backend refuses this same output (`CoreMlPetBackend.template`), so the probe must too.
            if (!norm.isFinite() || norm == 0.0) return PreflightFinding.Failed("embedder: the CLS half has no direction")
            probe.embedderReference.forEachIndexed { i, want ->
                val got = (cls[i] / norm).toFloat()
                if (!(abs(got - want) <= probe.embedderTolerance)) {
                    return PreflightFinding.Failed("embedder: CLS[$i] is $got, the export's is $want")
                }
            }
        }
        for (dets in measurement.dets) {
            if (dets.any { !it.isFinite() }) return PreflightFinding.Failed("detector: a non-finite box")
            val matches = probe.detectorReferences.any { reference ->
                reference.indices.all { abs(dets[it] - reference[it]) <= probe.detectorTolerance }
            }
            if (!matches) return PreflightFinding.Failed("detector: dets ${dets.take(8)} match no measured compute path")
        }
        val slowest: Double = maxOf(PreflightJudge.median(measurement.detectMs), PreflightJudge.median(measurement.embedMs))
        if (slowest > PreflightBudget.MAX_MEDIAN_PREDICT_MS) {
            return PreflightFinding.Slow("a median prediction of $slowest ms, over ${PreflightBudget.MAX_MEDIAN_PREDICT_MS}")
        }
        // ⚠ Measured with both models resident; unknown is not judged, as for SigLIP and faces.
        val available: Long? = measurement.availableBytes
        if (available != null && available < PreflightBudget.PLACEHOLDER_MIN_AVAILABLE_BYTES) {
            return PreflightFinding.Slow("$available bytes left with both pet models open")
        }
        return PreflightFinding.Passed(margin = PreflightBudget.MAX_MEDIAN_PREDICT_MS / slowest.coerceAtLeast(1.0))
    }
}

private fun TimeMark.elapsedMs(): Double = elapsedNow().inWholeMicroseconds / 1000.0
