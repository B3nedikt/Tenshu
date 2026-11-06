package app.tenshu.fit.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.tenshu.fit.model.Workout
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_history_empty_body
import app.tenshu.fit.shared.resources.fit_history_empty_title
import app.tenshu.fit.shared.resources.fit_history_retry
import app.tenshu.fit.shared.resources.fit_history_time_label
import app.tenshu.fit.shared.resources.fit_history_title
import app.tenshu.fit.shared.resources.fit_history_unavailable
import app.tenshu.fit.shared.resources.fit_history_week_summary
import app.tenshu.fit.shared.resources.fit_home_distance_label
import app.tenshu.fit.shared.resources.fit_unit_h
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.StatCell
import app.tenshu.fit.ui.components.WorkoutRow
import app.tenshu.fit.ui.components.clock
import app.tenshu.fit.ui.components.dateMedium
import app.tenshu.fit.ui.components.dayLabel
import app.tenshu.fit.ui.components.distanceText
import app.tenshu.fit.ui.components.distanceValueUnit
import app.tenshu.fit.ui.components.durationLong
import app.tenshu.fit.ui.components.workoutTitle
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.jetbrains.compose.resources.stringResource

/**
 * History (docs/proposals/fit-app.md screen 5) — a week roll-up card over the workout feed; each
 * row shows its provenance badge (fit-agent.md §1.3).
 *
 * The feed is **paged** (`fit-history-paging-handover.md`), so it reaches the whole of the user's
 * history rather than the repository's list window — which is what retired the "Earlier weeks"
 * label that used to sit at the bottom and go nowhere.
 *
 * ⚠ The week card does NOT come from the paged feed. Only the loaded pages exist at any moment, so
 * summing them would make the roll-up change as the user scrolls; it rides the view model's
 * bounded list read instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenWorkout: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val workouts = viewModel.workouts.collectAsLazyPagingItems()
    // §18.15: the headline is a real bar now, so it owns its own status-bar inset and shifts to
    // `surfaceContainer` once the feed is under it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FitTopBar(
                title = stringResource(Res.string.fit_history_title),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        // ⚠ Lazy, and now paged on top of that. Laziness alone was already load-bearing — an eager
        // `Column(verticalScroll)` composed every row on first paint AND again on every return to
        // the tab, which read as "the list reloads" — but it still needed the whole feed in memory.
        // Paging bounds that too: only the loaded pages exist, and `LazyPagingItems` appends as the
        // user nears the end.
        //
        // ⚠ NO `verticalArrangement`: an item is one workout, and consecutive workouts of the SAME
        // day must touch so their cards read as the one card the day-grouped feed drew. The gap
        // before a day header is emitted by the item itself instead.
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item(key = "week-summary") {
                WeekSummary(
                    workoutCount = state.weekWorkoutCount,
                    distanceM = state.weekDistanceM,
                    durationSec = state.weekDurationSec,
                )
            }

            items(
                count = workouts.itemCount,
                key = workouts.itemKey { it.workoutId },
            ) { index ->
                val workout = workouts[index]
                if (workout != null) {
                    // ⚠ `peek`, not `get`, for the neighbours: `get` RECORDS the access, and
                    // recording an access one row past the viewport is what tells Paging to load
                    // the next page — so deciding this row's corner radius would prefetch. `peek`
                    // reads the same presented item without the side effect.
                    val previous = if (index > 0) workouts.peek(index - 1) else null
                    val next = if (index + 1 < workouts.itemCount) workouts.peek(index + 1) else null
                    HistoryRow(
                        workout = workout,
                        today = viewModel.today,
                        // A day header is emitted when this row's date differs from its
                        // predecessor's — the paged equivalent of grouping, and the only form of
                        // grouping that survives a day straddling a page boundary.
                        startsDay = previous?.start?.date != workout.start.date,
                        // ⚠ The last LOADED row of a day, not necessarily the last one: when the
                        // next page has yet to arrive this rounds the card off early and squares it
                        // again when the page lands. Honest — it renders what is known — and
                        // invisible in practice, since the append fires a screenful ahead.
                        endsDay = next?.start?.date != workout.start.date,
                        onOpenWorkout = onOpenWorkout,
                    )
                }
            }

            // Load state comes from Paging, NOT `ListLoadPhase` — the phase type stays right for
            // Home and Browse, which are not paged. A refresh failure is terminal until retried
            // (unlike the repository's self-retrying list reads), which is why this one offers the
            // action rather than saying "still trying".
            when (workouts.loadState.refresh) {
                is LoadState.Loading -> if (workouts.itemCount == 0) item(key = "refresh") { FeedLoading() }
                is LoadState.Error -> item(key = "refresh") { FeedUnavailable(onRetry = workouts::retry) }
                else -> if (workouts.itemCount == 0) item(key = "empty") { FeedEmpty() }
            }

            when (workouts.loadState.append) {
                is LoadState.Loading -> item(key = "append") { FeedLoading() }
                is LoadState.Error -> item(key = "append") { FeedUnavailable(onRetry = workouts::retry) }
                else -> Unit
            }
        }
    }
}

/**
 * One workout, carrying its day header when it is the first of its day.
 *
 * The card's corners are squared where it meets a neighbour of the same day, so a run of rows
 * renders as the single day card the unpaged feed drew — the divider then sits exactly where the
 * `DayGroup` card's did.
 */
@Composable
private fun HistoryRow(
    workout: Workout,
    today: LocalDate,
    startsDay: Boolean,
    endsDay: Boolean,
    onOpenWorkout: (String) -> Unit,
) {
    Column {
        if (startsDay) {
            Spacer(Modifier.height(16.dp))
            DayHeader(date = workout.start.date, today = today)
        }
        FitCard(
            Modifier.fillMaxWidth(),
            padding = PaddingValues(horizontal = 16.dp),
            shape = dayRunShape(startsDay = startsDay, endsDay = endsDay),
        ) {
            Column {
                if (!startsDay) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                WorkoutRow(
                    activityType = workout.activityType,
                    title = workoutTitle(workout.title, workout.activityType),
                    source = workout.source,
                    meta = historyMeta(workout),
                    onClick = { onOpenWorkout(workout.workoutId) },
                )
            }
        }
    }
}

/** The card shape for a row's position within its day's run — derived from the theme, never a literal radius. */
@Composable
private fun dayRunShape(startsDay: Boolean, endsDay: Boolean): Shape {
    val base = MaterialTheme.shapes.large
    val square = CornerSize(0.dp)
    return when {
        startsDay && endsDay -> base
        startsDay -> base.copy(bottomStart = square, bottomEnd = square)
        endsDay -> base.copy(topStart = square, topEnd = square)
        else -> base.copy(square)
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = dayLabel(date.daysUntil(today), date),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = dateMedium(date),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeedLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        AppLoadingIndicator(size = AppLoadingSize.Inline)
    }
}

/**
 * Shown when the read FAILED, never when it merely returned nothing — [FeedEmpty] claims the user
 * has no workouts, which a failed read cannot know (the distinction `ListLoadState` exists to keep,
 * carried over to Paging's `LoadState`).
 */
@Composable
private fun FeedUnavailable(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.fit_history_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.fit_history_retry)) }
    }
}

@Composable
private fun FeedEmpty() {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.fit_history_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.fit_history_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun historyMeta(workout: Workout): String {
    val parts = mutableListOf(clock(workout.start), durationLong(workout.durationSec))
    workout.distanceM?.let { parts.add(distanceText(it)) }
    return parts.joinToString(" · ")
}

@Composable
private fun WeekSummary(workoutCount: Int, distanceM: Int, durationSec: Int) {
    val distance = distanceValueUnit(distanceM)
    FitCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                value = workoutCount.toString(),
                label = stringResource(Res.string.fit_history_week_summary),
                center = true,
                modifier = Modifier.weight(1f),
            )
            Divider()
            StatCell(
                value = distance.value,
                unit = distance.unit,
                label = stringResource(Res.string.fit_home_distance_label),
                center = true,
                modifier = Modifier.weight(1f),
            )
            Divider()
            StatCell(
                value = (durationSec / 3600).toString(),
                unit = stringResource(Res.string.fit_unit_h),
                label = stringResource(Res.string.fit_history_time_label),
                center = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Divider() {
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.height(40.dp).width(1.dp),
    ) {}
}
