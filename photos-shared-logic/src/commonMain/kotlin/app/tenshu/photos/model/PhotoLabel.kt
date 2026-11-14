package app.tenshu.photos.model

import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.InferenceCapability

/**
 * One thing a model said about one file — a `MediaLabel` row as Photos needs it
 * (`inference-service.md` IN-3a).
 *
 * ⚠ Carries a **path**, not a reference, because the row does: `:file-service` deliberately never
 * learns what a model is, so resolving the path to a [Photo] is a join this app performs — exactly
 * like the `Place` join behind [groupPhotosByPlace].
 */
data class PhotoLabel(
    val path: String,
    val label: String,
    /** 0–1000. Per-mille, not a float, because the stored field is an integer. */
    val confidencePerMille: Int,
    /**
     * [InferenceCapability.id] — which capability produced the row.
     *
     * ⚠⚠ **Required, with no default, because the default is what would hide the bug it
     * exists to stop.** A person's name is a `MediaLabel` too (IN-6 F3b), so a reader that cannot
     * tell capabilities apart renders "Anna" as a *Thing* alongside "Dog" — and the Things
     * section is described as what a MODEL said about a picture.
     */
    val capability: String,
)

/** A label and the photos carrying it — one card of the Things section (`photos-app.md` S4). */
data class LabelGroup(
    /** Display form, e.g. `Dog`. */
    val label: String,
    val photos: List<Photo>,
)

/**
 * Group photos by what a model called them, in one pass.
 *
 * ⚠⚠ **Over photos already read, never a query per label.** The Collections tab has exactly one
 * full-library read (§20's shared index) and every derived section is arithmetic over it. A read
 * per label would reintroduce the N-walks-per-refresh cost §20 was built to remove — and the label
 * vocabulary is open-ended, so N is not even bounded.
 *
 * ⚠⚠ **The [INDEX_MARKER_LABEL] sentinel is dropped first.** It is on *every* indexed file, so
 * without this the largest collection a user owns would be one called `__indexed` holding their
 * entire library. Machinery, not data.
 *
 * ⚠ A label needs [minPhotos] photos to become a collection. One photograph is not a collection —
 * it is a search result, and the tab already has a search. This is what keeps the section short
 * enough to read on a phone as a vocabulary grows into the hundreds.
 *
 * ⚠⚠ **[minConfidencePerMille] defaults to 0 — Photos does not own this threshold, and must not
 * invent one.** The cut-off that decides whether a label is worth having is IN-D9's, applied where
 * the vocabulary is calibrated; a second floor here would silently drop rows the calibrated side
 * decided to keep, and the user would have no way to tell that from "the model found nothing".
 *
 * ⚠⚠ And an invented floor would be *catastrophically* wrong, not slightly wrong. SigLIP scores
 * `sigmoid(111.00·cos − 12.89)`, and measured over a real library (`models.md` §3.7.2) **no
 * (image, label) pair reaches p = 0.5** — the highest observed is ≈ 0.25, and usable cut-offs sit
 * around **0.05–0.15**. A floor of "50% confident" sounds cautious and labels *nothing at all*.
 * The parameter stays so a caller with a calibrated number can pass one.
 *
 * ⚠ Labels are keyed case-insensitively and shown in the casing the model produced, capitalised.
 * A tagger that emits both `dog` and `Dog` is one collection, not two.
 */
fun groupPhotosByLabel(
    photos: List<Photo>,
    labels: List<PhotoLabel>,
    minPhotos: Int = DEFAULT_MIN_LABEL_PHOTOS,
    minConfidencePerMille: Int = DEFAULT_MIN_LABEL_CONFIDENCE_PER_MILLE,
): List<LabelGroup> {
    if (labels.isEmpty()) return emptyList()
    val byPath = photos.associateBy { it.path }

    // label key → (display form, path → best confidence for that path)
    val groups = mutableMapOf<String, MutableMap<String, Int>>()
    val display = mutableMapOf<String, String>()

    for (row in labels) {
        val label = row.label.trim()
        if (label.isEmpty() || label == INDEX_MARKER_LABEL) continue
        // ⚠⚠ Only a model's tag is a THING — an allowlist, never a denylist. A person's name is a
        // `MediaLabel` exactly like a tag (IN-6 F3b), and so will a pet's be under a capability
        // this build has never heard of (`photos-pets.md` §3). A denylist would show either as a
        // Things card ("Anna" or "Mochi" beside "Dog") on every build older than the capability.
        if (row.capability !in THING_CAPABILITIES) continue
        if (row.confidencePerMille < minConfidencePerMille) continue
        if (!byPath.containsKey(row.path)) continue
        val key = label.lowercase()
        display.getOrPut(key) { label.replaceFirstChar { c -> c.uppercaseChar() } }
        val paths = groups.getOrPut(key) { mutableMapOf() }
        // ⚠ Strongest wins per photo: the same file can carry `dog` twice (two model stamps, or two
        // capabilities once [THING_CAPABILITIES] grows), and the cover should be the one the model
        // was surest about rather than whichever row read first.
        paths[row.path] = maxOf(paths[row.path] ?: 0, row.confidencePerMille)
    }

    return groups.mapNotNull { (key, paths) ->
        if (paths.size < minPhotos) return@mapNotNull null
        val members = paths.entries
            // ⚠ Best-scoring first, and the cover is `photos.first()` — so the card shows the
            // photograph the model was surest about rather than the newest, which is what makes a
            // "Dog" card look like a dog.
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { byPath[it.key] }
        LabelGroup(display.getValue(key), members)
    }.sortedWith(compareByDescending<LabelGroup> { it.photos.size }.thenBy { it.label })
}

/** ⚠ Two, not one — see [groupPhotosByLabel]. A product choice, tunable, guessed on the safe side. */
const val DEFAULT_MIN_LABEL_PHOTOS = 2

/**
 * **0 — no floor.** See [groupPhotosByLabel]: the confidence cut-off belongs to whoever calibrated
 * the vocabulary, and Photos has calibrated nothing.
 *
 * ⚠ Deliberately not "a small number instead": 0.05 would be as invented as 0.5, just less
 * obviously so, and a plausible-looking constant is the kind nobody re-examines.
 */
const val DEFAULT_MIN_LABEL_CONFIDENCE_PER_MILLE = 0

/**
 * The capabilities whose labels are *Things* — what a model said a picture shows.
 *
 * ⚠⚠ **An allowlist, so a capability added later is invisible to Things until someone adds it
 * here.** Names typed by the user (people today, pets in `photos-pets.md`) are `MediaLabel` rows
 * too, and a released build cannot know a capability that does not exist yet. A denylist shipped
 * today would put tomorrow's pet name on this build's Things section.
 */
val THING_CAPABILITIES: Set<String> = setOf(InferenceCapability.CONTENT_TAGGING.id)
