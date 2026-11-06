package app.tenshu.control.ui.screens.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screen's shape: one scrolling body, models first and features last, and the only retry on the
 * catalog-unavailable notice.
 *
 * ⚠⚠ The first test is the one that matters on a phone. With part of the screen fixed above a list
 * that only got the height left over, the list could shrink to nothing and its rows could not be
 * reached at all.
 */
class ModelsScreenLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val models = (1..8).map { ManagedModel("model-$it", "Model $it", byteSize = 104_857_600) }

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    @Test
    fun theWholeBodyScrollsFromTheFirstModelToTheLastFeature() {
        var downloaded: String? = null
        var opened: InferenceCapability? = null
        composeTestRule.setContent {
            // ⚠ Shorter than the eight model rows, so the features start off screen.
            Box(Modifier.size(width = 400.dp, height = 480.dp)) {
                ModelsScreenContent(
                    state = ModelsUiState(loading = false, models = models),
                    onDownload = { downloaded = it },
                    onOpenFeature = { opened = it },
                    onBack = {},
                )
            }
        }
        // §38.1 — models first: the download is there without scrolling. ⚠ Not pressed here: a
        // focused lazy row stays composed, and the last assertion counts on it leaving.
        composeTestRule.onNodeWithContentDescription("Download Model 1").assertIsDisplayed()

        val feature = hasText(consentHeadline(search))
        // ⚠⚠ Off screen before the scroll, or this test proves nothing about scrolling. ⚠ Counted,
        // not `isNotDisplayed()`: a lazy row that is off screen is not composed, and that check
        // throws on a node that does not exist.
        assertEquals(
            0,
            composeTestRule.onAllNodes(feature).fetchSemanticsNodes().size,
            "the features must start off screen",
        )

        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(feature)
        composeTestRule.onNode(feature).assertIsDisplayed().performClick()
        assertEquals(search, opened)
        composeTestRule.onNodeWithContentDescription("Download Model 8").assertIsDisplayed().performClick()
        assertEquals("model-8", downloaded)
        // ⚠⚠ The top went with it. Reaching the last row is not enough: a list squeezed under a
        // fixed header still scrolls inside its sliver, and passes everything above.
        assertEquals(
            0,
            composeTestRule.onAllNodesWithContentDescription("Download Model 1").fetchSemanticsNodes().size,
            "the first model must scroll away with the list, not stay pinned above it",
        )
    }

    /** §38.1: the features are rows that open a screen — the two-switches-per-feature shape is gone. */
    @Test
    fun theModelsScreenCarriesNoSwitch() {
        composeTestRule.setContent {
            ModelsScreenContent(
                    state = ModelsUiState(
                        loading = false,
                        // ⚠ Few enough that the features are on screen without a scroll.
                        models = models.take(2),
                        consents = listOf(CapabilityConsent(search, realm = true, device = true)),
                    ),
                    onDownload = {},
                    onBack = {},
                )
        }

        composeTestRule.onNodeWithText("On for this device").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    /**
     * §38.3: with a model and nothing switched on, the indexing line names the section below — and
     * takes you to it, because it is now the last thing on the screen.
     */
    @Test
    fun theNothingSwitchedOnLineScrollsToTheFeatures() {
        val installed = models.map { it.copy(installed = true) }
        composeTestRule.setContent {
            Box(Modifier.size(width = 400.dp, height = 480.dp)) {
                ModelsScreenContent(
                    state = ModelsUiState(loading = false, models = installed),
                    onDownload = {},
                    onBack = {},
                )
            }
        }
        val line = hasText(NOTHING_SWITCHED_ON)
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(line)
        assertEquals(0, composeTestRule.onAllNodesWithText(consentHeadline(search)).fetchSemanticsNodes().size)

        composeTestRule.onNode(line).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(consentHeadline(search)).assertIsDisplayed()
    }

    /**
     * §38.3: a model that has just landed asks whether to switch its feature on — otherwise it does
     * nothing, and the reason is at the bottom of the screen.
     */
    @Test
    fun aLandedModelAsksWhetherToSwitchItsFeatureOn() {
        var accepted = 0
        var dismissed = 0
        composeTestRule.setContent {
            ModelsScreenContent(
                state = ModelsUiState(loading = false, models = models, featureOffer = search),
                onDownload = {},
                onAcceptFeatureOffer = { accepted++ },
                onDismissFeatureOffer = { dismissed++ },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Turn on \"${consentHeadline(search)}\" on this device?").assertIsDisplayed()
        // ⚠⚠ IN-D3: the cost is said in the question, not after the yes.
        composeTestRule.onNodeWithText(featureDescription(search)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").performClick()
        assertEquals(0 to 1, accepted to dismissed)
        composeTestRule.onNodeWithText("Turn on").performClick()
        assertEquals(1, accepted)
    }

    /** ⚠ A feature this build shows no row for is never offered: the answer could not be changed. */
    @Test
    fun aFeatureWithNoRowHereIsNotOffered() {
        composeTestRule.setContent {
            ModelsScreenContent(
                // ⚠ This desktop test build does not run faces (`faceClusteringRunsHere`).
                state = ModelsUiState(loading = false, models = models, featureOffer = InferenceCapability.FACE_CLUSTERING),
                onDownload = {},
                onBack = {},
            )
        }

        assertEquals(0, composeTestRule.onAllNodesWithText("Turn on").fetchSemanticsNodes().size)
    }

    /**
     * ⚠ At a phone's width. Beside the text, the two buttons squeezed "Indexing photos" onto two
     * lines and its status onto five.
     */
    @Test
    fun theIndexingButtonsSitUnderTheTextNotBesideIt() {
        composeTestRule.setContent {
            Box(Modifier.size(width = 400.dp, height = 900.dp)) {
                ModelsScreenContent(
                    // ⚠ Few enough that the indexing row is on screen: an off-screen lazy row has no bounds.
                    state = ModelsUiState(loading = false, models = models.take(2)),
                    onDownload = {},
                    onBack = {},
                )
            }
        }

        val status = composeTestRule
            .onNodeWithText("No model installed, so nothing is indexed yet.")
            .fetchSemanticsNode().boundsInRoot
        listOf("Index now", "Pause").forEach { label ->
            val button = composeTestRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(button.top >= status.bottom, "$label must sit under the status line, not beside it")
        }
    }

    @Test
    fun theTopBarCarriesNoRefresh() {
        composeTestRule.setContent {
            ModelsScreenContent(
                state = ModelsUiState(loading = false, models = models, catalogUnavailable = true),
                onDownload = {},
                onBack = {},
            )
        }

        assertEquals(
            0,
            composeTestRule.onAllNodesWithContentDescription("Refresh").fetchSemanticsNodes().size,
        )
    }

    /** ⚠ The only retry on the screen, on the one state a retry can change. */
    @Test
    fun tryAgainSitsOnTheCatalogUnavailableNoticeAndRetries() {
        var retried = 0
        composeTestRule.setContent {
            ModelsScreenContent(
                state = ModelsUiState(loading = false, models = models, catalogUnavailable = true),
                onDownload = {},
                onRetry = { retried++ },
                onBack = {},
            )
        }

        val tryAgain = hasText("Try again")
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(tryAgain)
        composeTestRule.onAllNodesWithText("Try again").assertCountIsOne()
        composeTestRule.onNodeWithText("Try again").assertIsDisplayed().performClick()

        assertEquals(1, retried)
    }

    @Test
    fun noOtherStateOffersTryAgain() {
        val states = listOf(
            ModelsUiState(loading = true),
            ModelsUiState(loading = false),
            ModelsUiState(loading = false, models = models),
            ModelsUiState(loading = false, models = models, storeReadOnly = StoreReadOnly.OwnedElsewhere),
        )
        var current by mutableStateOf(states.first())
        composeTestRule.setContent {
            ModelsScreenContent(state = current, onDownload = {}, onBack = {})
        }

        states.forEach { state ->
            current = state
            composeTestRule.waitForIdle()
            assertEquals(
                0,
                composeTestRule.onAllNodesWithText("Try again").fetchSemanticsNodes().size,
                "no retry outside the catalog-unavailable state: $state",
            )
            assertEquals(
                0,
                composeTestRule.onAllNodesWithContentDescription("Refresh").fetchSemanticsNodes().size,
            )
        }
        // ⚠ And the model rows are still there to say the screen rendered at all.
        current = states.last()
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription("Download Model 1"))
        composeTestRule.onNodeWithContentDescription("Download Model 1").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsOne() =
        assertEquals(1, fetchSemanticsNodes().size)
}
