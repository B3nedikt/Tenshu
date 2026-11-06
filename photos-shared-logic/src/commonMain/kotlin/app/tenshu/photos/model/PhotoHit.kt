package app.tenshu.photos.model

/**
 * Where a search hit came from (`photos-app.md` S2) — the badge on each row.
 *
 * ⚠ The badge is not decoration. A photo found because its *filename* contains "dog" and one found
 * because a model thinks there is a dog in it are different claims, and only the second can be
 * wrong in a way the user cannot see. Saying which is which is what makes the second acceptable.
 */
enum class PhotoHitSource {
    /** The filename or path contains the query. Certain, cheap, and the only one always available. */
    NAME,

    /** A model tagged the photo with a matching label (`MediaLabel`). Present only with IN-3b. */
    LABEL,

    /** The photo ranked highly for the query text (`photosLike`). Present only with IN-3b. */
    SEMANTIC,
}

/**
 * One row of the result list.
 *
 * ⚠ A photo found by more than one source is **one** hit carrying both, never two rows. Three
 * sources over one library will agree often, and a list that repeats a photo once per source reads
 * as a broken search rather than a thorough one.
 */
data class PhotoHit(
    val photo: Photo,
    val sources: Set<PhotoHitSource>,
    /** The label that matched, when [PhotoHitSource.LABEL] is among [sources]. */
    val matchedLabel: String? = null,
) {
    /**
     * Which badge to show when there is room for one.
     *
     * ⚠ Ordered by how *checkable* the claim is: a name match is something the user can verify at a
     * glance, a label names the word a model chose, and "looks like" is the least inspectable of
     * the three. Leading with the strongest evidence is the honest default.
     */
    val primarySource: PhotoHitSource
        get() = when {
            PhotoHitSource.NAME in sources -> PhotoHitSource.NAME
            PhotoHitSource.LABEL in sources -> PhotoHitSource.LABEL
            else -> PhotoHitSource.SEMANTIC
        }
}

/**
 * What the two inference-backed sources have to say about themselves.
 *
 * ⚠⚠ The distinction S2 is written around: **"nothing matched" and "this cannot run here" are
 * different answers, and only one is actionable.** `photosLike` throws rather than returning an
 * empty list precisely so a caller cannot collapse them by accident, and rendering a refusal as an
 * empty result is the mistake `mia-query-layer.md` §14.1 names.
 */
sealed interface SemanticSearchStatus {
    /** Ran, and its hits (if any) are in the list. */
    data object Available : SemanticSearchStatus

    /**
     * Did not run. [reason] is a `SemanticSearchUnavailable.Reasons` constant, kept raw so the
     * UI layer owns the sentence — the proxy's vocabulary and the screen's voice are different
     * things, and Mia has its own copy for its own voice.
     */
    data class Unavailable(val reason: String) : SemanticSearchStatus

    /** Something else went wrong. ⚠ Not merged into [Unavailable]: that one names a cause. */
    data object Failed : SemanticSearchStatus

    /**
     * This platform has no index to search and no way to build one yet — iOS until PIO-7
     * (`photos-ios.md` §3.2, §8.19). [device] is the shell's own noun for itself ("iPhone", "iPad").
     *
     * ⚠⚠ Not an [Unavailable] reason, on purpose: every one of those is the PROXY's answer, and one of
     * them — `noConsent` — sends the user to a switch. Here nothing was asked, there is no switch and
     * no model to download, and the sentence must never suggest one (`inference-service.md`:
     * "Settings→Models is the only route to a model").
     */
    data class NotBuiltHere(val device: String) : SemanticSearchStatus
}
