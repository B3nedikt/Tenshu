package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.calendar.model.CalendarMonthCells
import app.tenshu.calendar.model.MonthCellEvent
import app.tenshu.calendar.model.MonthGrid
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Titles inside the month cells (CA-2e) — the rendering half. The ordering, colour
 * join and overflow arithmetic are `CalendarMonthCellsTest` in `-shared-logic`; what
 * can only be checked by measuring is **how many rows a cell actually has room for**,
 * and that the fallback fires when it has room for none.
 */
@OptIn(ExperimentalTestApi::class)
class MonthCellEventsTest {

    private val day = LocalDate(2027, 5, 20)
    private val grid = MonthGrid.of(2027, 5)

    private fun event(title: String, hour: Int) = MonthCellEvent(
        occurrence = ResolvedOccurrence(
            eventId = title,
            calendarId = "work",
            recurrenceId = "",
            title = title,
            location = "",
            notes = "",
            startLocal = LocalDateTime(day, LocalTime(hour, 0)),
            endLocal = LocalDateTime(day, LocalTime(hour + 1, 0)),
            zoneId = "Europe/Berlin",
            allDay = false,
            isOverridden = false,
            recurrenceRule = "",
        ),
        colorArgb = 0xFF2196F3.toInt(),
    )

    // ⚠ Hours wrap: twenty events must stay inside a legal 24-hour clock.
    private fun events(n: Int) = (1..n).map { event("Event $it", 1 + (it % 20)) }

    @Test
    fun aDesktopCellListsItsEventsByTitle() {
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { TenshuTheme { Grid(mapOf(day to events(3))) } }
            waitForIdle()

            onNodeWithText("Event 1").assertIsDisplayed()
            onNodeWithText("Event 3").assertIsDisplayed()
        }
    }

    /**
     * ⚠⚠ The measured half of the overflow rule. A 900 dp window gives ~140 dp rows,
     * which is room for a handful of titles — so twenty events must collapse to a
     * "+N more" rather than either overflowing the cell or being silently dropped.
     * The count is read back off the screen, so a capacity miscalculation shows up
     * here as a wrong number rather than as a layout nobody looks at.
     */
    @Test
    fun tooManyEventsCollapseToACountedOverflowRow() {
        var moreLabel: String? = null
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { TenshuTheme { Grid(mapOf(day to events(20))) } }
            waitForIdle()

            val shown = (1..20).count {
                runCatching { onNodeWithText("Event $it").assertIsDisplayed() }.isSuccess
            }
            moreLabel = "+${20 - shown} more"
            onNodeWithText(moreLabel!!).assertIsDisplayed()

            assertTrue(shown in 1..8, "expected a handful of titles in a 140dp cell, got $shown")
        }
        assertTrue(moreLabel != null)
    }

    /** ⚠ A title a user can see but not click reads as broken. */
    @Test
    fun clickingATitleOpensThatEvent() {
        var opened: String? = null
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent {
                TenshuTheme { Grid(mapOf(day to events(3)), onOpen = { opened = it.title }) }
            }
            waitForIdle()
            onNodeWithText("Event 2").performClick()
            waitForIdle()
        }
        assertEquals("Event 2", opened)
    }

    /**
     * ⚠⚠ The fallback, and it is a *desktop* path, not a phone one: a window short
     * enough that a cell cannot fit one title drops back to the dot markers. Showing
     * nothing at all would make a busy day and an empty one identical.
     */
    @Test
    fun aCellWithNoRoomForATitleFallsBackToDotsRatherThanShowingNothing() {
        runDesktopComposeUiTest(width = 1440, height = 300) {
            setContent { TenshuTheme { Grid(mapOf(day to events(3))) } }
            waitForIdle()

            assertTrue(
                runCatching { onNodeWithText("Event 1").assertIsDisplayed() }.isFailure,
                "a 300dp window has no room for titles, yet one was drawn",
            )
        }
    }

    /** The phone keeps its dots — every branch here is `>= Medium`. */
    @Test
    fun aPhoneShowsNoTitles() {
        runDesktopComposeUiTest(width = 420, height = 800) {
            setContent { TenshuTheme { Grid(mapOf(day to events(3))) } }
            waitForIdle()

            assertTrue(
                runCatching { onNodeWithText("Event 1").assertIsDisplayed() }.isFailure,
                "Compact must keep the dot markers, not list titles",
            )
        }
    }

    /** Guards the fixture: the model these tests feed the grid is the one it ships with. */
    @Test
    fun fixtureMatchesTheProductionGrouping() {
        val produced = CalendarMonthCells.byDate(
            occurrences = events(2).map { it.occurrence },
            calendars = emptyList(),
        )
        assertEquals(listOf("Event 1", "Event 2"), produced.getValue(day).map { it.occurrence.title })
    }

    @androidx.compose.runtime.Composable
    private fun Grid(
        eventsByDate: Map<LocalDate, List<MonthCellEvent>>,
        onOpen: (ResolvedOccurrence) -> Unit = {},
    ) {
        Box(Modifier.fillMaxSize()) {
            MonthGridView(
                grid = grid,
                today = day,
                selectedDate = day,
                eventsByDate = eventsByDate,
                onSelectDate = {},
                onOpen = onOpen,
            )
        }
    }
}
