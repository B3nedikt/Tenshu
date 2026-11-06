package app.tenshu.fit.ui.screens.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.fit.data.RouteRegionStream
import app.tenshu.fit.model.Workout
import app.tenshu.fit.model.WorkoutLap
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_bpm
import app.tenshu.fit.shared.resources.fit_more
import app.tenshu.fit.shared.resources.fit_record_steps
import app.tenshu.fit.shared.resources.fit_workout_ascent
import app.tenshu.fit.shared.resources.fit_workout_captured
import app.tenshu.fit.shared.resources.fit_workout_hr_summary
import app.tenshu.fit.shared.resources.fit_workout_hr_title
import app.tenshu.fit.shared.resources.fit_workout_load_failed
import app.tenshu.fit.shared.resources.fit_workout_load_failed_body
import app.tenshu.fit.shared.resources.fit_workout_loading
import app.tenshu.fit.shared.resources.fit_workout_retry
import app.tenshu.fit.shared.resources.fit_workout_lap_header
import app.tenshu.fit.shared.resources.fit_workout_lap_hr_header
import app.tenshu.fit.shared.resources.fit_workout_lap_km_header
import app.tenshu.fit.shared.resources.fit_workout_delete
import app.tenshu.fit.shared.resources.fit_workout_delete_cancel
import app.tenshu.fit.shared.resources.fit_workout_delete_confirm_action
import app.tenshu.fit.shared.resources.fit_workout_delete_confirm_body
import app.tenshu.fit.shared.resources.fit_workout_delete_confirm_title
import app.tenshu.fit.shared.resources.fit_workout_delete_failed_body
import app.tenshu.fit.shared.resources.fit_workout_delete_failed_dismiss
import app.tenshu.fit.shared.resources.fit_workout_delete_failed_title
import app.tenshu.fit.shared.resources.fit_workout_lap_pace_header
import app.tenshu.fit.shared.resources.fit_workout_lap_time_header
import app.tenshu.fit.shared.resources.fit_workout_laps_title
import app.tenshu.fit.shared.resources.fit_workout_provenance_retained
import app.tenshu.fit.shared.resources.fit_workout_route_note
import app.tenshu.fit.shared.resources.fit_workout_route_title
import app.tenshu.fit.shared.resources.fit_workout_stat_avg_hr
import app.tenshu.fit.shared.resources.fit_workout_stat_distance
import app.tenshu.fit.shared.resources.fit_workout_stat_duration
import app.tenshu.fit.shared.resources.fit_workout_stat_energy
import app.tenshu.fit.shared.resources.fit_workout_stat_max_hr
import app.tenshu.fit.shared.resources.fit_workout_stat_pace
import app.tenshu.fit.shared.resources.fit_workout_not_found
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.HrChart
import app.tenshu.fit.ui.components.RoutePath
import app.tenshu.fit.ui.components.SectionHeader
import app.tenshu.fit.ui.components.SourceBadge
import app.tenshu.fit.ui.components.StatCell
import app.tenshu.fit.ui.components.TrustCue
import app.tenshu.fit.ui.components.WorkoutRouteMap
import app.tenshu.fit.ui.components.clock
import app.tenshu.fit.ui.components.dateLong
import app.tenshu.fit.ui.components.deviceLabel
import app.tenshu.fit.ui.components.distanceValueUnit
import app.tenshu.fit.ui.components.durationClock
import app.tenshu.fit.ui.components.energyValueUnit
import app.tenshu.fit.ui.components.groupThousands
import app.tenshu.fit.ui.components.intOrDash
import app.tenshu.fit.ui.components.paceValueUnit
import app.tenshu.fit.ui.components.workoutTitle
import app.tenshu.fit.ui.theme.ActivityAccents
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.fit.ui.theme.tabular
import org.jetbrains.compose.resources.stringResource

/** Workout detail (docs/proposals/fit-app.md screen 6). */
@Composable
fun WorkoutDetailScreen(
    viewModel: WorkoutDetailViewModel,
    onBack: () -> Unit,
    onOpenRouteMap: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val deleteFailed by viewModel.deleteFailed.collectAsState()
    var confirmingDelete by remember { mutableStateOf(false) }

    // The row is gone; leave the screen it was opened from. Keyed on the state so this fires
    // once, on the transition — not on every recomposition of a deleted screen.
    LaunchedEffect(state) {
        if (state is WorkoutDetailUiState.Deleted) onBack()
    }

    Column(Modifier.fillMaxSize()) {
        val title = (state as? WorkoutDetailUiState.Loaded)
            ?.let { workoutTitle(it.workout.title, it.workout.activityType) } ?: ""
        DetailTopBar(
            title = title,
            onBack = onBack,
            // Overflow only where there is something to act on — a Loading, NotFound or
            // already-Deleted screen has no workout to delete.
            onDelete = (state as? WorkoutDetailUiState.Loaded)?.let { { confirmingDelete = true } },
        )
        when (val s = state) {
            // ⚠ `Deleted` alone stays empty: the `LaunchedEffect` above is already popping the
            // screen, so anything drawn here is a flash on the way out.
            WorkoutDetailUiState.Deleted -> Unit

            // ⚠⚠ Never `Unit` like the line above. Measured on a real device against a cold Control
            // the read takes 9–23 s (90 ms warm); a blank screen under a back arrow for all of it
            // is indistinguishable from a crash.
            WorkoutDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppLoadingIndicator(size = AppLoadingSize.Medium)
                    Spacer(Modifier.height(MaterialTheme.spacing.m))
                    Text(
                        stringResource(Res.string.fit_workout_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ⚠⚠ Distinct from `NotFound` on purpose, and the reason `WorkoutRead` exists: this
            // screen must not tell the user their workout is gone when all that is known is that
            // the read did not finish. Retry is the affordance that difference buys.
            WorkoutDetailUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(MaterialTheme.spacing.l),
                ) {
                    Text(
                        stringResource(Res.string.fit_workout_load_failed),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.s))
                    Text(
                        stringResource(Res.string.fit_workout_load_failed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.m))
                    TextButton(onClick = viewModel::retry) {
                        Text(stringResource(Res.string.fit_workout_retry))
                    }
                }
            }

            WorkoutDetailUiState.NotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.fit_workout_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is WorkoutDetailUiState.Loaded ->
                Loaded(s.workout, s.regionBundlePath, s.regionStream, onOpenRouteMap)
        }
    }

    if (confirmingDelete) {
        AppDialog(
            title = stringResource(Res.string.fit_workout_delete_confirm_title),
            onDismissRequest = { confirmingDelete = false },
            confirmLabel = stringResource(Res.string.fit_workout_delete_confirm_action),
            onConfirm = {
                confirmingDelete = false
                viewModel.delete()
            },
            dismissLabel = stringResource(Res.string.fit_workout_delete_cancel),
            content = { Text(stringResource(Res.string.fit_workout_delete_confirm_body)) },
        )
    }

    // ⚠ A delete that could not happen must say so and leave the workout on screen. The
    // repository returns `false` rather than throwing when there is no proxy behind it, and
    // popping anyway would show the row vanishing while it is still in the store.
    if (deleteFailed) {
        AppDialog(
            title = stringResource(Res.string.fit_workout_delete_failed_title),
            onDismissRequest = { viewModel.clearDeleteFailure() },
            confirmLabel = stringResource(Res.string.fit_workout_delete_failed_dismiss),
            onConfirm = { viewModel.clearDeleteFailure() },
            content = { Text(stringResource(Res.string.fit_workout_delete_failed_body)) },
        )
    }
}

/**
 * The pushed-detail bar for the workout surfaces. `internal` (not private) so the full-screen
 * route map wears the same chrome as the detail it was opened from — Fit's screens carry their own
 * bar rather than a shared scaffold's.
 *
 * ⚠⚠ A real M3 top app bar (`FitTopBar`), not a hand-rolled `Row` that looks like one: the shell
 * supplies only the bottom inset, so the bar must claim the status-bar inset itself (§18.15).
 *
 * ⚠ [onDelete] `null` renders no overflow at all. An overflow with nothing behind it advertises
 * actions that do not exist — the same lie `ProfileRow` guards against with its conditional
 * chevron — so the affordance and its one action arrive together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    FitTopBar(
        title = title,
        onBack = onBack,
        actions = onDelete?.let { delete ->
            {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        FitIcons.More,
                        contentDescription = stringResource(Res.string.fit_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.fit_workout_delete)) },
                        onClick = {
                            expanded = false
                            delete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun Loaded(
    workout: Workout,
    regionBundlePath: String?,
    regionStream: RouteRegionStream?,
    onOpenRouteMap: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        HeroBand(workout)
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatGrid(workout)
            if (workout.hasRoute && workout.route.size >= 2) {
                RouteSection(workout, regionBundlePath, regionStream, onOpenRouteMap)
            }
            if (workout.hrSeries.size >= 2 && workout.avgHr != null) HrSection(workout)
            if (workout.laps.isNotEmpty()) LapsSection(workout.laps)
            ProvenanceFooter(workout)
        }
    }
}

@Composable
private fun HeroBand(workout: Workout) {
    Surface(color = ActivityAccents.container(workout.activityType), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            FitIcons.activity(workout.activityType),
                            contentDescription = null,
                            tint = ActivityAccents.stroke(workout.activityType),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = workoutTitle(workout.title, workout.activityType),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${dateLong(workout.start.date)} · ${clock(workout.start)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SourceBadge(workout.source, subtle = false)
        }
    }
}

@Composable
private fun StatGrid(workout: Workout) {
    val distance = workout.distanceM?.let { distanceValueUnit(it) }
    val pace = paceValueUnit(workout.effectivePaceSecPerKm)
    val energy = energyValueUnit(workout.activeEnergyKj)
    val bpm = stringResource(Res.string.fit_bpm)
    val cells = listOfNotNull(
        GridCell(FitIcons.Clock, durationClock(workout.durationSec), null, stringResource(Res.string.fit_workout_stat_duration)),
        GridCell(FitIcons.Distance, distance?.value ?: "—", distance?.unit, stringResource(Res.string.fit_workout_stat_distance)),
        GridCell(FitIcons.Route, pace.value, pace.unit, stringResource(Res.string.fit_workout_stat_pace)),
        GridCell(FitIcons.Flame, energy.value, energy.unit, stringResource(Res.string.fit_workout_stat_energy)),
        GridCell(FitIcons.Heart, intOrDash(workout.avgHr), bpm, stringResource(Res.string.fit_workout_stat_avg_hr)),
        GridCell(FitIcons.TrendingUp, intOrDash(workout.maxHr), bpm, stringResource(Res.string.fit_workout_stat_max_hr)),
        workout.steps?.let { GridCell(FitIcons.Steps, groupThousands(it), null, stringResource(Res.string.fit_record_steps)) },
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            cells.chunked(3).forEach { rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    rowCells.forEach { cell ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                                StatCell(value = cell.value, unit = cell.unit, label = cell.label, icon = cell.icon)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class GridCell(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val value: String,
    val unit: String?,
    val label: String,
)

@Composable
private fun RouteSection(
    workout: Workout,
    regionBundlePath: String?,
    regionStream: RouteRegionStream?,
    onOpenRouteMap: () -> Unit,
) {
    val onMap = workout.geoRoute.size >= 2
    Column {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(stringResource(Res.string.fit_workout_route_title))
            if (workout.ascentM != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(
                        FitIcons.ArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(Res.string.fit_workout_ascent, workout.ascentM!!),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (onMap) {
            // Real GPS over the offline basemap; BasemapChrome already carries the "Offline"
            // attribution, so the map-less note below is suppressed. Gesture-free here (this page
            // scrolls); tapping opens the full-screen, pannable map. Only this branch is tappable —
            // the schematic fallback below has no map to open.
            WorkoutRouteMap(
                route = workout.geoRoute,
                regionBundlePath = regionBundlePath,
                regionStream = regionStream,
                onClick = onOpenRouteMap,
            )
        } else {
            RoutePath(route = workout.route, color = ActivityAccents.stroke(workout.activityType))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FitIcons.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(Res.string.fit_workout_route_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HrSection(workout: Workout) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            SectionHeader(stringResource(Res.string.fit_workout_hr_title))
            Text(
                text = stringResource(Res.string.fit_workout_hr_summary, workout.avgHr ?: 0, workout.maxHr ?: 0),
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FitCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            HrChart(
                series = workout.hrSeries,
                avgBpm = workout.avgHr ?: 0,
                color = ActivityAccents.stroke(workout.activityType),
                guideColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun LapsSection(laps: List<WorkoutLap>) {
    val bestPace = laps.filterNot { it.partial }.minOfOrNull { it.paceSecPerKm }
    Column {
        SectionHeader(stringResource(Res.string.fit_workout_laps_title), Modifier.padding(bottom = 8.dp))
        FitCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Column {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    LapRow(
                        c0 = stringResource(Res.string.fit_workout_lap_header),
                        c1 = stringResource(Res.string.fit_workout_lap_km_header),
                        c2 = stringResource(Res.string.fit_workout_lap_time_header),
                        c3 = stringResource(Res.string.fit_workout_lap_pace_header),
                        c4 = stringResource(Res.string.fit_workout_lap_hr_header),
                        header = true,
                    )
                }
                laps.forEachIndexed { i, lap ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LapRow(
                        c0 = if (lap.partial) "${lap.index}·" else lap.index.toString(),
                        c1 = lapKm(lap.distanceM),
                        c2 = durationClock(lap.durationSec),
                        c3 = durationClock(lap.paceSecPerKm),
                        c4 = lap.avgHr.toString(),
                        best = !lap.partial && lap.paceSecPerKm == bestPace,
                    )
                }
            }
        }
    }
}

@Composable
private fun LapRow(
    c0: String, c1: String, c2: String, c3: String, c4: String,
    header: Boolean = false,
    best: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelSmall
    else MaterialTheme.typography.bodyMedium.tabular()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c0, style = style, color = muted, modifier = Modifier.weight(0.6f))
        Text(c1, style = style, color = if (header) muted else fg, modifier = Modifier.weight(0.9f), textAlign = TextAlign.End)
        Text(c2, style = style, color = if (header) muted else fg, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(
            c3,
            style = if (best) style.copy(fontWeight = FontWeight.SemiBold) else style,
            color = if (header) muted else if (best) MaterialTheme.colorScheme.primary else fg,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
        Text(c4, style = style, color = muted, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
    }
}

private fun lapKm(distanceM: Int): String {
    val whole = distanceM / 1000
    val hundredths = (distanceM % 1000) / 10
    val padded = if (hundredths < 10) "0$hundredths" else hundredths.toString()
    return "$whole.$padded"
}

@Composable
private fun ProvenanceFooter(workout: Workout) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = deviceLabel(workout.device, workout.source).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                Res.string.fit_workout_captured,
                clock(workout.start),
                clock(workout.end),
                stringResource(Res.string.fit_workout_provenance_retained),
            ),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TrustCue(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
