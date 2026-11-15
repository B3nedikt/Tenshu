package app.tenshu.photos

import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionSection
import app.tenshu.photos.ui.screens.collections.CollectionsLayout
import app.tenshu.photos.ui.screens.collections.CollectionsTile
import app.tenshu.photos.ui.screens.collections.CollectionsUiState
import app.tenshu.photos.ui.screens.collections.SectionLoad
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.search.ModelsSwitchHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Collections tab, decided once for both shells. */
class CollectionsLayoutTest {

    private fun smart(id: String) = SmartAlbum(id, id, "1 photo", listOf(Photo("/$id.jpg", 1L, isVideo = false, contentId = id)))
    private fun album(id: String) = AlbumSummary(id, id, createdAtMs = 1, photoCount = 1, coverPath = null)

    private fun content(state: CollectionsUiState, groupingRunsHere: Boolean = true) =
        assertIs<CollectionsLayout.Content>(CollectionsLayout.of(state, groupingRunsHere))

    @Test
    fun anUnreachableProxyIsAnError_neverAnEmptyShelf() {
        val layout = CollectionsLayout.of(CollectionsUiState(error = FeedError.Unreachable), groupingRunsHere = true)
        assertTrue((layout as CollectionsLayout.Failed).message.startsWith("Can't reach Tenshu Control."))
    }

    @Test
    fun aFailedRefreshOverContentKeepsTheContent_andSaysSo() {
        val layout = content(CollectionsUiState(albums = listOf(album("a")), error = FeedError.Unreachable))
        assertEquals("Can't reach Tenshu Control — showing what was loaded.", layout.refreshFailure)
        assertEquals(1, layout.albums.size)
    }

    @Test
    fun theTrashIsAlwaysUnderUtilities_evenOverAnEmptyLibrary() {
        // ⚠ `photos-ios.md` PIO-D3: the trash can hold photographs the library no longer shows, and
        // the way back to them must not depend on there being anything else on the tab.
        val utilities = content(CollectionsUiState()).utilities
        assertEquals(listOf(app.tenshu.photos.ui.screens.collections.CollectionsUtility.Kind.TRASH), utilities.map { it.kind })
        assertEquals("Trash", utilities.single().title)
    }

    @Test
    fun theAlbumsRowIsAlwaysThere_viewAllOnlyWithAlbums() {
        val empty = content(CollectionsUiState())
        assertTrue(empty.albums.isEmpty())
        assertEquals(false, empty.albumsViewAll, "nothing to view all of")
        assertNotNull(empty.emptyText, "and the tab says it is empty")
        assertEquals(true, content(CollectionsUiState(albums = listOf(album("a")))).albumsViewAll)
    }

    @Test
    fun derivedRowsAppearOnlyWithSomethingInThem_inOrder_andCappedAtTheRowLimit() {
        val places = List(20) { smart("p$it") }
        val layout = content(CollectionsUiState(places = places, years = listOf(smart("2024"))))
        assertEquals(listOf(CollectionSection.PLACES, CollectionSection.YEARS), layout.rows.map { it.section })
        assertEquals(CollectionsLayout.ROW_LIMIT, layout.rows.first().albums.size)
    }

    @Test
    fun peopleExplainsAnEmptySection_butOnlyWhereThereArePhotographsAndTheRemedyCanBeTaken() {
        val unindexed = CollectionsUiState(
            years = listOf(smart("2024")),
            peopleStatus = PeopleStatus.Unavailable(org.tenshu.inference.FaceGroupsUnavailable.Reasons.NOT_INDEXED),
        )
        assertNotNull(content(unindexed).people?.statusLine, "says why nobody is there")
        assertNull(content(unindexed, groupingRunsHere = false).people, "no nag where faces cannot be grouped")
        assertNull(content(unindexed.copy(years = emptyList())).people, "no photographs, no ask to index them")
    }

    /**
     * `ios-face-grouping.md` F7: where Photos runs its own proxy (iOS), People's line sends the user to
     * PHOTOS' Settings → Models — Control-on-iOS has no such screen — and a pass there starts with
     * *Index now*, not Android's idle-and-charging schedule. Control's lines are unchanged.
     */
    @Test
    fun peoplesLineNamesThisAppsModelsScreenWhereThisAppRunsItsOwnProxy() {
        fun line(reason: String?, home: ModelsSwitchHome) = (
            CollectionsLayout.of(
                CollectionsUiState(
                    years = listOf(smart("2024")),
                    peopleStatus = reason?.let { PeopleStatus.Unavailable(it) } ?: PeopleStatus.Available,
                ),
                groupingRunsHere = true,
                modelsSwitch = home,
            ) as CollectionsLayout.Content
            ).people?.statusLine
        val reasons = org.tenshu.inference.FaceGroupsUnavailable.Reasons
        assertEquals(
            "To see people here, turn on \"Group photos by people\" for this device in Photos, under Settings → Models.",
            line(reasons.NO_CONSENT, ModelsSwitchHome.THIS_APP),
        )
        assertEquals(
            "To see people here, download the face model in Photos, under Settings → Models.",
            line(reasons.NO_BACKEND, ModelsSwitchHome.THIS_APP),
        )
        assertEquals(
            "People appear after Photos looks through your photos on this device. Tap Index now, under Settings → Models.",
            line(reasons.NOT_INDEXED, ModelsSwitchHome.THIS_APP),
        )
        assertEquals("No people found yet.", line(null, ModelsSwitchHome.THIS_APP))
        assertEquals(
            "To see people here, download the face models in Control, under Settings → Models.",
            line(reasons.NO_BACKEND, ModelsSwitchHome.CONTROL),
        )
        val pending = (
            CollectionsLayout.of(
                CollectionsUiState(years = listOf(smart("2024")), peopleStatus = PeopleStatus.Unavailable(reasons.NO_BACKEND)),
                groupingRunsHere = true,
                modelsSwitch = ModelsSwitchHome.THIS_APP,
                faceModelPending = true,
            ) as CollectionsLayout.Content
            ).people?.statusLine
        assertEquals("People appear once the face model is ready on this device.", pending, "never 'download' a model already here")
        listOf(reasons.NO_CONSENT, reasons.NO_BACKEND, reasons.NOT_INDEXED).forEach { reason ->
            assertTrue("Control" !in line(reason, ModelsSwitchHome.THIS_APP)!!, "$reason names Control on iOS")
        }
    }

    @Test
    fun theTilesAreFavouritesAndDuplicates_plusVideosWhenThereAreAny() {
        assertEquals(
            listOf(CollectionsTile.Kind.FAVOURITES, CollectionsTile.Kind.DUPLICATES),
            content(CollectionsUiState()).tiles.map { it.kind },
        )
        val videos = smart("videos")
        val tiles = content(CollectionsUiState(videos = videos)).tiles
        assertEquals(videos, tiles.last().smartAlbum)
    }

    @Test
    fun aRouteFindsItsSectionByIdOrByAlias() {
        // ⚠ Naming a second cluster re-keys a person's card; the open route may hold an alias.
        val person = smart("person:1").copy(alternateIds = listOf("person:7"))
        val state = CollectionsUiState(people = listOf(person))
        assertEquals(person, state.smartAlbumFor("person:1"))
        assertEquals(person, state.smartAlbumFor("person:7"), "the alias still reaches it")
        assertNull(state.smartAlbumFor("person:9"))
    }

    @Test
    fun anAlbumScreenSaysWhyThereIsNoGrid_andNothingWhileLoadingOrWithPhotos() {
        val photo = Photo("/a.jpg", 1L, isVideo = false, contentId = "a")
        fun text(s: app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState) = s.statusText
        assertNull(text(app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState(loading = true)))
        assertNull(text(app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState(loading = false, photos = listOf(photo))))
        assertEquals(
            "This album is empty.\nAdd photos to it from the feed.",
            text(app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState(loading = false)),
        )
        assertEquals(
            "No favourites yet.\nTap the heart on a photo to keep it here.",
            text(app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState(loading = false, editable = false)),
        )
        assertTrue(
            text(app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState(loading = false, error = FeedError.Unreachable))!!
                .startsWith("Can't reach Tenshu Control."),
            "an error wins over empty",
        )
    }

    @Test
    fun namedPeopleComeFirstInViewAll_eachHalfKeepingItsOrder() {
        // ⚠ Unnamed clusters (largest first) must not bury the names.
        val big = smart("c1").copy(title = "")
        val ana = smart("c2").copy(title = "Ana")
        val small = smart("c3").copy(title = "")
        val ben = smart("c4").copy(title = "Ben")
        val ordered = CollectionsUiState(people = listOf(big, ana, small, ben)).peopleNamedFirst
        assertEquals(listOf("c2", "c4", "c1", "c3"), ordered.map { it.id })
    }

    // ── §36: per-section states, decided here so both shells draw the same ──────────────────

    @Test
    fun theTabIsDrawnAtOnce_neverAFullScreenSpinner() {
        val layout = CollectionsLayout.of(
            CollectionsUiState(loading = true, libraryLoad = SectionLoad.LOADING, peopleLoad = SectionLoad.LOADING),
            groupingRunsHere = true,
        )
        val content = assertIs<CollectionsLayout.Content>(layout)
        assertTrue(content.libraryPending, "Years shows a placeholder while the walk runs")
        assertTrue(content.people?.pending == true, "People shows a placeholder while its read runs")
    }

    /** ⚠⚠ While People's read is in flight its status holds the default — never said. */
    @Test
    fun peopleSaysNothingAboutModelsWhileItsReadIsInFlight() {
        val people = content(
            CollectionsUiState(years = listOf(smart("year:2026")), peopleLoad = SectionLoad.LOADING),
        ).people
        assertNull(people?.statusLine)
    }

    @Test
    fun aFailedSectionSaysSoBesideItsKeptCards_andAloneWhenItHasNone() {
        val kept = content(CollectionsUiState(years = listOf(smart("year:2026")), libraryLoad = SectionLoad.FAILED))
        assertEquals("Couldn't refresh years.", kept.rows.single { it.section == CollectionSection.YEARS }.failure)

        val none = content(CollectionsUiState(favouriteCount = 1, libraryLoad = SectionLoad.FAILED))
        assertEquals("Couldn't load years.", none.rows.single { it.section == CollectionSection.YEARS }.failure)
        assertNull(none.emptyText, "a failed walk is not an empty library")
    }

    /** An albums failure is the Albums row's; only an unreachable Control is the tab's. */
    @Test
    fun anAlbumsFailureStaysInItsRow() {
        val layout = CollectionsLayout.of(CollectionsUiState(albumsLoad = SectionLoad.FAILED), groupingRunsHere = true)
        assertEquals("Couldn't load your albums.", assertIs<CollectionsLayout.Content>(layout).albumsFailure)
    }

    @Test
    fun aFailedPeopleReadIsSaid_onlyWhereFacesCanBeGrouped() {
        val state = CollectionsUiState(years = listOf(smart("year:2026")), peopleLoad = SectionLoad.FAILED)
        assertEquals("Couldn't load people.", content(state).people?.failure)
        assertNull(content(state, groupingRunsHere = false).people, "no People section to fail in")
    }

    @Test
    fun oneFavouriteReadsOnePhoto() {
        fun favourites(n: Int) = content(CollectionsUiState(favouriteCount = n, favouritesLoad = SectionLoad.LOADED))
            .tiles.single { it.kind == CollectionsTile.Kind.FAVOURITES }.subtitle
        assertEquals("1 photo", favourites(1))
        assertEquals("3 photos", favourites(3))
    }

    @Test
    fun aFailedFavouritesReadDoesNotShowTheOldCount() {
        val tile = content(CollectionsUiState(favouriteCount = 7, favouritesLoad = SectionLoad.FAILED))
            .tiles.single { it.kind == CollectionsTile.Kind.FAVOURITES }
        assertEquals("Couldn't load · Tap to retry", tile.subtitle)
        assertTrue(tile.failed, "and a tap retries")
    }

    /** Every section's own page says its failed read — the same sentence as its row. */
    @Test
    fun everySectionPageSaysItsFailedRead() {
        val state = CollectionsUiState(
            years = listOf(smart("year:2026")),
            libraryLoad = SectionLoad.FAILED,
            albumsLoad = SectionLoad.FAILED,
        )
        assertEquals("Couldn't refresh years.", CollectionsLayout.sectionFailure(CollectionSection.YEARS, state, true))
        assertEquals("Couldn't load your albums.", CollectionsLayout.sectionFailure(CollectionSection.ALBUMS, state, true))
        assertEquals("Couldn't load places.", CollectionsLayout.sectionFailure(CollectionSection.PLACES, state, true), "a failed walk fails places too")
        assertNull(CollectionsLayout.sectionFailure(CollectionSection.PEOPLE, state.copy(peopleLoad = SectionLoad.FAILED), false))
        assertNull(CollectionsLayout.sectionFailure(CollectionSection.YEARS, CollectionsUiState(years = listOf(smart("year:2026"))), true))
    }

    /** No "0 photos" before a favourites read has answered. */
    @Test
    fun favouritesSaysLoadingUntilItsReadAnswers() {
        val tile = content(CollectionsUiState(favouritesLoad = SectionLoad.LOADING, years = listOf(smart("year:2026"))))
            .tiles.single { it.kind == CollectionsTile.Kind.FAVOURITES }
        assertEquals("Loading…", tile.subtitle)
    }

    /** The Videos tile shares the library walk with Years, and its failure. */
    @Test
    fun aFailedLibraryWalkMarksTheKeptVideosTile() {
        val videos = SmartAlbum("videos", "Videos", "3 videos", emptyList())
        val tile = content(CollectionsUiState(videos = videos, libraryLoad = SectionLoad.FAILED))
            .tiles.single { it.kind == CollectionsTile.Kind.VIDEOS }
        assertEquals("Couldn't refresh · Tap to retry", tile.subtitle)
        assertTrue(tile.failed)
    }

    /** Where faces cannot be grouped, a hidden People failure does not hide the empty copy. */
    @Test
    fun aHiddenPeopleFailureDoesNotHideTheEmptyCopy() {
        val state = CollectionsUiState(peopleLoad = SectionLoad.FAILED)
        assertNotNull(content(state, groupingRunsHere = false).emptyText)
        assertNull(content(state, groupingRunsHere = true).emptyText)
    }

    /** After a failed walk, kept Places and Things cards say they are not current. */
    @Test
    fun keptPlacesAndThingsSayAFailedWalkToo() {
        val state = CollectionsUiState(
            places = listOf(smart("place:a")),
            libraryLoad = SectionLoad.FAILED,
            placesLoad = SectionLoad.FAILED,
            thingsLoad = SectionLoad.FAILED,
        )
        val rows = content(state).rows
        assertEquals("Couldn't refresh places.", rows.single { it.section == CollectionSection.PLACES }.failure)
        assertTrue(rows.none { it.section == CollectionSection.THINGS }, "an empty Things leaves the walk's failure to Years")
    }
}
