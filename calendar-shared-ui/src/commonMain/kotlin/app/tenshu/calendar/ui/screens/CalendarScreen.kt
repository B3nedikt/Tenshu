package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.model.CalendarDateFormat
import app.tenshu.calendar.model.CalendarMonthCells
import app.tenshu.calendar.model.monthAndYear
import app.tenshu.calendar.ui.CalendarUiState
import app.tenshu.calendar.ui.CalendarView
import app.tenshu.calendar.ui.CalendarViewModel
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import app.tenshu.ui.layout.appContentWidth
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * Calendar's four views (CA-2's month + agenda, CA-4's week + day), and a launch
 * point for the CA-3 editor.
 *
 * ⚠ The screen itself still writes nothing — [onOpen] and [onCreateAt] hand the
 * decision up to whoever owns navigation, so this stays a pure projection of
 * `CalendarUiState`. The create affordance is [CalendarCreateButton], hoisted
 * for the same reason.
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onOpen: (ResolvedOccurrence) -> Unit,
    onCreateAt: (LocalDate, LocalTime) -> Unit,
    nowMinute: Int?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        CalendarHeader(
            state = state,
            onPrevious = { viewModel.step(-1) },
            onNext = { viewModel.step(1) },
            onToday = viewModel::goToToday,
            onSelectView = { view ->
                when (view) {
                    CalendarView.MONTH -> viewModel.showMonth()
                    CalendarView.WEEK -> viewModel.showWeek()
                    CalendarView.DAY -> viewModel.showDay()
                    CalendarView.AGENDA -> viewModel.showAgenda()
                }
            },
        )
        HorizontalDivider()

        when {
            // ⚠ Only a first load shows a spinner. Re-loading on a page step
            // keeps the old grid on screen, because flashing the whole view to
            // a spinner for a paging gesture reads as a glitch, not as progress.
            state.isLoading && !state.loaded -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.view == CalendarView.MONTH -> MonthPane(state, viewModel::selectDate, onOpen)

            state.isTimeGrid -> TimeGridView(
                schedules = state.schedules,
                calendars = state.calendars,
                today = state.today,
                nowMinute = nowMinute,
                onOpen = onOpen,
                onCreateAt = onCreateAt,
            )

            else -> AgendaPane(state, onOpen)
        }
    }
}

/**
 * The create affordance, hoisted so the shell can place it in its own `Scaffold`
 * — a FAB inside this `Column` would scroll away with the agenda.
 */
@Composable
fun CalendarCreateButton(state: CalendarUiState, onCreate: (LocalDate?) -> Unit) {
    FloatingActionButton(
        // ⚠ Creates on the **selected** day, not today: someone who has paged to
        // next month and tapped the 14th means the 14th.
        onClick = { onCreate(state.selectedDate) },
    ) {
        Icon(Icons.Default.Add, contentDescription = "New event")
    }
}

/**
 * Paging, the title and the view switcher.
 *
 * ⚠ Two arrangements, and the difference is not decoration. On a phone the
 * switcher is a full-width row under the title because that is the only way four
 * segments fit; carried onto a desktop window it stretches "Month | Week | Day |
 * Agenda" across the whole display, which is the single most un-native thing on
 * the screen. From [WindowWidthSizeClass.Expanded] up everything collapses into
 * one toolbar row — paging grouped at the leading edge, switcher wrapped to its
 * content at the trailing one.
 *
 * ⚠⚠ **Expanded, not `Medium` like the rest of this screen's adaptation.** The
 * grid's fix is about vertical fit and applies the moment the window stops being
 * a phone; this one is about *horizontal* room for three buttons, a title and
 * four segments at once, and 600–839 dp does not have it — measured at 700 dp the
 * title wraps to "May / 2027" and the toolbar grows a second line, which is worse
 * than the phone layout it replaced.
 */
@Composable
private fun CalendarHeader(
    state: CalendarUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSelectView: (CalendarView) -> Unit,
) {
    val wide = LocalWindowWidthSizeClass.current >= WindowWidthSizeClass.Expanded

    // ⚠ The arrows page whatever view is showing — a month, a week, a day — so
    // they are labelled "previous"/"next", not "previous month".
    // `CalendarViewModel.step` is the one entry point.
    val previous: @Composable () -> Unit = {
        IconButton(onPrevious, enabled = state.view.canPage) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
        }
    }
    val next: @Composable () -> Unit = {
        IconButton(onNext, enabled = state.view.canPage) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
        }
    }

    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            previous()
            next()
            TextButton(onToday) { Text("Today") }
            Text(
                text = state.headerTitle(),
                style = MaterialTheme.typography.titleLarge,
                // ⚠ One line, whatever happens. A wrapped title makes the toolbar
                // taller than the controls in it, and the whole point of this
                // arrangement is that it is one row.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            ViewSwitcher(state, onSelectView)
        }
        return
    }

    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            previous()
            Text(
                text = state.headerTitle(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            next()
            TextButton(onToday) { Text("Today") }
        }

        ViewSwitcher(state, onSelectView, Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
}

@Composable
private fun ViewSwitcher(
    state: CalendarUiState,
    onSelectView: (CalendarView) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        CalendarView.entries.forEachIndexed { index, view ->
            SegmentedButton(
                selected = state.view == view,
                onClick = { onSelectView(view) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = CalendarView.entries.size,
                ),
            ) { Text(view.label()) }
        }
    }
}

/**
 * The month grid plus the selected day's events.
 *
 * ⚠⚠ Stacked on a phone, side by side from [WindowWidthSizeClass.Medium] up — and
 * the split is what makes the grid usable, not just prettier. A fill-height grid
 * (see [MonthGridView]) and a stacked list are in direct competition for the same
 * vertical space: whatever the grid takes, the list loses, and a list under a grid
 * that has grown to fill a 900dp window has nothing left. Beside it, each gets an
 * axis of its own.
 */
@Composable
private fun MonthPane(
    state: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit,
    onOpen: (ResolvedOccurrence) -> Unit,
) {
    val grid = state.grid ?: return
    val sideBySide = LocalWindowWidthSizeClass.current >= WindowWidthSizeClass.Medium

    // ⚠ Grouped once per load, not per cell. `CalendarUiState.countsByDate` is a
    // computed getter that walks the whole window on every read, and forty-two cells
    // reaching into the occurrence list themselves is the same walk forty-two times.
    val eventsByDate = remember(state.occurrences, state.calendars) {
        CalendarMonthCells.byDate(state.occurrences, state.calendars)
    }

    val monthGrid: @Composable (Modifier) -> Unit = { gridModifier ->
        MonthGridView(
            grid = grid,
            today = state.today,
            selectedDate = state.selectedDate,
            eventsByDate = eventsByDate,
            onSelectDate = onSelectDate,
            onOpen = onOpen,
            modifier = gridModifier,
        )
    }

    if (sideBySide) {
        Row(Modifier.fillMaxSize()) {
            monthGrid(Modifier.weight(1f).padding(horizontal = 4.dp))
            VerticalDivider()
            SelectedDayPane(
                state = state,
                onOpen = onOpen,
                showDayHeader = true,
                modifier = Modifier.width(DAY_PANE_WIDTH),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        monthGrid(Modifier.padding(horizontal = 4.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SelectedDayPane(
            state = state,
            onOpen = onOpen,
            showDayHeader = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The selected day's events — the lower half on a phone, the trailing pane on a desktop. */
@Composable
private fun SelectedDayPane(
    state: CalendarUiState,
    onOpen: (ResolvedOccurrence) -> Unit,
    showDayHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val day = state.selectedDate
    val dayEvents = day?.let(state::occurrencesOn).orEmpty()

    Column(modifier) {
        // ⚠ Only in the side pane. Stacked, the grid's own highlighted cell is
        // right above the list and says which day this is; detached in a pane of
        // its own, a bare list of times names no date at all.
        if (showDayHeader && day != null) {
            Text(
                text = dayLabel(day, state.today),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        if (dayEvents.isEmpty()) {
            EmptyDay(day)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(dayEvents, key = { it.eventId + it.recurrenceId }) { occurrence ->
                    OccurrenceRow(occurrence, state.calendars, onOpen)
                }
            }
        }
    }
}

@Composable
private fun AgendaPane(state: CalendarUiState, onOpen: (ResolvedOccurrence) -> Unit) {
    if (state.occurrences.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing coming up", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // ⚠ Grouped by day with a sticky-ish header row rather than one flat list:
    // an agenda without day boundaries is unreadable past about five entries.
    val byDay = state.occurrences.groupBy { it.startLocal.date }

    // ⚠⚠ `appContentWidth()` BEFORE `fillMaxSize()`, never after — `fillMaxSize`
    // measures its child with a *fixed* width, so a cap placed inside one is
    // silently coerced back up and the rows stretch the full window again. That is
    // `ContentWidthOrderTest`'s whole subject. The centring `Box` is what the cap
    // needs to sit in the middle rather than hug the leading edge.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.appContentWidth().fillMaxSize()) {
            byDay.forEach { (date, dayEvents) ->
                item(key = "header-$date") {
                    Text(
                        text = dayLabel(date, state.today),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(dayEvents, key = { it.eventId + it.recurrenceId }) { occurrence ->
                    OccurrenceRow(occurrence, state.calendars, onOpen)
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(
    occurrence: ResolvedOccurrence,
    calendars: List<CalendarSummary>,
    onOpen: (ResolvedOccurrence) -> Unit,
) {
    val colour = calendars.firstOrNull { it.calendarId == occurrence.calendarId }
        ?.let { Color(it.colorArgb.toLong() or 0xFF000000L) }
        ?: MaterialTheme.colorScheme.primary

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(occurrence) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Column(Modifier.weight(1f)) {
            Text(
                text = occurrence.title.ifBlank { "(No title)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (occurrence.location.isNotBlank()) {
                Text(
                    text = occurrence.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            // ⚠ An all-day event has no meaningful time half — rendering
            // "00:00" for one is the classic all-day bug.
            text = if (occurrence.allDay) "All day"
                   else CalendarDateFormat.shortTime(occurrence.startLocal.time),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyDay(day: LocalDate?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = if (day == null) "Pick a day" else "No events",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * What the header says, per view.
 *
 * ⚠ A week straddling two months names both ("Aug – Sep 2026"): a week header
 * showing only the month its Monday falls in is wrong for two days out of seven,
 * and wrong in a way nobody notices until they have booked the wrong week.
 */
private fun CalendarUiState.headerTitle(): String = when (view) {
    CalendarView.MONTH, CalendarView.AGENDA ->
        CalendarDateFormat.monthAndYear(year, month)

    CalendarView.DAY ->
        schedules.firstOrNull()?.date?.let(CalendarDateFormat::mediumDate)
            ?: CalendarDateFormat.monthAndYear(year, month)

    CalendarView.WEEK -> {
        val first = schedules.firstOrNull()?.date
        val last = schedules.lastOrNull()?.date
        when {
            first == null || last == null -> CalendarDateFormat.monthAndYear(year, month)
            first.month.number == last.month.number && first.year == last.year ->
                CalendarDateFormat.monthAndYear(first.year, first.month.number)
            first.year != last.year ->
                CalendarDateFormat.monthAndYear(first.year, first.month.number) + " – " +
                    CalendarDateFormat.monthAndYear(last.year, last.month.number)
            else ->
                CalendarDateFormat.monthName(first.month.number) + " – " +
                    CalendarDateFormat.monthAndYear(last.year, last.month.number)
        }
    }
}

private fun CalendarView.label(): String = when (this) {
    CalendarView.MONTH -> "Month"
    CalendarView.WEEK -> "Week"
    CalendarView.DAY -> "Day"
    CalendarView.AGENDA -> "Agenda"
}

private fun dayLabel(date: LocalDate, today: LocalDate?): String =
    if (date == today) "Today · ${CalendarDateFormat.mediumDate(date)}"
    else CalendarDateFormat.mediumDate(date)

/**
 * The selected-day pane beside the month grid.
 *
 * ⚠ Fixed rather than a fraction: an event row is the same size whatever the
 * window is, so a proportional pane would be half-empty on a wide display while
 * the grid — the half that genuinely uses the space — went without it.
 */
private val DAY_PANE_WIDTH = 300.dp
