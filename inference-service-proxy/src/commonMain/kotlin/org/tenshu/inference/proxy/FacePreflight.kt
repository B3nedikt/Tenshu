package org.tenshu.inference.proxy

import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.TowerGuards

/**
 * The face preflight and the code a passing face verdict vouches for, as a number: the face key's
 * [PreflightKey.preflightRevision] (`ios-face-grouping.md` §6.2). Its own constant, never
 * [PREFLIGHT_REVISION]: a change to SigLIP's probe must not void a face pass, nor the reverse.
 *
 * ⚠⚠ **Bump it by hand, in the same change, whenever any of these changes what a face becomes or
 * what is accepted as a pass:** the face backend's guards and how it feeds the embedder
 * (`VisionCoreMlFaceBackend`, the face [CoreMlTowerSpec]); the probe images ([FaceProbeImages]),
 * the judge ([FacePreflightJudge]), its budget, and the content of an existing [FacePreflightProbe].
 * ⚠ Not for the detection pipeline (`FaceModel.VISION_PIPELINE_VERSION`, the stamp that re-indexes)
 * nor the decode (`IOS_FACE_PREPROCESSING_REVISION`): those are in the key already.
 *
 * Revisions: 1 is the first face preflight.
 */
const val FACE_PREFLIGHT_REVISION: Int = 1

/**
 * The fixed check of **one face-embedder export** (`ios-face-grouping.md` §6.2, FD12).
 *
 * ⚠⚠ **One real face, and only a licensed one** (FD12): never Vec2Face output, never a real person
 * without a recorded CC0 release. The build carries exactly one: a CC0 self-portrait from Wikimedia
 * Commons, provenance in §14.2. Three rules:
 * - **detector:** Vision, at the pinned revision, finds **at least one face** in it, each with five
 *   finite points inside its box;
 * - **embedder, on the face:** the aligned face sits closer to its own mirror image than to an
 *   unrelated pattern, by [faceMinMargin];
 * - **embedder, with no face:** the two-augmentation self-consistency rule — a fixed field under each
 *   of two fixed augmentations sits closer to it than an unrelated pattern does, by [minMargin].
 *
 * ⚠⚠ **Bound to the bytes it was measured on**: [embedderArchiveSha256]. Another export has no
 * probe, and an install with no probe gets no verdict.
 *
 * ⚠ **Host figures**: [hostMargin] and [hostNorm] were measured on the build Mac's iOS simulator
 * through `CoreMlSession` (`VisionCoreMlFaceRealTest`). [minMargin] is half the host
 * margin, SigLIP's allowance for what a phone's Neural Engine does differently; [faceMinMargin]
 * likewise of [faceHostMargin].
 *
 * ⚠⚠ **And "correct", not only "not broken"** ([hostReference]): the phone's vector for the field
 * must point where the host's did, `cos ≥` [minHostCosine]. A numerics drift (another Neural Engine
 * compiler, an fp16 path) that keeps vectors smooth but turns them passes every ranking and norm
 * above, and still moves every cosine clustering compares with 0.44. Only the Vision-free field is
 * pinned: a face's crop follows Vision's points, which differ by platform (§11.3: end-to-end phone
 * vs Mac cosine down to 0.856), so a pinned face vector would fail honest phones.
 *
 * @param hostReference the host's raw vector for [FaceProbeImages.reference], all 512 values.
 * @param minHostCosine F1b measured the same crop on the phone and the Mac at cos ≥ 0.999897 (fp16),
 *   and the phone's own `.cpuOnly` against `.cpuAndNeuralEngine` at ≥ 0.99944 (§11.3); 0.99 leaves
 *   ~10× that spread, and is still a turn of 8° that no compute-unit difference has shown.
 * @param imagesSha256 the probe images' pixels, pinned by `FaceReadinessTest.theProbeImagesAreTheOnesTheRecordWasMeasuredOn`.
 * @param normBand a raw vector's norm must be within this factor of [hostNorm], either way.
 */
class FacePreflightProbe(
    val id: String,
    val embedderArchiveSha256: String,
    val imagesSha256: String,
    val hostMargin: Double,
    val minMargin: Double,
    val hostNorm: Double,
    val normBand: Double,
    val faceHostMargin: Double,
    val faceMinMargin: Double,
    val hostReference: FloatArray,
    val minHostCosine: Double,
) {
    /** ⚠ A face key has no text tower and no vocabulary; a key that names either is not a face key. */
    fun isFor(key: PreflightKey): Boolean =
        key.runtime.visionArchiveSha256 == embedderArchiveSha256 &&
            key.runtime.textArchiveSha256 == null &&
            key.vocabularySha256 == null
}

/** Which face probe, if any, was measured on the export a key names. */
fun interface FacePreflightProbeLookup {
    fun forKey(key: PreflightKey): FacePreflightProbe?
}

/** The face probes this build carries: one per export a phone may still hold. */
internal object FacePreflightProbes {
    /**
     * The `auraface-v1-int8` Core ML export, manifest `984e39d3…fd87f` (`ios-face-grouping.md`
     * §12.1). ⚠ Host figures: the simulator on the build Mac (§14).
     */
    val AURAFACE_984E39D3D798 = FacePreflightProbe(
        id = "984e39d3d798",
        embedderArchiveSha256 = "d503f893c01153ae9fbc241973c797578b537d9dd4e6962d3326ec71a5c3b80c",
        imagesSha256 = FaceProbeImages.SHA256,
        // [M] 2026-11-10, iOS 26 simulator on the M4 Pro build Mac: cos(reference, brighter) 0.98186,
        // (reference, shifted) 0.99227, (reference, other) 0.20292; raw norms 8.763 / 8.632 / 8.707 / 10.193.
        hostMargin = 0.7789369046359343,
        minMargin = 0.389,
        hostNorm = 8.763407537050083,
        normBand = 1.5,
        // [M] the bundled face aligned on macOS Vision's points (the simulator's landmarks are not the
        // face's, §14.4): cos(face, mirror) 0.85539, cos(face, other) 0.00156; raw norm 23.76.
        faceHostMargin = 0.8538287277234827,
        faceMinMargin = 0.427,
        hostReference = FacePreflightHostReference_984e39d3d798.VECTOR,
        minHostCosine = 0.99,
    )

    val records: List<FacePreflightProbe> = listOf(AURAFACE_984E39D3D798)

    fun forKey(key: PreflightKey): FacePreflightProbe? = records.firstOrNull { it.isFor(key) }
}

/**
 * The face probe's images, drawn in integer arithmetic so every platform makes the same pixels,
 * with no file, decoder or resampler in between (as [PreflightProbeImage] is for SigLIP).
 *
 * ⚠⚠ **None is a face** (FD12). [reference] is a smooth colour field; [brighter] and [shifted] are
 * it under two fixed augmentations; [other] is an unrelated hard-edged pattern. The detector looks at
 * the bundled face ([FacePreflightModels.faceProbe]), not at these.
 * ⚠ Changing any of them is a new probe: bump [FACE_PREFLIGHT_REVISION] and re-measure the record.
 */
object FaceProbeImages {
    /** The embedder's input side: the ArcFace template's. */
    const val SIDE: Int = 112

    /** sha256 of [reference], [brighter], [shifted] and [other], in that order, as big-endian ARGB. */
    const val SHA256: String = "851dc40b79ebf2476a15f87509021ebf786703179217e4458e7955cb6e55d2a5"

    private const val BRIGHTER_BY: Int = 12
    private const val SHIFTED_BY: Int = 3

    /** A smooth field: red along x, green along y, blue along the diagonal, over a [side]² square. */
    private fun field(x: Int, y: Int, side: Int): Int {
        val last: Int = side - 1
        val red: Int = 32 + x * 191 / last
        val green: Int = 48 + y * 175 / last
        val blue: Int = 200 - (x + y) * 160 / (2 * last)
        return argb(red, green, blue)
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    /** Row-major packed ARGB, [SIDE]² entries. */
    fun reference(): IntArray = IntArray(SIDE * SIDE) { field(it % SIDE, it / SIDE, SIDE) }

    /** [reference], every channel [BRIGHTER_BY] levels up, clamped. */
    fun brighter(): IntArray = reference().map { pixel ->
        argb(((pixel shr 16) and 0xFF) + BRIGHTER_BY, ((pixel shr 8) and 0xFF) + BRIGHTER_BY, (pixel and 0xFF) + BRIGHTER_BY)
    }.toIntArray()

    /** [reference] moved [SHIFTED_BY] pixels right, the left edge repeated. */
    fun shifted(): IntArray = IntArray(SIDE * SIDE) { field(maxOf(0, it % SIDE - SHIFTED_BY), it / SIDE, SIDE) }

    /** 14-px squares in two greys, crossed by a dark diagonal band: nothing like [reference]. */
    fun other(): IntArray = IntArray(SIDE * SIDE) { index ->
        val x: Int = index % SIDE
        val y: Int = index / SIDE
        when {
            kotlin.math.abs(x - y) < 6 -> argb(20, 20, 30)
            (x / 14 + y / 14) % 2 == 0 -> argb(220, 220, 210)
            else -> argb(60, 60, 70)
        }
    }

    /** Every image, in [SHA256]'s order, as bytes. */
    fun bytes(): ByteArray {
        val all: List<IntArray> = listOf(reference(), brighter(), shifted(), other())
        val out = ByteArray(all.sumOf { it.size } * 4)
        var at = 0
        for (image in all) for (pixel in image) {
            out[at++] = (pixel ushr 24).toByte()
            out[at++] = (pixel ushr 16).toByte()
            out[at++] = (pixel ushr 8).toByte()
            out[at++] = pixel.toByte()
        }
        return out
    }
}

/** One face the detector reported in a probe image: its box and, when it could be mapped, its five points. */
class FaceProbeFace(val box: FaceBox, val landmarks: FaceLandmarks?)

/** What the detector made of a probe image, and at which revision it ran. */
class FaceProbeDetection(val revision: Int, val faces: List<FaceProbeFace>)

/** The bundled face, decoded upright: row-major packed ARGB, `width × height`. */
class FaceProbeImage(val width: Int, val height: Int, val argb: IntArray)

/** What the face preflight opens **outside the backend registry**, so it runs while nothing is claimed. */
interface FacePreflightModels {
    /**
     * The one face this build carries (§14.2), decoded as the backend decodes a photograph.
     * @throws Throwable when it does not decode: the build is broken, not the phone.
     */
    fun faceProbe(): FaceProbeImage

    /** @throws Throwable when the embedder will not load, or loads and is not the embedder. */
    fun openEmbedder(compiledPath: String): PreflightTower

    /**
     * The detector over [argb], `width × height` packed ARGB, at the pinned revision, as the
     * backend runs it. @throws Throwable on a detector error.
     */
    fun detect(argb: IntArray, width: Int, height: Int): FaceProbeDetection
}

/**
 * Everything one face probe observed. The judge reads nothing else.
 *
 * @param reference the embedder's raw vectors for [FaceProbeImages.reference], one per timed prediction.
 */
class FacePreflightMeasurement(
    thermalAtStart: Int,
    memoryWarned: Boolean,
    failure: String? = null,
    val detection: FaceProbeDetection? = null,
    val reference: List<FloatArray> = emptyList(),
    val brighter: FloatArray? = null,
    val shifted: FloatArray? = null,
    val other: FloatArray? = null,
    /** The bundled face, aligned on Vision's largest face and embedded; `null` when Vision found none. */
    val face: FloatArray? = null,
    /** The same aligned crop, mirrored left to right, embedded. */
    val faceMirrored: FloatArray? = null,
    /** Vision per request, **after** one warm-up request (F1b: the first in a process is slow). */
    val detectMs: List<Double> = emptyList(),
    val detectWarmUpMs: Double = 0.0,
    /** The embedder per prediction, after one warm-up prediction. */
    val embedMs: List<Double> = emptyList(),
    val embedderOpenMs: Double = 0.0,
    val embedWarmUpMs: Double = 0.0,
    availableBytes: Long? = null,
    footprintBytes: Long? = null,
    footprintBeforeBytes: Long? = null,
    thermalAtEnd: Int = thermalAtStart,
) : PreflightMeasurement(
    thermalAtStart = thermalAtStart,
    memoryWarned = memoryWarned,
    failure = failure,
    availableBytes = availableBytes,
    footprintBytes = footprintBytes,
    footprintBeforeBytes = footprintBeforeBytes,
    thermalAtEnd = thermalAtEnd,
) {
    override fun summary(): String =
        "thermal $thermalAtStart->$thermalAtEnd warned=$memoryWarned revision=${detection?.revision} faces=${detection?.faces?.size} " +
            "detect=${PreflightJudge.median(detectMs)}ms (warm-up $detectWarmUpMs) open=${embedderOpenMs}ms " +
            "embed=${PreflightJudge.median(embedMs)}ms (warm-up $embedWarmUpMs) footprint=$footprintBeforeBytes->$footprintBytes available=$availableBytes"
}

/** The face probe's rule (§6.2), as arithmetic over a [FacePreflightMeasurement]. */
internal object FacePreflightJudge {

    /**
     * How far outside its box, in box sizes, a landmark may sit and still be the box's face. ⚠ Vision's
     * boxes are tight (they leave out the chin), and the mouth corners sit on the box's lower edge.
     */
    const val LANDMARK_BOX_SLACK: Float = 0.25f

    fun judge(measurement: FacePreflightMeasurement, probe: FacePreflightProbe, width: Int = FaceModel.AuraFaceCoreMl.dimensions): PreflightFinding =
        PreflightJudge.conditioned(measurement, unconditioned(measurement, probe, width))

    private fun unconditioned(measurement: FacePreflightMeasurement, probe: FacePreflightProbe, width: Int): PreflightFinding {
        measurement.failure?.let { return PreflightFinding.Failed(it) }

        // ⚠⚠ The detector ran, at the revision every template is stamped with (FD9).
        val detection: FaceProbeDetection = measurement.detection ?: return PreflightFinding.Failed("the detector reported nothing")
        if (detection.revision != FaceModel.VISION_LANDMARKS_REVISION) {
            return PreflightFinding.Failed("the detector ran at revision ${detection.revision}, not ${FaceModel.VISION_LANDMARKS_REVISION}")
        }
        // ⚠⚠ The bundled face is a face: a detector that finds none there finds none anywhere (§6.2).
        if (detection.faces.isEmpty()) return PreflightFinding.Failed("the detector found no face in the face probe")
        for (face in detection.faces) faceRejection(face)?.let { return PreflightFinding.Failed("the detector's face: $it") }

        // ⚠⚠ The output before the speed: an embedder that answers quickly with NaN is not slow.
        val singles: List<Pair<String, FloatArray?>> = listOf(
            "brighter" to measurement.brighter,
            "shifted" to measurement.shifted,
            "other" to measurement.other,
            "face" to measurement.face,
            "mirrored face" to measurement.faceMirrored,
        )
        if (measurement.reference.isEmpty()) return PreflightFinding.Failed("reference: no vector")
        for ((name, vector) in measurement.reference.map { "reference" to it } + singles) {
            if (vector == null) return PreflightFinding.Failed("$name: no vector")
            TowerGuards.unusableReason(vector, width)?.let { return PreflightFinding.Failed("$name: $it") }
        }
        // ⚠⚠ Where the field's vector points, against the host's (see [FacePreflightProbe.hostReference]).
        if (probe.hostReference.size != width) return PreflightFinding.Failed("the record's host reference is ${probe.hostReference.size} wide")
        for (vector in measurement.reference) {
            val agreement: Double = PreflightJudge.cosine(vector, probe.hostReference)
            if (!(agreement >= probe.minHostCosine)) {
                return PreflightFinding.Failed("the field's vector is at cos $agreement to the host's; at least ${probe.minHostCosine} is required")
            }
        }
        // ⚠ The norm is the field's: a face's depends on Vision's crop, which differs by platform (§11.3).
        for (vector in measurement.reference) {
            val norm: Double = PreflightJudge.norm(vector)
            if (norm < probe.hostNorm / probe.normBand || norm > probe.hostNorm * probe.normBand) {
                return PreflightFinding.Failed("reference: a norm of $norm, the export's is ${probe.hostNorm}")
            }
        }
        val brighter: FloatArray = measurement.brighter!!
        val shifted: FloatArray = measurement.shifted!!
        val other: FloatArray = measurement.other!!
        // ⚠⚠ Every prediction's vector, on its worst augmentation.
        var margin: Double = Double.POSITIVE_INFINITY
        for (reference in measurement.reference) {
            val same: Double = minOf(PreflightJudge.cosine(reference, brighter), PreflightJudge.cosine(reference, shifted))
            margin = minOf(margin, same - PreflightJudge.cosine(reference, other))
        }
        if (margin < probe.minMargin) {
            return PreflightFinding.Failed(
                "the probe images are ranked by $margin, the export's margin is ${probe.hostMargin} and at least ${probe.minMargin} is required",
            )
        }
        val face: FloatArray = measurement.face!!
        val faceMargin: Double = PreflightJudge.cosine(face, measurement.faceMirrored!!) - PreflightJudge.cosine(face, other)
        if (faceMargin < probe.faceMinMargin) {
            return PreflightFinding.Failed(
                "the face is ranked by $faceMargin against its mirror, the export's is ${probe.faceHostMargin} and at least ${probe.faceMinMargin} is required",
            )
        }

        val detect: Double = PreflightJudge.median(measurement.detectMs)
        if (detect > PreflightBudget.MAX_MEDIAN_PREDICT_MS) return PreflightFinding.Slow("the detector runs in $detect ms")
        val embed: Double = PreflightJudge.median(measurement.embedMs)
        if (embed > PreflightBudget.MAX_MEDIAN_PREDICT_MS) return PreflightFinding.Slow("the embedder predicts in $embed ms")
        // ⚠ Unknown is not judged, as for SigLIP.
        val available: Long? = measurement.availableBytes
        if (available != null && available < PreflightBudget.PLACEHOLDER_MIN_AVAILABLE_BYTES) {
            return PreflightFinding.Slow("$available bytes left with the embedder open")
        }
        return PreflightFinding.Passed(margin)
    }

    /** `null` when [face] has a finite box and five finite points inside it (with [LANDMARK_BOX_SLACK]). */
    fun faceRejection(face: FaceProbeFace): String? {
        val box: FaceBox = face.box
        val edges: List<Float> = listOf(box.left, box.top, box.right, box.bottom)
        if (edges.any { !it.isFinite() } || box.right <= box.left || box.bottom <= box.top) return "a box of $box"
        val landmarks: FaceLandmarks = face.landmarks ?: return "no five points"
        val slackX: Float = (box.right - box.left) * LANDMARK_BOX_SLACK
        val slackY: Float = (box.bottom - box.top) * LANDMARK_BOX_SLACK
        val points: List<FacePoint> = listOf(landmarks.leftEye, landmarks.rightEye, landmarks.noseTip, landmarks.mouthLeft, landmarks.mouthRight)
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite()) return "a point at $point"
            if (point.x < box.left - slackX || point.x > box.right + slackX || point.y < box.top - slackY || point.y > box.bottom + slackY) {
                return "a point at $point outside $box"
            }
        }
        return null
    }
}

/**
 * The face pair's half of a [Preflight] (`ios-face-grouping.md` §6.2): Vision on a fixed image,
 * then the embedder on [FaceProbeImages], each timed **after one warm-up** (F1b: the first Vision
 * request in a process took up to 104 ms against 3–4 ms after it).
 *
 * ⚠⚠ The embedder is opened through [models], outside the backend, and closed before the verdict is
 * recorded, as SigLIP's towers are.
 */
class FacePreflightProber(
    private val models: FacePreflightModels,
    private val device: PreflightDevice,
    private val probes: FacePreflightProbeLookup = FacePreflightProbeLookup { key -> FacePreflightProbes.forKey(key) },
    private val time: TimeSource = TimeSource.Monotonic,
    private val model: FaceModel = FaceModel.AuraFaceCoreMl,
) : PreflightProber {

    override fun runFor(key: PreflightKey): PreflightRun? = probes.forKey(key)?.let { Run(it) }

    private inner class Run(private val probe: FacePreflightProbe) : PreflightRun {

        override fun judge(measurement: PreflightMeasurement): PreflightFinding = when (measurement) {
            is FacePreflightMeasurement -> FacePreflightJudge.judge(measurement, probe, model.dimensions)
            else -> PreflightFinding.Failed("not a face measurement")
        }

        override fun measure(target: PreparedModel, context: PreflightRunContext): PreflightMeasurement? {
            val thermalAtStart: Int = device.thermalState()
            val warningsAtStart: Long = device.memoryWarnings()
            val footprintBefore: Long? = device.footprintBytes()
            fun failed(why: String) = FacePreflightMeasurement(
                thermalAtStart = thermalAtStart,
                memoryWarned = device.memoryWarnings() != warningsAtStart,
                failure = why,
                thermalAtEnd = device.thermalState(),
            )
            val embedderPath: String = target.paths[ModelArtifactRole.FACE_EMBEDDER] ?: return failed("the install has no face embedder")

            // ⚠ The detector first: it opens no file of ours, and an OS that cannot run the pinned
            // revision is found before the embedder costs a load.
            if (context.isRetired()) return null
            val photo: FaceProbeImage = runCatching { models.faceProbe() }.getOrElse { return failed("the face probe did not decode: $it") }
            var mark: TimeMark = time.markNow()
            var detection: FaceProbeDetection = runCatching { models.detect(photo.argb, photo.width, photo.height) }
                .getOrElse { return failed("the detector failed: $it") }
            val detectWarmUpMs: Double = mark.elapsedMs()
            val detectMs = ArrayList<Double>()
            repeat(DETECTIONS) {
                if (context.isRetired()) return null
                mark = time.markNow()
                detection = runCatching { models.detect(photo.argb, photo.width, photo.height) }.getOrElse { return failed("the detector failed: $it") }
                detectMs += mark.elapsedMs()
            }
            // The largest face Vision mapped, aligned and cropped as the backend crops one; and its mirror.
            val side: Int = model.template.side
            val crop: IntArray? = detection.faces.filter { it.landmarks != null }
                .maxByOrNull { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }
                ?.let { found ->
                    runCatching {
                        resampleFace(photo.argb, photo.width, photo.height, side, alignFace(found.landmarks!!, model.template, photo.width, photo.height))
                    }.getOrNull()
                }
            val mirrored: IntArray? = crop?.let { pixels -> IntArray(pixels.size) { pixels[(it / side) * side + (side - 1 - it % side)] } }

            var embedder: PreflightTower? = null
            context.holdingSessions()
            try {
                if (context.isRetired()) return null
                mark = time.markNow()
                embedder = runCatching { models.openEmbedder(embedderPath) }.getOrElse { return failed("the embedder did not load: $it") }
                val embedderOpenMs: Double = mark.elapsedMs()
                fun tensor(argb: IntArray): FloatArray = ImagePreprocessing.preprocessPixels(argb, FaceProbeImages.SIDE, model.profile)
                val reference: FloatArray = tensor(FaceProbeImages.reference())
                val references = ArrayList<FloatArray>()
                val embedMs = ArrayList<Double>()
                val embedWarmUpMs: Double
                val brighter: FloatArray
                val shifted: FloatArray
                val other: FloatArray
                val face: FloatArray?
                val faceMirrored: FloatArray?
                try {
                    mark = time.markNow()
                    embedder.predict(reference)
                    embedWarmUpMs = mark.elapsedMs()
                    repeat(PreflightBudget.PREDICTIONS_PER_TOWER) {
                        if (context.isRetired()) return null
                        mark = time.markNow()
                        references += embedder.predict(reference)
                        embedMs += mark.elapsedMs()
                    }
                    brighter = embedder.predict(tensor(FaceProbeImages.brighter()))
                    shifted = embedder.predict(tensor(FaceProbeImages.shifted()))
                    other = embedder.predict(tensor(FaceProbeImages.other()))
                    face = crop?.let { embedder.predict(tensor(it)) }
                    faceMirrored = mirrored?.let { embedder.predict(tensor(it)) }
                } catch (failure: Throwable) {
                    return failed("a prediction failed: $failure")
                }
                return FacePreflightMeasurement(
                    thermalAtStart = thermalAtStart,
                    memoryWarned = device.memoryWarnings() != warningsAtStart,
                    detection = detection,
                    reference = references,
                    brighter = brighter,
                    shifted = shifted,
                    other = other,
                    face = face,
                    faceMirrored = faceMirrored,
                    detectMs = detectMs,
                    detectWarmUpMs = detectWarmUpMs,
                    embedMs = embedMs,
                    embedderOpenMs = embedderOpenMs,
                    embedWarmUpMs = embedWarmUpMs,
                    // ⚠ With the embedder still open, as D12 asks.
                    availableBytes = device.availableBytes(),
                    footprintBytes = device.footprintBytes(),
                    footprintBeforeBytes = footprintBefore,
                    thermalAtEnd = device.thermalState(),
                )
            } finally {
                runCatching { embedder?.close() }
            }
        }
    }

    private fun TimeMark.elapsedMs(): Double = elapsedNow().toDouble(DurationUnit.MILLISECONDS)

    private companion object {
        /** Timed detector requests after the warm-up. */
        const val DETECTIONS: Int = 4
    }
}
