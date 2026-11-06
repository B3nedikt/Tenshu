package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.calendar.model.CalendarDateFormat
import app.tenshu.calendar.model.DaySchedule
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The time grid used to show the same twelve hours whatever the window.**
 * `HOUR_HEIGHT` was a flat `56.dp` tuned for a phone, so a 1000 dp-tall desktop
 * window bought nothing but a longer scrollbar. `TimeGridMetrics` derives an hour
 * from the viewport instead — "show twelve hours at a time", with the old constant
 * kept as a floor.
 *
 * Measured off the **hour ruler**, because that is the artefact a user reads a time
 * off: the gap between the "09:00" and "10:00" labels *is* the hour height. The
 * third test then taps the column and checks the event lands at the hour the ruler
 * drew — the two arithmetics that must not drift, and the reason the scale is
 * threaded through as one object rather than re-derived per composable.
 */
@OptIn(ExperimentalTestApi::class)
class TimeGridScaleTest {

    private val date = LocalDate(2027, 5, 20)
    private val schedule = DaySchedule(date = date, allDay = emptyList(), timed = emptyList())

    private fun label(hour: Int) = CalendarDateFormat.shortTime(LocalTime(hour, 0))

    /** The drawn gap between two adjacent hour labels — i.e. one hour, as rendered. */
    private fun measuredHourHeight(windowHeight: Int): Dp {
        var height = Dp.Unspecified
        runDesktopComposeUiTest(width = 1440, height = windowHeight) {
            setContent { TenshuTheme { Grid() } }
            waitForIdle()
            height = onNodeWithText(label(10)).getBoundsInRoot().top -
                onNodeWithText(label(9)).getBoundsInRoot().top
        }
        return height
    }

    @Test
    fun aTallerWindowShowsTheSameTwelveHours_soTheHourGrows() {
        val tall = measuredHourHeight(windowHeight = 1000)
        val short = measuredHourHeight(windowHeight = 760)

        assertTrue(
            tall > short,
            "the hour did not grow with the window: ${short} at 760dp, ${tall} at 1000dp",
        )
        // Twelve hours fill the viewport, less the day-header band above the grid.
        assertTrue(
            abs(tall.value * 12 - 1000f) < 120f,
            "twelve hours came to ${tall * 12}, nowhere near the 1000dp window",
        )
    }

    /**
     * ⚠ The floor, and the reason it exists: below a certain window height, showing
     * twelve hours would mean showing twelve unreadable ones. A short window keeps
     * the phone's 56 dp rows and scrolls.
     */
    @Test
    fun aShortWindowKeepsTheReadableFloorRatherThanSqueezing() {
        assertEquals(56f, measuredHourHeight(windowHeight = 420).value, 0.5f)
    }

    /**
     * ⚠⚠ The scale that draws the ruler is the scale that reads a tap. A grid drawn
     * at one and read at another creates events at an hour nobody pointed at — and
     * it is silent, because both halves look right on their own.
     */
    @Test
    fun aTapLandsOnTheHourTheRulerDrew() {
        var created: Pair<LocalDate, LocalTime>? = null

        runDesktopComposeUiTest(width = 1440, height = 1000) {
            setContent { TenshuTheme { Grid(onCreateAt = { d, t -> created = d to t }) } }
            waitForIdle()

            val tenOClock = onNodeWithText(label(10)).getBoundsInRoot()
            val hourHeight = tenOClock.top - onNodeWithText(label(9)).getBoundsInRoot().top

            // A quarter into the 10:00 row, well clear of both boundaries and of the
            // label's own lift, in a day column (x is past the 48dp ruler).
            onRoot().performTouchInput {
                click(
                    Offset(
                        x = with(density) { 200.dp.toPx() },
                        y = with(density) { (tenOClock.top + hourHeight * 0.25f).toPx() },
                    )
                )
            }
            waitForIdle()
        }

        assertEquals(date to LocalTime(10, 0), created, "tap did not create at the hour shown")
    }

    @androidx.compose.runtime.Composable
    private fun Grid(onCreateAt: (LocalDate, LocalTime) -> Unit = { _, _ -> }) {
        Box(Modifier.fillMaxSize()) {
            TimeGridView(
                schedules = listOf(schedule),
                calendars = emptyList(),
                today = date,
                nowMinute = null,
                onOpen = {},
                onCreateAt = onCreateAt,
            )
        }
    }
}
