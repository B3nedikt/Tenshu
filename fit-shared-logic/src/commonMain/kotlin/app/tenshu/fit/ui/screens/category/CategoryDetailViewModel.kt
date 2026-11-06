package app.tenshu.fit.ui.screens.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ChartMath
import app.tenshu.fit.model.ChartRange
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.FlowLevel
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.LastUpdated
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.NormalizedPoint
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.tenshu.liststate.ListLoadState.Companion.itemsOrEmpty
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/** One row in a series entries list — a day's value with its provenance. */
data class CategoryEntry(
    val date: LocalDate,
    val daysAgo: Int,
    val value: Int,
    val source: Source,
)

/** One reading in a Body/Vitals history list — a fractional value with its provenance (§12 B2). */
data class SampleReading(
    val date: LocalDate,
    val daysAgo: Int,
    val value: Double,
    val source: Source,
)

/**
 * Which metric a category-detail screen is showing. The UI picks the localized title and the
 * value formatting (Steps/SleepDuration → the count-vs-duration split; Weight/RestingHeartRate →
 * the latest-reading format).
 */
enum class CategoryMetric {
    Steps,
    /** Energy **consumed** — Nutrition's intake series. Not [ActiveEnergy]. */
    Energy,
    SleepDuration, Weight, RestingHeartRate, Cycle,
    // ── Vitals' other readings — picked on the Vitals detail itself ([VITALS_METRICS]) ─────
    /** SpO₂, percent. */
    BloodOxygen,
    /** Breaths per minute. */
    RespiratoryRate,
    // ── Activity's other daily series (fit-app.md §18.18) — opened from Home's cards ─────────
    /** WHO moderate + vigorous minutes per day. */
    ActiveMinutes,
    /** Metres per day; the UI formats by the owner's unit system. */
    Distance,
    /** Energy **burned** per day (kJ; UI shows kcal). Not [Energy], which is intake. */
    ActiveEnergy,
}

/** One row of the Cycle log — a logged bleeding day (§12 B3b). */
data class CycleLogRow(
    val date: LocalDate,
    val daysAgo: Int,
    val flow: FlowLevel,
    val periodStart: Boolean,
)

/**
 * How the detail renders: a D/W/M/Y **trend** (a metric with per-day history), a single **latest**
 * reading (a metric the store keeps only latest-per-metric — Body/Vitals `HealthSample`, until the
 * B2 time-series store), or **empty** (no data / a category with no detail).
 */
enum class CategoryDetailMode { Series, Latest, Cycle, Empty }

data class CategoryDetailUiState(
    val categoryId: CategoryId = CategoryId.Activity,
    val metric: CategoryMetric = CategoryMetric.Steps,
    /**
     * The metrics this screen can switch between, in display order; empty = no switcher. Only
     * Vitals has one today. ⚠ Shown in every [mode], Empty included — a user with blood oxygen but
     * no resting heart rate must still be able to reach it.
     */
    val metricChoices: List<CategoryMetric> = emptyList(),
    val mode: CategoryDetailMode = CategoryDetailMode.Empty,
    // ── Series (Activity steps, Sleep duration) ─────────────────────────────────────
    val range: ChartRange = ChartRange.Week,
    /** Bar values for the selected window, oldest first. Steps = count; Sleep = minutes. */
    val values: List<Int> = emptyList(),
    val dailyAverage: Int = 0,
    /** Goal line; 0 = no goal (Sleep has none in v1). */
    val goal: Int = 0,
    /** Newest first. */
    val entries: List<CategoryEntry> = emptyList(),
    // ── Latest (Body weight, Vitals resting HR) ─────────────────────────────────────
    val latestValue: Double = 0.0,
    /** Store-provided unit ("kg", "bpm"); a data field, not a localized label. */
    val latestUnit: String = "",
    val latestUpdated: LastUpdated = LastUpdated.Never,
    val latestSource: Source = Source.HealthConnect,
    /** Prior readings for the history list, newest first (§12 B2); empty when only one reading. */
    val history: List<SampleReading> = emptyList(),
    /** Min–max-scaled trend polyline over [history] (oldest→newest); empty with fewer than two readings. */
    val trend: List<NormalizedPoint> = emptyList(),
    // ── Cycle (§12 B3b) ─────────────────────────────────────────────────────────────
    /** Current day of the menstrual cycle (days since the last period start). */
    val cycleDay: Int = 0,
    /** The logged bleeding days, newest first. */
    val cycleLog: List<CycleLogRow> = emptyList(),
)

/**
 * Category detail (docs/proposals/fit-app.md §2 screen 10; §12 B1) — one generic screen driven by
 * [categoryId]. Activity charts the steps series (the original worked exemplar); Sleep charts the
 * per-night duration series; Body/Vitals show the single latest reading (their `HealthSample` store
 * is latest-per-metric — a time-series store is §12 B2). Medical has its own screen and
 * Nutrition/Cycle are disabled (no store), so they never reach here; both fall through to [Empty].
 * The D/W/M/Y switcher re-windows the same series (§5: same component, different window).
 */
class CategoryDetailViewModel(
    private val categoryId: CategoryId,
    private val repository: FitRepository,
    /**
     * Which series to show. Only Activity has more than one (steps, active minutes, distance,
     * energy burned — all columns of the same [DailyAggregate] row); every other category has
     * exactly [defaultMetric] and ignores this.
     */
    requestedMetric: CategoryMetric = defaultMetric(categoryId),
) : ViewModel() {

    private val metricChoices: List<CategoryMetric> =
        if (categoryId == CategoryId.Vitals) VITALS_METRICS else emptyList()

    /** Vitals switches in place ([selectMetric]); Activity's comes from the route; the rest are fixed. */
    private val selectedMetric = MutableStateFlow(
        when {
            categoryId == CategoryId.Activity -> requestedMetric
            requestedMetric in metricChoices -> requestedMetric
            else -> defaultMetric(categoryId)
        },
    )
    private val metric: CategoryMetric get() = selectedMetric.value
    private val range = MutableStateFlow(ChartRange.Week)

    private val _uiState = MutableStateFlow(
        CategoryDetailUiState(categoryId = categoryId, metric = metric, metricChoices = metricChoices),
    )
    val uiState: StateFlow<CategoryDetailUiState> get() = _uiState

    /**
     * ⚠⚠ Read per emission, never captured: this view model is built once and outlives midnight,
     * and these `observe*` collects run for its whole life. Passing `repository.today` in as a
     * parameter here — which is what this did — pinned every "N days ago" label to the date the
     * screen was first opened. See [FitRepository.today].
     */
    private val today: LocalDate get() = repository.today

    init {
        viewModelScope.launch {
            when (categoryId) {
                CategoryId.Activity -> when (metric) {
                    CategoryMetric.ActiveMinutes -> observeDaily({ it.activeMinutes }, { it.activeMinutes })
                    CategoryMetric.Distance -> observeDaily({ it.distanceM }, { 0 }) // no distance goal
                    CategoryMetric.ActiveEnergy -> observeDaily({ it.activeEnergyKj }, { 0 }) // no energy goal
                    else -> observeDaily({ it.steps }, { it.steps })
                }
                CategoryId.Nutrition -> observeNutrition()
                CategoryId.Sleep -> observeSleep()
                CategoryId.Body -> observeSamples(Metric.Weight, restingOnly = false)
                // `collectLatest`: a switch cancels the previous metric's read before the next
                // one's first emission, so a slow old read can never overwrite the new metric.
                CategoryId.Vitals -> selectedMetric.collectLatest { m ->
                    when (m) {
                        CategoryMetric.BloodOxygen -> observeSamples(Metric.BloodOxygen, restingOnly = false)
                        CategoryMetric.RespiratoryRate -> observeSamples(Metric.RespiratoryRate, restingOnly = false)
                        else -> observeSamples(Metric.HeartRate, restingOnly = true)
                    }
                }
                CategoryId.Cycle -> observeCycle()
                // Medical has its own screen — never navigates here.
                else -> Unit
            }
        }
    }

    fun selectRange(newRange: ChartRange) {
        range.value = newRange
    }

    /** Switch the reading shown; ignored unless [newMetric] is one of this screen's [metricChoices]. */
    fun selectMetric(newMetric: CategoryMetric) {
        if (newMetric in metricChoices) selectedMetric.value = newMetric
    }

    /** One column of the daily aggregate as a series — steps and its three siblings. */
    private suspend fun observeDaily(value: (DailyAggregate) -> Int, goal: (Goals) -> Int) {
        // Flattened at the last mile: the category-detail chart has no failure affordance of its
        // own (its "no data for this range" state is drawn by the chart itself), so it takes rows
        // or nothing. ⚠ The honest fix is to give it the three states too — tracked in
        // `fit-read-scaling.md`, not smuggled in here.
        combine(
            repository.aggregates().map { it.itemsOrEmpty() },
            repository.goals(),
            range,
        ) { aggregates, goals, range ->
            val window = ChartMath.windowAggregates(aggregates, range)
            seriesState(
                range = range,
                values = window.map(value),
                goal = goal(goals),
                entries = window.reversed().map { day ->
                    CategoryEntry(
                        date = day.date,
                        daysAgo = day.date.daysUntil(today),
                        value = value(day),
                        // Today's row is the on-device counter; prior days arrived by import.
                        source = if (day.date == today) Source.Recorded else Source.HealthConnect,
                    )
                },
            )
        }.collect { _uiState.value = it }
    }

    private suspend fun observeCycle() {
        repository.cycle().collect { days ->
            val newestFirst = days.sortedByDescending { it.date }
            _uiState.value = if (newestFirst.isEmpty()) {
                CategoryDetailUiState(categoryId = categoryId, metric = metric, metricChoices = metricChoices, mode = CategoryDetailMode.Empty)
            } else {
                val anchor = days.filter { it.periodStart }.maxByOrNull { it.date }?.date
                    ?: days.maxByOrNull { it.date }?.date
                CategoryDetailUiState(
                    categoryId = categoryId,
                    metric = metric,
                    metricChoices = metricChoices,
                    mode = CategoryDetailMode.Cycle,
                    cycleDay = anchor?.let { it.daysUntil(today) + 1 } ?: 0,
                    cycleLog = newestFirst.map { c ->
                        CycleLogRow(c.date, c.date.daysUntil(today), c.flow, c.periodStart)
                    },
                )
            }
        }
    }

    private suspend fun observeNutrition() {
        combine(repository.nutrition(), range) { days, range ->
            val window = days.sortedBy { it.date }.takeLast(range.days)
            seriesState(
                range = range,
                values = window.map { it.energyKj },
                goal = 0, // no energy goal in v1
                entries = window.reversed().map { day ->
                    CategoryEntry(
                        date = day.date,
                        daysAgo = day.date.daysUntil(today),
                        value = day.energyKj,
                        source = Source.HealthConnect,
                    )
                },
            )
        }.collect { _uiState.value = it }
    }

    private suspend fun observeSleep() {
        combine(repository.sleepSessions(), range) { sessions, range ->
            val window = sessions.sortedBy { it.date }.takeLast(range.days)
            seriesState(
                range = range,
                values = window.map { it.durationSec / 60 },
                goal = 0, // no sleep goal in v1
                entries = window.reversed().map { s ->
                    CategoryEntry(
                        date = s.date,
                        daysAgo = s.date.daysUntil(today),
                        value = s.durationSec / 60,
                        source = s.source,
                    )
                },
            )
        }.collect { _uiState.value = it }
    }

    private suspend fun observeSamples(sampleMetric: Metric, restingOnly: Boolean) {
        repository.sampleHistory(sampleMetric).collect { readings ->
            val series = readings.filter { !restingOnly || it.resting }.sortedByDescending { it.time }
            _uiState.value = series.firstOrNull()?.let { newest ->
                latestState(newest, series, today)
            } ?: CategoryDetailUiState(categoryId = categoryId, metric = metric, metricChoices = metricChoices, mode = CategoryDetailMode.Empty)
        }
    }

    private fun seriesState(
        range: ChartRange,
        values: List<Int>,
        goal: Int,
        entries: List<CategoryEntry>,
    ) = CategoryDetailUiState(
        categoryId = categoryId,
        metric = metric,
        metricChoices = metricChoices,
        mode = CategoryDetailMode.Series,
        range = range,
        values = values,
        dailyAverage = ChartMath.dailyAverage(values),
        goal = goal,
        entries = entries,
    )

    private fun latestState(newest: SampleLatest, series: List<SampleLatest>, today: LocalDate) =
        CategoryDetailUiState(
            categoryId = categoryId,
            metric = metric,
            metricChoices = metricChoices,
            mode = CategoryDetailMode.Latest,
            latestValue = newest.value,
            latestUnit = newest.unit,
            latestUpdated = lastUpdated(newest.time.date, today),
            latestSource = newest.source,
            // The whole series (newest first, incl. the current value) — the history list renders it
            // when there is more than one reading.
            history = series.map { r ->
                SampleReading(
                    date = r.time.date,
                    daysAgo = r.time.date.daysUntil(today),
                    value = r.value,
                    source = r.source,
                )
            },
            // Oldest→newest for the left→right trend line.
            trend = ChartMath.minMaxPolyline(series.reversed().map { it.value }),
        )

    private fun lastUpdated(date: LocalDate, today: LocalDate): LastUpdated {
        val days = date.daysUntil(today)
        return if (days <= 0) LastUpdated.Today else LastUpdated.DaysAgo(days)
    }

    companion object {
        /** Vitals' readings, in switcher order; the first is the one Browse opens on. */
        val VITALS_METRICS: List<CategoryMetric> = listOf(
            CategoryMetric.RestingHeartRate, CategoryMetric.BloodOxygen, CategoryMetric.RespiratoryRate,
        )

        /** The metric a category opens on; Activity's is steps. */
        fun defaultMetric(id: CategoryId): CategoryMetric = when (id) {
            CategoryId.Nutrition -> CategoryMetric.Energy
            CategoryId.Cycle -> CategoryMetric.Cycle
            CategoryId.Sleep -> CategoryMetric.SleepDuration
            CategoryId.Body -> CategoryMetric.Weight
            CategoryId.Vitals -> CategoryMetric.RestingHeartRate
            else -> CategoryMetric.Steps
        }
    }
}
