package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailScreen
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailUiState
import app.tenshu.photos.ui.screens.albumdetail.NameSuggestionPrompt
import app.tenshu.photos.ui.screens.albumdetail.PersonHeader
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** IN-6 F6c name suggestions on a person's page: the question, the fold, and their answers. */
@OptIn(ExperimentalTestApi::class)
class NameSuggestionScreenTest {

    private fun photo(i: Int) = Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    private fun ComposeUiTest.page(title: String, prompt: NameSuggestionPrompt?) {
        setContent {
            TenshuTheme {
                AlbumDetailScreen(
                    state = AlbumDetailUiState(
                        title = title,
                        photos = listOf(photo(1), photo(2)),
                        loading = false,
                        editable = false,
                        person = PersonHeader(cover = null, companions = emptyList(), key = "person:g1"),
                    ),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onRemove = {},
                    onBack = {},
                    onName = {},
                    suggestion = prompt,
                )
            }
        }
    }

    @Test
    fun anUnnamedPersonIsAskedAndEachAnswerIsWired() = runComposeUiTest {
        val answers = mutableListOf<String>()
        page(
            title = "",
            prompt = NameSuggestionPrompt.of("Benedikt", 0, "", false, { answers += "yes" }, { answers += "no" }),
        )

        onNodeWithText("Is this Benedikt?").assertIsDisplayed()
        onNodeWithText("Yes").performClick()
        onNodeWithText("No").performClick()
        assertEquals(listOf("yes", "no"), answers)
    }

    @Test
    fun aFoldSaysItsPhotosAreUnconfirmedAndOffersConfirmOrNot() = runComposeUiTest {
        val answers = mutableListOf<String>()
        page(
            title = "Benedikt",
            prompt = NameSuggestionPrompt.of(null, 2, "Benedikt", false, { answers += "confirm" }, { answers += "not" }),
        )

        onNodeWithText("2 photos matched by face on this device — not yet named Benedikt.").assertIsDisplayed()
        onNodeWithText("Confirm").performClick()
        onNodeWithText("Not Benedikt").performClick()
        assertEquals(listOf("confirm", "not"), answers)
    }

    @Test
    fun aNoThatDidNotSaveSaysSo() = runComposeUiTest {
        page(title = "", prompt = NameSuggestionPrompt.of("Benedikt", 0, "", true, {}, {}))
        onNodeWithText("That answer wasn't saved. Please try again.").assertIsDisplayed()
    }

    @Test
    fun nothingToAskShowsNothing() = runComposeUiTest {
        page(title = "", prompt = NameSuggestionPrompt.of(null, 0, "", false, {}, {}))
        assertEquals(0, onAllNodesWithText("Yes").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Confirm").fetchSemanticsNodes().size)
    }
}
