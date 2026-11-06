package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlin.test.assertEquals
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoHit
import app.tenshu.photos.model.PhotoHitSource
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.ui.screens.search.PhotoSearchScreen
import app.tenshu.photos.ui.screens.search.PhotoSearchUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test

/**
 * What S2 renders (`photos-app.md` S2).
 *
 * ⚠⚠ The load-bearing tests are the two that keep *"nothing matched"* and *"a source could not run
 * here"* apart. `photosLike` throws rather than answering empty for exactly this reason, and on
 * every device today it does throw — the production backend registry is empty — so the refusal
 * path is the *common* one, not the edge.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoSearchScreenTest {

    private fun photo(name: String) =
        Photo("/Backup/Phone/$name", 1_000L, isVideo = false, contentId = "c$name")

    private fun screen(
        state: PhotoSearchUiState,
        onRetry: () -> Unit = {},
        onReturn: () -> Unit = {},
    ) = @androidx.compose.runtime.Composable {
        TenshuTheme {
            PhotoSearchScreen(
                state = state,
                loadPreview = { _, _ -> null },
                onQueryChanged = {},
                onPhotoClick = {},
                onBack = {},
                onRetry = onRetry,
                onReturn = onReturn,
            )
        }
    }

    // ── `photos-ios.md` §8.19: the screen draws the shared layout ───────────────────────────────────

    @Test
    fun couldNotLookIsNotDrawnAsNothingMatched_andOffersTheRetry() = runComposeUiTest {
        // ⚠⚠ A source that did not answer found nothing because it looked at nothing.
        var retried = 0
        setContent(
            screen(
                PhotoSearchUiState(query = "dog", settled = true, couldNotLook = setOf(PhotoHitSource.NAME)),
                onRetry = { retried++ },
            )
        )

        waitUntil {
            onAllNodesWithText(
                "Couldn't search everything. Nothing turned up in what was looked through — " +
                    "that doesn't mean nothing matches."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("No photos match \"dog\".").assertDoesNotExist()
        onNodeWithText("Try Again").performClick()
        runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun resultsOverASourceThatCouldNotLookSaySo_withTheRetryBesideTheNote() = runComposeUiTest {
        var retried = 0
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "dog",
                    hits = listOf(PhotoHit(photo("dog-park.jpg"), setOf(PhotoHitSource.NAME))),
                    settled = true,
                    couldNotLook = setOf(PhotoHitSource.LABEL),
                ),
                onRetry = { retried++ },
            )
        )

        waitUntil {
            onAllNodesWithText("Couldn't search by tag this time, so there may be more.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Try Again").performClick()
        runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun nothingToRetryWhenEverySourceAnswered() = runComposeUiTest {
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "dog",
                    hits = listOf(PhotoHit(photo("dog-park.jpg"), setOf(PhotoHitSource.NAME))),
                    settled = true,
                    semanticStatus = SemanticSearchStatus.Unavailable("noBackend"),
                )
            )
        )

        waitUntil { onAllNodesWithText("dog").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Try Again").assertDoesNotExist()
    }

    @Test
    fun wherDescriptionSearchIsNotBuiltTheScreenSaysSo() = runComposeUiTest {
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "giraffe",
                    settled = true,
                    semanticStatus = SemanticSearchStatus.NotBuiltHere("iPhone"),
                )
            )
        )

        waitUntil {
            onAllNodesWithText("Search by description isn't available on this iPhone yet.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("No photos match \"giraffe\".").assertExists()
    }

    @Test
    fun comingBackToTheScreenAsksForAReRead_openingItDoesNot() = runComposeUiTest {
        // ⚠⚠ Back from the viewer, a photograph trashed there must not still be a result. The entry
        // leaves composition while the viewer is on top and keeps its saved state — which is what a
        // `SaveableStateProvider` models here.
        var returns = 0
        var shown by mutableStateOf(true)
        setContent {
            val holder = rememberSaveableStateHolder()
            if (shown) {
                holder.SaveableStateProvider("search") {
                    screen(PhotoSearchUiState(query = "dog", settled = true), onReturn = { returns++ })()
                }
            }
        }
        waitForIdle()
        runOnIdle { assertEquals(0, returns, "the first opening is not a return") }

        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        runOnIdle { assertEquals(1, returns, "the return re-reads") }
    }

    /** Opened by Cmd/Ctrl+F, the user must be able to type at once. */
    @Test
    fun theQueryFieldHasFocusWhenTheScreenOpens() = runComposeUiTest {
        setContent(screen(PhotoSearchUiState()))
        waitForIdle()

        onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun anIdleBarInvitesANameSearch() = runComposeUiTest {
        // ⚠ It says what actually works here rather than promising three sources, two of which no
        // device can currently run.
        setContent(screen(PhotoSearchUiState()))

        waitUntil { onAllNodesWithText("Search by file name.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRefusalIsNotRenderedAsNothingMatched() = runComposeUiTest {
        // ⚠⚠ The mistake `mia-query-layer.md` §14.1 names, in the screen where it would happen.
        // Name search found a photo; source 3 could not run. Both facts must be on screen.
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "dog",
                    hits = listOf(PhotoHit(photo("dog-park.jpg"), setOf(PhotoHitSource.NAME))),
                    settled = true,
                    semanticStatus = SemanticSearchStatus.Unavailable("noBackend"),
                )
            )
        )

        waitUntil {
            onAllNodesWithText(
                "Searching photos by what's in them needs a model on this device, " +
                    "and there isn't one yet."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // ⚠ And the working half is still a working half — no error state over a successful search.
        onNodeWithText("No photos match \"dog\".").assertDoesNotExist()
    }

    @Test
    fun aGenuineMissStillReadsAsAMiss() = runComposeUiTest {
        setContent(screen(PhotoSearchUiState(query = "giraffe", settled = true)))

        waitUntil {
            onAllNodesWithText("No photos match \"giraffe\".").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun eachRefusalReasonGetsItsOwnSentence() = runComposeUiTest {
        // ⚠ Four reasons need four responses. "Switched off" and "no model" send a user to
        // different places — or, today, tell them different true things about why not.
        for ((reason, expected) in listOf(
            "noConsent" to "Searching photos by what's in them is switched off for this device. Turn on " +
                "\"Search photos by description\" in Control, under Settings → Models.",
            "notIndexed" to "Your photos haven't been looked through on this device yet.",
            "noTextEmbedding" to
                "The model on this device can tag photos but can't search them by description.",
            "somethingNewerProxiesKnowAbout" to
                "Searching photos by what's in them isn't available on this device.",
        )) {
            setContent(
                screen(
                    PhotoSearchUiState(
                        query = "dog",
                        settled = true,
                        semanticStatus = SemanticSearchStatus.Unavailable(reason),
                    )
                )
            )
            waitUntil { onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun aNameMatchCarriesNoBadge() = runComposeUiTest {
        // ⚠ The only source a user can verify by reading the filename. Labelling it would add
        // noise to the common case rather than information.
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "dog",
                    hits = listOf(PhotoHit(photo("dog-park.jpg"), setOf(PhotoHitSource.NAME))),
                    settled = true,
                )
            )
        )

        // ⚠ Anchored on the query in the field, not on the placeholder — a field with a value
        // does not render its placeholder, so waiting for it would never end.
        waitUntil { onAllNodesWithText("dog").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("looks like this").assertDoesNotExist()
        onNodeWithText("tagged \"dog\"").assertDoesNotExist()
    }

    @Test
    fun aModelBackedHitSaysWhyItIsThere() = runComposeUiTest {
        // ⚠⚠ The inverse: a hit nobody can verify from the filename must say what put it there.
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "dog",
                    hits = listOf(
                        PhotoHit(photo("IMG_0007.jpg"), setOf(PhotoHitSource.LABEL), matchedLabel = "dog"),
                        PhotoHit(photo("IMG_0009.jpg"), setOf(PhotoHitSource.SEMANTIC)),
                    ),
                    settled = true,
                )
            )
        )

        waitUntil { onAllNodesWithText("tagged \"dog\"").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("looks like this").assertExists()
    }

    @Test
    fun aCappedListSaysSo() = runComposeUiTest {
        // ⚠ A screen that quietly shows 60 of 900 is the dishonest version of a capped list.
        setContent(
            screen(
                PhotoSearchUiState(
                    query = "img",
                    hits = listOf(PhotoHit(photo("IMG_0001.jpg"), setOf(PhotoHitSource.NAME))),
                    settled = true,
                    capped = true,
                )
            )
        )

        waitUntil {
            onAllNodesWithText(
                "Showing the first 1 matches. Narrow the search to see fewer, better ones."
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anUnsettledSearchShowsNeitherResultsNorAnEmptyState() = runComposeUiTest {
        // ⚠ Between two letters of a query that will match plenty, "No photos match" must not flash.
        setContent(screen(PhotoSearchUiState(query = "do", searching = true)))

        onNodeWithText("No photos match \"do\".").assertDoesNotExist()
        onNodeWithText("Search by file name.").assertDoesNotExist()
    }
}
