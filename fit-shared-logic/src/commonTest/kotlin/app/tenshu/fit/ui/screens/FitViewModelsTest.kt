package app.tenshu.fit.ui.screens

import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.data.MockRecordingEngine
import app.tenshu.fit.data.RecordingState
import app.tenshu.fit.data.RegionBundleResolver
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GeoCoord
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.ChartRange
import app.tenshu.liststate.ListLoadState.Companion.itemsOrEmpty
import app.tenshu.fit.model.ChartMath
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Units
import app.tenshu.fit.ui.AppViewModel
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.fit.ui.screens.browse.BrowseViewModel
import app.tenshu.fit.ui.screens.category.CategoryDetailMode
import app.tenshu.fit.ui.screens.category.CategoryDetailViewModel
import app.tenshu.fit.ui.screens.category.CategoryMetric
import app.tenshu.fit.ui.screens.history.HistoryViewModel
import app.tenshu.fit.ui.screens.home.HomeViewModel
import app.tenshu.fit.ui.screens.medical.MedicalViewModel
import app.tenshu.fit.ui.screens.onboarding.AboutYouField
import app.tenshu.fit.ui.screens.onboarding.OnboardingStep
import app.tenshu.fit.ui.screens.onboarding.OnboardingUiState
import app.tenshu.fit.ui.screens.onboarding.OnboardingViewModel
import app.tenshu.fit.ui.screens.profile.ProfileViewModel
import app.tenshu.fit.ui.screens.record.RecordViewModel
import app.tenshu.fit.ui.screens.workout.WorkoutDetailUiState
import app.tenshu.fit.ui.screens.workout.WorkoutDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.LocalDate
import org.tenshu.preference.BiologicalSex
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class FitViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun app_exposesOnboardingGate() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(AppViewModel(repo))
        assertEquals(false, vm.onboarded.value)
        repo.completeOnboarding()
        assertEquals(true, vm.onboarded.value)
    }

    @Test
    fun home_derivesRingWeekAndDelta() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(HomeViewModel(repo)).uiState.value
        assertEquals(42, state.today?.activeMinutes)
        assertEquals(30, state.goals.activeMinutes)
        assertEquals(7, state.week.size)
        // The synthesized prior week exists, so the delta is present.
        assertNotNull(state.weekDeltaPercent)
        assertEquals("w-sun-run", state.latestWorkout?.workoutId)
    }

    @Test
    fun home_emptyStore_marksLoadedWithNoToday() = vmTest { vms ->
        // An empty store (e.g. Health Connect granted but no records yet) emits once with no
        // aggregates. Home keys its empty state off `load == LOADED && today == null`, so the
        // phase must reach LOADED even though `today` stays null — otherwise the screen can't
        // tell "loading" from "genuinely empty".
        val state = vms.own(HomeViewModel(EmptyFitRepository())).uiState.value
        assertEquals(ListLoadPhase.LOADED, state.load)
        assertNull(state.today)
        assertTrue(state.week.isEmpty())
        assertNull(state.latestWorkout)
    }

    /**
     * ⚠⚠ fit-app.md §9.15: without a platform health source (a phone without Health Connect,
     * desktop) Home's empty state must say so — and offer file import where the shell can pick one —
     * instead of inviting the user to "connect a health source" that does not exist.
     */
    @Test
    fun home_withoutAHealthSource_saysSoAndOffersFileImport() = vmTest { vms ->
        val noSource = object : FitRepository by MockFitRepository(scope = backgroundScope) {
            override fun availableSources() = listOf(ImportSource.ImportFile, ImportSource.StartFresh)
        }
        val state = vms.own(HomeViewModel(noSource, canImportFiles = true)).uiState.value
        assertFalse(state.healthSourceAvailable, "Start fresh / file import are not a health source")
        assertTrue(state.canImportFiles)

        val withHc = vms.own(HomeViewModel(MockFitRepository(scope = backgroundScope))).uiState.value
        assertTrue(withHc.healthSourceAvailable, "the mock device has Health Connect")
        assertFalse(withHc.canImportFiles, "no picker bound, no file action")
    }

    @Test
    fun home_beforeFirstEmission_isLoading() {
        // The default state (no repository emission yet) must read as LOADING so Home stays
        // blank rather than flashing the empty state on the first frame.
        val initial = app.tenshu.fit.ui.screens.home.HomeUiState()
        assertEquals(ListLoadPhase.LOADING, initial.load)
        assertNull(initial.today)
    }

    @Test
    fun home_failedRead_isNotTheEmptyStore() = vmTest { vms ->
        // ⚠ The regression this whole change exists for. A failed aggregates read used to fold to
        // emptyList(), arriving as `loaded = true, today = null` — indistinguishable from an
        // answered-and-empty store, so Home rendered "No activity yet — record a workout or
        // connect a health source" at the one moment it could not know either.
        val state = vms.own(HomeViewModel(FailingFitRepository())).uiState.value
        assertEquals(ListLoadPhase.FAILED, state.load)
        assertNull(state.today)
    }

    @Test
    fun browse_failedRead_keepsTheCardsAndFlagsThem() = vmTest { vms ->
        // Loaded first, then a failure: the categories already on screen stay (they are the last
        // known good ones), and only the phase moves. Blanking them would be a second lie on top
        // of the first.
        val repository = SwitchableCategoriesRepository()
        val vm = vms.own(BrowseViewModel(repository))
        repository.emit(ListLoadState.Loaded(listOf(activityCategory())))
        assertEquals(ListLoadPhase.LOADED, vm.uiState.value.categoriesLoad)
        assertEquals(1, vm.uiState.value.categories.size)

        repository.emit(ListLoadState.Failed)
        assertEquals(ListLoadPhase.FAILED, vm.uiState.value.categoriesLoad)
        assertEquals(
            1,
            vm.uiState.value.categories.size,
            "a failed refresh blanked the cards instead of flagging them",
        )
    }

    @Test
    fun browse_beforeFirstEmission_isLoading() {
        // Browse's connect-a-data-source prompt keys off "every category is empty", which on the
        // first frame is vacuously true — so the phase, not the list, is what gates it.
        assertEquals(
            ListLoadPhase.LOADING,
            app.tenshu.fit.ui.screens.browse.BrowseUiState().categoriesLoad,
        )
    }

    @Test
    fun history_groupsByDayNewestFirstWithWeekSummary() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(HistoryViewModel(repo)).uiState.value
        assertEquals(6, state.weekWorkoutCount)
        assertEquals(6, state.days.size)
        assertEquals(0, state.days.first().daysAgo) // today
        assertEquals("w-sun-run", state.days.first().workouts.single().workoutId)
        assertTrue(state.days.zipWithNext().all { (a, b) -> a.date > b.date })
        // 32+72+47+38+34+41 minutes of moving time; strength has no distance.
        assertEquals((32 + 72 + 47 + 38 + 34 + 41) * 60, state.weekDurationSec)
        assertEquals(5200 + 28400 + 3100 + 6400 + 15200, state.weekDistanceM)
    }

    /**
     * The other half of the 2026-08-13 "Today over yesterday's workouts" bug: even with a
     * repository that answers from the live clock, a view model that reads `repository.today` once
     * in its constructor pins the feed's day headers to whenever the screen was first opened — and
     * a `ViewModel` outlives midnight just as readily as the process-scoped repository does.
     *
     * ⚠ Restore `val today = repository.today` in [HistoryViewModel] and this fails.
     */
    @Test
    fun history_todayFollowsTheRepositoryAcrossMidnight() = vmTest { vms ->
        val repo = RollingDayRepository(MockFitRepository(scope = backgroundScope), LocalDate(2026, 8, 12))
        val vm = vms.own(HistoryViewModel(repo))
        assertEquals(LocalDate(2026, 8, 12), vm.today)

        repo.day = LocalDate(2026, 8, 13)
        assertEquals(
            LocalDate(2026, 8, 13),
            vm.today,
            "the feed's day headers must be relative to today, not to when the screen was opened",
        )
    }

    @Test
    fun workoutDetail_loadsAndMisses() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val loaded = vms.own(WorkoutDetailViewModel(repo, regions = null, "w-sat-ride")).uiState.value
        assertIs<WorkoutDetailUiState.Loaded>(loaded)
        assertEquals(ActivityType.Cycling, loaded.workout.activityType)
        assertIs<WorkoutDetailUiState.NotFound>(vms.own(WorkoutDetailViewModel(repo, regions = null, "nope")).uiState.value)
    }

    @Test
    fun workoutDetail_threadsResolvedRegionBundlePath_forAGpsWorkout() = vmTest { vms ->
        // A workout carrying real GPS upgrades from the schematic/demo to its resolved region.
        val base = MockFitRepository(scope = backgroundScope)
        val repo = object : FitRepository by base {
            override suspend fun workout(id: String) =
                when (val read = base.workout(id)) {
                    is app.tenshu.fit.data.WorkoutRead.Found -> app.tenshu.fit.data.WorkoutRead.Found(
                        read.workout.copy(
                            geoRoute = listOf(GeoCoord(43.77, 11.25), GeoCoord(43.78, 11.26)),
                        ),
                    )
                    else -> read
                }
        }
        var seen: List<GeoCoord>? = null
        val resolver = RegionBundleResolver { route -> seen = route; "/staged/it-firenze.pmtiles" }

        val loaded = vms.own(WorkoutDetailViewModel(repo, resolver, "w-sat-ride")).uiState.value
        assertIs<WorkoutDetailUiState.Loaded>(loaded)
        assertEquals("/staged/it-firenze.pmtiles", loaded.regionBundlePath)
        assertEquals(2, seen?.size)
    }

    @Test
    fun record_startTickFinishNavigates() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repo, scope = backgroundScope)
        val vm = vms.own(RecordViewModel(engine))
        vm.selectActivity(ActivityType.Cycling)
        vm.start()
        advanceTimeBy(90_000)
        runCurrent()
        val live = assertIs<RecordingState.Active>(vm.uiState.value.recording)
        assertEquals(ActivityType.Cycling, live.activityType)
        assertEquals(90, live.elapsedSec)
        vm.finish()
        runCurrent()
        val finishedId = vm.uiState.value.finishedWorkoutId
        assertNotNull(finishedId)
        assertEquals(Source.Recorded, assertIs<app.tenshu.fit.data.WorkoutRead.Found>(repo.workout(finishedId)).workout.source)
        vm.consumeFinished()
        assertNull(vm.uiState.value.finishedWorkoutId)
    }

    @Test
    fun record_finishSavedLocallyShowsNoticeInsteadOfNavigating() = vmTest { vms ->
        // A repository whose save journals locally (Control unreachable, outbox bound).
        val repo = object : FitRepository by MockFitRepository(scope = backgroundScope) {
            override suspend fun addWorkout(
                workout: app.tenshu.fit.model.Workout,
                track: app.tenshu.fit.model.RecordedTrack?,
            ) = app.tenshu.fit.data.WorkoutSaveOutcome.SavedLocally
        }
        val vm = vms.own(RecordViewModel(MockRecordingEngine(repo, scope = backgroundScope)))
        vm.start()
        advanceTimeBy(60_000)
        runCurrent()
        vm.finish()
        runCurrent()
        // No detail navigation (nothing readable yet) — the saved-locally notice instead; the
        // session has ended (the durable journal owns the workout now).
        assertNull(vm.uiState.value.finishedWorkoutId)
        assertTrue(vm.uiState.value.savedLocally)
        assertIs<RecordingState.Idle>(vm.uiState.value.recording)
        vm.consumeSavedLocally()
        assertFalse(vm.uiState.value.savedLocally)
    }

    @Test
    fun record_finishFailureKeepsSessionAndShowsRetryNotice() = vmTest { vms ->
        // A repository without a durable journal: a save against a dead proxy throws.
        val repo = object : FitRepository by MockFitRepository(scope = backgroundScope) {
            override suspend fun addWorkout(
                workout: app.tenshu.fit.model.Workout,
                track: app.tenshu.fit.model.RecordedTrack?,
            ): app.tenshu.fit.data.WorkoutSaveOutcome = throw IllegalStateException("Proxy not connected")
        }
        val vm = vms.own(RecordViewModel(MockRecordingEngine(repo, scope = backgroundScope)))
        vm.start()
        advanceTimeBy(60_000)
        runCurrent()
        vm.finish()
        runCurrent()
        // The engine kept the session (Idle only after a successful save) → Save can retry.
        // Since RC-2 a failed commit parks the draft Reviewing (capture is already torn down).
        assertNull(vm.uiState.value.finishedWorkoutId)
        assertTrue(vm.uiState.value.saveFailed)
        assertIs<RecordingState.Reviewing>(vm.uiState.value.recording)
        vm.dismissSaveFailed()
        assertFalse(vm.uiState.value.saveFailed)
    }

    @Test
    fun record_stopParksDraftAndSaveAppliesReviewEdits() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(RecordViewModel(MockRecordingEngine(repo, scope = backgroundScope)))
        vm.selectActivity(ActivityType.Running)
        vm.start()
        advanceTimeBy(60_000)
        runCurrent()
        vm.stop()
        runCurrent()
        // Nothing saved yet — the draft is under review.
        val before = repo.workouts().first().size
        val reviewing = assertIs<RecordingState.Reviewing>(vm.uiState.value.recording)
        assertEquals(60, reviewing.draft.workout.durationSec)
        // Review edits: title + activity-type correction ride the commit.
        vm.setDraftTitle("Morning run")
        vm.setDraftActivity(ActivityType.Walking)
        vm.save()
        runCurrent()
        val finishedId = vm.uiState.value.finishedWorkoutId
        assertNotNull(finishedId)
        val saved = assertIs<app.tenshu.fit.data.WorkoutRead.Found>(repo.workout(finishedId)).workout
        assertEquals("Morning run", saved.title)
        assertEquals(ActivityType.Walking, saved.activityType)
        assertEquals(before + 1, repo.workouts().first().size)
        assertIs<RecordingState.Idle>(vm.uiState.value.recording)
    }

    @Test
    fun record_discardFromReviewSavesNothing() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(RecordViewModel(MockRecordingEngine(repo, scope = backgroundScope)))
        val before = repo.workouts().first().size
        vm.start()
        advanceTimeBy(30_000)
        runCurrent()
        vm.stop()
        runCurrent()
        assertIs<RecordingState.Reviewing>(vm.uiState.value.recording)
        vm.discard()
        runCurrent()
        assertIs<RecordingState.Idle>(vm.uiState.value.recording)
        assertEquals(before, repo.workouts().first().size)
    }

    @Test
    fun browse_hasSevenCategories() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        assertEquals(7, vms.own(BrowseViewModel(repo)).uiState.value.categories.size)
    }

    @Test
    fun categoryDetail_activityWindowsByRange() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(CategoryDetailViewModel(CategoryId.Activity, repo))
        val week = vm.uiState.value
        assertEquals(CategoryDetailMode.Series, week.mode)
        assertEquals(CategoryMetric.Steps, week.metric)
        assertEquals(ChartRange.Week, week.range)
        assertEquals(7, week.values.size)
        assertEquals(7739, week.dailyAverage)
        assertEquals(8000, week.goal)
        // Entries newest first; today's entry is the on-device counter.
        assertEquals(0, week.entries.first().daysAgo)
        assertEquals(Source.Recorded, week.entries.first().source)
        assertEquals(Source.HealthConnect, week.entries.last().source)
        vm.selectRange(ChartRange.Month)
        runCurrent()
        val month = vm.uiState.value
        assertEquals(ChartRange.Month, month.range)
        assertEquals(28, month.values.size) // the mock ships 28 days; Month clamps
    }

    /**
     * Activity's other series (fit-app.md §18.18): each metric charts its OWN column of the daily
     * aggregate, with its own goal — not steps relabelled.
     */
    @Test
    fun categoryDetail_activityMetricsChartTheirOwnColumn() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val week = ChartMath.windowAggregates(repo.aggregates().first().itemsOrEmpty(), ChartRange.Week)
        val cases = listOf(
            Triple(CategoryMetric.ActiveMinutes, week.map { it.activeMinutes }, 30),
            Triple(CategoryMetric.Distance, week.map { it.distanceM }, 0),
            Triple(CategoryMetric.ActiveEnergy, week.map { it.activeEnergyKj }, 0),
        )
        for ((metric, expected, goal) in cases) {
            val state = vms.own(CategoryDetailViewModel(CategoryId.Activity, repo, metric)).uiState.value
            assertEquals(CategoryDetailMode.Series, state.mode, "$metric")
            assertEquals(metric, state.metric)
            assertEquals(expected, state.values, "$metric values")
            assertEquals(goal, state.goal, "$metric goal")
            assertEquals(expected.last(), state.entries.first().value, "$metric newest entry")
        }
        // The fixture must tell the columns apart, or the assertions above prove nothing.
        assertTrue(cases.map { it.second }.toSet().size == 3 && cases.none { it.second == week.map { d -> d.steps } })
    }

    @Test
    fun categoryDetail_nonActivityIgnoresARequestedMetric() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(CategoryDetailViewModel(CategoryId.Sleep, repo, CategoryMetric.Distance)).uiState.value
        assertEquals(CategoryMetric.SleepDuration, state.metric)
    }

    @Test
    fun categoryDetail_cycleShowsDayAndLog() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(CategoryDetailViewModel(CategoryId.Cycle, repo)).uiState.value
        assertEquals(CategoryDetailMode.Cycle, state.mode)
        assertEquals(CategoryMetric.Cycle, state.metric)
        assertEquals(15, state.cycleDay) // period start was 14 days ago → day 15
        assertEquals(5, state.cycleLog.size) // the mock ships five logged days
        assertTrue(state.cycleLog.first().daysAgo <= state.cycleLog.last().daysAgo) // newest first
    }

    @Test
    fun categoryDetail_nutritionShowsEnergySeries() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(CategoryDetailViewModel(CategoryId.Nutrition, repo)).uiState.value
        assertEquals(CategoryDetailMode.Series, state.mode)
        assertEquals(CategoryMetric.Energy, state.metric)
        assertEquals(0, state.goal) // no energy goal in v1
        assertEquals(7, state.values.size) // the mock ships a week of nutrition
        assertEquals(7_740, state.entries.first().value) // today's energy (kJ), newest first
    }

    @Test
    fun categoryDetail_sleepShowsDurationSeries() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(CategoryDetailViewModel(CategoryId.Sleep, repo))
        val state = vm.uiState.value
        assertEquals(CategoryDetailMode.Series, state.mode)
        assertEquals(CategoryMetric.SleepDuration, state.metric)
        assertEquals(0, state.goal) // no sleep goal in v1
        // The mock ships one night of 7h12m; values are per-night minutes.
        assertEquals(listOf(432), state.values)
        assertEquals(432, state.dailyAverage)
        assertEquals(Source.HealthConnect, state.entries.first().source)
    }

    @Test
    fun categoryDetail_bodyShowsLatestWeightWithHistory() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(CategoryDetailViewModel(CategoryId.Body, repo)).uiState.value
        assertEquals(CategoryDetailMode.Latest, state.mode)
        assertEquals(CategoryMetric.Weight, state.metric)
        // Newest reading is the current value; the rest form the history list (§12 B2).
        assertEquals(74.2, state.latestValue)
        assertEquals("kg", state.latestUnit)
        assertEquals(Source.HealthConnect, state.latestSource)
        assertEquals(5, state.history.size) // the mock ships a 5-reading weight series
        assertEquals(74.2, state.history.first().value) // newest first
        assertTrue(state.history.first().daysAgo <= state.history.last().daysAgo)
    }

    @Test
    fun categoryDetail_vitalsShowsRestingHrHistory() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val state = vms.own(CategoryDetailViewModel(CategoryId.Vitals, repo)).uiState.value
        assertEquals(CategoryDetailMode.Latest, state.mode)
        assertEquals(CategoryMetric.RestingHeartRate, state.metric)
        assertEquals(62.0, state.latestValue)
        assertEquals("bpm", state.latestUnit)
        assertEquals(5, state.history.size) // the mock ships a 5-reading resting-HR series
        assertEquals(CategoryDetailViewModel.VITALS_METRICS, state.metricChoices)
    }

    // The Health Connect declaration says Fit shows SpO₂ and respiratory rate (Play rejected the
    // build when it did not): each must be reachable from Vitals and render its own reading.
    @Test
    fun categoryDetail_vitalsSwitchesToBloodOxygenAndRespiratoryRate() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(CategoryDetailViewModel(CategoryId.Vitals, repo))

        vm.selectMetric(CategoryMetric.BloodOxygen)
        runCurrent()
        vm.uiState.value.let { s ->
            assertEquals(CategoryMetric.BloodOxygen, s.metric)
            assertEquals(CategoryDetailMode.Latest, s.mode)
            assertEquals(98.0, s.latestValue)
            assertEquals("%", s.latestUnit)
            assertEquals(CategoryDetailViewModel.VITALS_METRICS, s.metricChoices)
        }

        vm.selectMetric(CategoryMetric.RespiratoryRate)
        runCurrent()
        vm.uiState.value.let { s ->
            assertEquals(CategoryMetric.RespiratoryRate, s.metric)
            assertEquals(14.0, s.latestValue)
            assertEquals("/min", s.latestUnit)
        }

        // A metric outside Vitals' choices is ignored, not shown under a Vitals title.
        vm.selectMetric(CategoryMetric.Steps)
        runCurrent()
        assertEquals(CategoryMetric.RespiratoryRate, vm.uiState.value.metric)
    }

    @Test
    fun categoryDetail_onlyVitalsOffersAMetricSwitcher() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        assertTrue(vms.own(CategoryDetailViewModel(CategoryId.Body, repo)).uiState.value.metricChoices.isEmpty())
        assertTrue(vms.own(CategoryDetailViewModel(CategoryId.Activity, repo)).uiState.value.metricChoices.isEmpty())
    }

    @Test
    fun medical_sectionsInFixedOrder() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val sections = vms.own(MedicalViewModel(repo)).uiState.value.sections
        assertEquals(
            listOf(
                MedicalRecordKind.Medication,
                MedicalRecordKind.Immunization,
                MedicalRecordKind.Allergy,
                MedicalRecordKind.Condition,
            ),
            sections.map { it.kind },
        )
        assertEquals(2, sections.first().records.size)
        // Newest effective date first within a section.
        assertEquals("Cetirizine", sections.first().records.first().title)
    }

    @Test
    fun profile_readsTheSourceRegistryAndMutates() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(ProfileViewModel(repo))
        runCurrent()
        // ⚠ Rows come from the registry now, not from scanning workout provenance (DS-1..DS-3).
        // The old derivation could not represent a source on ANOTHER device, which is the whole
        // reason the section was rebuilt — so that is what this asserts.
        assertEquals(
            listOf(DataSourceKind.HealthConnect, DataSourceKind.AppleHealth),
            vm.uiState.value.dataSources.map { it.kind },
        )
        assertEquals(
            "iPhone 15",
            vm.uiState.value.dataSources.last().deviceLabel,
            "another device's row is named; this device's is not",
        )
        vm.saveGoals(activeMinutes = 45, steps = 12_000)
        runCurrent()
        assertEquals(45, vm.uiState.value.goals.activeMinutes)
        vm.setUnits(Units.Imperial)
        runCurrent()
        assertEquals(Units.Imperial, vm.uiState.value.profile?.units)
    }

    /**
     * The iOS "Import from Apple Health" row: a MANUAL import of every category. ⚠⚠ `automatic`
     * must be false — an automatic pass is skipped by the auto-import switch and, on iOS, is the
     * unattended path the HealthKit sheet must never come from; the tap is the user action.
     */
    @Test
    fun profile_importFromHealthSourceStartsAManualImportOfEveryCategory() = vmTest { vms ->
        var started: Triple<Set<CategoryId>, ImportWindow, Boolean>? = null
        val base = MockFitRepository(scope = backgroundScope)
        val repo = object : FitRepository by base {
            override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
                started = Triple(selection, window, automatic)
            }
        }
        val vm = vms.own(ProfileViewModel(repo))
        runCurrent()
        assertEquals(null, started, "nothing imports until the user taps")

        vm.importFromHealthSource()
        runCurrent()

        assertEquals(Triple(CategoryId.entries.toSet(), ImportWindow.Recent, false), started)
    }

    @Test
    fun profile_editsEveryDeclaredFieldAndBothMeasurements() = vmTest { vms ->
        // FP-3: every Profile row now has an editor, which is what earns each row its chevron back
        // (fit-app.md §18.6). Height and weight go through the *measurement* path in the real
        // repository; the mock collapses that, which is exactly what the seam is for.
        val vm = vms.own(ProfileViewModel(MockFitRepository(scope = backgroundScope)))

        vm.saveName("  Benedikt  ")
        vm.saveBirthDate(LocalDate(1990, 3, 14))
        vm.saveSex(BiologicalSex.OTHER)
        vm.saveHeight(181)
        vm.saveWeight(72.5)
        runCurrent()

        val profile = assertNotNull(vm.uiState.value.profile)
        assertEquals("Benedikt", profile.name, "a typed name is trimmed before it is stored")
        assertEquals(LocalDate(1990, 3, 14), profile.birthDate)
        assertEquals(BiologicalSex.OTHER, profile.sex)
        assertEquals(181, profile.heightCm)
        assertEquals(72.5, profile.weightKg)
    }

    @Test
    fun profile_clearingADeclaredFieldReturnsItToUnset() = vmTest { vms ->
        val vm = vms.own(ProfileViewModel(MockFitRepository(scope = backgroundScope)))

        vm.saveSex(BiologicalSex.MALE)
        vm.saveBirthDate(LocalDate(1990, 3, 14))
        runCurrent()
        assertEquals(BiologicalSex.MALE, vm.uiState.value.profile?.sex)

        // "Not set" has to stay reachable — a mis-tap on a date picker or a radio list must be
        // undoable, and unset is a real answer rather than a failure state (§18.4).
        vm.saveSex(null)
        vm.saveBirthDate(null)
        vm.saveName("   ")
        runCurrent()
        val profile = assertNotNull(vm.uiState.value.profile)
        assertNull(profile.sex)
        assertNull(profile.birthDate)
        assertNull(profile.name, "a blank name clears rather than storing whitespace")
    }

    @Test
    fun profile_rejectsAnImplausibleMeasurementRatherThanStoringIt() = vmTest { vms ->
        val vm = vms.own(ProfileViewModel(MockFitRepository(scope = backgroundScope)))

        vm.saveHeight(178)
        vm.saveWeight(74.2)
        runCurrent()

        // A slipped decimal point or a stray digit must not become a stored reading — and because
        // these are appended to a *series*, a bad one would also skew the Body chart forever.
        vm.saveHeight(1780)
        vm.saveWeight(7420.0)
        vm.saveHeight(0)
        runCurrent()

        val profile = assertNotNull(vm.uiState.value.profile)
        assertEquals(178, profile.heightCm, "an implausible height is ignored, keeping the last good one")
        assertEquals(74.2, profile.weightKg)
    }

    @Test
    fun profile_paintsBeforeWorkoutsArrive() = vmTest { vms ->
        // The workout read is the heaviest flow Profile combines, and all it feeds is the
        // provenance badges — it must not gate the first paint (fit-read-scaling.md Phase 1b).
        // A store that never answers (slow / unreachable Control) still paints profile + goals;
        // the sources section just stays empty until the read lands.
        val base = MockFitRepository(scope = backgroundScope)
        val repo = object : FitRepository by base {
            override fun workouts() = kotlinx.coroutines.flow.emptyFlow<List<app.tenshu.fit.model.Workout>>()
        }
        val state = vms.own(ProfileViewModel(repo)).uiState.value
        assertNotNull(state.profile, "profile must paint without the workout read")
        assertEquals(30, state.goals.activeMinutes)
    }

    @Test
    fun onboarding_importPathAndStartFresh() = vmTest { vms ->
        val repo = MockFitRepository(scope = backgroundScope)
        val vm = vms.own(OnboardingViewModel(repo))
        val initial = vm.uiState.value
        assertEquals(OnboardingStep.Welcome, initial.step)
        assertEquals(3, initial.sources.size)
        // Non-empty categories pre-checked.
        assertTrue(CategoryId.Activity in initial.selection)
        assertTrue(CategoryId.Nutrition !in initial.selection)

        vm.chooseSource(ImportSource.HealthConnect)
        assertEquals(OnboardingStep.Permissions, vm.uiState.value.step)
        vm.toggleCategory(CategoryId.Sleep)
        assertTrue(CategoryId.Sleep !in vm.uiState.value.selection)
        vm.importSelected()
        runCurrent()
        assertEquals(OnboardingStep.Importing, vm.uiState.value.step)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.uiState.value.overallFraction > 0f)
        assertTrue(!vm.uiState.value.importComplete)
        // Let the mock import run to the end — the screen flips to its completion state.
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1f, vm.uiState.value.overallFraction)
        assertTrue(vm.uiState.value.importComplete)
        vm.enterApp()
        runCurrent()
        assertEquals(true, vms.own(AppViewModel(repo)).onboarded.value)

        // Start fresh short-circuits.
        val repo2 = MockFitRepository(scope = backgroundScope)
        vms.own(OnboardingViewModel(repo2)).chooseSource(ImportSource.StartFresh)
        runCurrent()
        assertEquals(true, vms.own(AppViewModel(repo2)).onboarded.value)
    }

    @Test
    fun onboarding_livePlatformSourceWithNoCategories_skipsPickerAndImportsAll() = vmTest { vms ->
        // The live repository can't enumerate categories with estimates (importCategories() is
        // empty), and a live platform source's consent is the OS permission sheet — so choosing
        // it must skip the (empty, dead-end) picker and import the full selection directly.
        val base = MockFitRepository(scope = backgroundScope)
        var imported: Set<CategoryId>? = null
        val repo = object : FitRepository by base {
            override fun importCategories() = emptyList<app.tenshu.fit.model.ImportCategory>()
            override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) { imported = selection }
        }
        val vm = vms.own(OnboardingViewModel(repo))
        vm.chooseSource(ImportSource.HealthConnect)
        runCurrent()
        assertEquals(OnboardingStep.Importing, vm.uiState.value.step)
        assertEquals(CategoryId.entries.toSet(), imported)

        // The mock (with real categories + estimates) keeps the picker.
        val vm2 = vms.own(OnboardingViewModel(MockFitRepository(scope = backgroundScope)))
        vm2.chooseSource(ImportSource.HealthConnect)
        assertEquals(OnboardingStep.Permissions, vm2.uiState.value.step)
    }

    /**
     * The deep-history step is **self-skipping**: it only appears where the platform actually has a
     * gate the user can open. `Unsupported` (no such notion) and `Granted` (already answered) both
     * go straight to the import, so the step can never be an unanswerable question.
     */
    @Test
    fun onboarding_aboutYouStepAsksOnlyForWhatTheImportCouldNotSupply() = vmTest { vms ->
        // FP-3 (§18.6): the ask comes AFTER the import, and self-skips per field — the import may
        // already have answered it, and asking first would be asking for data we are about to be
        // given. The mock's profile arrives fully populated, so there is nothing left to ask.
        val vm = vms.own(OnboardingViewModel(MockFitRepository(scope = backgroundScope)))
        runCurrent()
        assertTrue(vm.uiState.value.missingAboutYouFields.isEmpty())
        assertFalse(vm.uiState.value.needsAboutYouStep, "a filled profile must not show the step")

        vm.enterApp()
        runCurrent()
        assertNotEquals(OnboardingStep.AboutYou, vm.uiState.value.step, "nothing to ask ⇒ straight in")
    }

    @Test
    fun onboarding_aboutYouStepAppearsForTheFieldsHealthConnectCannotProvide() = vmTest { vms ->
        // The Android shape: Health Connect has no demographics API at all, so birth date and sex
        // are still open after any import, while the body measurements may have arrived.
        val repo = MockFitRepository(scope = backgroundScope)
        repo.updateBirthDate(null)
        repo.updateSex(null)
        val vm = vms.own(OnboardingViewModel(repo))
        runCurrent()

        assertEquals(
            setOf(AboutYouField.BirthDate, AboutYouField.Sex),
            vm.uiState.value.missingAboutYouFields,
            "only the unanswerable fields are asked; height/weight came from the import",
        )

        vm.enterApp()
        runCurrent()
        assertEquals(OnboardingStep.AboutYou, vm.uiState.value.step)

        // Every field is optional: leaving without answering still enters the app.
        vm.finishAboutYou()
        runCurrent()
        assertTrue(repo.onboarded().first(), "a skipped About-you must never block entry")
    }

    @Test
    fun onboarding_deepHistoryStepOnlyAppearsWhenTheGrantIsAvailable() = vmTest { vms ->
        suspend fun stepFor(access: HistoryAccess): OnboardingStep {
            val repo = MockFitRepository(scope = backgroundScope).asLiveSourceWith(access)
            val vm = vms.own(OnboardingViewModel(repo, permissionRequester = RecordingPermissionRequester()))
            vm.chooseSource(ImportSource.HealthConnect)
            runCurrent()
            return vm.uiState.value.step
        }
        assertEquals(OnboardingStep.DeepHistory, stepFor(HistoryAccess.Grantable))
        assertEquals(OnboardingStep.Importing, stepFor(HistoryAccess.Unsupported))
        assertEquals(OnboardingStep.Importing, stepFor(HistoryAccess.Granted))
    }

    /**
     * "Allow past data" hands off to the platform sheet and must **not** start its own import: the
     * shell starts the read when the grant result lands, and a read started here would take the
     * repository's import lock and make the shell's full-history one step aside — silently
     * downgrading exactly the request the user just made.
     */
    @Test
    fun onboarding_allowDeepHistoryDefersTheImportToTheGrantResult() = vmTest { vms ->
        val requester = RecordingPermissionRequester()
        var startedWindow: ImportWindow? = null
        val base = MockFitRepository(scope = backgroundScope).asLiveSourceWith(HistoryAccess.Grantable)
        val repo = object : FitRepository by base {
            override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
                startedWindow = window
            }
        }
        val vm = vms.own(OnboardingViewModel(repo, permissionRequester = requester))
        vm.chooseSource(ImportSource.HealthConnect)
        runCurrent()
        vm.allowDeepHistory()
        runCurrent()

        assertEquals(OnboardingStep.Importing, vm.uiState.value.step)
        assertEquals(1, requester.fullHistoryRequests)
        assertEquals(null, startedWindow)
    }

    /** "Not now" must still import — the ordinary window, immediately. */
    @Test
    fun onboarding_skipDeepHistoryImportsTheRecentWindow() = vmTest { vms ->
        var startedWindow: ImportWindow? = null
        val base = MockFitRepository(scope = backgroundScope).asLiveSourceWith(HistoryAccess.Grantable)
        val repo = object : FitRepository by base {
            override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
                startedWindow = window
            }
        }
        val vm = vms.own(OnboardingViewModel(repo, permissionRequester = RecordingPermissionRequester()))
        vm.chooseSource(ImportSource.HealthConnect)
        runCurrent()
        vm.skipDeepHistory()
        runCurrent()

        assertEquals(OnboardingStep.Importing, vm.uiState.value.step)
        assertEquals(ImportWindow.Recent, startedWindow)
    }

    /**
     * Without a bound requester there is no sheet to wait for, so "Allow" must fall back to an
     * ordinary import rather than parking the user on a progress screen that never moves.
     */
    @Test
    fun onboarding_allowDeepHistoryWithNoRequesterFallsBackToImporting() = vmTest { vms ->
        var startedWindow: ImportWindow? = null
        val base = MockFitRepository(scope = backgroundScope).asLiveSourceWith(HistoryAccess.Grantable)
        val repo = object : FitRepository by base {
            override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
                startedWindow = window
            }
        }
        val vm = vms.own(OnboardingViewModel(repo, permissionRequester = null))
        vm.chooseSource(ImportSource.HealthConnect)
        runCurrent()
        vm.allowDeepHistory()
        runCurrent()

        assertEquals(ImportWindow.Recent, startedWindow)
    }

    /**
     * Profile's entry is the same offer, permanently available. Grantable → prompt (the shell runs
     * the import on the answer); already Granted → run the deep read directly, since the grant is
     * in place and the tap *is* the request.
     */
    @Test
    fun onboarding_importCompleteRequiresNonEmptyDoneRows() {
        assertTrue(!OnboardingUiState(progress = null).importComplete)
        assertTrue(!OnboardingUiState(progress = emptyList()).importComplete)
        assertTrue(
            !OnboardingUiState(
                progress = listOf(ImportProgress(CategoryId.Activity, imported = 5, total = 10)),
            ).importComplete,
        )
        // An all-empty selection must not read as a completed import.
        assertTrue(
            !OnboardingUiState(
                progress = listOf(ImportProgress(CategoryId.Activity, imported = 0, total = 0)),
            ).importComplete,
        )
        assertTrue(
            OnboardingUiState(
                progress = listOf(
                    ImportProgress(CategoryId.Activity, imported = 10, total = 10),
                    ImportProgress(CategoryId.Sleep, imported = 0, total = 0),
                ),
            ).importComplete,
        )
    }
}

/**
 * Presents [this] as a **live platform source**: no enumerable categories (the real Health-Connect
 * repository cannot estimate them, which is what makes onboarding skip the picker) and a fixed
 * deep-history verdict.
 */
private fun FitRepository.asLiveSourceWith(access: HistoryAccess): FitRepository =
    object : FitRepository by this {
        override fun importCategories() = emptyList<app.tenshu.fit.model.ImportCategory>()
        override fun historyAccess() = kotlinx.coroutines.flow.MutableStateFlow(access)
    }

/** Records what the shell was asked to prompt for, so a view model's hand-off can be asserted. */
private class RecordingPermissionRequester : app.tenshu.fit.data.HealthPermissionRequester {
    var requests = 0
    var fullHistoryRequests = 0
    override fun request() { requests++ }
    override fun requestFullHistory() { fullHistoryRequests++ }
}

/**
 * A [FitRepository] whose store is empty — every collection emits `emptyList()` once. Stands in
 * for the honest first-run state on a real device (no imported or recorded activity yet). The
 * three flows Home combines ([aggregates], [goals], [workouts]) emit; the rest are unused here
 * and stay empty flows.
 */
/**
 * The other empty: a repository whose aggregates read **failed**.
 *
 * ⚠ It exists because [EmptyFitRepository] alone cannot catch the bug. Both produce a Home with no
 * `today` and no week; only the phase separates them, and before `ListLoadState` there was no
 * phase — so a fake that modelled only the answered-and-empty path made the broken screen look
 * correct. A fake that models only the happy path hides the missing branch.
 */
private class FailingFitRepository : app.tenshu.fit.data.FitRepository by EmptyFitRepository() {
    override fun aggregates() = kotlinx.coroutines.flow.flowOf(app.tenshu.liststate.ListLoadState.Failed)
}

/** Drives [BrowseViewModel] through an arbitrary sequence of load states. */
private class SwitchableCategoriesRepository(
    private val delegate: app.tenshu.fit.data.FitRepository = EmptyFitRepository(),
) : app.tenshu.fit.data.FitRepository by delegate {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<
        app.tenshu.liststate.ListLoadState<app.tenshu.fit.model.CategorySummary>
        >(app.tenshu.liststate.ListLoadState.Loading)

    fun emit(next: app.tenshu.liststate.ListLoadState<app.tenshu.fit.model.CategorySummary>) {
        state.value = next
    }

    override fun categories() = state
}

private fun activityCategory() = app.tenshu.fit.model.CategorySummary(
    id = CategoryId.Activity,
    teaser = app.tenshu.fit.model.CategoryTeaser.ActiveMinutesToday(42),
    updated = app.tenshu.fit.model.LastUpdated.Today,
)

private class EmptyFitRepository : app.tenshu.fit.data.FitRepository {
    override val today = kotlinx.datetime.LocalDate(2026, 1, 1)
    override fun refuseIfRealmWritesHeld() = Unit // a fake with no realm to guard
    // ⚠ `Loaded(emptyList())`, NOT `Failed`: this fake stands in for a store that answered and
    // holds nothing, which is precisely the state ListLoadState exists to keep distinct from a
    // read that could not answer. See [FailingFitRepository] for the other one.
    override fun aggregates() = kotlinx.coroutines.flow.flowOf(
        app.tenshu.liststate.ListLoadState.Loaded(emptyList<app.tenshu.fit.model.DailyAggregate>()),
    )
    override fun workouts() = kotlinx.coroutines.flow.flowOf(emptyList<app.tenshu.fit.model.Workout>())
    override fun goals() = kotlinx.coroutines.flow.flowOf(app.tenshu.fit.model.Goals(activeMinutes = 30, steps = 8000))
    override fun categories() =
        kotlinx.coroutines.flow.emptyFlow<app.tenshu.liststate.ListLoadState<app.tenshu.fit.model.CategorySummary>>()
    override fun samplesLatest() = kotlinx.coroutines.flow.emptyFlow<List<app.tenshu.fit.model.SampleLatest>>()
    override fun sleepSessions() = kotlinx.coroutines.flow.emptyFlow<List<app.tenshu.fit.model.SleepSession>>()
    override fun medicalRecords() = kotlinx.coroutines.flow.emptyFlow<List<app.tenshu.fit.model.MedicalRecord>>()
    override fun profile() = kotlinx.coroutines.flow.emptyFlow<app.tenshu.fit.model.FitProfile>()
    override fun onboarded() = kotlinx.coroutines.flow.flowOf(true)
    // ⚠ `Failed`, not `NotFound`: this double stands in for a repository with nothing behind it,
    // and "no proxy" is a failed read, not an answered one.
    override suspend fun workout(id: String): app.tenshu.fit.data.WorkoutRead =
        app.tenshu.fit.data.WorkoutRead.Failed
    override suspend fun addWorkout(workout: app.tenshu.fit.model.Workout, track: app.tenshu.fit.model.RecordedTrack?) =
        app.tenshu.fit.data.WorkoutSaveOutcome.Synced
    override fun availableSources() = emptyList<ImportSource>()
    override fun importCategories() = emptyList<app.tenshu.fit.model.ImportCategory>()
    override fun importProgress() = kotlinx.coroutines.flow.emptyFlow<List<app.tenshu.fit.model.ImportProgress>?>()
    override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) = Unit
    override suspend fun completeOnboarding() = Unit
    override suspend fun updateGoals(goals: app.tenshu.fit.model.Goals) = Unit
    override suspend fun updateUnits(units: Units) = Unit
}

/**
 * A repository whose idea of "today" moves — the one thing a test can do that a real overnight
 * process does for free. See `FitViewModelsTest.history_todayFollowsTheRepositoryAcrossMidnight`.
 */
private class RollingDayRepository(
    delegate: FitRepository,
    var day: LocalDate,
) : FitRepository by delegate {
    override val today: LocalDate get() = day
}
