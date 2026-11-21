package app.tenshu.ui.layout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tenshu.ui.test.NoMotion

/**
 * Pins [AdaptiveNavItem.primaryAction]: in the bar on a phone, a FAB — and **not** a rail item —
 * at every wider class (`fit-app.md` §18.20).
 *
 * The label is counted by text node in the **unmerged** tree (M3 clears the extended FAB's text out
 * of the merged one): a bar or rail item renders its label once, and so does the FAB. So
 * "one Record and no FAB" is the bar, "one Record and a FAB" is the FAB alone, and two would mean
 * the rail kept the item as well as gaining the FAB.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptivePrimaryActionTest {

    private var recordClicks = 0

    private fun items() = listOf(
        AdaptiveNavItem("home", Icons.Outlined.Home, "Home", selected = true, onClick = {}),
        AdaptiveNavItem("history", Icons.Outlined.History, "History", selected = false, onClick = {}),
        AdaptiveNavItem(
            "record", Icons.Outlined.Add, "Record", selected = false,
            onClick = { recordClicks++ }, primaryAction = true,
        ),
    )

    private fun check(widthPx: Int, showFab: Boolean = true, expectFab: Boolean) {
        recordClicks = 0
        runDesktopComposeUiTest(width = widthPx, height = 800, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = items(), showPrimaryActionFab = showFab) {
                        Text("body")
                    }
                }
            }
            waitForIdle()
            // The ordinary destinations are in the chrome at every class.
            onAllNodesWithText("Home", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("History", useUnmergedTree = true).assertCountEquals(1)
            if (expectFab) {
                onNodeWithTag(PRIMARY_ACTION_FAB_TAG).assertIsDisplayed()
                // ⚠ The accessible name, read off the merged tree a screen reader sees: M3 clears
                // the extended FAB's own text semantics, so without an explicit description the
                // most important control on the screen was unnamed.
                onNodeWithTag(PRIMARY_ACTION_FAB_TAG).assertContentDescriptionEquals("Record")
                // The FAB is the *only* Record: the rail dropped the item.
                onAllNodesWithText("Record", useUnmergedTree = true).assertCountEquals(1)
                onNodeWithTag(PRIMARY_ACTION_FAB_TAG).performClick()
                waitForIdle()
                assertEquals(1, recordClicks, "the FAB opens the primary action")
            } else {
                onAllNodesWithTag(PRIMARY_ACTION_FAB_TAG).assertCountEquals(0)
            }
        }
    }

    @Test
    fun compact_keepsTheItemInTheBar_andDrawsNoFab() {
        check(widthPx = 599, expectFab = false)
        // Still in the bar on a phone — one Record, and it is the bar item.
        runDesktopComposeUiTest(width = 599, height = 800, effectContext = NoMotion) {
            setContent {
                TenshuTheme { AdaptiveNavScaffold(navItems = items()) { Text("body") } }
            }
            onAllNodesWithText("Record", useUnmergedTree = true).assertCountEquals(1)
        }
    }

    private fun clearanceAt(widthPx: Int, showFab: Boolean = true): Dp {
        var seen = (-1).dp
        runDesktopComposeUiTest(width = widthPx, height = 800, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = items(), showPrimaryActionFab = showFab) {
                        seen = LocalPrimaryActionClearance.current
                        Text("body")
                    }
                }
            }
            waitForIdle()
        }
        return seen
    }

    /**
     * The last row of a list under the FAB must be able to scroll clear of it: the content sees
     * the FAB's height plus its inset, and nothing where there is no FAB.
     */
    @Test
    fun clearance_coversTheFab_onlyWhereItIsDrawn() {
        val withFab = clearanceAt(1280)
        // 56 dp FAB + a 16 dp inset above and below.
        assertTrue(withFab >= 88.dp, "clearance $withFab must cover the FAB and its inset")
        assertEquals(0.dp, clearanceAt(599), "a phone has no FAB")
        assertEquals(0.dp, clearanceAt(1280, showFab = false), "an opted-out screen has no FAB")
    }

    @Test
    fun compact_ignoresShowPrimaryActionFab() = check(widthPx = 599, showFab = true, expectFab = false)

    @Test
    fun medium_dropsTheItemFromTheRail_forAFab() = check(widthPx = 800, expectFab = true)

    @Test
    fun expanded_dropsTheItemFromTheRail_forAFab() = check(widthPx = 1280, expectFab = true)

    @Test
    fun rail_onAScreenThatOptsOut_showsNeitherFabNorRailItem() {
        check(widthPx = 1280, showFab = false, expectFab = false)
        runDesktopComposeUiTest(width = 1280, height = 800, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = items(), showPrimaryActionFab = false) { Text("body") }
                }
            }
            onAllNodesWithText("Record", useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
