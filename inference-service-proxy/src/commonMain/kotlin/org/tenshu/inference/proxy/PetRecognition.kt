package org.tenshu.inference.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.PetCropInput
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which detected animals become templates (`photos-pets.md` PT-D11, §10.8).
 *
 * ⚠⚠ Every number is `m2-pet-embedding.py`'s or §10.8's measurement, and the grouping cut that
 * follows ([PetClustering.BestGuess]) is only safe over crops chosen this way:
 * - [floor]: below it a query is not an animal for Pets — the M2 script's `FLOOR`.
 * - [duplicateIou]: DETR can answer one animal with two queries; the less confident is dropped.
 * - [overlapLimit]: ⚠⚠ **a crop holding more than this share of ANOTHER animal's box is skipped**.
 *   Two cats eating side by side made each other's ×1.15 square half the other cat, and the household
 *   run merged them at 0.93 (§10.8). Only 0.10 removed both mixes; it skips 7 of 31 crops there. A
 *   huddled pair goes unindexed in that photograph rather than merged — the recoverable direction.
 */
data class PetSelection(
    val floor: Float,
    val duplicateIou: Float,
    val overlapLimit: Float,
) {
    /** The animals worth embedding in a `width × height` image, most confident first, and how many overlap. */
    fun select(animals: List<DetectedAnimal>, width: Int, height: Int): Selected {
        val kept = ArrayList<DetectedAnimal>()
        for (animal in animals.filter { it.confidence >= floor }.sortedByDescending { it.confidence }) {
            if (kept.all { iou(it, animal) <= duplicateIou }) kept += animal
        }
        val clear = kept.filter { animal ->
            val square = PetCropInput.square(width, height, animal)
            kept.none { other -> other !== animal && shareInside(other, square, width, height) > overlapLimit }
        }
        return Selected(clear, overlapping = kept.size - clear.size)
    }

    data class Selected(val animals: List<DetectedAnimal>, val overlapping: Int)

    companion object {
        /** ⚖️ PT-D11 (2026-11-19), provisional: re-measure on cross-day photos. */
        val BestGuess = PetSelection(floor = 0.5f, duplicateIou = 0.5f, overlapLimit = 0.10f)

        internal fun iou(a: DetectedAnimal, b: DetectedAnimal): Float {
            val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - ix * iy
            return if (union > 0f) ix * iy / union else 0f
        }

        /** The share of [other]'s box (pixels) that falls inside [square] `[l, t, r, b)`. */
        internal fun shareInside(other: DetectedAnimal, square: IntArray, width: Int, height: Int): Float {
            val l = other.left * width
            val t = other.top * height
            val r = other.right * width
            val b = other.bottom * height
            val area = (r - l) * (b - t)
            if (area <= 0f) return 0f
            val ix = max(0f, min(r, square[2].toFloat()) - max(l, square[0].toFloat()))
            val iy = max(0f, min(b, square[3].toFloat()) - max(t, square[1].toFloat()))
            return ix * iy / area
        }
    }
}

/** What looking for pets in one photograph came to — [FaceDetectionOutcome]'s three answers. */
sealed interface PetOutcome {
    /** ⚠⚠ An empty [pets] is a FACT, recorded so no later pass opens the photograph again. */
    data class Found(val pets: List<StoredFace>, val overlapping: Int = 0) : PetOutcome

    /** These bytes are not an image this device decodes — remembered, like a face backend's. */
    data object Unreadable : PetOutcome

    /** ⚠⚠ A model could not run. Says nothing about the photograph and is NEVER recorded. */
    data object Failed : PetOutcome
}

/**
 * Pets' half of an index pass (`photos-pets.md` §2): bytes in, one template per selected cat or dog out.
 *
 * ⚠ A [ModelBackedBackend], because the pets store's stamp must move when the embedder, the detector or
 * the selection does — templates from another pipeline are not comparable.
 */
interface PetBackend : ModelBackedBackend {
    /** Whether both models are there to run. Cheap: opens nothing. */
    fun available(): Boolean

    suspend fun findPets(bytes: ByteArray, orientation: Int?): PetOutcome
}

/** The embedder half, over the tensor [PetCropInput] makes. `OnnxPetEmbedder` is the real one. */
interface PixelPetEmbedder {
    fun available(): Boolean

    /** The model's whole output ([PetEmbedderModel.WIDTH] floats); ⚠⚠ `null` = could not run. */
    fun embed(input: FloatArray): FloatArray?
}

/**
 * The shared [PetBackend]: the platform's upright decode (the face path's, so a box means the same
 * frame), RF-DETR, [PetSelection], then DINOv2's CLS half, L2-normalised.
 *
 * ⚠⚠ Serialised: [PixelAnimalDetector] and the embedder session are not thread-safe, and this owns its
 * own detector instance — never the face backend's, which runs under that backend's lock.
 */
class PixelPetBackend(
    private val decoder: FaceImageDecoder,
    private val detector: PixelAnimalDetector,
    private val embedder: PixelPetEmbedder,
    private val model: PetEmbedderModel = PetEmbedderModel.DinoV2SmallZeroShot,
    detectorModel: AnimalDetectorModel = AnimalDetectorModel.RfDetrNanoInt8,
    private val selection: PetSelection = PetSelection.BestGuess,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PetBackend {

    /**
     * The embedder's identity, with the pipeline in [ModelProvenance.labelSetVersion]: the detector's
     * version under [PET_PIPELINE_VERSION]. ⚠⚠ Bump [PET_PIPELINE_VERSION] when the crop, the decode or
     * [PetSelection] changes — the store then starts over rather than mixing two pipelines' templates.
     */
    override val provenance: ModelProvenance =
        ModelProvenance(model.modelId, model.modelVersion, labelSetVersion = PET_PIPELINE_VERSION * 1000 + detectorModel.modelVersion)

    private val lock = Mutex()

    override fun available(): Boolean = detector.available() && embedder.available()

    override suspend fun findPets(bytes: ByteArray, orientation: Int?): PetOutcome = lock.withLock {
        withContext(dispatcher) {
            if (!available()) return@withContext PetOutcome.Failed
            val image: FaceImage = runCatching { decoder.decodeUpright(bytes, orientation) }.getOrNull()
                ?: return@withContext PetOutcome.Unreadable
            val animals = detector.detect(image.argb, image.width, image.height) ?: return@withContext PetOutcome.Failed
            val selected = selection.select(animals, image.width, image.height)
            val pets = ArrayList<StoredFace>(selected.animals.size)
            for (animal in selected.animals) {
                // ⚠⚠ All or nothing, as the face half: one failed embed means the model did not run,
                // and storing the rest would mark the photograph done with a pet silently lost.
                val output = embedder.embed(PetCropInput.tensor(image, animal)) ?: return@withContext PetOutcome.Failed
                val template = template(output) ?: return@withContext PetOutcome.Failed
                pets += StoredFace(FaceBox(animal.left, animal.top, animal.right, animal.bottom, animal.confidence), template)
            }
            PetOutcome.Found(pets, selected.overlapping)
        }
    }

    /** The CLS half, unit length; `null` for a wrong width or a vector with no direction (NaN, zero). */
    private fun template(output: FloatArray): FloatArray? {
        if (output.size != PetEmbedderModel.WIDTH) return null
        val cls = output.copyOf(PetEmbedderModel.TEMPLATE)
        var sum = 0.0
        for (v in cls) sum += v.toDouble() * v
        val norm = sqrt(sum)
        if (!norm.isFinite() || norm == 0.0) return null
        return FloatArray(cls.size) { (cls[it] / norm).toFloat() }
    }

    companion object {
        /** The crop, decode and [PetSelection] as of PT-3a. See [provenance]. */
        const val PET_PIPELINE_VERSION: Int = 1
    }
}

/**
 * ⚖️ PT-D11's grouping: **cosine ≥ 0.85** on DINOv2-S's CLS half, never below (`photos-pets.md` §10.8).
 *
 * ⚠⚠ A **split-first** cut, not a calibration: at 0.85 the household run's two littermate tabbies never
 * share a group, with [clusterFaces]'s own greedy-centroid algorithm as with single-link, and the user
 * joins the resulting groups by naming them. Below it nothing merges without the user (PT-D6). ⚠ From one
 * ten-minute session; re-measure on cross-day photos before trusting it further.
 *
 * ⚠ Species are not stored: across species DINOv2's cosine stayed below 0.21 (a puppy against every cat,
 * §10.7), far under the cut.
 */
object PetClustering {
    val BestGuess: FaceClustering = FaceClustering.Calibrated(threshold = FaceSimilarityThreshold(0.85f), minFaces = 2)
}
