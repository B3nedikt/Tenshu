package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.model.CalendarDateFormat
import app.tenshu.calendar.model.DaySchedule
import app.tenshu.calendar.model.MINUTES_PER_DAY
import app.tenshu.calendar.model.PositionedEvent
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The week / day time grid (CA-4).
 *
 * ⭐⭐ **Thin on purpose.** Every hard question — which column an event goes in,
 * how wide it is, how an overnight shift splits across two days — was answered
 * in `DayLayout` / `daySchedule` (`-shared-logic`), where it is unit-tested and
 * where the CA-8 SwiftUI grid reads the same answer. What is left here is
 * arithmetic on `dp` and a `Box` per event. That split is `calendar-app.md`
 * §5.2's "single most important structural decision in the UI layer".
 *
 * ⚠ One shared vertical scroll for the ruler and every day column, so the hour
 * labels cannot drift out of step with the events beside them.
 *
 * ⚠⚠ **The vertical scale is derived from the window, not fixed** — see
 * [TimeGridMetrics]. Every dp in here comes from that one object, and the
 * file-level `HOUR_HEIGHT` constant it replaced is deliberately gone: a second
 * source of scale is how the ruler and the tap-to-create arithmetic drift apart.
 */
@Composable
fun TimeGridView(
    schedules: List<DaySchedule>,
    calendars: List<CalendarSummary>,
    today: LocalDate?,
    /** Minutes since midnight, for the current-time line; `null` to omit it. */
    nowMinute: Int?,
    onOpen: (ResolvedOccurrence) -> Unit,
    onCreateAt: (LocalDate, LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (schedules.isEmpty()) return

    val scroll = rememberScrollState()

    Column(modifier.fillMaxSize()) {
        DayHeaderRow(schedules, today, calendars, onOpen)
        HorizontalDivider()

        // ⚠ Measures what is left *after* the header band rather than the whole
        // window: the band grows with the day's all-day events, and scaling the
        // hours to a height the grid does not have is how you get a viewport that
        // shows eleven hours on the days somebody has holidays booked.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val metrics = TimeGridMetrics.forViewport(maxHeight)
            val density = LocalDensity.current

            // ⚠ Opens on the working day rather than at midnight — a grid that starts
            // at 00:00 shows eight hours of empty night before anything you came to see.
            // ⚠⚠ Waits for a real `maxValue`: `scrollTo` clamps, so firing before the
            // grid has been measured would silently scroll to 0 and look like the
            // feature was never written.
            // ⚠ Keyed on the date alone, *not* on the metrics: re-running it on a resize
            // would yank the grid back to 08:00 every time the window edge moved.
            LaunchedEffect(schedules.first().date) {
                val target = with(density) { (metrics.hourHeight * OPENING_HOUR).roundToPx() }
                snapshotFlow { scroll.maxValue }.first { it > 0 }
                scroll.scrollTo(target)
            }

            Row(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                HourRuler(metrics)
                schedules.forEach { schedule ->
                    VerticalDivider(Modifier.height(metrics.gridHeight))
                    DayColumn(
                        schedule = schedule,
                        calendars = calendars,
                        metrics = metrics,
                        nowMinute = nowMinute.takeIf { schedule.date == today },
                        onOpen = onOpen,
                        onCreateAt = onCreateAt,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The grid's vertical scale: how tall an hour is, and everything that follows.
 *
 * ⚠⚠ **An hour is a fraction of the viewport, not a constant.** `HOUR_HEIGHT` used
 * to be a flat `56.dp` "about twelve hours of a working day fit a phone at this" —
 * true, and the reason a desktop window gained nothing from being tall: the same
 * twelve hours showed in a 600 dp window and a 1400 dp one, with the rest of the
 * height spent on a longer scrollbar. [VISIBLE_HOURS] is what that comment
 * described, promoted to the thing the layout is actually computed from — the
 * same "show N hours at a time" macOS Calendar exposes as a preference.
 *
 * ⚠ [MIN_HOUR_HEIGHT] is the old constant kept as a **floor**, so this can only
 * ever grow an hour, never shrink one. A short window keeps today's readable
 * 56 dp rows and scrolls, which is right: below a certain size, showing twelve
 * hours would mean showing twelve unreadable ones.
 *
 * ⚠ No ceiling, deliberately. On a very tall display an hour genuinely should be
 * tall — "twelve hours at a time" is the contract, and capping it would put back
 * the wasted space this exists to remove.
 */
private data class TimeGridMetrics(val hourHeight: Dp) {
    val minuteHeight: Dp get() = hourHeight / 60
    val gridHeight: Dp get() = hourHeight * HOURS_PER_DAY

    companion object {
        /**
         * ⚠ An unbounded [viewport] falls back to the floor rather than dividing by
         * infinity. It should not happen — the grid sits in a `fillMaxSize` column —
         * but a caller who ever puts this inside a scrolling parent gets today's
         * layout instead of a `NaN` height.
         */
        fun forViewport(viewport: Dp): TimeGridMetrics = TimeGridMetrics(
            if (viewport.isFinite) {
                maxOf(MIN_HOUR_HEIGHT, viewport / VISIBLE_HOURS)
            } else {
                MIN_HOUR_HEIGHT
            }
        )
    }
}

/** Weekday, date, and the all-day band — the part that does not scroll away. */
@Composable
private fun DayHeaderRow(
    schedules: List<DaySchedule>,
    today: LocalDate?,
    calendars: List<CalendarSummary>,
    onOpen: (ResolvedOccurrence) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(RULER_WIDTH))   // aligns the header over the ruler

        schedules.forEach { schedule ->
            val isToday = schedule.date == today
            Column(
                Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = CalendarDateFormat.weekdayShort(schedule.date.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = schedule.date.day.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )

                // ⚠ Capped and scrolling rather than growing without bound:
                // three overlapping holidays must not push the grid off screen,
                // which is the whole reason all-day events were banded instead
                // of drawn in the column.
                if (schedule.allDay.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = ALL_DAY_BAND_MAX)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        schedule.allDay.forEach { occurrence ->
                            AllDayChip(occurrence, calendars, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllDayChip(
    occurrence: ResolvedOccurrence,
    calendars: List<CalendarSummary>,
    onOpen: (ResolvedOccurrence) -> Unit,
) {
    Text(
        text = occurrence.title.ifBlank { "(No title)" },
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(occurrence.colourIn(calendars).copy(alpha = ALL_DAY_TINT))
            .clickable { onOpen(occurrence) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** The hour labels down the left edge. */
@Composable
private fun HourRuler(metrics: TimeGridMetrics) {
    Column(Modifier.width(RULER_WIDTH)) {
        (0 until HOURS_PER_DAY).forEach { hour ->
            Box(Modifier.height(metrics.hourHeight).fillMaxWidth()) {
                // ⚠ Midnight gets no label: it sits at the very top with nothing
                // above it to divide from, and a "00:00" there reads as a
                // clipped row rather than a boundary.
                if (hour > 0) {
                    Text(
                        text = CalendarDateFormat.shortTime(LocalTime(hour, 0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 4.dp)
                            .offset(y = LABEL_LIFT),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    schedule: DaySchedule,
    calendars: List<CalendarSummary>,
    metrics: TimeGridMetrics,
    nowMinute: Int?,
    onOpen: (ResolvedOccurrence) -> Unit,
    onCreateAt: (LocalDate, LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠⚠ The *same* scale the ruler is drawn at. This is the number that turns a
    // tap into a time, so a column drawn at one scale and read at another would
    // create events at an hour nobody pointed at — the failure mode that makes a
    // window-derived height worth threading rather than re-deriving.
    val minuteHeightPx = with(LocalDensity.current) { metrics.minuteHeight.toPx() }

    // ⚠⚠ `BoxWithConstraints` because the horizontal placement is a *fraction*
    // of the column and `offset` speaks `dp`. The layout layer deliberately
    // reports fractions rather than pixels — it has no idea how wide a phone is,
    // and a renderer that had to be told would not be the same one iOS uses.
    BoxWithConstraints(
        modifier
            .height(metrics.gridHeight)
            .pointerInput(schedule.date) {
                detectTapGestures { offset ->
                    // ⚠ Snapped to the half hour. An unsnapped tap yields
                    // "10:37", which nobody means — the gesture says "around
                    // ten", and the editor is where it gets refined.
                    val minute = (offset.y / minuteHeightPx)
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY - 1)
                    val snapped = (minute / SNAP_MINUTES) * SNAP_MINUTES
                    onCreateAt(schedule.date, LocalTime(snapped / 60, snapped % 60))
                }
            }
    ) {
        val columnWidth = maxWidth

        HourLines(metrics)

        schedule.timed.forEach { positioned ->
            EventBlock(positioned, columnWidth, metrics, calendars, onOpen)
        }

        // ⚠ Drawn last so nothing can cover it — the current-time line is the
        // one thing on this screen that answers "where am I now".
        nowMinute?.let { CurrentTimeLine(it, metrics) }
    }
}

@Composable
private fun HourLines(metrics: TimeGridMetrics) {
    Column(Modifier.fillMaxSize()) {
        repeat(HOURS_PER_DAY) {
            Box(Modifier.height(metrics.hourHeight).fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun BoxScope.EventBlock(
    positioned: PositionedEvent<ResolvedOccurrence>,
    columnWidth: Dp,
    metrics: TimeGridMetrics,
    calendars: List<CalendarSummary>,
    onOpen: (ResolvedOccurrence) -> Unit,
) {
    val occurrence = positioned.value

    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(
                x = columnWidth * positioned.offsetFraction,
                y = metrics.minuteHeight * positioned.topMinute,
            )
            .width(columnWidth * positioned.widthFraction)
            .height(metrics.minuteHeight * (positioned.bottomMinute - positioned.topMinute))
            .padding(end = 2.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(occurrence.colourIn(calendars).copy(alpha = EVENT_TINT))
            .clickable { onOpen(occurrence) }
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = occurrence.title.ifBlank { "(No title)" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            // ⚠ Two lines at most and ellipsised: a 20-minute block is ~19 dp
            // tall, and text that overflowed would spill over its neighbours.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoxScope.CurrentTimeLine(nowMinute: Int, metrics: TimeGridMetrics) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(y = metrics.minuteHeight * nowMinute)
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.error)
    )
}

private fun ResolvedOccurrence.colourIn(calendars: List<CalendarSummary>): Color =
    calendars.firstOrNull { it.calendarId == calendarId }
        ?.let { Color(it.colorArgb.toLong() or 0xFF000000L) }
        ?: FALLBACK_COLOUR

private const val HOURS_PER_DAY = 24

/**
 * How much of the day the grid aims to show at once — the layout's one input, and
 * the number `HOUR_HEIGHT`'s old "about twelve hours of a working day fit a phone"
 * comment was really describing. macOS Calendar calls it "Show N hours at a time".
 */
private const val VISIBLE_HOURS = 12

/** The floor an hour never goes below — the phone-tuned constant this used to be. */
private val MIN_HOUR_HEIGHT = 56.dp
private val RULER_WIDTH = 48.dp
private val ALL_DAY_BAND_MAX = 48.dp

/** Lifts an hour label so it straddles its divider rather than sitting under it. */
private val LABEL_LIFT = (-6).dp

private val FALLBACK_COLOUR = Color(0xFF4285F4)
private const val EVENT_TINT = 0.30f
private const val ALL_DAY_TINT = 0.45f
private const val OPENING_HOUR = 8
private const val SNAP_MINUTES = 30
