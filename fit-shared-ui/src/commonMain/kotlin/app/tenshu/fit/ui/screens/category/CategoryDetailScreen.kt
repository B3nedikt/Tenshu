package app.tenshu.fit.ui.screens.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ChartRange
import app.tenshu.fit.model.FitFormat
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_category_avg_subtitle
import app.tenshu.fit.shared.resources.fit_category_avg_subtitle_energy
import app.tenshu.fit.shared.resources.fit_category_avg_subtitle_sleep
import app.tenshu.fit.shared.resources.fit_category_daily_average
import app.tenshu.fit.shared.resources.fit_category_data_sources
import app.tenshu.fit.shared.resources.fit_category_energy_title
import app.tenshu.fit.shared.resources.fit_category_active_energy_title
import app.tenshu.fit.shared.resources.fit_category_active_minutes_title
import app.tenshu.fit.shared.resources.fit_category_avg_subtitle_active_minutes
import app.tenshu.fit.shared.resources.fit_category_avg_subtitle_distance
import app.tenshu.fit.shared.resources.fit_category_distance_title
import app.tenshu.fit.shared.resources.fit_duration_minutes
import app.tenshu.fit.shared.resources.fit_category_cycle_current
import app.tenshu.fit.shared.resources.fit_category_cycle_day
import app.tenshu.fit.shared.resources.fit_category_cycle_log
import app.tenshu.fit.shared.resources.fit_category_cycle_period_start
import app.tenshu.fit.shared.resources.fit_category_cycle_title
import app.tenshu.fit.shared.resources.fit_category_entries
import app.tenshu.fit.shared.resources.fit_category_history
import app.tenshu.fit.shared.resources.fit_category_latest
import app.tenshu.fit.shared.resources.fit_category_no_data
import app.tenshu.fit.shared.resources.fit_category_no_data_hint
import app.tenshu.fit.shared.resources.fit_category_resting_hr_title
import app.tenshu.fit.shared.resources.fit_category_blood_oxygen_title
import app.tenshu.fit.shared.resources.fit_category_respiratory_rate_title
import app.tenshu.fit.shared.resources.fit_vitals_tab_blood_oxygen
import app.tenshu.fit.shared.resources.fit_vitals_tab_respiratory_rate
import app.tenshu.fit.shared.resources.fit_vitals_tab_resting_hr
import app.tenshu.fit.shared.resources.fit_category_sleep_title
import app.tenshu.fit.shared.resources.fit_category_sources_subtitle
import app.tenshu.fit.shared.resources.fit_category_steps_title
import app.tenshu.fit.shared.resources.fit_category_weight_title
import app.tenshu.fit.shared.resources.fit_range_day
import app.tenshu.fit.shared.resources.fit_steps_value
import app.tenshu.fit.shared.resources.fit_unit_kcal
import app.tenshu.fit.shared.resources.fit_range_month
import app.tenshu.fit.shared.resources.fit_range_week
import app.tenshu.fit.shared.resources.fit_range_year
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.groupedRows
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.dayLabel
import app.tenshu.fit.ui.components.flowLabel
import app.tenshu.fit.ui.components.SampleTrend
import app.tenshu.fit.ui.components.SectionHeader
import app.tenshu.fit.ui.components.SourceBadge
import app.tenshu.fit.ui.components.TrendBar
import app.tenshu.fit.ui.components.TrendBars
import app.tenshu.fit.ui.components.distanceText
import app.tenshu.fit.ui.components.durationLong
import app.tenshu.fit.ui.components.groupThousands
import app.tenshu.fit.ui.components.oneDecimal
import app.tenshu.fit.ui.components.updatedText
import app.tenshu.fit.ui.components.weekdayShort
import app.tenshu.fit.ui.theme.tabular
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val RANGES = listOf(
    ChartRange.Day to Res.string.fit_range_day,
    ChartRange.Week to Res.string.fit_range_week,
    ChartRange.Month to Res.string.fit_range_month,
    ChartRange.Year to Res.string.fit_range_year,
)

/**
 * Category detail (docs/proposals/fit-app.md screen 10; §12 B1) — one generic screen driven by the
 * VM's [CategoryMetric]/[CategoryDetailMode]. Activity + Sleep render a D/W/M/Y trend; Body + Vitals
 * render a single latest reading (their store is latest-per-metric until §12 B2). The value
 * formatting (count vs. duration) follows the metric.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    viewModel: CategoryDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = stringResource(titleFor(state.metric)), onBack = onBack)
        // Vitals' reading picker. Above the mode branch, so it survives an Empty metric: blood
        // oxygen with no resting heart rate must still be one tap away.
        if (state.metricChoices.isNotEmpty()) {
            MetricSwitcher(
                choices = state.metricChoices,
                selected = state.metric,
                onSelect = viewModel::selectMetric,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        // ⚠ Only the **Latest** branch is lazy, and only its reading history. The other three are
        // bounded by construction — Series' entries come from `ChartMath.windowAggregates(range)`
        // (365 at the widest), Cycle's log is a handful of days, Empty is a message — so an eager
        // Column is the right shape for them and a LazyColumn would be ceremony. Latest's history
        // is `sampleHistory(metric)`, which is the **whole** retained time series: a decade-long
        // import is tens of thousands of readings (`fit-read-scaling.md`), every one of them
        // composed on open and again on every return to this screen.
        //
        // ⚠⚠ The honest fix for Latest is also to *window the read* — `sampleHistory` is the one
        // Fit read with no bound, and unlike `workouts`/`sleepSessions` it cannot simply
        // `.take(N)`: the stream is unfiltered and the metric filter is applied client-side, so a
        // global top-N would silently drop metrics with few readings. That needs a per-metric
        // filtered read and is recorded in `fit-read-scaling.md`, not smuggled in here.
        when (state.mode) {
            CategoryDetailMode.Latest -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                latestContent(state)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.mode) {
                    CategoryDetailMode.Series -> SeriesContent(state, viewModel::selectRange)
                    CategoryDetailMode.Cycle -> CycleContent(state)
                    CategoryDetailMode.Empty -> EmptyContent()
                    CategoryDetailMode.Latest -> Unit // handled above
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(state: CategoryDetailUiState, onSelectRange: (ChartRange) -> Unit) {
    RangeSwitcher(selected = state.range, onSelect = onSelectRange)

    val bars = state.entries.reversed().map { TrendBar(it.date, it.value) }
    FitCard(Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = stringResource(Res.string.fit_category_daily_average).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            ) {
                Text(
                    text = formatValue(state.metric, state.dailyAverage),
                    style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(avgSubtitleFor(state.metric)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            TrendBars(
                bars = bars,
                goal = state.goal,
                accent = MaterialTheme.colorScheme.primary,
                valueLabel = { calloutValue(state.metric, it) },
            )
        }
    }

    Column {
        SectionHeader(stringResource(Res.string.fit_category_entries), Modifier.padding(bottom = 8.dp))
        FitCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp)) {
            Column {
                state.entries.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = weekdayShort(entry.date.dayOfWeek),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp),
                        )
                        Text(
                            text = formatValue(state.metric, entry.value),
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        SourceBadge(entry.source)
                    }
                }
            }
        }
    }

    DataSourcesRow(state)
}

/**
 * Body / Vitals: the store keeps only the latest reading per metric (`HealthSample`), so there is no
 * history to chart until §12 B2. Show that one value with its provenance, honestly.
 */
/**
 * Latest mode as lazy items (see the call site for why only this branch).
 *
 * ⚠ No `verticalArrangement` on the enclosing `LazyColumn` — it would apply between the grouped
 * history rows and split the card. Each block carries its own bottom padding instead.
 */
private fun LazyListScope.latestContent(state: CategoryDetailUiState) {
    item(key = "latest-value") { LatestValueCard(state) }

    // The trend line over the retained readings (§12 B2) — a value-ranged axis, not zero-anchored bars.
    if (state.trend.size > 1) {
        item(key = "trend") {
            FitCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SampleTrend(points = state.trend, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // The retained readings (§12 B2). Only worth a list once there's more than the current value.
    if (state.history.size > 1) {
        item(key = "history-header") {
            SectionHeader(stringResource(Res.string.fit_category_history), Modifier.padding(bottom = 8.dp))
        }
        groupedRows(
            items = state.history,
            key = { "${it.date}-${it.value}-${it.source}" },
        ) { reading -> HistoryRow(state, reading) }
        item(key = "history-gap") { Spacer(Modifier.size(16.dp)) }
    }

    item(key = "sources") { DataSourcesRow(state) }
}

@Composable
private fun HistoryRow(state: CategoryDetailUiState, reading: SampleReading) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dayLabel(reading.daysAgo, reading.date),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatLatest(state.metric, reading.value)} ${state.latestUnit}",
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        SourceBadge(reading.source)
    }
}

@Composable
private fun LatestValueCard(state: CategoryDetailUiState) {
    FitCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column {
            Text(
                text = stringResource(Res.string.fit_category_latest).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            ) {
                Text(
                    text = formatLatest(state.metric, state.latestValue),
                    style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.latestUnit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = updatedText(state.latestUpdated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SourceBadge(state.latestSource)
            }
        }
    }
}

/**
 * Cycle (§12 B3b): a "Cycle day N" header + a log of recent bleeding days (date + flow + a
 * period-start marker). No numeric chart — flow is categorical. The card is data-driven (only shown
 * when there are logged days), so this branch always has content.
 */
@Composable
private fun CycleContent(state: CategoryDetailUiState) {
    FitCard(Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = stringResource(Res.string.fit_category_cycle_current).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.fit_category_cycle_day, state.cycleDay),
                style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    if (state.cycleLog.isNotEmpty()) {
        Column {
            SectionHeader(stringResource(Res.string.fit_category_cycle_log), Modifier.padding(bottom = 8.dp))
            FitCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp)) {
                Column {
                    state.cycleLog.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = dayLabel(row.daysAgo, row.date),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.periodStart) {
                                Text(
                                    text = stringResource(Res.string.fit_category_cycle_period_start),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = flowLabel(row.flow),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
    DataSourcesRow(state)
}

@Composable
private fun EmptyContent() {
    FitCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.fit_category_no_data),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.fit_category_no_data_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DataSourcesRow(state: CategoryDetailUiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FitIcons.category(state.categoryId),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.fit_category_data_sources),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.fit_category_sources_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            FitIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Series value: steps group as thousands; sleep minutes → duration; energy kJ → kcal; distance by unit system. */
@Composable
private fun formatValue(metric: CategoryMetric, value: Int): String = when (metric) {
    CategoryMetric.SleepDuration -> durationLong(value * 60)
    CategoryMetric.Energy, CategoryMetric.ActiveEnergy -> groupThousands(FitFormat.kjToKcal(value))
    // Carries its own unit (km / mi / m / ft) — the unit system decides which.
    CategoryMetric.Distance -> distanceText(value)
    else -> groupThousands(value)
}

/** The chart callout's value, with its unit where the metric has one ("10,423 steps"). */
@Composable
private fun calloutValue(metric: CategoryMetric, value: Int): String = when (metric) {
    CategoryMetric.Steps -> stringResource(Res.string.fit_steps_value, groupThousands(value))
    CategoryMetric.Energy, CategoryMetric.ActiveEnergy ->
        "${formatValue(metric, value)} ${stringResource(Res.string.fit_unit_kcal)}"
    CategoryMetric.ActiveMinutes -> stringResource(Res.string.fit_duration_minutes, value)
    else -> formatValue(metric, value)
}

/** Latest value: weight and respiratory rate one-decimal; resting HR and SpO₂ whole. */
private fun formatLatest(metric: CategoryMetric, value: Double): String = when (metric) {
    CategoryMetric.Weight, CategoryMetric.RespiratoryRate -> oneDecimal(value)
    // Rounded, not truncated: a stored 97.6 % reads 98, as the source app shows it.
    CategoryMetric.BloodOxygen -> kotlin.math.round(value).toInt().toString()
    else -> value.toInt().toString()
}

private fun titleFor(metric: CategoryMetric): StringResource = when (metric) {
    CategoryMetric.Steps -> Res.string.fit_category_steps_title
    CategoryMetric.Energy -> Res.string.fit_category_energy_title
    CategoryMetric.SleepDuration -> Res.string.fit_category_sleep_title
    CategoryMetric.Weight -> Res.string.fit_category_weight_title
    CategoryMetric.RestingHeartRate -> Res.string.fit_category_resting_hr_title
    CategoryMetric.BloodOxygen -> Res.string.fit_category_blood_oxygen_title
    CategoryMetric.RespiratoryRate -> Res.string.fit_category_respiratory_rate_title
    CategoryMetric.Cycle -> Res.string.fit_category_cycle_title
    CategoryMetric.ActiveMinutes -> Res.string.fit_category_active_minutes_title
    CategoryMetric.Distance -> Res.string.fit_category_distance_title
    CategoryMetric.ActiveEnergy -> Res.string.fit_category_active_energy_title
}

private fun avgSubtitleFor(metric: CategoryMetric): StringResource = when (metric) {
    CategoryMetric.SleepDuration -> Res.string.fit_category_avg_subtitle_sleep
    CategoryMetric.Energy, CategoryMetric.ActiveEnergy -> Res.string.fit_category_avg_subtitle_energy
    CategoryMetric.ActiveMinutes -> Res.string.fit_category_avg_subtitle_active_minutes
    CategoryMetric.Distance -> Res.string.fit_category_avg_subtitle_distance
    else -> Res.string.fit_category_avg_subtitle
}

@Composable
private fun RangeSwitcher(selected: ChartRange, onSelect: (ChartRange) -> Unit) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RANGES.forEach { (range, labelRes) ->
                RangeButton(labelRes, range == selected, { onSelect(range) }, Modifier.weight(1f))
            }
        }
    }
}

/** Short switcher labels — three must fit one row at phone width, so not the full titles. */
private fun switcherLabel(metric: CategoryMetric): StringResource = when (metric) {
    CategoryMetric.RestingHeartRate -> Res.string.fit_vitals_tab_resting_hr
    CategoryMetric.BloodOxygen -> Res.string.fit_vitals_tab_blood_oxygen
    CategoryMetric.RespiratoryRate -> Res.string.fit_vitals_tab_respiratory_rate
    else -> titleFor(metric)
}

@Composable
private fun MetricSwitcher(
    choices: List<CategoryMetric>,
    selected: CategoryMetric,
    onSelect: (CategoryMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.forEach { m ->
                RangeButton(switcherLabel(m), m == selected, { onSelect(m) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RangeButton(labelRes: StringResource, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Box(Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
