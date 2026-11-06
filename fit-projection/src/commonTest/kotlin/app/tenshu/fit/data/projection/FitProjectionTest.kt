package app.tenshu.fit.data.projection

import app.tenshu.fit.data.projection.FitProjection.MedicalRecordInput
import app.tenshu.fit.data.projection.FitProjection.SampleInput
import app.tenshu.fit.data.projection.FitProjection.SleepSessionInput
import app.tenshu.fit.data.projection.FitProjection.WorkoutInput
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategoryTeaser
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.SleepStageKind
import app.tenshu.fit.model.Source
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.tenshu.health.common.RouteSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the pure [FitProjection] mapping — no client, no proxy. Proves the
 * lazy-stored `Workout` → materialised presentation-model derivations (`docs/proposals/fit-app.md`
 * §3 D1). Fixed [TimeZone.UTC] so the epoch-millis → day/time conversions are deterministic.
 */
class FitProjectionTest {

    private val utc = TimeZone.UTC

    // A base UTC-midnight; +offsets keep workouts on the intended calendar day.
    private val dayBase = 1_800_000_000_000L      // an arbitrary instant on some UTC day D
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun input(
        id: String,
        activityType: String = "Running",
        source: String = "Recorded",
        startMs: Long = dayBase,
        durationSec: Long = 1_800,
        distanceM: Long = 0,
        activeEnergyKj: Long = 0,
        avgHr: Long = 0,
    ) = WorkoutInput(id, activityType, source, startMs, startMs + durationSec * 1_000, durationSec, "", distanceM, activeEnergyKj, avgHr)

    @Test
    fun workoutMapsTypeSourceAndFoldsZeroSentinelsToNull() {
        val w = FitProjection.workout(
            input("a", activityType = "Cycling", source = "HealthConnect", distanceM = 5_000, activeEnergyKj = 0, avgHr = 140),
            utc,
        )
        assertEquals(ActivityType.Cycling, w.activityType)
        assertEquals(Source.HealthConnect, w.source)
        assertEquals(5_000, w.distanceM)
        assertNull(w.activeEnergyKj)      // 0 sentinel → unknown
        assertEquals(140, w.avgHr)
        assertEquals(1_800, w.durationSec)
    }

    @Test
    fun unknownActivityTypeAndSourceFallBack() {
        val w = FitProjection.workout(input("a", activityType = "Yoga", source = "Garmin"), utc)
        assertEquals(ActivityType.Other, w.activityType)
        assertEquals(Source.Manual, w.source)
    }

    @Test
    fun enumNamesRoundTripToServiceStrings() {
        ActivityType.entries.forEach { assertEquals(it, FitProjection.activityType(FitProjection.activityTypeName(it))) }
        Source.entries.forEach { assertEquals(it, FitProjection.source(FitProjection.sourceName(it))) }
    }

    @Test
    fun workoutsSortNewestFirst() {
        val ws = FitProjection.workouts(
            listOf(input("old", startMs = dayBase), input("new", startMs = dayBase + day)),
            utc,
        )
        assertEquals(listOf("new", "old"), ws.map { it.workoutId })
    }

    @Test
    fun workoutDerivedGroupsPerDayOldestFirstWithSummedActiveMinutes() {
        val aggregates = FitProjection.workoutDerived(
            listOf(
                input("d0-a", startMs = dayBase + hour, durationSec = 1_800, distanceM = 2_000),   // day D, 30 min
                input("d0-b", startMs = dayBase + 5 * hour, durationSec = 600, distanceM = 1_000),  // day D, 10 min
                input("d1", startMs = dayBase + day + hour, durationSec = 1_200),                    // day D+1, 20 min
            ),
            utc,
        )
        assertEquals(2, aggregates.size)
        assertTrue(aggregates[0].date < aggregates[1].date)          // oldest first
        assertEquals(40, aggregates[0].activeMinutes)                 // 30 + 10
        assertEquals(3_000, aggregates[0].distanceM)                  // summed
        assertEquals(0, aggregates[0].steps)                          // a workout carries no step count
        assertEquals(20, aggregates[1].activeMinutes)
    }

    @Test
    fun dailyAggregatesPreferStoredAndFallBackToWorkoutDerived() {
        val dayD = FitProjection.workoutDerived(listOf(input("d0", startMs = dayBase)), utc).first().date
        val stored = FitProjection.DailyActivityInput(date = dayD, steps = 8_360, distanceM = 6_100, activeEnergyKj = 1_720, activeMinutes = 39)

        val merged = FitProjection.dailyAggregates(
            stored = listOf(stored),
            workouts = listOf(
                input("d0", startMs = dayBase, durationSec = 1_800),               // same day as the stored aggregate
                input("d1", startMs = dayBase + day + hour, durationSec = 1_200),   // day D+1: no stored aggregate
            ),
            zone = utc,
        )
        assertEquals(2, merged.size)
        // Day D: the stored aggregate wins (real steps), not the workout-derived steps=0.
        assertEquals(8_360, merged[0].steps)
        assertEquals(39, merged[0].activeMinutes)
        // Day D+1: falls back to workout-derived (steps 0, 20 min).
        assertEquals(0, merged[1].steps)
        assertEquals(20, merged[1].activeMinutes)
    }

    @Test
    fun dailyAggregatesFoldWorkoutMinutesWhenStoredActiveMinutesIsZero() {
        // The Health Connect case: a stored daily aggregate carries real steps/distance/energy but
        // activeMinutes = 0 (HC has no "active minutes" record). A same-day workout must lift the
        // Home ring off zero — the stored 0 must not shadow the workout-derived minutes (per-field
        // merge, not whole-row).
        val dayD = FitProjection.workoutDerived(listOf(input("d0", startMs = dayBase)), utc).first().date
        val stored = FitProjection.DailyActivityInput(date = dayD, steps = 6_120, distanceM = 4_500, activeEnergyKj = 1_940, activeMinutes = 0)

        val merged = FitProjection.dailyAggregates(
            stored = listOf(stored),
            workouts = listOf(input("d0", startMs = dayBase, durationSec = 1_800, distanceM = 2_000)),   // 30 min, same day
            zone = utc,
        ).single()

        assertEquals(6_120, merged.steps)          // stored owns steps
        assertEquals(4_500, merged.distanceM)      // stored whole-day distance preferred over the workout's 2_000
        assertEquals(1_940, merged.activeEnergyKj) // stored energy preferred
        assertEquals(30, merged.activeMinutes)     // folded from the workout, NOT the stored 0
    }

    @Test
    fun dailyAggregatesFallBackToWorkoutStatsWhenStoredFieldIsZero() {
        // A stored aggregate can be partial (0 in a field the store didn't populate). Each stat
        // falls back independently to the workout-derived value.
        val dayD = FitProjection.workoutDerived(listOf(input("d0", startMs = dayBase)), utc).first().date
        val stored = FitProjection.DailyActivityInput(date = dayD, steps = 6_120, distanceM = 0, activeEnergyKj = 0, activeMinutes = 0)

        val merged = FitProjection.dailyAggregates(
            stored = listOf(stored),
            workouts = listOf(input("d0", startMs = dayBase, durationSec = 1_800, distanceM = 2_000, activeEnergyKj = 900)),
            zone = utc,
        ).single()

        assertEquals(6_120, merged.steps)
        assertEquals(2_000, merged.distanceM)      // stored 0 → workout distance
        assertEquals(900, merged.activeEnergyKj)   // stored 0 → workout energy
        assertEquals(30, merged.activeMinutes)     // stored 0 → workout minutes
    }

    @Test
    fun estimateActiveMinutesExcludesIncidentalStepsAndConverts() {
        assertEquals(0, FitProjection.estimateActiveMinutes(0))
        assertEquals(0, FitProjection.estimateActiveMinutes(1_000))   // below the incidental floor
        assertEquals(0, FitProjection.estimateActiveMinutes(2_000))   // exactly the floor
        assertEquals(30, FitProjection.estimateActiveMinutes(8_000))  // default step goal ≈ default 30-min goal
        assertEquals(40, FitProjection.estimateActiveMinutes(10_000))
    }

    @Test
    fun dailyAggregatesEstimateActiveMinutesFromStepsWhenNoWorkout() {
        // The de-Googled/walk-only case: a stored day with real steps but activeMinutes = 0 and NO
        // logged workout. The ring must reflect the movement via the steps estimate, not read 0.
        val stored = FitProjection.DailyActivityInput(date = LocalDate(2026, 5, 24), steps = 8_000, distanceM = 6_100, activeEnergyKj = 1_720, activeMinutes = 0)
        val merged = FitProjection.dailyAggregates(stored = listOf(stored), workouts = emptyList(), zone = utc).single()
        assertEquals(8_000, merged.steps)
        assertEquals(30, merged.activeMinutes)   // estimated from steps
    }

    @Test
    fun dailyAggregatesPreferLoggedWorkoutOverStepsEstimate() {
        // On a day with BOTH a high step count (estimate 30) and a logged 20-min workout, the
        // workout figure wins — higher fidelity, and avoids double-counting the workout's steps.
        val dayD = FitProjection.workoutDerived(listOf(input("d0", startMs = dayBase)), utc).first().date
        val stored = FitProjection.DailyActivityInput(date = dayD, steps = 8_000, distanceM = 6_100, activeEnergyKj = 1_720, activeMinutes = 0)
        val merged = FitProjection.dailyAggregates(
            stored = listOf(stored),
            workouts = listOf(input("d0", startMs = dayBase, durationSec = 1_200)),   // 20 min
            zone = utc,
        ).single()
        assertEquals(20, merged.activeMinutes)
    }

    @Test
    fun categoriesSurfaceActivityTeaserAndDimTheRest() {
        val aggregates = FitProjection.workoutDerived(listOf(input("a", durationSec = 1_800)), utc)
        val today = aggregates.last().date
        val categories = FitProjection.categories(aggregates, nutrition = emptyList(), cycle = emptyList(), sleep = emptyList(), samples = emptyList(), medical = emptyList(), today = today)

        val activity = categories.first { it.id == CategoryId.Activity }
        assertEquals(CategoryTeaser.ActiveMinutesToday(30), activity.teaser)

        // Cycle is data-driven (§12 B3b): with no cycle data the card is absent entirely, not dimmed.
        assertTrue(categories.none { it.id == CategoryId.Cycle })
        assertTrue(categories.first { it.id == CategoryId.Sleep }.teaser is CategoryTeaser.NoEntries)
        assertTrue(categories.first { it.id == CategoryId.Body }.teaser is CategoryTeaser.NoEntries)
        // Medical, too: no records → no card (nothing on Android or iOS fills it).
        assertTrue(categories.none { it.id == CategoryId.Medical })
        assertEquals(5, categories.size) // Cycle and Medical hidden → five cards
    }

    @Test
    fun cycleCardAppearsOnlyWithData() {
        val today = LocalDate(2026, 5, 24)
        val cycle = FitProjection.cycleDays(
            listOf(
                FitProjection.MenstruationDayInput(LocalDate(2026, 5, 10), "Medium", periodStart = true),
                FitProjection.MenstruationDayInput(LocalDate(2026, 5, 11), "Light", periodStart = false),
            ),
        )
        val withCycle = FitProjection.categories(emptyList(), emptyList(), cycle, emptyList(), emptyList(), emptyList(), today)
        assertEquals(6, withCycle.size) // Cycle now present (Medical still hidden: no records)
        // Anchor = the period-start day (14 days before today) → cycle day 15.
        assertEquals(CategoryTeaser.CycleDay(15), withCycle.first { it.id == CategoryId.Cycle }.teaser)
    }

    @Test
    fun categoriesReportNoEntriesWhenNoWorkouts() {
        val categories = FitProjection.categories(emptyList(), nutrition = emptyList(), cycle = emptyList(), sleep = emptyList(), samples = emptyList(), medical = emptyList(), today = LocalDate(2026, 1, 1))
        assertTrue(categories.first { it.id == CategoryId.Activity }.teaser is CategoryTeaser.NoEntries)
        assertTrue(categories.first { it.id == CategoryId.Nutrition }.teaser is CategoryTeaser.NoEntries)
    }

    @Test
    fun nutritionTeaserReflectsTodayEnergy() {
        val today = LocalDate(2026, 5, 24)
        val nutrition = FitProjection.nutritionDays(
            listOf(
                FitProjection.DailyNutritionInput(LocalDate(2026, 5, 23), energyKj = 7_000, hydrationMl = 1_800),
                FitProjection.DailyNutritionInput(today, energyKj = 8_400, hydrationMl = 2_100),
            ),
        )
        assertEquals(2, nutrition.size)
        assertEquals(today, nutrition.last().date) // oldest-first (trend order)
        val card = FitProjection.categories(emptyList(), nutrition, emptyList(), emptyList(), emptyList(), emptyList(), today)
            .first { it.id == CategoryId.Nutrition }
        assertEquals(CategoryTeaser.Energy(8_400), card.teaser) // newest day's energy
    }

    @Test
    fun samplesMapMetricsAndDropUnknown() {
        val samples = FitProjection.samples(
            listOf(
                SampleInput("Weight", 74.2, "kg", dayBase, "HealthConnect", resting = false),
                SampleInput("HeartRate", 62.0, "bpm", dayBase, "HealthConnect", resting = true),
                SampleInput("Vo2Max", 48.0, "ml/kg/min", dayBase, "HealthConnect", resting = false),   // unmodelled → dropped
            ),
            utc,
        )
        assertEquals(setOf(Metric.Weight, Metric.HeartRate), samples.map { it.metric }.toSet())
        assertEquals(true, samples.first { it.metric == Metric.HeartRate }.resting)
        assertEquals(74.2, samples.first { it.metric == Metric.Weight }.value)
    }

    @Test
    fun latestPerMetricPicksNewestByTime() {
        val dayMs = 24 * 3600 * 1000L
        val samples = FitProjection.samples(
            listOf(
                SampleInput("Weight", 75.0, "kg", dayBase, "HealthConnect", resting = false),          // older
                SampleInput("Weight", 74.2, "kg", dayBase + dayMs, "HealthConnect", resting = false),  // newer
                SampleInput("HeartRate", 60.0, "bpm", dayBase, "HealthConnect", resting = true),
            ),
            utc,
        )
        assertEquals(3, samples.size) // the time-series retains every reading (§12 B2)
        val latest = FitProjection.latestPerMetric(samples)
        assertEquals(2, latest.size) // one per (metric, resting)
        assertEquals(74.2, latest.first { it.metric == Metric.Weight }.value) // newest weight wins
    }

    @Test
    fun medicalRecordsMapKindsAndSortNewestFirst() {
        val records = FitProjection.medicalRecords(
            listOf(
                MedicalRecordInput("old", "Condition", "Asthma", "", LocalDate(2018, 9, 12), "Charité", "HealthConnect"),
                MedicalRecordInput("new", "Medication", "Cetirizine", "10 mg", LocalDate(2026, 3, 2), "Dr. N", "HealthConnect"),
            ),
        )
        assertEquals(listOf("Cetirizine", "Asthma"), records.map { it.title })   // newest effective-date first
        assertEquals(MedicalRecordKind.Medication, records.first().kind)
        assertEquals(MedicalRecordKind.Condition, FitProjection.medicalKind("Nonsense"))   // unknown → Condition
    }

    @Test
    fun bodyVitalsAndMedicalTeasersReflectStoredData() {
        val today = LocalDate(2026, 5, 24)
        val samples = FitProjection.samples(
            listOf(
                SampleInput("Weight", 74.2, "kg", dayBase, "HealthConnect", resting = false),
                SampleInput("HeartRate", 62.0, "bpm", dayBase, "HealthConnect", resting = true),
            ),
            utc,
        )
        val medical = FitProjection.medicalRecords(
            listOf(MedicalRecordInput("m1", "Allergy", "Pollen", "", LocalDate(2019, 4, 1), "", "HealthConnect")),
        )
        val categories = FitProjection.categories(emptyList(), emptyList(), emptyList(), emptyList(), samples, medical, today)
        assertEquals(CategoryTeaser.Weight(74.2), categories.first { it.id == CategoryId.Body }.teaser)
        assertEquals(CategoryTeaser.RestingHeartRate(62), categories.first { it.id == CategoryId.Vitals }.teaser)
        assertEquals(CategoryTeaser.RecordCount(1), categories.first { it.id == CategoryId.Medical }.teaser)
    }

    @Test
    fun vitalsTeaserFallsBackToBloodOxygenThenRespiratoryRate() {
        val today = LocalDate(2026, 5, 24)
        fun vitals(vararg inputs: SampleInput) =
            FitProjection.categories(emptyList(), emptyList(), emptyList(), emptyList(), FitProjection.samples(inputs.toList(), utc), emptyList(), today)
                .first { it.id == CategoryId.Vitals }

        val spo2 = SampleInput("BloodOxygen", 97.6, "%", dayBase, "HealthConnect", resting = false)
        val rr = SampleInput("RespiratoryRate", 14.5, "/min", dayBase, "HealthConnect", resting = false)
        // No resting HR: SpO₂ (rounded) leads, and the card is not dimmed as empty.
        vitals(spo2, rr).let { assertEquals(CategoryTeaser.BloodOxygen(98), it.teaser); assertFalse(it.isEmpty) }
        assertEquals(CategoryTeaser.RespiratoryRate(14.5), vitals(rr).teaser)
        // A non-resting heart rate (a workout's) is not a Vitals teaser.
        assertEquals(CategoryTeaser.NoEntries, vitals(SampleInput("HeartRate", 140.0, "bpm", dayBase, "HealthConnect", resting = false)).teaser)
    }

    @Test
    fun sleepMapsStagesSourceAndDropsZeroMinuteStages() {
        val session = FitProjection.sleep(
            SleepSessionInput(
                date = LocalDate(2026, 5, 24),
                startMs = dayBase, endMs = dayBase + 7 * hour,
                durationSec = 7 * 3600, source = "HealthConnect",
                awakeMin = 14, remMin = 96, lightMin = 232, deepMin = 0,   // deep unknown → dropped
            ),
            utc,
        )
        assertEquals(Source.HealthConnect, session.source)
        assertEquals(
            listOf(SleepStageKind.Awake, SleepStageKind.Rem, SleepStageKind.Light),
            session.stages.map { it.stage },
        )
        assertEquals(96, session.stages.first { it.stage == SleepStageKind.Rem }.minutes)
    }

    @Test
    fun sleepCategoryTeaserReflectsLastNight() {
        val today = LocalDate(2026, 5, 24)
        val sessions = FitProjection.sleeps(
            listOf(
                SleepSessionInput(LocalDate(2026, 5, 22), dayBase, dayBase + 6 * hour, 6 * 3600, "HealthConnect", 10, 80, 200, 70),
                SleepSessionInput(today, dayBase, dayBase + 7 * hour, 7 * 3600 + 12 * 60, "HealthConnect", 14, 96, 232, 90),
            ),
            utc,
        )
        // Newest night first.
        assertEquals(today, sessions.first().date)

        val sleepCard = FitProjection.categories(emptyList(), emptyList(), emptyList(), sessions, emptyList(), emptyList(), today).first { it.id == CategoryId.Sleep }
        assertEquals(CategoryTeaser.SleepDuration(7 * 60 + 12), sleepCard.teaser)   // 432 min last night
    }

    @Test
    fun projectGeoRoute_convertsE7ToRealDegrees_forTheBasemap() {
        val geo = FitProjection.projectGeoRoute(
            listOf(
                RouteSample(tSec = 0, latE7 = 437696000, lngE7 = 112558000), // 43.7696, 11.2558 (Firenze)
                RouteSample(tSec = 5, latE7 = 437700000, lngE7 = 112560000),
            ),
        )
        assertEquals(2, geo.size)
        assertEquals(43.7696, geo[0].latitude, 1e-6)
        assertEquals(11.2558, geo[0].longitude, 1e-6)
        assertTrue(FitProjection.projectGeoRoute(emptyList()).isEmpty())
    }
}
