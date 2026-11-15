package org.tenshu.inference.proxy

import org.tenshu.inference.ModelProvenance

/**
 * Where a face is in an image — fractions of the image's size, the shape [TextRegion] already uses.
 *
 * ⚠ Fractions rather than pixels so a box survives the image being decoded at a different scale,
 * which it routinely is: a detector runs on a downscaled bitmap and the crop is taken from a larger
 * one.
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /**
     * How sure the detector is that this is a face **at all** — not who it is.
     *
     * ⚠ Distinct from the clustering similarity in [FaceSimilarityThreshold], and the two are
     * routinely confused. This one gates whether a crop is worth embedding; that one decides
     * whether two embeddings are the same person.
     */
    val confidence: Float = 1f,
) {
    // ⚠⚠ Equal at **float32** precision, by `toBits`. The face-template log stores float32 bits,
    // and on Kotlin/JS a `Float` is a double in memory — a literal included — so `0.11f` reopens as
    // `0.10999999940395355` and a generated equality would say the row changed. `toBits` rounds on
    // JS and is the JVM's own `Float.equals`/`hashCode` elsewhere (NaN equals NaN, -0 is not 0), so
    // off JS nothing about this class's equality moved. Rounding the fields on construction does not
    // work instead: the value then no longer equals the literal it was built from.
    override fun equals(other: Any?): Boolean =
        other is FaceBox && left.toBits() == other.left.toBits() && top.toBits() == other.top.toBits() &&
            right.toBits() == other.right.toBits() && bottom.toBits() == other.bottom.toBits() &&
            confidence.toBits() == other.confidence.toBits()

    override fun hashCode(): Int {
        var hash = left.toBits()
        hash = 31 * hash + top.toBits()
        hash = 31 * hash + right.toBits()
        hash = 31 * hash + bottom.toBits()
        return 31 * hash + confidence.toBits()
    }
}

/**
 * A point in an image, as fractions of its size — [FaceBox]'s convention.
 *
 * ⚠ Normalised to the **image**, never to the face's own box. Apple's `VNFaceLandmarkRegion2D`
 * normalises to the box and offers `pointsInImage(imageSize:)` to undo it; a backend that forgets
 * that conversion produces landmarks which look plausible and sit in the wrong place.
 */
data class FacePoint(val x: Float, val y: Float)

/**
 * The five points every mainstream identity embedder aligns on.
 *
 * ⚠⚠ **Required, not optional, and this is the decision that keeps vectors comparable across
 * platforms.** Identity models (ArcFace-class at 112×112, FaceNet at 160×160) are trained on crops
 * produced by a **5-point similarity transform** onto a canonical template — not on "whatever the
 * detector's box contained". Apple's Vision boxes are notoriously tight (they exclude forehead,
 * chin and ears) and any detector we ship elsewhere frames differently, so cropping *by the box*
 * would put iOS faces and Android faces in systematically different regions of the same model's
 * space. Sharing the weights would then buy nothing: the property we want is comparability, and it
 * dies on the crop, silently.
 *
 * ⭐ So the platform contributes **boxes and landmarks** — exactly IN-D13's "the platform
 * contributes the capabilities it owns" — and the alignment is ours, shared, and the same
 * everywhere. Making these non-null is what stops a detector that cannot produce them from
 * quietly emitting incomparable vectors.
 */
data class FaceLandmarks(
    val leftEye: FacePoint,
    val rightEye: FacePoint,
    val noseTip: FacePoint,
    val mouthLeft: FacePoint,
    val mouthRight: FacePoint,
)

/**
 * One face a detector found: where it is, and the points an embedder aligns on.
 *
 * ⚠ [FaceBox] is what a UI draws and what a crop is bounded by; [FaceLandmarks] is what the crop
 * is *aligned* by. They are not interchangeable — see [FaceLandmarks].
 */
data class DetectedFace(val box: FaceBox, val landmarks: FaceLandmarks)

/**
 * What one attempt to find faces in an image came to.
 *
 * ⚠⚠ **Three outcomes, because "no faces" is a FACT and the other two are not.** Most photographs in
 * a real library contain no face at all, so the common case must be recordable — a pass that could
 * not tell "no faces here" from "we failed to look" would re-read the whole library on every run,
 * for ever. This is [ImageEmbeddingOutcome]'s lesson (§32.4) applied before the embed rather than
 * after it, and it matters more here because the negative case is the majority one.
 */
sealed interface FaceDetectionOutcome {

    /**
     * The detector ran. ⚠ [faces] may be **empty**, and that is the fact worth remembering: this
     * photograph has no faces in it, and no future pass needs to open it again.
     */
    data class Detected(val faces: List<DetectedFace>) : FaceDetectionOutcome

    /** These bytes are not an image this device can decode. Stable for this file on this device. */
    data object Unreadable : FaceDetectionOutcome

    /** The detector could not run — missing, rejected, or failed this time. Says nothing about the file. */
    data object Failed : FaceDetectionOutcome
}

/**
 * One face's identity vector, and where in the photograph it was found.
 *
 * ⚠⚠ **This is the biometric artifact, and it never leaves the device** (IN-D5). It is held only by
 * [FaceTemplateStore], is re-derivable from content the device already has, and appears in no schema:
 * `InferenceService` carries labels and nothing else. What syncs is a `MediaLabel` naming a person —
 * a string the user typed — and `photos-people-and-pets.md` §3.1 is the table of the three custodies.
 */
data class StoredFace(val box: FaceBox, val vector: FloatArray) {
    // ⚠ FloatArray is identity-compared by default, the same trap [ImageEmbedding] documents.
    override fun equals(other: Any?): Boolean =
        other is StoredFace && box == other.box && vector.contentEquals(other.vector)

    override fun hashCode(): Int = 31 * box.hashCode() + vector.contentHashCode()
}

/**
 * Everything one photograph contributed to the face index, as the store holds it.
 *
 * ⚠ A row with an empty [faces] list is not an absence — it is the recorded fact that this
 * photograph was looked at and has no faces in it (see [FaceDetectionOutcome.Detected]).
 */
data class FaceRow(
    val filePath: String,
    val faces: List<StoredFace>,
    val producedBy: ModelProvenance,
)
