package app.tenshu.fit.ui.screens.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.fit.model.FitFormat
import app.tenshu.fit.shared.resources.fit_unit_kcal
import app.tenshu.fit.shared.resources.fit_steps_value
import app.tenshu.fit.shared.resources.fit_home_details
import app.tenshu.fit.ui.screens.category.CategoryMetric
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.Workout
import app.tenshu.fit.shared.resources.fit_duration_minutes
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_home_active_minutes
import app.tenshu.fit.shared.resources.fit_home_distance_label
import app.tenshu.fit.shared.resources.fit_home_empty_body
import app.tenshu.fit.shared.resources.fit_home_empty_body_no_source
import app.tenshu.fit.shared.resources.fit_home_empty_import_file
import app.tenshu.fit.shared.resources.fit_home_empty_cta
import app.tenshu.fit.shared.resources.fit_home_unavailable
import app.tenshu.fit.shared.resources.fit_home_unavailable_update
import app.tenshu.fit.shared.resources.fit_nav_home
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.fit.shared.resources.fit_home_empty_title
import app.tenshu.fit.shared.resources.fit_home_energy_label
import app.tenshu.fit.shared.resources.fit_home_goal_reached
import app.tenshu.fit.shared.resources.fit_home_history_link
import app.tenshu.fit.shared.resources.fit_home_latest_workout
import app.tenshu.fit.shared.resources.fit_home_min_to_go
import app.tenshu.fit.shared.resources.fit_home_of_goal
import app.tenshu.fit.shared.resources.fit_home_steps_label
import app.tenshu.fit.shared.resources.fit_home_today_eyebrow
import app.tenshu.fit.shared.resources.fit_home_trend_title
import app.tenshu.fit.shared.resources.fit_home_vs_last_week
import app.tenshu.fit.shared.resources.fit_home_who_subtitle
import app.tenshu.fit.ui.components.Eyebrow
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.ImportStatusBanner
import app.tenshu.fit.ui.components.Ring
import app.tenshu.fit.ui.components.StatCell
import app.tenshu.fit.ui.components.TrendBar
import app.tenshu.fit.ui.components.TrendBars
import app.tenshu.fit.ui.components.WorkoutRow
import app.tenshu.fit.ui.components.clock
import app.tenshu.fit.ui.components.dateLong
import app.tenshu.fit.ui.components.distanceText
import app.tenshu.fit.ui.components.distanceValueUnit
import app.tenshu.fit.ui.components.durationLong
import app.tenshu.fit.ui.components.energyValueUnit
import app.tenshu.fit.ui.components.groupThousands
import app.tenshu.fit.ui.components.workoutTitle
import app.tenshu.fit.ui.theme.tabular
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

/**
 * Home (docs/proposals/fit-app.md screen 4) — the single Active-Minutes goal ring (three-ring
 * variant dropped, D-locked), a steps/distance/energy strip, a 7-day trend with a week
 * delta, the latest-workout card, and the trust cue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenWorkout: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onStartRecording: () -> Unit,
    /** The empty state's "Import a file" — the batch import screen, which owns the picker. */
    onOpenBatchImport: () -> Unit = {},
    /**
     * A week card or a stat under the ring was tapped — open that series' detail (week chart +
     * day-by-day entries), as Google Fit does (fit-app.md §18.18).
     */
    onOpenMetric: (CategoryMetric) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    // Whether the unavailable state may claim to be retrying (§8.6) — see [HomeUnavailableState].
    val waitingWillFix by viewModel.waitingWillFix.collectAsState()
    val today = state.today
    // Home scrolls as ONE page: the bar leaves with the content and returns only once the content
    // is back at the top (fit-app.md §18.16). `exitUntilCollapsed` does that on the small bar too —
    // it collapses the full 64.dp. ⚠ `snapAnimationSpec = null`: the default snap finishes a
    // half-collapsed bar on its own after a fling, which unlocks it from the content it scrolls
    // with. ⚠ Home only — the Journal and the other tabs keep the pinned bar, a list's header
    // stays put.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(snapAnimationSpec = null)
    // ⚠ The bar sits HERE, not inside `HomeContent`, so all three of Home's states wear it. An
    // empty or failed Home that lost its header would be the header-jump this sweep exists to
    // remove, just moved from between tabs to between states of one tab.
    //
    // ⚠ Its title is the nav label, NOT the date: the eyebrow + date block below is *content* —
    // it names the day the aggregates describe, and only the loaded state has a day to name.
    // Hoisting it into the bar would either lose it or make the title flicker between states.
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FitTopBar(
                title = stringResource(Res.string.fit_nav_home),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                today != null ->
                    HomeContent(state, today, onOpenWorkout, onOpenHistory, viewModel::retryImport, onOpenMetric)
                // ⚠ FAILED before the empty state, and the order is load-bearing: both arrive with
                // no aggregates, and the empty state is a *claim* ("you have no activity — connect
                // a source or record one") that a failed read has no standing to make.
                state.load == ListLoadPhase.FAILED -> HomeUnavailableState(waitingWillFix)
                // Loaded with no aggregates at all — no imported/recorded activity yet (e.g. Health
                // Connect granted but empty, or awaiting a first recording). Guide the user instead
                // of rendering a blank tab: the banner says *why* it is empty, which the empty
                // state alone cannot — "no data" and "no permission" look the same from here.
                state.load == ListLoadPhase.LOADED ->
                    HomeEmptyState(state, onStartRecording, onOpenBatchImport, viewModel::retryImport)
                // First frame before the repository's flows have emitted — stay blank, don't flash
                // empty.
                else -> Unit
            }
        }
    }
}

/**
 * The aggregates read failed and there is nothing cached to show.
 *
 * No retry button, deliberately: the read is a reactive `flow()` subscription that retries itself
 * (`core-client-resubscription.md` R1b), so a button would duplicate work already in flight and
 * imply the user is responsible for it. The shell's Control-unreachable banner (§9.14) sits above
 * this and explains the common cause when that is what it is.
 *
 * ⚠⚠ [waitingWillFix] is what keeps that first paragraph honest. Self-retrying is true of a
 * transport outage and **false** of a schema refusal, which rejects the session until the user
 * updates something. Found on the first device pass of the skew banner: the shell said *"Update
 * Control to keep your health data syncing"* while this text, directly beneath it, said *"Still
 * trying…"*. The specific claim was right and the reassuring one was wrong, on the same screen.
 *
 * ⚠ The alternative copy deliberately does **not** name an app or point at the banner. The banner
 * already names it, "above" is fragile, and saying it twice is how one of them goes stale.
 */
@Composable
private fun HomeUnavailableState(waitingWillFix: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(
                if (waitingWillFix) Res.string.fit_home_unavailable
                else Res.string.fit_home_unavailable_update
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    today: DailyAggregate,
    onOpenWorkout: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onRetryImport: () -> Unit,
    onOpenMetric: (CategoryMetric) -> Unit,
) {
    // ⚠ One scroll container, date included: the date block used to sit above the scroll area and
    // stayed put while the rest moved under it. The date sits OUTSIDE the 12.dp spacing so its
    // 8/4 padding is unchanged — moving it in must not move anything beneath it.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Eyebrow(stringResource(Res.string.fit_home_today_eyebrow))
            Text(
                text = dateLong(today.date),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Above the ring: data that is stale, partial, or fake shouldn't be discovered *below*
            // the numbers it undermines. Renders nothing when the import is healthy.
            ImportStatusBanner(
                status = state.importStatus,
                onRetry = onRetryImport,
                modifier = Modifier.fillMaxWidth(),
            )
            HeroRing(today = today, goals = state.goals)
            StatStrip(today = today, onOpenMetric = onOpenMetric)
            WeekCharts(state = state, onOpenMetric = onOpenMetric)
            LatestWorkoutModule(
                workout = state.latestWorkout,
                onOpenWorkout = onOpenWorkout,
                onOpenHistory = onOpenHistory,
            )
        }
    }
}

@Composable
private fun HomeEmptyState(
    state: HomeUiState,
    onStartRecording: () -> Unit,
    onOpenBatchImport: () -> Unit,
    onRetryImport: () -> Unit,
) {
    val importStatus = state.importStatus
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The whole point of the slice: an empty Home that explains itself. Without this, a user
        // whose Health Connect holds nothing sees the same screen as one who denied the permission.
        ImportStatusBanner(
            status = importStatus,
            onRetry = onRetryImport,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    FitIcons.Steps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.fit_home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // ⚠⚠ Two honest variants, never sample data (fit-app.md §9.15). Without Health Connect
        // (a phone that cannot run it, or desktop) there is no source to connect, so the copy says
        // so instead of promising one — the iOS twin is HomeView.swift's Apple Health variant.
        Text(
            text = stringResource(
                if (state.healthSourceAvailable) Res.string.fit_home_empty_body
                else Res.string.fit_home_empty_body_no_source,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStartRecording, modifier = Modifier.height(50.dp)) {
            Icon(FitIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.fit_home_empty_cta),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // The copy above names file import as the other way in, so offer it — where the shell
        // can pick a file at all.
        if (!state.healthSourceAvailable && state.canImportFiles) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenBatchImport, modifier = Modifier.height(50.dp)) {
                Text(
                    text = stringResource(Res.string.fit_home_empty_import_file),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun HeroRing(today: DailyAggregate, goals: Goals) {
    val reached = today.activeMinutes >= goals.activeMinutes
    FitCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(24.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Ring(
                value = today.activeMinutes,
                goal = goals.activeMinutes,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = today.activeMinutes.toString(),
                    style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.fit_home_of_goal, goals.activeMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reached) {
                    Icon(
                        FitIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = if (reached) stringResource(Res.string.fit_home_goal_reached)
                    else stringResource(Res.string.fit_home_min_to_go, goals.activeMinutes - today.activeMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.fit_home_who_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatStrip(today: DailyAggregate, onOpenMetric: (CategoryMetric) -> Unit) {
    val distance = distanceValueUnit(today.distanceM)
    val energy = energyValueUnit(today.activeEnergyKj)
    FitCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                value = groupThousands(today.steps),
                label = stringResource(Res.string.fit_home_steps_label),
                icon = FitIcons.Steps,
                center = true,
                modifier = Modifier.weight(1f).openable { onOpenMetric(CategoryMetric.Steps) },
            )
            StripDivider()
            StatCell(
                value = distance.value,
                unit = distance.unit,
                label = stringResource(Res.string.fit_home_distance_label),
                icon = FitIcons.Distance,
                center = true,
                modifier = Modifier.weight(1f).openable { onOpenMetric(CategoryMetric.Distance) },
            )
            StripDivider()
            StatCell(
                value = energy.value,
                unit = energy.unit,
                label = stringResource(Res.string.fit_home_energy_label),
                icon = FitIcons.Flame,
                center = true,
                modifier = Modifier.weight(1f).openable { onOpenMetric(CategoryMetric.ActiveEnergy) },
            )
        }
    }
}

@Composable
private fun StripDivider() {
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.height(40.dp).width(1.dp),
    ) {}
}

/**
 * A stat cell opens its series' detail. ⚠ Clipped before `clickable` so the ripple keeps the
 * rounded shape inside the strip instead of flooding the card.
 */
@Composable
private fun Modifier.openable(onClick: () -> Unit): Modifier {
    val label = stringResource(Res.string.fit_home_details)
    return clip(MaterialTheme.shapes.medium)
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .padding(vertical = 4.dp)
}

/**
 * Home's week charts, one card per daily series, in Google Fit's order (fit-app.md §18.18):
 * energy burned, steps, active minutes, distance. Each opens its detail.
 */
@Composable
private fun WeekCharts(state: HomeUiState, onOpenMetric: (CategoryMetric) -> Unit) {
    val week = state.week
    WeekTrendCard(
        title = stringResource(Res.string.fit_home_energy_label),
        bars = week.map { TrendBar(it.date, it.activeEnergyKj) },
        goal = 0, // no energy goal
        valueLabel = { "${groupThousands(FitFormat.kjToKcal(it))} ${stringResource(Res.string.fit_unit_kcal)}" },
        onOpen = { onOpenMetric(CategoryMetric.ActiveEnergy) },
    )
    WeekTrendCard(
        title = stringResource(Res.string.fit_home_steps_label),
        bars = week.map { TrendBar(it.date, it.steps) },
        goal = state.goals.steps,
        valueLabel = { stringResource(Res.string.fit_steps_value, groupThousands(it)) },
        onOpen = { onOpenMetric(CategoryMetric.Steps) },
    )
    WeekTrendCard(
        title = stringResource(Res.string.fit_home_active_minutes),
        bars = week.map { TrendBar(it.date, it.activeMinutes) },
        goal = state.goals.activeMinutes,
        valueLabel = { stringResource(Res.string.fit_duration_minutes, it) },
        deltaPercent = state.weekDeltaPercent,
        onOpen = { onOpenMetric(CategoryMetric.ActiveMinutes) },
    )
    WeekTrendCard(
        title = stringResource(Res.string.fit_home_distance_label),
        bars = week.map { TrendBar(it.date, it.distanceM) },
        goal = 0, // no distance goal
        valueLabel = { distanceText(it) },
        onOpen = { onOpenMetric(CategoryMetric.Distance) },
    )
}

/**
 * One week card: title, an optional week-over-week delta, a › and the 7-day [TrendBars].
 *
 * ⚠ The **whole card** is the button, like Google Fit's. That does not fight the chart's
 * scrubbing: [TrendBars] consumes the drag once it is horizontal, which cancels this click, and a
 * vertical drag is the page's scroll, which cancels it too — only a plain tap opens the detail.
 */
@Composable
private fun WeekTrendCard(
    title: String,
    bars: List<TrendBar>,
    goal: Int,
    valueLabel: @Composable (Int) -> String,
    onOpen: () -> Unit,
    deltaPercent: Int? = null,
) {
    val details = stringResource(Res.string.fit_home_details)
    FitCard(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = details, role = Role.Button, onClick = onOpen),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (deltaPercent != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                            if (deltaPercent >= 0) FitIcons.ArrowUp else FitIcons.ArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(Res.string.fit_home_vs_last_week, "${abs(deltaPercent)}%"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Decorative: the card's click label already says "Details".
                Icon(
                    FitIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            TrendBars(
                bars = bars,
                goal = goal,
                accent = MaterialTheme.colorScheme.primary,
                valueLabel = valueLabel,
            )
        }
    }
}

@Composable
private fun LatestWorkoutModule(
    workout: Workout?,
    onOpenWorkout: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    if (workout == null) return
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(Res.string.fit_home_latest_workout).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.fit_home_history_link),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenHistory).padding(4.dp),
            )
        }
        val meta = "${clock(workout.start)} · ${latestBits(workout)}"
        FitCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            WorkoutRow(
                activityType = workout.activityType,
                title = workoutTitle(workout.title, workout.activityType),
                source = workout.source,
                meta = meta,
                onClick = { onOpenWorkout(workout.workoutId) },
            )
        }
    }
}

@Composable
private fun latestBits(workout: Workout): String {
    val parts = mutableListOf(durationLong(workout.durationSec))
    workout.distanceM?.let { parts.add(distanceText(it)) }
    return parts.joinToString(" · ")
}
