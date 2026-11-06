package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The calendars sidebar (CA-2f).
 *
 * ⚠ The row is the checkbox — `toggleable` + `Checkbox(onCheckedChange = null)` — so
 * these assertions read the *row's* checked state. If someone splits them into two
 * controls, `assertIsOn` on the row stops resolving and this file says so, rather
 * than the regression being "the click target got 200 dp smaller", which nobody
 * notices in a diff.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarSidebarTest {

    private val work = CalendarSummary("work", "Work", 0xFF2196F3.toInt(), sensitive = false, visible = true)
    private val home = CalendarSummary("home", "Home", 0xFF4CAF50.toInt(), sensitive = false, visible = false)

    @Test
    fun listsEveryCalendar_showingWhichAreVisibleOnThisDevice() {
        runDesktopComposeUiTest(width = 400, height = 600) {
            setContent { TenshuTheme { Sidebar() } }
            waitForIdle()

            onNodeWithText("Work").assertIsDisplayed().assertIsOn()
            // ⚠ A hidden calendar stays listed and unchecked. Dropping it from the
            // list would leave no way to bring it back.
            onNodeWithText("Home").assertIsDisplayed().assertIsOff()
        }
    }

    @Test
    fun clickingARowTogglesThatCalendar() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        runDesktopComposeUiTest(width = 400, height = 600) {
            setContent {
                TenshuTheme { Sidebar(onToggleVisible = { id, v -> toggles += id to v }) }
            }
            waitForIdle()

            onNodeWithText("Work").performClick()
            onNodeWithText("Home").performClick()
            waitForIdle()
        }

        assertEquals(listOf("work" to false, "home" to true), toggles)
    }

    /** ⚠ Renaming and deleting stay on `CalendarsScreen`; the sidebar only routes there. */
    @Test
    fun offersOneWayThroughToTheFullManagementScreen() {
        var managed = 0
        runDesktopComposeUiTest(width = 400, height = 600) {
            setContent { TenshuTheme { Sidebar(onManage = { managed++ }) } }
            waitForIdle()

            onNodeWithText("Manage calendars").performClick()
            waitForIdle()
        }
        assertEquals(1, managed)
    }

    @androidx.compose.runtime.Composable
    private fun Sidebar(
        onToggleVisible: (String, Boolean) -> Unit = { _, _ -> },
        onManage: () -> Unit = {},
    ) {
        Box(Modifier.fillMaxSize()) {
            CalendarSidebar(
                calendars = listOf(work, home),
                onToggleVisible = onToggleVisible,
                onManage = onManage,
            )
        }
    }
}
