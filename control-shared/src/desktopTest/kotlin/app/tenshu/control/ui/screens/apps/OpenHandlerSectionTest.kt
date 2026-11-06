package app.tenshu.control.ui.screens.apps

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.OpenHandlerChoice
import org.junit.Rule
import org.junit.Test
import org.tenshu.core.common.app.HandlerTable
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The *"opens by default"* picker (`control-cross-app-open.md` TT-4b).
 *
 * ⚠⚠ The property that matters is not that radio buttons render — it is that the picker cannot
 * disagree with the resolver. Three ways it could: showing a choice that changes nothing
 * ([noSectionWhenThereIsNothingToChoose]), naming a default that is not the app that would open
 * ([theDefaultOptionNamesTheAppThatWouldActuallyOpen]), and offering a type with no label
 * ([everyChoosableTypeHasALabel]).
 */
class OpenHandlerSectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val timeline = InstalledApp(
        appId = "app.tenshu.timeline",
        name = "Timeline",
        iconName = "",
        version = "1.0.0",
        shortDescription = "",
        installedAt = 1L,
        verified = true,
        deviceCount = 1,
    )
    private val maps = timeline.copy(appId = "app.tenshu.maps", name = "Maps")

    private fun placeChoice(chosen: String? = null) = OpenHandlerChoice(
        type = HandlerTable.PLACE_TYPE,
        // ⚠ Effective order, as the registry produces it: the chosen app first.
        candidates = listOf(timeline, maps).sortedByDescending { it.appId == chosen },
        chosenAppId = chosen,
    )

    @Test
    fun theDefaultOptionNamesTheAppThatWouldActuallyOpen() {
        // ⚠⚠ Not cosmetic. A user choosing between "Recommended" and "Timeline" cannot tell whether
        // those are the same thing; naming it answers that without a second screen — and it is only
        // answerable because `candidates` arrives in the resolver's own order.
        rule.setContent { OpenHandlerSection(choices = listOf(placeChoice()), onChoose = { _, _ -> }) }

        rule.onNodeWithText("Places").assertIsDisplayed()
        rule.onNodeWithText("Recommended (Timeline)").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithText("Maps").assertIsNotSelected()
    }

    @Test
    fun theDefaultLabelFollowsTheOrderRatherThanNamingAFixedApp() {
        // ⚠⚠ The guard against hard-coding "Timeline": if the table's order ever changes, or a
        // realm has only Maps and one other candidate, the label must move with it. A test that
        // only ever rendered the shipped order could not tell a derived label from a constant.
        val reordered = placeChoice().copy(candidates = listOf(maps, timeline))
        rule.setContent { OpenHandlerSection(choices = listOf(reordered), onChoose = { _, _ -> }) }

        rule.onNodeWithText("Recommended (Maps)").assertIsDisplayed()
    }

    @Test
    fun aChosenAppIsTheSelectedOption() {
        rule.setContent {
            OpenHandlerSection(choices = listOf(placeChoice(chosen = maps.appId)), onChoose = { _, _ -> })
        }

        rule.onNodeWithText("Maps").assertIsSelected()
        rule.onNodeWithText("Recommended (Maps)").assertIsNotSelected()
    }

    @Test
    fun choosingEmitsTheAppId_andTheDefaultEmitsNull() {
        val emitted = mutableListOf<Pair<String, String?>>()
        rule.setContent {
            OpenHandlerSection(
                choices = listOf(placeChoice(chosen = maps.appId)),
                onChoose = { type, appId -> emitted += type to appId },
            )
        }

        rule.onNodeWithText("Timeline").performClick()
        // ⚠ Clearing is a real action, not the absence of one: it returns the type to the built-in
        // order, which is a different state from "chose the app the platform happens to prefer".
        rule.onNodeWithText("Recommended (Maps)").performClick()

        assertEquals(
            listOf(HandlerTable.PLACE_TYPE to "app.tenshu.timeline", HandlerTable.PLACE_TYPE to null),
            emitted,
        )
    }

    @Test
    fun noSectionWhenThereIsNothingToChoose() {
        // ⚠⚠ Absent, not empty-stated. A realm with one place app cannot express a preference, and
        // explaining a setting the user cannot make is noise on a screen whose job is the app list.
        rule.setContent {
            OpenHandlerSection(choices = emptyList(), onChoose = { _, _ -> })
            Text("marker")
        }

        rule.onNodeWithText("marker").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextOrEmpty("Opens by default").isEmpty())
    }

    @Test
    fun everyChoosableTypeHasALabel() {
        // ⚠⚠ The guard that makes the raw-type fallback a safety net rather than the plan: adding a
        // second candidate for a type in `HandlerTable` makes it choosable, and this fails until
        // someone gives it a name. Without it the first sign would be a user seeing
        // "tenshu/timeline/moment" in their settings.
        val labels = mutableMapOf<String, String>()
        rule.setContent {
            HandlerTable.choosableTypes().forEach { type -> labels[type] = openHandlerTypeLabel(type) }
        }
        rule.waitForIdle()

        assertTrue(labels.isNotEmpty(), "no choosable types — this test would pass vacuously")
        labels.forEach { (type, label) -> assertNotEquals(type, label, "no label for $type") }
    }

    /** ⚠ `onAllNodesWithText` throws when nothing matches on some backends; this asks safely. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextOrEmpty(
        text: String,
    ): List<Unit> = runCatching { onNodeWithText(text).assertIsDisplayed(); listOf(Unit) }
        .getOrElse { emptyList() }
}
