package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.calendar.model.CalendarDateFormat
import app.tenshu.calendar.model.CalendarMonthCells
import app.tenshu.calendar.model.MonthCell
import app.tenshu.calendar.model.MonthCellEvent
import app.tenshu.calendar.model.MonthGrid
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The month grid.
 *
 * ⭐ Deliberately thin: every decision about *which* dates appear, in what order
 * and with what week start was made in `MonthGrid` (`-shared-logic`) and is unit
 * tested there. What is left here is drawing — which is the half that has to be
 * rewritten in SwiftUI anyway, and the half that a test could not check
 * meaningfully. That split is `calendar-app.md` §5.2.
 *
 * The grid is always six rows, so the layout does not jump as the user pages
 * between a four-week and a six-week month. How those rows are *sized* depends on
 * the window:
 *
 * ⚠⚠ **On a phone the cells are square; from [WindowWidthSizeClass.Medium] up they
 * fill the height they are given.** Square cells make the grid's height a function
 * of the window's *width* — `6 × width/7`, or ~0.857·W — which on a desktop window
 * exceeds the available height at every size a display actually has (a 1440dp-wide
 * window would need a 1400dp-tall one). Compose does not scroll or clip that
 * overflow: `Column` hands the later week rows `maxHeight = 0`, so the last two
 * weeks *and* the day list below them silently disappeared, and widening the window
 * made it worse rather than better. `MonthGridFillsWindowTest` pins both halves.
 *
 * ⚠ Weighted rows have no minimum height by design — a weight in a fixed-height
 * `Column` measures its child with `min == max`, so a `heightIn(min = …)` inside one
 * is a no-op. The grid squeezes instead of overflowing, which is what a calendar
 * that must always show six weeks has to do.
 */
@Composable
fun MonthGridView(
    grid: MonthGrid,
    today: LocalDate?,
    selectedDate: LocalDate?,
    /** Per-day events, in cell order — `CalendarMonthCells.byDate`. */
    eventsByDate: Map<LocalDate, List<MonthCellEvent>>,
    onSelectDate: (LocalDate) -> Unit,
    onOpen: (ResolvedOccurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillsHeight = LocalWindowWidthSizeClass.current >= WindowWidthSizeClass.Medium

    Column(modifier = if (fillsHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            grid.weekdayOrder.forEach { day ->
                Text(
                    text = CalendarDateFormat.weekdayShort(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
            }
        }

        // ⚠⚠ One measurement for the whole grid, not one per cell. Every row is an
        // equal weight of the same box, so how many titles fit is a property of the
        // *grid*; asking 42 cells the same question with 42 `BoxWithConstraints`
        // subcompositions would also let two cells disagree, which is the one thing a
        // table must never do.
        BoxWithConstraints(
            if (fillsHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
        ) {
            val capacity = if (fillsHeight) chipCapacity(maxHeight / MonthGrid.ROWS) else 0

            Column(if (fillsHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                grid.rows.forEach { week ->
                    // ⚠ Rules only once the cells are rectangles. At phone size the square
                    // cells read as a grid on their own, and lines between them are noise;
                    // at desktop size a bare number floating in a 200×120dp box does not.
                    if (fillsHeight) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    Row(
                        if (fillsHeight) {
                            Modifier.fillMaxWidth().weight(1f)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    ) {
                        week.forEachIndexed { index, cell ->
                            if (fillsHeight && index > 0) {
                                VerticalDivider(
                                    Modifier.fillMaxHeight(),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            DayCell(
                                cell = cell,
                                isToday = cell.date == today,
                                isSelected = cell.date == selectedDate,
                                events = eventsByDate[cell.date].orEmpty(),
                                capacity = capacity,
                                fillsHeight = fillsHeight,
                                onClick = { onSelectDate(cell.date) },
                                onOpen = onOpen,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How many rows of titles a cell of [rowHeight] has room for, after the day number.
 *
 * ⚠ Returns 0 rather than a fraction when the cell is too short, and the caller reads
 * that as "fall back to the dots" — a cell with room for half a title has room for
 * neither a title nor the "+N more" that would stand in for it.
 */
private fun chipCapacity(rowHeight: Dp): Int =
    ((rowHeight - DAY_NUMBER_BLOCK) / CHIP_ROW_HEIGHT).toInt().coerceAtLeast(0)

@Composable
private fun DayCell(
    cell: MonthCell,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<MonthCellEvent>,
    /** Title rows this cell has room for; 0 falls back to the dots. See [chipCapacity]. */
    capacity: Int,
    /** See [MonthGridView] — square cell (phone) vs. one that fills its row (desktop). */
    fillsHeight: Boolean,
    onClick: () -> Unit,
    onOpen: (ResolvedOccurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠ Out-of-month days stay tappable — tapping one is how a user navigates to
    // the end of last month, and greying them out while making them inert is a
    // dead zone people poke at repeatedly.
    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        cell.inMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    val showTitles = fillsHeight && capacity > 0

    Box(
        modifier = if (fillsHeight) {
            modifier.fillMaxHeight().clickable(onClick = onClick).padding(top = 4.dp)
        } else {
            modifier.aspectRatio(1f).clickable(onClick = onClick)
        },
        // ⚠ A rectangular cell hangs its number from the top the way a wall
        // calendar does; centring it in a 200×120dp box leaves the number
        // floating with no relationship to the day it names.
        contentAlignment = if (fillsHeight) Alignment.TopCenter else Alignment.Center,
    ) {
        Column(
            // ⚠ Titles are read left-to-right, so a cell that lists them stops
            // centring its contents — a centred stack of ragged chips has no edge
            // for the eye to run down.
            modifier = if (showTitles) Modifier.fillMaxWidth() else Modifier,
            horizontalAlignment = if (showTitles) {
                Alignment.Start
            } else {
                Alignment.CenterHorizontally
            },
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .then(if (showTitles) Modifier.align(Alignment.CenterHorizontally) else Modifier)
                    .size(DAY_NUMBER_BLOCK)
                    .clip(CircleShape)
                    .background(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isToday -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.Transparent
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cell.date.day.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday && !isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        contentColor
                    },
                )
            }

            if (showTitles) {
                val fit = CalendarMonthCells.fit(events, capacity)
                fit.shown.forEach { event ->
                    EventChip(event, onOpen)
                }
                if (fit.hiddenCount > 0) {
                    Text(
                        text = "+${fit.hiddenCount} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp, top = 1.dp),
                    )
                }
            } else {
                // ⚠ A count, capped at three dots. A dot per event turns a busy day
                // into an unreadable smear, and the exact number is what the day's
                // list below is for.
                //
                // ⚠⚠ Still reached on a *desktop* window, not only on a phone: a cell
                // too short for one title falls back here rather than showing nothing.
                // Dropping to no marker at all would make a busy day and an empty one
                // identical, which is worse than a coarse marker.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    repeat(minOf(events.size, MAX_DOTS)) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * One event inside a month cell.
 *
 * ⚠ Clickable in its own right, and that is why it exists rather than being a plain
 * label: a title a user can see but not click reads as broken. The inner `clickable`
 * consumes the tap, so opening an event does not also re-select the day underneath it.
 *
 * ⚠ Tinted rather than filled, matching `AllDayChip` in the time grid — a saturated
 * bar per event turns a busy month into a colour chart.
 */
@Composable
private fun EventChip(event: MonthCellEvent, onOpen: (ResolvedOccurrence) -> Unit) {
    Text(
        text = event.occurrence.title.ifBlank { "(No title)" },
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(Color(event.colorArgb.toLong() or 0xFF000000L).copy(alpha = CHIP_TINT))
            .clickable { onOpen(event.occurrence) }
            .padding(horizontal = 3.dp),
    )
}

private const val MAX_DOTS = 3
private const val CHIP_TINT = 0.30f

/** The day-number circle — the vertical budget a cell spends before any titles. */
private val DAY_NUMBER_BLOCK = 32.dp

/** One title row: the chip itself plus the padding that separates it from the next. */
private val CHIP_ROW_HEIGHT = 18.dp

