package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategorySummary
import app.tenshu.fit.model.CategoryTeaser
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.LastUpdated
import app.tenshu.fit.model.Units
import app.tenshu.liststate.ListLoadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockFitRepositoryTest {

    @Test
    fun seedsTheMockWeekPlusThreeSynthesizedWeeks() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        // `assertIs<Loaded>`, not `itemsOrEmpty()`: a Failed read would otherwise read as an empty
        // list and this would fail as "expected 28, got 0" — a message about seeding, for a
        // loading bug.
        val aggregates = assertIs<ListLoadState.Loaded<DailyAggregate>>(repository.aggregates().first()).items
        assertEquals(28, aggregates.size)
        assertEquals(repository.today, aggregates.last().date)
        assertEquals(LocalDate(2026, 5, 24), repository.today)
        // Oldest-first and contiguous.
        assertTrue(aggregates.zipWithNext().all { (a, b) -> a.date < b.date })
        assertEquals(6, repository.workouts().first().size)
    }

    @Test
    fun workoutLookupFindsByIdAndMissesGracefully() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val read = repository.workout("w-sun-run")
        assertIs<WorkoutRead.Found>(read)
        val run = read.workout
        assertEquals(5200, run.distanceM)
        assertTrue(run.laps.isNotEmpty())
        assertTrue(run.route.isNotEmpty())
        assertTrue(run.hrSeries.isNotEmpty())
        // ⚠ `NotFound`, asserted by identity rather than by null: an in-memory list cannot fail to
        // be read, so this is the one repository for which a miss is genuinely an absence. Pinning
        // it here is what stops a future edit from quietly answering `Failed` for a missing row.
        assertEquals(WorkoutRead.NotFound, repository.workout("nope"))
    }

    @Test
    fun addedWorkoutSortsNewestFirst() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val sunRun = assertIs<WorkoutRead.Found>(repository.workout("w-sun-run")).workout
        val newest = sunRun.copy(
            workoutId = "w-new",
            start = sunRun.start.let {
                kotlinx.datetime.LocalDateTime(it.year, it.month, it.day, 18, 0)
            },
        )
        repository.addWorkout(newest)
        assertEquals("w-new", repository.workouts().first().first().workoutId)
    }

    @Test
    fun categoriesDeriveTeasersFromState() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val categories = assertIs<ListLoadState.Loaded<CategorySummary>>(repository.categories().first()).items
        assertEquals(7, categories.size)
        assertEquals(CategoryTeaser.ActiveMinutesToday(42), categories.first { it.id == CategoryId.Activity }.teaser)
        assertEquals(CategoryTeaser.Weight(74.2), categories.first { it.id == CategoryId.Body }.teaser)
        assertEquals(LastUpdated.DaysAgo(2), categories.first { it.id == CategoryId.Body }.updated)
        assertEquals(CategoryTeaser.RestingHeartRate(62), categories.first { it.id == CategoryId.Vitals }.teaser)
        assertEquals(CategoryTeaser.SleepDuration(432), categories.first { it.id == CategoryId.Sleep }.teaser)
        assertEquals(CategoryTeaser.RecordCount(6), categories.first { it.id == CategoryId.Medical }.teaser)
        // Nutrition is now backed (§12 B3a) — today's energy teaser.
        assertEquals(CategoryTeaser.Energy(7_740), categories.first { it.id == CategoryId.Nutrition }.teaser)
        // Cycle is data-driven + backed by the mock (§12 B3b) — period start was 14 days ago → day 15.
        assertEquals(CategoryTeaser.CycleDay(15), categories.first { it.id == CategoryId.Cycle }.teaser)
    }

    @Test
    fun onboardingGateAndSources() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        assertFalse(repository.onboarded().first())
        assertEquals(
            listOf(ImportSource.HealthConnect, ImportSource.ImportFile, ImportSource.StartFresh),
            repository.availableSources(),
        )
        repository.completeOnboarding()
        assertTrue(repository.onboarded().first())
    }

    @Test
    fun importProgressAdvancesToCompletion() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        assertNull(repository.importProgress().first())
        repository.startImport(setOf(CategoryId.Activity, CategoryId.Medical, CategoryId.Nutrition))
        runCurrent()
        // Nutrition has an estimated count of 0 → not part of the progress list.
        val initial = repository.importProgress().first()!!
        assertEquals(2, initial.size)
        assertTrue(initial.none { it.done })
        advanceTimeBy(60_000)
        runCurrent()
        val finished = repository.importProgress().first()!!
        assertTrue(finished.all { it.done })
    }

    @Test
    fun profileMutations() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        repository.updateGoals(repository.goals().first().copy(steps = 10_000))
        assertEquals(10_000, repository.goals().first().steps)
        repository.updateUnits(Units.Imperial)
        assertEquals(Units.Imperial, repository.profile().first().units)
    }
}
