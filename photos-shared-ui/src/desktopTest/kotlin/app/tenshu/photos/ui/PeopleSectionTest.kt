package app.tenshu.photos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.model.FaceCover
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.ALBUM_COVER_TAG
import app.tenshu.photos.ui.components.FACE_AVATAR_TAG
import app.tenshu.photos.ui.screens.collections.CollectionsScreen
import app.tenshu.photos.ui.screens.collections.CollectionsUiState
import app.tenshu.photos.ui.screens.collections.PEOPLE_STATUS_TAG
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.collections.peopleStatusLine
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.inference.FaceGroupsUnavailable.Reasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The People section of Collections: face cards and the line that says why it is empty. */
@OptIn(ExperimentalTestApi::class)
class PeopleSectionTest {

    private fun photo(i: Int) = Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    private fun person(title: String, face: FaceCover?) = SmartAlbum(
        id = "person:$title",
        title = title,
        subtitle = "2 photos",
        photos = listOf(photo(1), photo(2)),
        coverFace = face,
    )

    // ⚠ A year row is what says the library HAS photographs; without one People says nothing.
    private val year2026 = SmartAlbum("year:2026", "2026", "2 photos", listOf(photo(1), photo(2)))

    // ⚠ `years = emptyList()` in the card tests: a year card is an album cover too, and they count those.
    private fun state(people: List<SmartAlbum> = emptyList(), status: PeopleStatus, years: List<SmartAlbum> = listOf(year2026)) =
        CollectionsUiState(favouriteCount = 1, years = years, people = people, peopleStatus = status)

    // ── the copy, as a pure function ────────────────────────────────────────────────────────

    @Test
    fun everyRefusalHasItsOwnLine() {
        fun line(status: PeopleStatus) = peopleStatusLine(status, hasPeople = false, groupingRunsHere = true)
        val lines = listOf(
            line(PeopleStatus.Unavailable(Reasons.NO_CONSENT)),
            line(PeopleStatus.Unavailable(Reasons.NO_BACKEND)),
            line(PeopleStatus.Unavailable(Reasons.NOT_INDEXED)),
            line(PeopleStatus.Unavailable(Reasons.UNCALIBRATED)),
            line(PeopleStatus.Failed),
            line(PeopleStatus.Available),
        )
        assertTrue(lines.all { it != null })
        assertEquals(lines.size, lines.toSet().size, "two statuses share a sentence: $lines")
    }

    @Test
    fun consentLineNamesControlsFeatureAndThisDevice() {
        val line = peopleStatusLine(PeopleStatus.Unavailable(Reasons.NO_CONSENT), false, true)!!
        // ⚠ Control's own label (ModelsScreen's consentHeadline): a sentence naming a label that
        // does not exist is worse than none. ⚠⚠ "for this device", because on for another device
        // answers NO_CONSENT here too.
        assertTrue("\"Group photos by people\"" in line, line)
        assertTrue("for this device" in line, line)
        assertTrue("Settings → Models" in line, line)
    }

    @Test
    fun anUnknownReasonGetsTheGenericLine_notAnEmptySection() {
        assertEquals(
            peopleStatusLine(PeopleStatus.Unavailable(Reasons.UNCALIBRATED), false, true),
            peopleStatusLine(PeopleStatus.Unavailable("someNewerReason"), false, true),
        )
    }

    @Test
    fun availableWithPeopleSaysNothing() {
        assertNull(peopleStatusLine(PeopleStatus.Available, hasPeople = true, groupingRunsHere = true))
    }

    @Test
    fun aRefusalStillSpeaksWhenSyncedNamesFillTheSection() {
        // A name from another device comes from labels alone; this device still groups nobody.
        assertEquals(
            peopleStatusLine(PeopleStatus.Unavailable(Reasons.NO_CONSENT), hasPeople = false, groupingRunsHere = true),
            peopleStatusLine(PeopleStatus.Unavailable(Reasons.NO_CONSENT), hasPeople = true, groupingRunsHere = true),
        )
    }

    @Test
    fun aPlatformThatCannotGroupFacesNeverNags() {
        for (status in listOf(
            PeopleStatus.Unavailable(Reasons.NO_BACKEND),
            PeopleStatus.Unavailable(Reasons.NO_CONSENT),
            PeopleStatus.Failed,
            PeopleStatus.Available,
        )) {
            assertNull(peopleStatusLine(status, hasPeople = false, groupingRunsHere = false), "$status")
        }
    }

    // ── the screen ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aPersonWithACoverFaceIsDrawnAsTheirFace() = runComposeUiTest {
        val requested = mutableListOf<String>()
        val face = FaceCover("/face.jpg", FaceRegion(0.4f, 0.3f, 0.6f, 0.5f))
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = state(listOf(person("Anna", face)), PeopleStatus.Available, years = emptyList()),
                    loadPreview = { path, _ -> requested += path; null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                    groupingRunsHere = true,
                )
            }
        }

        waitUntil { onAllNodesWithText("Anna").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(FACE_AVATAR_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(onAllNodesWithTag(ALBUM_COVER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        waitUntil { requested.isNotEmpty() }
        // ⚠ The face's own photograph, not the person's first — they differ in this fixture.
        assertEquals(listOf("/face.jpg"), requested.distinct())
        // ⚠ No count on a face (`photos-app.md` §35) — the person page carries it.
        assertTrue(onAllNodesWithText("2 photos").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(PEOPLE_STATUS_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aPersonWithNoFaceHereFallsBackToTheWholePhotograph() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = state(listOf(person("Bob", face = null)), PeopleStatus.Available, years = emptyList()),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                    groupingRunsHere = true,
                )
            }
        }

        waitUntil { onAllNodesWithText("Bob").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ALBUM_COVER_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(onAllNodesWithTag(FACE_AVATAR_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // ⚠ No count on the fallback either — People shows none.
        assertTrue(onAllNodesWithText("2 photos").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anEmptyPeopleSectionSaysWhy_forEveryStatus() = runComposeUiTest {
        var current by mutableStateOf(state(status = PeopleStatus.Available))
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
                    groupingRunsHere = true,
                )
            }
        }

        for (status in listOf(
            PeopleStatus.Available,
            PeopleStatus.Unavailable(Reasons.NO_CONSENT),
            PeopleStatus.Unavailable(Reasons.NO_BACKEND),
            PeopleStatus.Unavailable(Reasons.NOT_INDEXED),
            PeopleStatus.Unavailable(Reasons.UNCALIBRATED),
            PeopleStatus.Failed,
        )) {
            current = state(status = status)
            waitForIdle()
            onNodeWithText("People").assertIsDisplayed()
            onNodeWithTag(PEOPLE_STATUS_TAG)
                .assertIsDisplayed()
                .assertTextEquals(peopleStatusLine(status, hasPeople = false, groupingRunsHere = true)!!)
        }
    }

    @Test
    fun onAPlatformThatCannotGroupFaces_thereIsNoPeopleSectionAtAll() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = state(status = PeopleStatus.Unavailable(Reasons.NO_BACKEND)),
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

        waitUntil { onAllNodesWithText("Favourites").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("People").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(PEOPLE_STATUS_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anEmptyLibraryLeavesItToTheTabsOwnEmptyCopy() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(peopleStatus = PeopleStatus.Unavailable(Reasons.NOT_INDEXED)),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                    groupingRunsHere = true,
                )
            }
        }

        waitUntil { onAllNodesWithText("Favourites").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(PEOPLE_STATUS_TAG).fetchSemanticsNodes().isEmpty())
    }

    /** An empty album makes the tab non-empty, but there is still nothing to index. */
    @Test
    fun anEmptyAlbumWithNoPhotographsAsksForNothing() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(
                        albums = listOf(AlbumSummary(albumId = "a1", name = "Trip", createdAtMs = 1L, photoCount = 0, coverPath = null)),
                        peopleStatus = PeopleStatus.Unavailable(Reasons.NOT_INDEXED),
                    ),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = {},
                    groupingRunsHere = true,
                )
            }
        }

        waitUntil { onAllNodesWithText("Trip").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(PEOPLE_STATUS_TAG).fetchSemanticsNodes().isEmpty())
    }
}
