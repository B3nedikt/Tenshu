package org.tenshu.inference.proxy

/**
 * The animals Pets can name in v1 (`photos-pets.md` PT-D5). A detector that finds others — a bird,
 * a horse — drops them before they reach this type.
 */
enum class AnimalSpecies { CAT, DOG }

/**
 * One cat or dog a detector found, as fractions of the image's size with the origin top-left —
 * [FaceBox]'s convention, so a face box and an animal box from the same image compare directly.
 *
 * ⚠ [confidence] is how sure the detector is that this is a cat or dog **at all**, never which one.
 */
data class DetectedAnimal(
    val species: AnimalSpecies,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)

/**
 * PT-D9 (`photos-pets.md` §6): **a face box lying inside a confident cat or dog box is not a person.**
 *
 * The People pass drops such a face before it is embedded, which is the human/non-human check
 * `tech-debt.md` *"People: a non-human face can form a person"* asks for: one that does not track
 * image quality, unlike the embedder norm gate that failed on previews (People §12.15).
 *
 * ⚠⚠ **Unmeasured.** Both numbers are a starting point, not a calibration: M5 measures them on a
 * verified library, and the bar is *zero real people lost*. That is why no shipping graph turns the
 * veto on yet. Containment is measured over the **face's** area, so a person holding a cat — whose
 * face sits beside the cat's box, not inside it — is kept; a face on a large dog's flank is the
 * case M5 has to look at.
 *
 * ⚠ A veto is part of the face pipeline: a backend that applies it stamps a different pipeline
 * version, so a library indexed without it re-indexes when it is turned on.
 */
data class AnimalVeto(
    /** An animal box below this detector confidence vetoes nothing. */
    val minAnimalConfidence: Float,
    /** The share of the face box's area that must lie inside one animal box. */
    val minFaceInside: Float,
) {
    init {
        require(minAnimalConfidence in 0f..1f) { "minAnimalConfidence $minAnimalConfidence" }
        require(minFaceInside > 0f && minFaceInside <= 1f) { "minFaceInside $minFaceInside" }
    }

    /** Whether [face] lies inside one of [animals] by this veto's two numbers. */
    fun vetoes(face: FaceBox, animals: List<DetectedAnimal>): Boolean {
        val faceArea = extent(face.left, face.right) * extent(face.top, face.bottom)
        if (faceArea <= 0f) return false
        return animals.any { animal ->
            if (animal.confidence < minAnimalConfidence) return@any false
            val inside = extent(maxOf(face.left, animal.left), minOf(face.right, animal.right)) *
                extent(maxOf(face.top, animal.top), minOf(face.bottom, animal.bottom))
            inside / faceArea >= minFaceInside
        }
    }

    /** [faces] split into the ones kept and how many this veto removed. */
    fun apply(faces: List<DetectedFace>, animals: List<DetectedAnimal>): Vetoed {
        if (animals.isEmpty()) return Vetoed(faces, 0)
        val kept = faces.filterNot { vetoes(it.box, animals) }
        return Vetoed(kept, faces.size - kept.size)
    }

    class Vetoed(val kept: List<DetectedFace>, val removed: Int)

    companion object {
        /** The proposed operating point, ⚠ unmeasured (M5). */
        val Proposed = AnimalVeto(minAnimalConfidence = 0.5f, minFaceInside = 0.8f)

        private fun extent(from: Float, to: Float): Float = (to - from).coerceAtLeast(0f)
    }
}

/**
 * A cat and dog detector over decoded pixels, as a face backend's veto asks it (`photos-pets.md`
 * §10.1). `OnnxAnimalDetector` is the RF-DETR one; a test passes a fake.
 */
interface PixelAnimalDetector {
    /** Whether the model is there to run. Cheap: opens nothing. */
    fun available(): Boolean

    /**
     * Every cat and dog in a `width × height` packed-ARGB image, upright. ⚠⚠ `null` means the model
     * could not run, never "no animals".
     */
    fun detect(argb: IntArray, width: Int, height: Int): List<DetectedAnimal>?
}
