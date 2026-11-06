package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import app.tenshu.photos.ui.components.PHOTO_TILE_TAG
import app.tenshu.photos.ui.screens.albumdetail.PersonHeader
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailScreen
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState
import app.tenshu.photos.ui.screens.collections.CollectionsScreen
import app.tenshu.photos.ui.screens.collections.CollectionsUiState
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What S4 (Collections) and S5 (album detail) render (`photos-app.md` §21). */
@OptIn(ExperimentalTestApi::class)
class CollectionsScreensTest {

    private fun album(id: String, name: String, count: Int) =
        AlbumSummary(id, name, createdAtMs = 0L, photoCount = count, coverPath = null)

    private fun photo(i: Int) = Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    @Test
    fun albumsShowTheirNameAndCount() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(albums = listOf(album("a", "Holiday", 12))),
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

        waitUntil { onAllNodesWithText("Holiday").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Holiday").assertIsDisplayed()
        onNodeWithText("12 photos").assertIsDisplayed()
    }

    @Test
    fun anAlbumOfOneReadsOnePhoto() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(albums = listOf(album("a", "Holiday", 1))),
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

        waitUntil { onAllNodesWithText("Holiday").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1 photo").assertIsDisplayed()
    }

    @Test
    fun favouritesIsAlwaysOffered_evenWithNoAlbums() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(albums = emptyList(), favouriteCount = 3),
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

        waitUntil { onAllNodesWithText("Favourites").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("3 photos").assertIsDisplayed()
    }

    @Test
    fun anUnreachableProxyShowsTheProxyMessage_neverAnEmptyShelf() = runComposeUiTest {
        var retried = false
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(error = FeedError.Unreachable),
                    loadPreview = { _, _ -> null },
                    onOpenAlbum = { _, _ -> },
                    onOpenSmartAlbum = {},
                    onOpenFavourites = {},
                    onOpenDuplicates = {},
                    onCreateAlbum = {},
                    onRetry = { retried = true },
                )
            }
        }

        waitUntil { onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            onAllNodesWithText("Nothing here yet.\nCollections group your library by place, date and what's in a photo — and an album is a list you choose yourself.")
                .fetchSemanticsNodes().isEmpty(),
        )
        onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    @Test
    fun theEmptyCopySpeaksForTheWholeTab() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(albums = emptyList(), favouriteCount = 0),
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

        // ⚠ The copy names the whole tab, not just albums — an empty Collections tab that says
        // "no albums yet" tells a user the Places and Things sections do not exist, when in fact
        // they have no photos to derive any from. It still carries the one fact users get wrong
        // about albums everywhere: an album is a list, not a folder.
        waitUntil {
            onAllNodesWithText(
                "Nothing here yet.\nCollections group your library by place, date and what's in a photo — and an album is a list you choose yourself."
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aLongPressRemovesOnlyWhileNoEditRuns() = runComposeUiTest {
        // ⚠ The view model ignores an edit while one runs, so a long-press offered meanwhile
        // would be a removal that silently never happened.
        var writing by androidx.compose.runtime.mutableStateOf(true)
        val removed = mutableListOf<String>()
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "Holiday", photos = listOf(photo(1)), loading = false, writing = writing),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = { removed += it.path },
                    onBack = {},
                )
            }
        }
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performTouchInput { longClick() }
        waitForIdle()
        assertTrue(removed.isEmpty(), "not offered while an edit runs")

        writing = false
        waitForIdle()
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf("/p1.jpg"), removed, "and offered again once it ends")
    }

    @Test
    fun thereIsNoBackWhileAnEditRuns() = runComposeUiTest {
        // ⚠⚠ A reopened album is a new view model whose lock is free.
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "Holiday", photos = listOf(photo(1)), loading = false, writing = true),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                )
            }
        }
        onNodeWithContentDescription("Back").assertIsNotEnabled()
    }

    @Test
    fun anEmptyFavouritesSaysHowToFillIt_notHowToFillAnAlbum() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(
                        title = "Favourites",
                        photos = emptyList(),
                        loading = false,
                        editable = false,
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                )
            }
        }

        waitUntil {
            onAllNodesWithText("No favourites yet.\nTap the heart on a photo to keep it here.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("This album is empty.\nAdd photos to it from the feed.")
                .fetchSemanticsNodes().isEmpty(),
            "Favourites cannot be filled from the feed's add-to-album gesture, so it must not say so",
        )
    }

    @Test
    fun theBackAffordanceIsThere() = runComposeUiTest {
        var backs = 0
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(
                        title = "Holiday",
                        photos = listOf(photo(1), photo(2)),
                        loading = false,
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = { backs++ },
                )
            }
        }

        waitUntil { onAllNodesWithText("Holiday").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun thingsRenderUnderTheirOwnHeader_aboveTheOtherDerivedSections() = runDesktopComposeUiTest(width = 1024, height = 2400) {
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(
                        things = listOf(
                            SmartAlbum("label:dog", "Dog", "4 photos", listOf(photo(1), photo(2))),
                        ),
                        years = listOf(
                            SmartAlbum("year:2026", "2026", "9 photos", listOf(photo(3))),
                        ),
                    ),
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

        // ⚠⚠ Things is its own row, above Years: "Dog" is a claim a model made about the picture
        // and "2026" is a fact off the file; a user deciding whether to trust a group is owed the
        // difference, and the section they cannot reach any other way comes first (§35).
        //
        // ⚠ Ordering is asserted by position, only after both nodes are known to exist, so an absent
        // node cannot pass it for the wrong reason.
        waitUntil { onAllNodesWithText("Years").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Things").assertIsDisplayed()
        onNodeWithText("Dog").assertIsDisplayed()
        val things = onAllNodesWithText("Things").fetchSemanticsNodes().single().boundsInRoot
        val dog = onAllNodesWithText("Dog").fetchSemanticsNodes().single().boundsInRoot
        val years = onAllNodesWithText("Years").fetchSemanticsNodes().single().boundsInRoot
        assertTrue(things.top < dog.top, "Dog sits under the Things header")
        assertTrue(dog.bottom <= years.top, "Years must be BELOW Things")
    }

    @Test
    fun theThingsHeaderIsAbsentWhenNothingHasBeenLabelled() = runComposeUiTest {
        // ⚠⚠ Every device today: the proxy binds an empty backend registry, so no `MediaLabel`
        // exists anywhere. An empty "Things" header would advertise a feature nobody can turn on —
        // the argument that keeps dead tabs out of the app.
        setContent {
            TenshuTheme {
                CollectionsScreen(
                    state = CollectionsUiState(
                        years = listOf(
                            SmartAlbum("year:2026", "2026", "9 photos", listOf(photo(1))),
                        ),
                    ),
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

        waitUntil { onAllNodesWithText("Years").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Things").fetchSemanticsNodes().isEmpty())
    }

    // ── the person header ────────────────────────────────────────────────────────────────

    @Test
    fun aPersonOpensWithTheirHeaderNameAndCount() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(
                        title = "Anna",
                        photos = listOf(photo(1), photo(2)),
                        loading = false,
                        person = PersonHeader(cover = null),
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                )
            }
        }
        onNodeWithText("Anna").assertIsDisplayed()
        onNodeWithText("2 photos").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("Only them", substring = true).fetchSemanticsNodes().size, "pulled after the device check")
    }

    @Test
    fun aPersonWithOnePhotoReadsOnePhoto() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(
                        title = "Anna",
                        photos = listOf(photo(1)),
                        loading = false,
                        person = PersonHeader(cover = null),
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                )
            }
        }
        onNodeWithText("1 photo").assertIsDisplayed()
    }

    /** An unnamed group's name slot IS the naming action. */
    @Test
    fun anUnnamedPersonsHeaderOffersTheName() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(
                        title = "",
                        photos = listOf(photo(1)),
                        loading = false,
                        person = PersonHeader(cover = null),
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                )
            }
        }
        // ⚠ Twice: the top bar's action and the header's name slot — both open the same dialog.
        assertEquals(2, onAllNodesWithText("Add a name").fetchSemanticsNodes().size)
    }

    // ── naming a person ──────────────────────────────────────────────────────────────────────

    @Test
    fun aPersonScreenOffersANameAndAPlaceThatIsNotOneDoesNot() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                )
            }
        }
        onNodeWithText("Add a name").assertIsDisplayed()
    }

    @Test
    fun withoutANamingCallbackThereIsNoNamingButtonAtAll() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "2026", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    // ⚠⚠ A year is not a person. The action must be ABSENT rather than
                    // present-and-inert — a button that does nothing is the failure this repo
                    // keeps choosing not to ship.
                )
            }
        }
        assertEquals(0, onAllNodesWithText("Add a name").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Rename").fetchSemanticsNodes().size)
    }

    @Test
    fun anAlreadyNamedPersonIsOfferedAnotherPersonAndNeverARename() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "Anna", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                )
            }
        }
        // ⚠⚠ Never "Rename": `nameFaceGroup` is ADDITIVE and keys rows by the label text, so
        // "Anna" → "Anne" would leave the Anna rows synced beside the new ones. A button that says
        // Rename and cannot rename is a promise the data layer will not keep.
        assertEquals(0, onAllNodesWithText("Rename").fetchSemanticsNodes().size)

        // ⚠⚠⚠ But it must stay REACHABLE, and hiding it created a dead end that no rule on the
        // data can avoid: two clusters holding the same photographs — one Anna's, one Bob's — look
        // exactly like one person split across two clusters, so suppression has to hide the
        // duplicate in one case and would strand Bob in the other. Only the user knows which, so
        // the affordance is what separates them.
        onNodeWithText("Add another person").assertIsDisplayed()
    }

    @Test
    fun addingAnotherPersonStartsFromAnEmptyFieldRatherThanTheCurrentName() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "Anna", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                )
            }
        }
        onNodeWithText("Add another person").performClick()

        // ⚠⚠ Handing back "Anna" would invite an edit the data layer cannot perform — the Rename
        // trap in a new costume. The sheet asks who this is, from empty.
        onNodeWithText("Who is this?").assertIsDisplayed()
    }

    @Test
    fun aRefusedNameIsShownToTheUserRatherThanOnlyRecorded() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                    nameFailure = NamingOutcome.NoConsent,
                )
            }
        }
        // ⚠⚠ The sheet closes on confirm, so without this the person is simply still
        // unnamed and the user concludes the save worked. State nothing reads is state never set.
        onNodeWithText("That name wasn't saved").assertIsDisplayed()
    }

    @Test
    fun theNamingSheetSaysTheNameWillReachTheUsersOtherDevices() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                )
            }
        }
        onNodeWithText("Add a name").performClick()

        // ⚠⚠ Every other derived thing in Photos is device-local, so a user has no reason
        // to assume a name is not. Naming writes rows that SYNC (threat model A40), and the moment
        // to say so is before they type.
        onNodeWithText(
            "This name is saved with these photos and appears on your other devices.",
        ).assertIsDisplayed()
    }

    @Test
    fun anEmptyNameCannotBeSaved() = runComposeUiTest {
        var named: String? = null
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(title = "", photos = listOf(photo(1)), loading = false),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = { named = it },
                )
            }
        }
        onNodeWithText("Add a name").performClick()
        // ⚠ The op treats a blank name as a no-op, so a live Save would look like it worked.
        onNodeWithText("Save").performClick()

        assertEquals(null, named)
    }

    // ── two people together ──────────────────────────────────────────────────────────────

    private val annasPhotos = listOf(photo(1), photo(2), photo(3))
    private val bob = SmartAlbum("person:name:Bob", "Bob", "", listOf(photo(2), photo(3), photo(9)))
    private val stranger = SmartAlbum("person:g7", "", "", listOf(photo(8)))

    private fun ComposeUiTest.annasPage(
        person: PersonHeader? = PersonHeader(cover = null, companions = listOf(bob, stranger), key = "person:name:Anna"),
        onOpen: (Photo, List<Photo>) -> Unit = { _, _ -> },
        onName: ((String) -> Unit)? = null,
    ) {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    onName = onName,
                    state = AlbumDetailUiState(
                        title = "Anna",
                        photos = annasPhotos,
                        loading = false,
                        editable = false,
                        person = person,
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onPhotoInListClick = onOpen,
                )
            }
        }
    }

    private fun ComposeUiTest.tileCount() = onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().size

    @Test
    fun theWithChipIsOfferedOnAPersonAndNeverOnAYearOrPlace() = runComposeUiTest {
        annasPage(person = null)
        assertEquals(0, onAllNodesWithText("With…").fetchSemanticsNodes().size)
    }

    @Test
    fun choosingASecondPersonNarrowsTheGridToPhotographsOfBoth() = runComposeUiTest {
        annasPage()
        assertEquals(3, tileCount())

        onNodeWithText("With…").performClick()
        // Unnamed people are offered too — intersection by path works for them the same.
        onNodeWithText("Unnamed").assertIsDisplayed()
        onNodeWithText("Bob").performClick()

        waitUntil { tileCount() == 2 }
        onNodeWithText("Anna & Bob").assertIsDisplayed()
        onNodeWithText("2 photos").assertIsDisplayed()
    }

    @Test
    fun theViewerPagesThroughTheNarrowedList() = runComposeUiTest {
        var handed: List<Photo>? = null
        annasPage(onOpen = { _, visible -> handed = visible })
        onNodeWithText("With…").performClick()
        onNodeWithText("Bob").performClick()
        waitUntil { tileCount() == 2 }

        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performClick()
        assertEquals(listOf(photo(2), photo(3)), handed)
    }

    @Test
    fun peopleNeverPhotographedTogetherSaySoRatherThanShowABlankGrid() = runComposeUiTest {
        annasPage()
        onNodeWithText("With…").performClick()
        onNodeWithText("Unnamed").performClick()

        waitUntil { onAllNodesWithText("No photos of both yet").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, tileCount())
        onNodeWithText("Anna & Unnamed").assertIsDisplayed()
    }

    @Test
    fun removingTheSecondPersonRestoresTheWholeGrid() = runComposeUiTest {
        annasPage()
        onNodeWithText("With…").performClick()
        onNodeWithText("Bob").performClick()
        waitUntil { tileCount() == 2 }

        onNodeWithContentDescription("Remove Bob").performClick()
        waitUntil { tileCount() == 3 }
        onNodeWithText("Anna").assertIsDisplayed()
        onNodeWithText("With…").assertIsDisplayed()
    }

    /** A picker with nobody in it would be a chip that does nothing. */
    @Test
    fun aPersonWithNobodyElseToPickIsNotOfferedTheChip() = runComposeUiTest {
        annasPage(person = PersonHeader(cover = null))
        onNodeWithText("Anna").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("With…").fetchSemanticsNodes().size)
    }

    /**
     * ⚠⚠ Naming writes the name to EVERY photo of the primary person, not the
     * intersection on screen — so while narrowed the action is withdrawn, and it returns with the
     * whole grid.
     */
    @Test
    fun namingIsNotOfferedWhileTheGridIsNarrowed() = runComposeUiTest {
        annasPage(onName = {})
        onNodeWithText("Add another person").assertIsDisplayed()

        onNodeWithText("With…").performClick()
        onNodeWithText("Bob").performClick()
        waitUntil { tileCount() == 2 }
        assertEquals(0, onAllNodesWithText("Add another person").fetchSemanticsNodes().size)

        onNodeWithContentDescription("Remove Bob").performClick()
        waitUntil { tileCount() == 3 }
        onNodeWithText("Add another person").assertIsDisplayed()
    }
}
