package org.tenshu.inference.proxy

import kotlin.jvm.JvmInline
import kotlin.math.sqrt

/**
 * One face, addressed.
 *
 * ⚠ The index is into the photograph's own [FaceRow.faces] list, so a reference is only meaningful
 * beside the store that produced it — which is the point: these never leave the device.
 */
data class FaceRef(val filePath: String, val faceIndex: Int)

/**
 * Faces this device believes are one person.
 *
 * ⚠⚠ **A cluster is scaffolding, not a record.** IN-D5: cluster identity is never synced, never
 * stored, and never named — what the user names becomes a `MediaLabel` per photograph. Two devices
 * may cluster the same library differently and both be right; the labels they produce agree.
 */
data class FaceCluster(val faces: List<FaceRef>) {
    val size: Int get() = faces.size

    /** The photographs this person appears in, de-duplicated — two faces in one photo is one photo. */
    val filePaths: List<String> get() = faces.map { it.filePath }.distinct()
}

/**
 * How close two templates must be to be the same person.
 *
 * ⚠⚠ **There is no default, and that is deliberate — this is the face slice's uncalibrated
 * vocabulary.** `VocabularyLabel` makes the same refusal for the same reason, and §27.2 records what
 * a plausible-sounding constant cost there: a floor of 0.500 *"sounds cautious and would have
 * labelled **nothing at all**"*. A clustering threshold is worse, because both ways of being wrong
 * are visible — too tight splits one person into four rows, too loose merges two siblings — and a
 * number taken from a paper describes that paper's model, not ours.
 *
 * ⭐ So the number arrives with a measurement against a real labelled library (F2), and until then
 * [FaceClustering.Uncalibrated] is the production value and it produces **no clusters at all**.
 */
@JvmInline
value class FaceSimilarityThreshold(val cosine: Float) {
    init {
        require(cosine in -1f..1f) { "a cosine similarity threshold must be in [-1, 1], was $cosine" }
    }
}

/**
 * The calibrated numbers face clustering needs, or the absence of them.
 *
 * ⚠ [minFaces] is the second knob and it hides behind the first: how many photographs a person must
 * appear in before they earn a row. Showing every one-photo stranger is noise — Photos' Things grid
 * makes the same call with `DEFAULT_MIN_LABEL_PHOTOS = 2`.
 */
sealed interface FaceClustering {

    data class Calibrated(
        val threshold: FaceSimilarityThreshold,
        val minFaces: Int = 2,
    ) : FaceClustering

    /**
     * ⚠⚠ The production value until F2 measures one. Emits nothing, however confident the model is —
     * `LabelVocabulary.Empty`'s posture, and for its reason: a feature that guesses is how a user
     * learns not to trust it, and *"this slice reduces what a device would write, from everything to
     * nothing, which is the correct direction to be wrong in while the numbers are unknown."*
     */
    data object Uncalibrated : FaceClustering

    companion object {
        /**
         * ⭐⭐ **The measured operating point** (F2m, `photos-people-and-pets.md` §12.6) — what
         * production wires, replacing [Uncalibrated] on 2026-09-24.
         *
         * **Where 0.44 comes from.** It is not a midpoint of a plausible range; it is the
         * threshold the one human-verified run was produced at. On a 400-photograph camera roll
         * the maintainer confirmed by eye that cluster 0 held 29 photographs of one person with
         * **no intruder**, and that a cat the detector had mistaken for a face fell out on its own.
         *
         * The band around it is wide and measured, not assumed:
         * - the largest cluster holds at 29 from **0.20 through 0.52** — a coherent identity, since
         *   a lucky grouping fragments as the threshold tightens;
         * - the nearest pair between that cluster and any *other* person sits at **0.10–0.20**, so
         *   different people top out more than twice as far away as this cut;
         * - the knee in cluster count sits between **0.28 and 0.44**.
         *
         * ⚠⚠ **Chosen at the HIGH end of the safe band on purpose, and the asymmetry is the whole
         * argument.** The two ways to be wrong do not cost the same:
         * - too high **splits** one person into several clusters — the user names both, the names
         *   fold (`labelKey` is per file/capability/label), and it costs them one extra tap;
         * - too low **merges** two people — and the durable artifact is a synced `MediaLabel`
         *   saying *this photograph shows Anna*, so a merge writes a stranger's face under a
         *   friend's name, on every device the user owns, where nothing will ever correct it.
         *
         * Splitting is recoverable by the person who can see the mistake. Merging is not.
         *
         * ⚠ **One subject, one library, 46 faces.** This is an operating point, not a benchmark,
         * and §12.6 is explicit that clusters 1–4 were never identity-checked. Re-measure before
         * trusting it on a different checkpoint — the threshold describes THIS model's geometry and
         * transfers to no other.
         *
         * ⭐⭐ **Re-measured for the shipped checkpoint, AuraFace int8, 2026-09-24 (§12.8c) — and
         * KEPT at 0.44.** On the same library the verified cluster holds at 29 from 0.20 through
         * 0.44 and fragments by 0.52 (HSFace held to 0.52), and the nearest pair between it and any
         * other person is **0.215**. 0.40 was tried and reproduces fp32's clusters exactly, but it
         * sits under twice that distance; 0.44 clears it and costs one borderline face split off an
         * unverified cluster — the recoverable direction, by the argument above.
         *
         * ⚠ [Calibrated.minFaces] stays at 2, matching Photos' own `DEFAULT_MIN_LABEL_PHOTOS`: one
         * photograph is a search result rather than a person. ⭐ Not 3, although that would drop
         * the two smallest unverified clusters — a spurious card is clutter the user can ignore,
         * while a missing person is not something they can fix.
         */
        val Measured = Calibrated(threshold = FaceSimilarityThreshold(0.44f), minFaces = 2)
    }
}

/**
 * Group faces into people, device-locally (IN-6, `photos-people-and-pets.md` §3).
 *
 * ⚠⚠ Greedy and **order-dependent by construction**: each face joins the nearest cluster whose
 * centroid it is within [FaceClustering.Calibrated.threshold] of, or starts a new one. A different
 * visiting order can therefore produce different groups from the same library — which is a property
 * of the algorithm, not a defect, precisely because IN-D5 makes clusters local and disposable. Rows
 * are visited in a **deterministic** order all the same, so the same store clusters the same way
 * twice; a UI that reshuffled itself between openings would look broken even though nothing is.
 *
 * ⚠ Centroids are re-normalised as they absorb faces, so "similarity to a cluster" stays a cosine
 * rather than drifting with the number of members.
 */
fun clusterFaces(rows: List<FaceRow>, clustering: FaceClustering): List<FaceCluster> {
    val calibrated = clustering as? FaceClustering.Calibrated ?: return emptyList()

    val members = mutableListOf<MutableList<FaceRef>>()
    val centroids = mutableListOf<FloatArray>()

    rows.sortedBy { it.filePath }.forEach { row ->
        row.faces.forEachIndexed { index, face ->
            val best = centroids.indices.maxByOrNull { cosine(centroids[it], face.vector) }
            if (best != null && cosine(centroids[best], face.vector) >= calibrated.threshold.cosine) {
                members[best] += FaceRef(row.filePath, index)
                centroids[best] = absorb(centroids[best], members[best].size, face.vector)
            } else {
                members += mutableListOf(FaceRef(row.filePath, index))
                centroids += normalise(face.vector)
            }
        }
    }

    return members
        .filter { it.size >= calibrated.minFaces }
        .map { FaceCluster(it.toList()) }
        // ⚠ Largest first: the people a user photographs most are the ones they want to name.
        .sortedByDescending { it.size }
}

/** A running mean, re-normalised — see the function KDoc. */
private fun absorb(centroid: FloatArray, newSize: Int, vector: FloatArray): FloatArray {
    val blended = FloatArray(centroid.size) { i ->
        (centroid[i] * (newSize - 1) + vector[i]) / newSize
    }
    return normalise(blended)
}

private fun normalise(vector: FloatArray): FloatArray {
    val length = sqrt(vector.fold(0f) { sum, v -> sum + v * v })
    // ⚠ A zero vector cannot be normalised; leaving it alone keeps it maximally dissimilar to
    // everything rather than producing NaNs that compare false against themselves.
    if (length == 0f) return vector
    return FloatArray(vector.size) { vector[it] / length }
}

/**
 * ⚠ Plain dot product: both sides are unit length by construction — the backend returns unit
 * vectors and [normalise] keeps centroids that way — so this IS the cosine without the division.
 */
private fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return -1f
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
