package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.BatchImportSummary
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.model.ImportRunEntry
import app.tenshu.fit.model.ImportRunStatus
import app.tenshu.fit.model.ImportRunWindow
import app.tenshu.fit.model.CategorySummary
import app.tenshu.liststate.ListLoadState
import kotlinx.coroutines.flow.map
import app.tenshu.fit.model.CategoryTeaser
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.LastUpdated
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.CycleEntry
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.NutritionDay
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.SleepSession
import app.tenshu.fit.model.Units
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * In-memory [FitRepository] over [FitMockData] (Ph2, docs/proposals/fit-app.md §6).
 * The swappable seam — the real `:health-service` projector replaces this behind the
 * interface with one Koin binding.
 *
 * Onboarding state is in-memory and resets per launch, by design (UI-only v1). The
 * mock "import" advances per-category counts on a ticker so the progress screen has
 * something honest to render.
 */
class MockFitRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FitRepository {

    override val today: LocalDate = FitMockData.today

    private val aggregates = MutableStateFlow(FitMockData.dailyAggregates)
    private val nutrition = MutableStateFlow(FitMockData.nutrition)
    private val cycle = MutableStateFlow(FitMockData.cycle)
    private val workouts = MutableStateFlow(FitMockData.workouts)
    private val samples = MutableStateFlow(FitMockData.samplesLatest)
    private val sleep = MutableStateFlow(FitMockData.sleepSessions)
    private val medical = MutableStateFlow(FitMockData.medicalRecords)
    private val goals = MutableStateFlow(FitMockData.goals)
    private val profile = MutableStateFlow(FitMockData.profile)
    private val onboarded = MutableStateFlow(false)
    private val importProgress = MutableStateFlow<List<ImportProgress>?>(null)
    private var importJob: Job? = null

    // ⚠ Always `Loaded`, never `Failed` — a mock has nothing that can fail, and pretending
    // otherwise would make every preview and screen test render a failure state nobody asked for.
    // The `Failed` arm is exercised by the fakes that override these (see the screen tests).
    override fun aggregates(): Flow<ListLoadState<DailyAggregate>> =
        aggregates.map { ListLoadState.Loaded(it) }

    override fun nutrition(): Flow<List<NutritionDay>> = nutrition.asStateFlow()

    override fun cycle(): Flow<List<CycleEntry>> = cycle.asStateFlow()

    override fun workouts(): Flow<List<Workout>> = workouts.asStateFlow()

    /** The mock has no realm to guard: an explicitly open gate (ADR 0025 §8.6). */
    override fun refuseIfRealmWritesHeld() = Unit

    // ── Data sources (DS-4) ─────────────────────────────────────────────────────────────
    //
    // The mock graph drives previews and the desktop mock app, so these carry a plausible
    // two-device realm rather than an empty list — the section's whole point is that another
    // device's source is visible, and an empty mock would hide the one thing worth looking at.
    private val mockSources = listOf(
        DataSourceRow(
            kind = DataSourceKind.HealthConnect,
            deviceId = "mock-this-device",
            deviceLabel = null,
            isThisDevice = true,
            state = DataSourceState.Imported(itemCount = 165L, at = 1_754_000_000_000L),
            autoImport = true,
        ),
        DataSourceRow(
            kind = DataSourceKind.AppleHealth,
            deviceId = "mock-other-device",
            deviceLabel = "iPhone 15",
            isThisDevice = false,
            state = DataSourceState.Imported(itemCount = 89L, at = 1_753_900_000_000L),
            autoImport = true,
        ),
    )

    override fun dataSources(): Flow<List<DataSourceRow>> = flowOf(mockSources)

    override fun batchImport(): Flow<BatchImportSummary?> = flowOf(
        BatchImportSummary(
            lastRunAt = 1_753_000_000_000L,
            lastStatus = ImportRunStatus.Succeeded,
            runCount = 1,
        )
    )

    override fun importHistory(kind: DataSourceKind?): Flow<List<ImportRunEntry>> = flowOf(
        when (kind) {
            null -> listOf(
                ImportRunEntry(
                    startedAt = 1_753_000_000_000L,
                    finishedAt = 1_753_000_060_000L,
                    window = ImportRunWindow.File,
                    status = ImportRunStatus.Succeeded,
                    itemCount = 4_012L,
                    workoutCount = 806L,
                    byteCount = 91_000_000L,
                    fileName = "takeout-20260801.zip",
                )
            )
            DataSourceKind.TenshuHealth -> emptyList()
            else -> listOf(
                ImportRunEntry(
                    startedAt = 1_754_000_000_000L,
                    finishedAt = 1_754_000_010_000L,
                    window = ImportRunWindow.Recent,
                    status = ImportRunStatus.Succeeded,
                    itemCount = 165L,
                    workoutCount = 12L,
                )
            )
        }
    )

    override fun categories(): Flow<ListLoadState<CategorySummary>> =
        // `combine` maxes at five typed flows, so the day trio is folded first.
        combine(
            combine(aggregates, nutrition, cycle) { a, n, c -> Triple(a, n, c) },
            samples, sleep, medical,
        ) { (aggregates, nutrition, cycle), samples, sleep, medical ->
            ListLoadState.Loaded(
                buildCategories(aggregates, nutrition, cycle, samples, sleep, medical),
            )
        }

    override fun samplesLatest(): Flow<List<SampleLatest>> = samples.asStateFlow()

    override fun sampleHistory(metric: Metric): Flow<List<SampleLatest>> = flowOf(
        FitMockData.sampleHistory.filter { it.metric == metric }.sortedByDescending { it.time },
    )

    override fun sleepSessions(): Flow<List<SleepSession>> = sleep.asStateFlow()

    override fun medicalRecords(): Flow<List<MedicalRecord>> = medical.asStateFlow()

    override fun goals(): Flow<Goals> = goals.asStateFlow()

    override fun profile(): Flow<FitProfile> = profile.asStateFlow()

    override fun onboarded(): Flow<Boolean> = onboarded.asStateFlow()

    override suspend fun workout(id: String): WorkoutRead =
        // An in-memory list cannot fail to be read, so absence here is genuine absence — this is
        // the one implementation for which `NotFound` is always the honest answer.
        workouts.value.firstOrNull { it.workoutId == id }
            ?.let { WorkoutRead.Found(it) }
            ?: WorkoutRead.NotFound

    override suspend fun addWorkout(workout: Workout, track: RecordedTrack?): WorkoutSaveOutcome {
        // The mock keeps the whole in-memory [Workout] (route + hrSeries already on it), so the
        // dense [track] is redundant here — the real store is where it becomes a persisted blob.
        // ⚠ Upsert by `workoutId`, not prepend. The real store is content-addressed
        // (`WorkoutDataIdProvider`), so writing the same id twice folds to one row — and a mock
        // that appended instead reported duplicates for an operation the store deduplicates.
        // That mattered the moment something re-wrote a stable id: FA-2's promoter is idempotent
        // *by construction* (`detected:<traceId>`), and against the old mock its convergence
        // looked broken while the real store was fine.
        workouts.value = (listOf(workout) + workouts.value.filterNot { it.workoutId == workout.workoutId })
            .sortedByDescending { it.start }
        return WorkoutSaveOutcome.Synced
    }

    override suspend fun deleteWorkout(id: String): Boolean {
        val before = workouts.value.size
        workouts.value = workouts.value.filterNot { it.workoutId == id }
        return workouts.value.size < before
    }

    // ── Onboarding ──────────────────────────────────────────────────────────────────

    /**
     * The mock device has Health Connect present (the design's primary frame is
     * Android-with-HC); file import and start-fresh are always offered.
     */
    override fun availableSources(): List<ImportSource> = listOf(
        ImportSource.HealthConnect,
        ImportSource.ImportFile,
        ImportSource.StartFresh,
    )

    override fun importCategories(): List<ImportCategory> = FitMockData.importCategories

    override fun importProgress(): Flow<List<ImportProgress>?> = importProgress.asStateFlow()

    override suspend fun startImport(
        selection: Set<CategoryId>,
        window: ImportWindow,
        automatic: Boolean,
    ) {
        importJob?.cancel()
        val chosen = FitMockData.importCategories
            .filter { it.id in selection && it.estimatedCount > 0 }
        importProgress.value = chosen.map { ImportProgress(it.id, imported = 0, total = it.estimatedCount) }
        importJob = scope.launch {
            // Advance categories one after another, in visible slices.
            while (true) {
                val current = importProgress.value ?: return@launch
                val next = current.toMutableList()
                val active = next.indexOfFirst { !it.done }
                if (active < 0) return@launch
                val row = next[active]
                next[active] = row.copy(imported = (row.imported + (row.total / 9).coerceAtLeast(1)).coerceAtMost(row.total))
                importProgress.value = next
                delay(350)
            }
        }
    }

    override suspend fun completeOnboarding() {
        onboarded.value = true
    }

    // ── Profile mutations ───────────────────────────────────────────────────────────

    override suspend fun updateGoals(goals: Goals) {
        this.goals.value = goals
    }

    override suspend fun updateUnits(units: Units) {
        profile.value = profile.value.copy(units = units)
    }

    // FP-3: the mock keeps every edit in its one in-memory profile. Height and weight look like
    // plain copies here, but the real repository writes them as `HealthSample` rows — the mock has
    // no store to write to, and the seam is what makes that difference invisible to the UI.

    override suspend fun updateOwnerName(name: String) {
        profile.value = profile.value.copy(name = name.ifBlank { null })
    }

    override suspend fun updateBirthDate(date: kotlinx.datetime.LocalDate?) {
        profile.value = profile.value.copy(birthDate = date)
    }

    override suspend fun updateSex(sex: org.tenshu.preference.BiologicalSex?) {
        profile.value = profile.value.copy(sex = sex)
    }

    override suspend fun recordHeight(centimetres: Int) {
        profile.value = profile.value.copy(heightCm = centimetres)
    }

    override suspend fun recordWeight(kilograms: Double) {
        profile.value = profile.value.copy(weightKg = kilograms)
    }

    // ── Category derivation ─────────────────────────────────────────────────────────

    private fun buildCategories(
        aggregates: List<DailyAggregate>,
        nutrition: List<NutritionDay>,
        cycle: List<CycleEntry>,
        samples: List<SampleLatest>,
        sleep: List<SleepSession>,
        medical: List<MedicalRecord>,
    ): List<CategorySummary> {
        val todayAggregate = aggregates.lastOrNull()
        val lastNutrition = nutrition.maxByOrNull { it.date }
        val cycleAnchor = cycle.filter { it.periodStart }.maxByOrNull { it.date }?.date
            ?: cycle.maxByOrNull { it.date }?.date
        val weight = samples.firstOrNull { it.metric == Metric.Weight }
        val vitals = app.tenshu.fit.data.projection.FitProjection.vitalsTeaser(samples)
        val lastSleep = sleep.maxByOrNull { it.date }
        return listOf(
            CategorySummary(
                id = CategoryId.Activity,
                teaser = todayAggregate?.let { CategoryTeaser.ActiveMinutesToday(it.activeMinutes) }
                    ?: CategoryTeaser.NoEntries,
                updated = if (todayAggregate?.date == today) LastUpdated.Today else LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Body,
                teaser = weight?.let { CategoryTeaser.Weight(it.value) } ?: CategoryTeaser.NoEntries,
                updated = weight?.let { daysAgo(it.time.date) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Vitals,
                teaser = vitals?.first ?: CategoryTeaser.NoEntries,
                updated = vitals?.let { daysAgo(it.second.time.date) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Sleep,
                teaser = lastSleep?.let { CategoryTeaser.SleepDuration(it.durationSec / 60) }
                    ?: CategoryTeaser.NoEntries,
                updated = lastSleep?.let { daysAgo(it.date) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Nutrition,
                teaser = lastNutrition?.takeIf { it.energyKj > 0 }?.let { CategoryTeaser.Energy(it.energyKj) }
                    ?: CategoryTeaser.NoEntries,
                updated = lastNutrition?.let { daysAgo(it.date) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Cycle,
                teaser = cycleAnchor?.let { CategoryTeaser.CycleDay(it.daysUntil(today) + 1) } ?: CategoryTeaser.NotSetUp,
                updated = cycleAnchor?.let { daysAgo(it) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Medical,
                teaser = if (medical.isEmpty()) CategoryTeaser.NoEntries else CategoryTeaser.RecordCount(medical.size),
                updated = if (medical.isEmpty()) LastUpdated.Never else LastUpdated.Imported,
            ),
            // Cycle is data-driven (§12 B3b) — present only when there is cycle data, never sex-gated.
        ).filterNot { it.id == CategoryId.Cycle && cycle.isEmpty() }
    }

    private fun daysAgo(date: LocalDate): LastUpdated {
        val days = date.daysUntil(today)
        return if (days <= 0) LastUpdated.Today else LastUpdated.DaysAgo(days)
    }
}
