package app.tenshu.photos.ui.screens.search

import app.tenshu.photos.model.PhotoHit
import app.tenshu.photos.model.PhotoHitSource
import app.tenshu.photos.model.SemanticSearchStatus

/**
 * The Search screen as both shells draw it (`photos-app.md` S2, `photos-ios.md` §8.19).
 *
 * ⚠⚠ Decided here, drawn there (PIO-D2). Which of the screen's states is showing, the note above
 * the results, when a retry is offered and every sentence lived inside Compose's `PhotoSearchScreen`;
 * the iOS screen would have held a second copy, and nothing iOS compiles in the cloud.
 *
 * ⚠⚠ The states it keeps apart: **type to search** ([Body.Idle]), **searching**, **results**,
 * **nothing matched** ([Body.Empty]), **could not look** ([Body.CouldNotLook], with a retry), and —
 * beside whichever of those is showing, never instead of it — **off** ([notes]): a source that
 * cannot run here. A refusal from one source is a footnote on a working screen, not an error state.
 */
data class PhotoSearchLayout(
    /**
     * Why a source did not contribute, above the body. ⚠ "Off" lines stay up while a newer search
     * runs — they are true of the device, not of a query, and a line that came and went with every
     * keystroke would shake the list. "Could not look" lines belong to one answer and wait for it.
     */
    val notes: List<String>,
    val body: Body,
    /**
     * Whether [PhotoSearchCopy.RETRY] is offered beside the [notes] — some source could not look, but
     * there are results on screen. ⚠ [Body.CouldNotLook] carries its own retry; never both.
     */
    val retryInNotes: Boolean,
) {
    sealed interface Body {
        /** Nothing typed. */
        data class Idle(val text: String) : Body

        data object Searching : Body

        /** [footers] — the cap line, when the list was cut rather than exhausted. */
        data class Results(val hits: List<PhotoHit>, val footers: List<String>) : Body

        /** ⚠ Every source that was asked ANSWERED, and none had a hit. Only then. */
        data class Empty(val text: String) : Body

        /**
         * ⚠⚠ Nothing to show, and at least one source did not answer — neither "no results" nor
         * "off". Always drawn with [PhotoSearchCopy.RETRY].
         */
        data class CouldNotLook(val text: String) : Body
    }

    companion object {
        fun of(state: PhotoSearchUiState): PhotoSearchLayout {
            if (state.isIdle) {
                return PhotoSearchLayout(emptyList(), Body.Idle(PhotoSearchCopy.idle(state)), retryInNotes = false)
            }
            val off = listOfNotNull(PhotoSearchCopy.off(state.semanticStatus, state.modelsSwitch))
            if (state.searching || !state.settled) {
                return PhotoSearchLayout(off, Body.Searching, retryInNotes = false)
            }
            val failed = state.couldNotLook
            if (state.hits.isEmpty()) {
                val body = if (failed.isEmpty()) {
                    Body.Empty(PhotoSearchCopy.empty(state.query))
                } else {
                    Body.CouldNotLook(PhotoSearchCopy.couldNotLook(nothingLooked = !anySourceLooked(state)))
                }
                return PhotoSearchLayout(off, body, retryInNotes = false)
            }
            return PhotoSearchLayout(
                notes = off + PhotoSearchCopy.missed(failed),
                body = Body.Results(
                    hits = state.hits,
                    footers = listOfNotNull(if (state.capped) PhotoSearchCopy.capped(state.hits.size) else null),
                ),
                retryInNotes = failed.isNotEmpty(),
            )
        }

        /**
         * Whether at least one source actually LOOKED. ⚠ "Off" is not looking: a refusal the proxy
         * named, or a platform with no index, searched nothing — so name and tag both failing beside
         * it is "nothing was looked through", not "nothing turned up".
         */
        private fun anySourceLooked(state: PhotoSearchUiState): Boolean {
            val failed = state.couldNotLook
            return PhotoHitSource.NAME !in failed ||
                PhotoHitSource.LABEL !in failed ||
                (state.semanticStatus == SemanticSearchStatus.Available && PhotoHitSource.SEMANTIC !in failed)
        }
    }
}

/** Every sentence of the Search screen — one copy for both shells. */
object PhotoSearchCopy {
    /** The bar's placeholder. */
    const val PROMPT = "Search photos"

    /**
     * ⚠ It says what actually works rather than promising three sources, two of which no device can
     * run today.
     */
    const val IDLE = "Search by file name."

    /** [IDLE] where search by description has been seen to run on this device. */
    const val IDLE_WITH_DESCRIPTION = "Search by file name or by what's in a photo."

    /**
     * The idle line. ⚠⚠ It promises search by description only when source 3's last ANSWER was that
     * it ran ([PhotoSearchUiState.semanticKnown] is [SemanticSearchStatus.Available]). Every
     * other state keeps [IDLE]: not asked yet (the seed is an assumption, and on a device with no
     * backend it is false), no model, still preparing, not indexed, no consent, a model that cannot
     * embed text, a phone found unsuitable, a platform with no index ([SemanticSearchStatus.NotBuiltHere]),
     * and a last attempt that failed — each a query that would refuse, or might. ⚠ The line names no
     * reason: that is the note's, once a query is typed.
     */
    fun idle(state: PhotoSearchUiState): String =
        if (state.semanticKnown == SemanticSearchStatus.Available) IDLE_WITH_DESCRIPTION else IDLE

    const val RETRY = "Try Again"

    /** ⚠ The query as the user typed it, trimmed — the question that was asked. */
    fun empty(query: String): String = "No photos match \"${query.trim()}\"."

    /**
     * ⚠ Said rather than hidden: a screen that quietly shows 60 of 900 is the dishonest version of a
     * capped list.
     */
    fun capped(shown: Int): String =
        "Showing the first $shown matches. Narrow the search to see fewer, better ones."

    /**
     * Nothing to show AND a source did not answer. ⚠⚠ Never "No photos match": what was not looked
     * through cannot have been found empty (`photos-app.md` §40's rule, for search).
     */
    fun couldNotLook(nothingLooked: Boolean): String = if (nothingLooked) {
        "Couldn't search your photos. Nothing was looked through — that doesn't mean nothing matches."
    } else {
        "Couldn't search everything. Nothing turned up in what was looked through — " +
            "that doesn't mean nothing matches."
    }

    /**
     * What the results on screen may be MISSING — one line per source that did not answer.
     * ⚠ Source 3's line keeps its own words: it was never "a name" or "a tag" the user could check.
     */
    fun missed(couldNotLook: Set<PhotoHitSource>): List<String> = buildList {
        val name = PhotoHitSource.NAME in couldNotLook
        val label = PhotoHitSource.LABEL in couldNotLook
        when {
            name && label -> add("Couldn't search by file name or tag this time, so there may be more.")
            name -> add("Couldn't search by file name this time, so there may be more.")
            label -> add("Couldn't search by tag this time, so there may be more.")
        }
        if (PhotoHitSource.SEMANTIC in couldNotLook) add(SEMANTIC_FAILED)
    }

    /**
     * ⚠ Distinct from a named refusal: something broke, and pretending to know what would be a
     * confident guess about a failure we do not understand.
     */
    const val SEMANTIC_FAILED = "Searching photos by appearance didn't work this time."

    /**
     * Why source 3 is OFF here, or `null` when it ran (or failed — that is [missed]'s line).
     *
     * ⚠ Photos' own wording, deliberately not Mia's. The four reasons and their meanings are shared
     * (`SemanticSearchUnavailable.Reasons`); the sentences are not, because Mia answers in the first
     * person in a chat and this is a note under a search bar.
     *
     * ⚠⚠ Only the consent refusal names an action, because it is the only one with a switch to point
     * at: Settings → Models (`inference-service.md` §32), in Control or, where this app runs its own
     * proxy, in this app ([modelsSwitch]). ⚠ It names that screen's own words for the switch — a
     * sentence pointing at a label that does not exist is worse than none. It cannot reach a platform
     * that has no such switch: there the status is [SemanticSearchStatus.NotBuiltHere] and the proxy is
     * never asked ([DescriptionSearch.NotBuilt]).
     */
    fun off(status: SemanticSearchStatus, modelsSwitch: ModelsSwitchHome): String? = when (status) {
        SemanticSearchStatus.Available, SemanticSearchStatus.Failed -> null
        // ⚠⚠ "Yet", and nothing to do about it: no switch, no download — neither exists here.
        is SemanticSearchStatus.NotBuiltHere -> "Search by description isn't available on this ${status.device} yet."
        is SemanticSearchStatus.Unavailable -> when (status.reason) {
            "noConsent" ->
                "Searching photos by what's in them is switched off for this device. Turn on " +
                    "\"Search photos by description\" in " +
                    when (modelsSwitch) {
                        ModelsSwitchHome.CONTROL -> "Control"
                        ModelsSwitchHome.THIS_APP -> "Photos"
                    } + ", under Settings → Models."
            "noBackend" ->
                "Searching photos by what's in them needs a model on this device, and there isn't one yet."
            "notIndexed" ->
                "Your photos haven't been looked through on this device yet."
            "noTextEmbedding" ->
                "The model on this device can tag photos but can't search them by description."
            // ⚠ A reason this build has not heard of — a newer proxy, or one added without updating
            // this list. The generic line is the honest answer (MQ-D4's rule).
            else -> "Searching photos by what's in them isn't available on this device."
        }
    }

    /**
     * Which source found this row — the badge under its tile, or `null` for none.
     * ⚠ See [PhotoHit.primarySource] for why the order is what it is.
     */
    fun badge(hit: PhotoHit): String? = when (hit.primarySource) {
        // ⚠ No badge for a name match: it is the only source a user can verify by reading the
        // filename, so labelling it adds noise to the common case rather than information.
        PhotoHitSource.NAME -> null
        PhotoHitSource.LABEL -> hit.matchedLabel?.let { "tagged \"$it\"" } ?: "tagged"
        PhotoHitSource.SEMANTIC -> "looks like this"
    }
}
