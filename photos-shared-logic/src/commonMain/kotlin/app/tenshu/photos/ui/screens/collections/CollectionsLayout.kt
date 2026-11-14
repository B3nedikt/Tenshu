package app.tenshu.photos.ui.screens.collections

import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.search.ModelsSwitchHome

/** A fixed destination at the top of the tab — a compact tile, not a full-width block (§35). */
data class CollectionsTile(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    /** The smart album a [Kind.VIDEOS] tile opens; `null` for the others. */
    val smartAlbum: SmartAlbum? = null,
    /**
     * Its read failed (§36): the tile says so and a tap RETRIES rather than opening what it kept —
     * the failed tile's own Try again, as every failed section has one.
     */
    val failed: Boolean = false,
) {
    enum class Kind { FAVOURITES, DUPLICATES, VIDEOS }
}

/**
 * A utility at the foot of the tab — Apple's *Utilities* (`photos-ios.md` PIO-D3, §8.17): the
 * places a user goes to look after the library rather than to look at it.
 */
data class CollectionsUtility(val kind: Kind, val title: String, val subtitle: String) {
    enum class Kind { TRASH }

    companion object {
        const val SECTION_TITLE = "Utilities"
    }
}

/**
 * The People row: its one explanatory line, its cards, and whether *View all* is offered.
 *
 * @param pending its read is in flight and nothing is drawn yet — a placeholder row (§36).
 * @param failure its read failed — said in the row, beside any cards kept from before (§36).
 */
data class CollectionsPeopleRow(
    val statusLine: String?,
    val people: List<SmartAlbum>,
    val viewAll: Boolean,
    val pending: Boolean = false,
    val failure: String? = null,
)

/**
 * A derived section's row — present when it has something in it, or when its read failed (§36).
 *
 * @param failure the section's read failed: said in the row, beside any cards kept from before.
 */
data class CollectionsSmartRow(
    val section: CollectionSection,
    val albums: List<SmartAlbum>,
    val failure: String? = null,
)

/**
 * The Collections tab as both shells draw it (`photos-app.md` S4/§35).
 *
 * ⚠⚠ Decided here, drawn there. Which sections show, which may show while empty, when *View all* is
 * offered, the tiles and every sentence the tab says are decided once, not inside Compose's
 * `CollectionsScreen` and again in the iOS tab: two copies of a tab drift into two apps that show
 * one library differently.
 */
sealed interface CollectionsLayout {
    /** Nothing to show yet. */
    data object Loading : CollectionsLayout

    /** ⚠⚠ Error before empty, always — an unreachable proxy is not an empty shelf. */
    data class Failed(val message: String) : CollectionsLayout

    data class Content(
        /**
         * ⚠⚠ A failed refresh over content already on screen still says so — without it
         * a timed-out refresh after an album edit keeps stale rows and offered no way to retry.
         */
        val refreshFailure: String?,
        val tiles: List<CollectionsTile>,
        /** ⚠⚠ Never a bare header over no cards: present with cards, or with a line saying WHY not. */
        val people: CollectionsPeopleRow?,
        /** ⚠ The Albums row is ALWAYS present, even empty — it holds the one way to make an album. */
        val albums: List<AlbumSummary>,
        /**
         * ⚠ *View all* whenever a section has anything, not only when it overflows its row: a link that
         * comes and goes makes the user count tiles to learn whether there are more.
         */
        val albumsViewAll: Boolean,
        /** Places, Things, Years — each only when it has something in it. */
        val rows: List<CollectionsSmartRow>,
        /**
         * ⚠ Always present, even over an empty library: the trash can hold photographs the library no
         * longer shows, and the way back to them must not depend on there being anything else.
         */
        val utilities: List<CollectionsUtility>,
        /** The tab's own empty copy, or `null`. */
        val emptyText: String?,
        /** The albums read failed — said in the Albums row, not over the tab (§36). */
        val albumsFailure: String? = null,
        /** The library walk is in flight and there are no years yet — a placeholder Years row (§36). */
        val libraryPending: Boolean = false,
    ) : CollectionsLayout

    companion object {
        /** How many items a row shows before *View all*. */
        const val ROW_LIMIT: Int = 12

        /**
         * What a section's own page says when its read failed (§36), or `null` — the same sentence the
         * tab's row says, so a page never passes kept cards off as current. Shared so
         * the iOS section page says it too.
         */
        fun sectionFailure(section: CollectionSection, state: CollectionsUiState, groupingRunsHere: Boolean): String? {
            val libraryFailed = state.libraryLoad == SectionLoad.FAILED
            val (load, empty, noun) = when (section) {
                CollectionSection.PEOPLE -> {
                    if (!groupingRunsHere) return null
                    Triple(state.peopleLoad, state.people.isEmpty(), "people")
                }
                CollectionSection.ALBUMS -> Triple(state.albumsLoad, state.albums.isEmpty(), "your albums")
                CollectionSection.PLACES ->
                    Triple(if (libraryFailed) SectionLoad.FAILED else state.placesLoad, state.places.isEmpty(), "places")
                CollectionSection.THINGS ->
                    Triple(if (libraryFailed) SectionLoad.FAILED else state.thingsLoad, state.things.isEmpty(), "things")
                CollectionSection.YEARS -> Triple(state.libraryLoad, state.years.isEmpty(), "years")
            }
            if (load != SectionLoad.FAILED) return null
            return if (empty) "Couldn't load $noun." else "Couldn't refresh $noun."
        }

        /**
         * [groupingRunsHere] is the platform's answer to "can this device group faces?" — ⚠⚠ it
         * silences People's line where the remedy cannot be taken (see [peopleStatusLine]).
         * [modelsSwitch] is where that line sends the user: Control (Android) or this app (iOS);
         * [faceModelPending] says the face model is installed and not ready yet (iOS only).
         */
        fun of(
            state: CollectionsUiState,
            groupingRunsHere: Boolean,
            modelsSwitch: ModelsSwitchHome = ModelsSwitchHome.CONTROL,
            faceModelPending: Boolean = false,
        ): CollectionsLayout = when {
            // ⚠⚠ No full-screen spinner any more (§36): the tab draws its tiles and a placeholder row
            // per loading section at once, and each fills in when its own read lands. [Loading] stays
            // for a shell that has not drawn anything yet, but this never answers it.
            // ⚠⚠ Error before empty, always — an unreachable proxy is not an empty shelf; and only once
            // the refresh is over, so a slow albums read cannot flash it over sections still loading.
            state.error != null && !state.hasContent && !state.loading -> Failed(
                when (state.error) {
                    FeedError.Unreachable ->
                        "Can't reach Tenshu Control.\nYour collections are safe — Control just isn't running."
                    else -> "Something went wrong loading your collections."
                },
            )
            else -> content(state, groupingRunsHere, modelsSwitch, faceModelPending)
        }

        private fun content(
            state: CollectionsUiState,
            groupingRunsHere: Boolean,
            modelsSwitch: ModelsSwitchHome,
            faceModelPending: Boolean,
        ): Content {
            // ⚠ `years`, not `isEmpty`: an empty album keeps the tab from being empty, and a user with
            // no photographs must not be asked to index them. A year row exists exactly
            // when a photograph does.
            // ⚠⚠ Not while People's read is in flight (§36): `peopleStatus` holds its default ("no face
            // models") until the read lands, and saying so would send the user to download a model they
            // already have.
            val peopleLoading = state.peopleLoad == SectionLoad.LOADING
            val peopleLine = if (state.years.isEmpty() || peopleLoading) {
                null
            } else {
                peopleStatusLine(state.peopleStatus, hasPeople = state.people.isNotEmpty(), groupingRunsHere, modelsSwitch, faceModelPending)
            }
            // ⚠ Gated like everything else People says: a platform that cannot group faces
            // never grows a People section to report a failure in.
            val peopleFailure = if (state.peopleLoad == SectionLoad.FAILED && groupingRunsHere) {
                if (state.people.isEmpty()) "Couldn't load people." else "Couldn't refresh people."
            } else {
                null
            }
            val peoplePending = peopleLoading && state.people.isEmpty() && groupingRunsHere
            // ⚠ A failed read is said in its section — beside cards kept from before, since stale rows
            // shown as current are the silent kind of wrong — and on its own when it has none. Places and
            // Things fail only when the walk itself succeeded: a walk failure is one failure, said once,
            // on Years.
            fun failure(load: SectionLoad, title: String, empty: Boolean): String? =
                if (load == SectionLoad.FAILED) (if (empty) "Couldn't load $title." else "Couldn't refresh $title.") else null
            val libraryOk = state.libraryLoad != SectionLoad.FAILED
            return Content(
                albumsFailure = if (state.albumsLoad == SectionLoad.FAILED && state.error == null) "Couldn't load your albums." else null,
                libraryPending = state.years.isEmpty() && state.libraryLoad == SectionLoad.LOADING,
                refreshFailure = state.error?.let { error ->
                    when (error) {
                        FeedError.Unreachable -> "Can't reach Tenshu Control — showing what was loaded."
                        else -> "Couldn't refresh your collections."
                    }
                },
                tiles = buildList {
                    // ⚠ A failed read says so rather than showing the last count as current.
                    // ⚠ And no "0 photos" before a read has said so.
                    val favourites = when (state.favouritesLoad) {
                        SectionLoad.FAILED -> "Couldn't load · Tap to retry"
                        // ⚠ §36 step 2: the last known count while its read is in flight — a count only
                        // the on-device snapshot can supply, since a confirmed one stays LOADED.
                        SectionLoad.LOADING -> if (state.favouriteCount > 0) photoCount(state.favouriteCount) else "Loading…"
                        SectionLoad.LOADED -> photoCount(state.favouriteCount)
                    }
                    add(CollectionsTile(CollectionsTile.Kind.FAVOURITES, "Favourites", favourites, failed = state.favouritesLoad == SectionLoad.FAILED))
                    // ⚠ No count: knowing it means walking the realm again. A promise of work, not a result.
                    add(CollectionsTile(CollectionsTile.Kind.DUPLICATES, "Duplicates", "Find copies"))
                    state.videos?.let { videos ->
                        // ⚠ Videos is fed by the same library walk as Years: when that walk failed, its
                        // kept count is not current either.
                        val subtitle = if (state.libraryLoad == SectionLoad.FAILED) "Couldn't refresh · Tap to retry" else videos.subtitle
                        add(CollectionsTile(CollectionsTile.Kind.VIDEOS, videos.title, subtitle, videos, failed = state.libraryLoad == SectionLoad.FAILED))
                    }
                },
                people = if (state.people.isNotEmpty() || peopleLine != null || peopleFailure != null || peoplePending) {
                    CollectionsPeopleRow(
                        statusLine = if (peopleFailure != null) null else peopleLine,
                        people = state.people.take(ROW_LIMIT),
                        viewAll = state.people.isNotEmpty(),
                        pending = peoplePending,
                        failure = peopleFailure,
                    )
                } else {
                    null
                },
                albums = state.albums.take(ROW_LIMIT),
                albumsViewAll = state.albums.isNotEmpty(),
                rows = listOf(
                    // ⚠ After a failed walk, Places and Things keep their cards — and say they are
                    // not current; an EMPTY one stays absent, its failure being the walk's, said on
                    // Years.
                    Triple(CollectionSection.PLACES, state.places, if (libraryOk || state.places.isNotEmpty()) failure(state.placesLoad, "places", state.places.isEmpty()) else null),
                    Triple(CollectionSection.THINGS, state.things, if (libraryOk || state.things.isNotEmpty()) failure(state.thingsLoad, "things", state.things.isEmpty()) else null),
                    Triple(CollectionSection.YEARS, state.years, failure(state.libraryLoad, "years", state.years.isEmpty())),
                ).filter { (_, albums, failure) -> albums.isNotEmpty() || failure != null }
                    .map { (section, albums, failure) -> CollectionsSmartRow(section, albums.take(ROW_LIMIT), failure) },
                utilities = listOf(
                    CollectionsUtility(
                        CollectionsUtility.Kind.TRASH,
                        "Trash",
                        "Deleted photos wait here until you empty it",
                    ),
                ),
                emptyText = if (state.isEmpty(ignorePeople = !groupingRunsHere)) {
                    "Nothing here yet.\nCollections group your library by place, date and " +
                        "what's in a photo — and an album is a list you choose yourself."
                } else {
                    null
                },
            )
        }
    }
}
