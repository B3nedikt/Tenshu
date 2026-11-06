package app.tenshu.photos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import app.tenshu.photos.ui.screens.collections.SECTION_PLACEHOLDER_TAG
import app.tenshu.photos.ui.screens.collections.SectionLoad
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.model.FaceCover
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionSection
import app.tenshu.photos.ui.screens.collections.CollectionSectionScreen
import app.tenshu.photos.ui.screens.collections.CollectionsScreen
import app.tenshu.photos.ui.screens.collections.CollectionsUiState
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.inference.FaceGroupsUnavailable.Reasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Collections tab as rows, and the page behind each row's *View all* (`photos-app.md` §35). */
@OptIn(ExperimentalTestApi::class)
class CollectionRowsTest {

    private fun photo(i: Int) = Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")
    private val face = FaceCover("/face.jpg", FaceRegion(0.4f, 0.3f, 0.6f, 0.5f))
    private fun person(id: String, title: String) =
        SmartAlbum(id, title, "2 photos", listOf(photo(1), photo(2)), coverFace = face)

    private val year2026 = SmartAlbum("year:2026", "2026", "9 photos", listOf(photo(3)))
    private val munich = SmartAlbum("place:munich", "Munich", "Bavaria", listOf(photo(4)))

    @Test
    fun eachSectionsViewAllOpensThatSection() = runComposeUiTest {
        val opened = mutableListOf<CollectionSection>()
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(
                        people = listOf(person("cluster:1", "Anna")),
                        places = listOf(munich),
                        years = listOf(year2026),
                        peopleStatus = PeopleStatus.Available,
                    ),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                    onOpenSection = { opened += it },
                    groupingRunsHere = true,
                )
            }
        }

        waitUntil { onAllNodesWithText("View all").fetchSemanticsNodes().isNotEmpty() }
        // People, Places, Years — Albums has none: an empty albums row has nothing to view.
        val links = onAllNodesWithText("View all")
        assertEquals(3, links.fetchSemanticsNodes().size)
        for (i in 0 until 3) links[i].performClick()
        assertEquals(listOf(CollectionSection.PEOPLE, CollectionSection.PLACES, CollectionSection.YEARS), opened)
    }

    /** ⚠ The Albums row is there with no albums: it holds the only way to make one. */
    @Test
    fun theAlbumsRowLeadsWithNewAlbum_evenWithNoAlbums() = runComposeUiTest {
        var creating = 0
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(years = listOf(year2026)),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = { creating++ },
                    onRetry = {},
                )
            }
        }

        waitUntil { onAllNodesWithText("New album").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Albums").assertIsDisplayed()
        onNodeWithText("New album").performClick()
        assertEquals(1, creating)
    }

    @Test
    fun videosIsACompactTileBesideFavouritesAndDuplicates() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(
                        years = listOf(year2026),
                        videos = SmartAlbum("videos", "Videos", "3 videos", listOf(photo(5))),
                    ),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = { opened += it.id },
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                )
            }
        }

        waitUntil { onAllNodesWithText("Videos").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Favourites").assertIsDisplayed()
        onNodeWithText("Duplicates").assertIsDisplayed()
        onNodeWithText("Videos").performClick()
        assertEquals(listOf("videos"), opened)
    }

    // ── the section pages ───────────────────────────────────────────────────────────────────

    private fun androidx.compose.ui.test.ComposeUiTest.sectionPage(
        section: CollectionSection,
        state: CollectionsUiState,
        onOpen: (SmartAlbum) -> Unit = {},
        onCreateAlbum: () -> Unit = {},
    ) = setContent {
        TenshuTheme {
            CollectionSectionScreen(
                section = section,
                state = state,
                loadPreview = { _, _ -> null },
                onOpenAlbum = { _, _ -> },
                onOpenSmartAlbum = onOpen,
                onCreateAlbum = onCreateAlbum,
                onBack = {},
                groupingRunsHere = true,
            )
        }
    }

    /** ⚠ Named first: an unnamed face is what a user scrolls past to find someone they named. */
    @Test
    fun thePeoplePageListsNamedPeopleFirst_withoutCounts() = runComposeUiTest {
        sectionPage(
            CollectionSection.PEOPLE,
            CollectionsUiState(
                people = listOf(person("cluster:1", ""), person("person:name:anna", "Anna"), person("cluster:2", "")),
                peopleStatus = PeopleStatus.Available,
            ),
        )

        waitUntil { onAllNodesWithText("Anna").fetchSemanticsNodes().isNotEmpty() }
        val anna = onAllNodesWithText("Anna").fetchSemanticsNodes().single().boundsInRoot
        val title = onAllNodesWithText("People").fetchSemanticsNodes().single().boundsInRoot
        // First cell of the first row: nothing to its left, directly under the bar.
        assertTrue(anna.left < 200f && anna.top > title.bottom, "Anna must be the first cell, got $anna")
        assertTrue(onAllNodesWithText("2 photos").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anEmptyPeoplePageSaysWhy() = runComposeUiTest {
        sectionPage(
            CollectionSection.PEOPLE,
            CollectionsUiState(years = listOf(year2026), peopleStatus = PeopleStatus.Unavailable(Reasons.NOT_INDEXED)),
        )
        waitUntil { onAllNodesWithText("People appear after", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun thePlacesPageIsAListThatOpensThePlace() = runComposeUiTest {
        val opened = mutableListOf<String>()
        sectionPage(CollectionSection.PLACES, CollectionsUiState(places = listOf(munich)), onOpen = { opened += it.id })

        waitUntil { onAllNodesWithText("Munich").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Bavaria").assertIsDisplayed()
        onNodeWithText("Munich").performClick()
        assertEquals(listOf("place:munich"), opened)
    }

    @Test
    fun theAlbumsPageOffersNewAlbumInItsBar() = runComposeUiTest {
        var creating = 0
        sectionPage(
            CollectionSection.ALBUMS,
            CollectionsUiState(albums = listOf(AlbumSummary("a1", "Holiday", 1L, 12, null))),
            onCreateAlbum = { creating++ },
        )

        waitUntil { onAllNodesWithText("Holiday").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("New album").performClick()
        assertEquals(1, creating)
    }

    /** A failed refresh over albums already shown must still offer a retry. */
    @Test
    fun aFailedRefreshOverShownAlbumsStillOffersTryAgain() = runComposeUiTest {
        var retries = 0
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(
                        albums = listOf(AlbumSummary("a1", "Holiday", 1L, 12, null)),
                        years = listOf(year2026),
                        error = FeedError.Failed("timed out in faceGroups"),
                    ),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = { retries++ },
                )
            }
        }

        waitUntil { onAllNodesWithText("Holiday").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Couldn't refresh your collections.").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    /**
     * Most users have no albums, and their loaded years and people must survive a
     * failed refresh — and a refresh in flight — instead of vanishing behind a full-screen state.
     */
    @Test
    fun withNoAlbumsLoadedSectionsSurviveARefreshAndAFailure() = runComposeUiTest {
        var current by mutableStateOf(CollectionsUiState(years = listOf(year2026), loading = true))
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = current,
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                )
            }
        }

        waitUntil { onAllNodesWithText("2026").fetchSemanticsNodes().isNotEmpty() }

        current = CollectionsUiState(years = listOf(year2026), error = FeedError.Failed("timed out in faceGroups"))
        waitUntil { onAllNodesWithText("Couldn't refresh your collections.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2026").assertIsDisplayed()
    }

    // ── §36: sections load on their own ─────────────────────────────────────────────────────

    private fun androidx.compose.ui.test.ComposeUiTest.tab(state: CollectionsUiState, onRetry: () -> Unit = {}) = setContent {
        TenshuTheme {
            CollectionsScreen(
                state = state,
                loadPreview = { _, _ -> null },
                onOpenAlbum = { _, _ -> },
                onOpenSmartAlbum = {},
                onOpenFavourites = {},
                onOpenDuplicates = {},
                onCreateAlbum = {},
                onRetry = onRetry,
                groupingRunsHere = true,
            )
        }
    }

    /**
     * ⚠⚠ While People's read is in flight `peopleStatus` holds its default ("no face models") —
     * the tab must show a placeholder, never that line.
     */
    @Test
    fun whilePeopleLoadsItShowsAPlaceholder_neverTheStatusLine() = runComposeUiTest {
        tab(CollectionsUiState(years = listOf(year2026), peopleLoad = SectionLoad.LOADING, loading = true))

        waitUntil { onAllNodesWithText("People").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(SECTION_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("face models", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFailedPeopleReadSaysSoInItsSection_withItsOwnTryAgain() = runComposeUiTest {
        var retries = 0
        tab(CollectionsUiState(years = listOf(year2026), peopleLoad = SectionLoad.FAILED), onRetry = { retries++ })

        waitUntil { onAllNodesWithText("Couldn't load people.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2026").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    /** No full-screen spinner: the tiles are there at once, and the library's row says it is coming. */
    @Test
    fun beforeAnyReadLandsTheTabIsDrawnNotASpinner() = runComposeUiTest {
        tab(
            CollectionsUiState(
                loading = true,
                libraryLoad = SectionLoad.LOADING,
                peopleLoad = SectionLoad.LOADING,
            ),
        )

        waitUntil { onAllNodesWithText("Favourites").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Albums").assertIsDisplayed()
        assertTrue(onAllNodesWithTag(SECTION_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFailedLibraryWalkSaysSo_ratherThanLookingEmpty() = runComposeUiTest {
        tab(CollectionsUiState(favouriteCount = 1, libraryLoad = SectionLoad.FAILED, peopleLoad = SectionLoad.FAILED))

        waitUntil { onAllNodesWithText("Couldn't load years.").fetchSemanticsNodes().isNotEmpty() }
        // ⚠ Not "Nothing here yet" beside it — a failed walk is not an empty library.
        assertTrue(onAllNodesWithText("Nothing here yet", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** A failed refresh beside retained years says so instead of passing them off as current. */
    @Test
    fun aFailedRefreshBesideRetainedYearsSaysSo() = runComposeUiTest {
        tab(CollectionsUiState(years = listOf(year2026), libraryLoad = SectionLoad.FAILED))

        waitUntil { onAllNodesWithText("Couldn't refresh years.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2026").assertIsDisplayed()
    }

    /** An albums failure is the Albums row's, not a full-screen error over the tab. */
    @Test
    fun anAlbumsFailureStaysInTheAlbumsRow() = runComposeUiTest {
        tab(CollectionsUiState(albumsLoad = SectionLoad.FAILED))

        waitUntil { onAllNodesWithText("Couldn't load your albums.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Favourites").assertIsDisplayed()
        onNodeWithText("New album").assertIsDisplayed()
    }

    /** A platform that cannot group faces never grows a People section to fail in. */
    @Test
    fun withoutFaceGroupingAFailedPeopleReadShowsNoPeopleSection() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(years = listOf(year2026), peopleLoad = SectionLoad.FAILED),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                    groupingRunsHere = false,
                )
            }
        }
        waitUntil { onAllNodesWithText("2026").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("People").fetchSemanticsNodes().isEmpty())
    }

    /** The People page says a failed refresh too, beside the cards it kept. */
    @Test
    fun thePeoplePageSaysAFailedRefreshBesideTheCardsItKept() = runComposeUiTest {
        var retries = 0
        setContent {
            TenshuTheme {
                CollectionSectionScreen(
                    section = CollectionSection.PEOPLE,
                    state = CollectionsUiState(
                        people = listOf(person("person:name:anna", "Anna")),
                        peopleLoad = SectionLoad.FAILED,
                    ),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onCreateAlbum = {},
                    onBack = {},
                    onRetry = { retries++ },
                    groupingRunsHere = true,
                )
            }
        }
        waitUntil { onAllNodesWithText("Couldn't refresh people.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Anna").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    /** Not only People — the Years page says a failed refresh beside the years it kept. */
    @Test
    fun theYearsPageSaysAFailedRefreshBesideTheYearsItKept() = runComposeUiTest {
        var retries = 0
        sectionPage(
            CollectionSection.YEARS,
            CollectionsUiState(years = listOf(year2026), libraryLoad = SectionLoad.FAILED),
        )
        waitUntil { onAllNodesWithText("Couldn't refresh years.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2026").assertIsDisplayed()
    }

    /** A failed Favourites tile is its own Try again. */
    @Test
    fun tappingAFailedFavouritesTileRetries() = runComposeUiTest {
        var retries = 0
        var opened = 0
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(years = listOf(year2026), favouritesLoad = SectionLoad.FAILED),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = { opened++ },
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = { retries++ },
                )
            }
        }
        waitUntil { onAllNodesWithText("Couldn't load · Tap to retry").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Couldn't load · Tap to retry").performClick()
        assertEquals(1, retries)
        assertEquals(0, opened)
    }
}
