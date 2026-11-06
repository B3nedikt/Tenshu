package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import app.tenshu.calendar.model.MonthGrid
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **A square-celled month grid cannot fit a desktop window, and Compose does not
 * say so.**
 *
 * With `Modifier.weight(1f).aspectRatio(1f)` per cell, the grid's height is a
 * function of the window's *width*: six rows of `width / 7` ≈ `0.857 · W`. A
 * 1440 dp-wide window would need to be over 1400 dp tall for that to fit. It never
 * is — and the overflow neither scrolls nor clips visibly. `Column` measures its
 * children against the space *remaining*, so the last week rows are handed
 * `maxHeight = 0` and simply vanish, along with the day list beneath them. Widening
 * the window makes it worse, which is the opposite of what a resize should do.
 *
 * These tests exist so that is a measurement rather than an argument. The desktop
 * case reads back the cell in the **last** week row: with square cells its row is
 * measured at zero height and the day number spills to y = 908 dp in a 900 dp
 * window — off the bottom edge, unclipped and unscrollable. The phone case pins
 * that the square-cell layout it replaces is untouched.
 *
 * ⚠ The fixture is May 2027 for a reason, and it self-checks: it is a 31-day month
 * whose 1st falls on a Saturday and whose predecessor has 30 days, so "31" appears
 * exactly **once** in the whole 42-cell grid *and* lands in the final row — the one
 * cell whose day number can address the last row unambiguously. Every other number
 * there is duplicated by the leading or trailing month.
 */
@OptIn(ExperimentalTestApi::class)
class MonthGridFillsWindowTest {

    private val grid = MonthGrid.of(year = 2027, month = 5)

    /** Guards the fixture itself — see the class KDoc. */
    @Test
    fun fixture_hasExactlyOneCellLabelled31_andItIsInTheLastRow() {
        assertEquals(1, grid.cells.count { it.date.day == 31 }, "'31' must be unique in the grid")
        assertTrue(
            grid.rows.last().any { it.date.day == 31 },
            "'31' must sit in the final week row",
        )
    }

    @Test
    fun onADesktopWindow_theLastWeekRowIsStillOnScreen() {
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { TenshuTheme { Grid() } }
            waitForIdle()

            val lastRowCell = onNodeWithText("31", useUnmergedTree = true).getBoundsInRoot()

            // The regression itself: the sixth week row rendered past the bottom edge.
            assertTrue(
                lastRowCell.bottom.value <= 900f,
                "the last week row ends at ${lastRowCell.bottom}, past the 900dp window",
            )
            // …and it is genuinely the sixth row of six, not five rows collapsed onto
            // each other. Six even rows put it below y = 700 in a 900dp window.
            assertTrue(
                lastRowCell.top.value > 700f,
                "the last week row starts at ${lastRowCell.top} — the rows are not filling the window",
            )
            assertTrue(lastRowCell.height.value > 0f, "the last week row has no height")
        }
    }

    /**
     * ⚠ The phone path must stay byte-identical: square cells, natural height, no
     * fill. At 420 dp wide the grid wants ~400 dp and the window has 800, so a grid
     * that measured the full height would be the tell that the desktop branch had
     * leaked into `Compact`.
     */
    @Test
    fun onAPhone_theGridKeepsItsNaturalSquareCelledHeight() {
        var measured = -1
        runDesktopComposeUiTest(width = 420, height = 800) {
            setContent {
                TenshuTheme { Grid(Modifier.onSizeChanged { measured = it.height }) }
            }
            waitForIdle()
        }

        assertTrue(measured > 0, "the grid never measured")
        assertTrue(
            measured < 800,
            "the grid measured ${measured}dp tall — Compact must wrap its content, not fill",
        )
    }

    @androidx.compose.runtime.Composable
    private fun Grid(modifier: Modifier = Modifier) {
        Box(Modifier.fillMaxSize()) {
            MonthGridView(
                grid = grid,
                today = null,
                selectedDate = null,
                eventsByDate = emptyMap(),
                onSelectDate = {},
                onOpen = {},
                modifier = modifier,
            )
        }
    }
}
