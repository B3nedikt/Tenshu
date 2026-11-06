package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * ⚠⚠ **Tests and previews only — never a shipping binding** (`fit-app.md` §9.15). Its outcome is
 * [ImportOutcome.Sample], which `FitProjectionRepository` refuses to persist: an import over this
 * importer writes nothing. A platform without a live source binds [NoHealthSource] instead. A test
 * that needs the fixture *in the store* writes [ImportOutcome.health] through [HealthImportWriter]
 * itself, which says out loud that it is seeding.
 *
 * A **deterministic in-process [HealthImporter]**: a realistic recent-history fixture (a week of
 * daily activity + sleep, the latest per-metric samples, a few workouts, an imported medical
 * history), dated relative to *today*, in the [ImportedHealth] shape every importer produces.
 * Advertised as [ImportSource.HealthConnect] (it emulates a Health-Connect-shaped import).
 */
class ReferenceHealthImporter(
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : HealthImporter {

    /** A fixture is never a source to refresh from unattended (see [HealthImporter.autoRefreshReady]). */
    override suspend fun autoRefreshReady(): Boolean = false

    override fun availableSources(): List<ImportSource> = listOf(ImportSource.HealthConnect)

    /**
     * Always an [ImportOutcome.Sample] — this importer *is* the fixture. Saying so is the whole
     * point: it is what lets `FitProjectionRepository` tell invented numbers from real ones and
     * refuse to persist them.
     */
    override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome =
        ImportOutcome.Sample(fixture(categories))

    private fun fixture(categories: Set<CategoryId>): ImportedHealth {
        val today = Clock.System.todayIn(zone)

        val dailyActivities = if (CategoryId.Activity in categories) {
            SEED_DAILY.map { d ->
                ImportedDailyActivity(
                    date = today.minus(d.daysAgo, DateTimeUnit.DAY).toString(),
                    steps = d.steps, distanceM = d.distanceM, activeEnergyKj = d.activeEnergyKj, activeMinutes = d.activeMinutes,
                )
            }
        } else emptyList()

        val workouts = if (CategoryId.Activity in categories) {
            SEED_WORKOUTS.map { w ->
                val date = today.minus(w.daysAgo, DateTimeUnit.DAY)
                val startMs = date.atTime(LocalTime(w.hour, w.minute)).toInstant(zone).toEpochMilliseconds()
                ImportedWorkout(
                    workoutId = w.id, activityType = w.activityType, source = w.source,
                    startMs = startMs, endMs = startMs + w.durationSec * 1_000, durationSec = w.durationSec,
                    title = w.title, distanceM = w.distanceM, activeEnergyKj = w.activeEnergyKj, avgHr = w.avgHr,
                )
            }
        } else emptyList()

        val sleepSessions = if (CategoryId.Sleep in categories) {
            SEED_SLEEP.map { s ->
                val wakeDate = today.minus(s.daysAgo, DateTimeUnit.DAY)
                val bedDate = wakeDate.minus(1, DateTimeUnit.DAY)
                val durationMin = s.awakeMin + s.remMin + s.lightMin + s.deepMin
                val startMs = bedDate.atTime(LocalTime(s.bedHour, s.bedMinute)).toInstant(zone).toEpochMilliseconds()
                ImportedSleepSession(
                    date = wakeDate.toString(), startMs = startMs, endMs = startMs + durationMin * 60_000,
                    durationSec = durationMin * 60, source = s.source,
                    awakeMin = s.awakeMin, remMin = s.remMin, lightMin = s.lightMin, deepMin = s.deepMin,
                )
            }
        } else emptyList()

        val dailyNutritions = if (CategoryId.Nutrition in categories) {
            SEED_NUTRITION.map { n ->
                ImportedDailyNutrition(
                    date = today.minus(n.daysAgo, DateTimeUnit.DAY).toString(),
                    energyKj = n.energyKj, hydrationMl = n.hydrationMl,
                )
            }
        } else emptyList()

        val menstruationDays = if (CategoryId.Cycle in categories) {
            SEED_CYCLE.map { c ->
                ImportedMenstruationDay(
                    date = today.minus(c.daysAgo, DateTimeUnit.DAY).toString(),
                    flow = c.flow, periodStart = c.periodStart,
                )
            }
        } else emptyList()

        // Body = weight; Vitals = HR / SpO₂ / RR. A sample joins the import if its category is selected.
        val samples = SEED_SAMPLES
            .filter { s -> (if (s.metric in BODY_METRICS) CategoryId.Body else CategoryId.Vitals) in categories }
            .map { s ->
                val timeMs = today.minus(s.daysAgo, DateTimeUnit.DAY)
                    .atTime(LocalTime(s.hour, s.minute)).toInstant(zone).toEpochMilliseconds()
                ImportedSample(s.metric, s.value, s.unit, timeMs, s.source, s.resting)
            }

        val medicalRecords = if (CategoryId.Medical in categories) {
            SEED_MEDICAL.map { m -> ImportedMedicalRecord(m.recordId, m.kind, m.title, m.detail, m.effectiveDate, m.issuer, m.source) }
        } else emptyList()

        return ImportedHealth(
            workouts = workouts,
            dailyActivities = dailyActivities,
            dailyNutritions = dailyNutritions,
            menstruationDays = menstruationDays,
            sleepSessions = sleepSessions,
            samples = samples,
            medicalRecords = medicalRecords,
        )
    }

    private data class DailySeed(val daysAgo: Int, val steps: Long, val distanceM: Long, val activeEnergyKj: Long, val activeMinutes: Long)
    private data class NutritionSeed(val daysAgo: Int, val energyKj: Long, val hydrationMl: Long)
    private data class CycleSeed(val daysAgo: Int, val flow: String, val periodStart: Boolean = false)
    private data class SleepSeed(val daysAgo: Int, val bedHour: Int, val bedMinute: Int, val awakeMin: Long, val remMin: Long, val lightMin: Long, val deepMin: Long, val source: String = "HealthConnect")
    private data class SampleSeed(val metric: String, val value: String, val unit: String, val daysAgo: Int, val hour: Int, val minute: Int, val source: String = "HealthConnect", val resting: Boolean = false)
    private data class MedicalSeed(val recordId: String, val kind: String, val title: String, val detail: String, val effectiveDate: String, val issuer: String, val source: String)
    private data class WorkoutSeed(val id: String, val activityType: String, val source: String, val title: String, val daysAgo: Int, val hour: Int, val minute: Int, val durationSec: Long, val distanceM: Long = 0, val activeEnergyKj: Long = 0, val avgHr: Long = 0)

    companion object {
        const val WORKOUT_TODAY_RUN = "seed-workout-run-today"
        const val WORKOUT_YESTERDAY_WALK = "seed-workout-walk"
        const val WORKOUT_CYCLE = "seed-workout-cycle"
        const val WORKOUT_STRENGTH = "seed-workout-strength"

        private val SEED_DAILY = listOf(
            DailySeed(daysAgo = 0, steps = 6_120, distanceM = 4_500, activeEnergyKj = 1_940, activeMinutes = 42),
            DailySeed(daysAgo = 1, steps = 6_480, distanceM = 4_700, activeEnergyKj = 2_510, activeMinutes = 51),
            DailySeed(daysAgo = 2, steps = 7_010, distanceM = 5_000, activeEnergyKj = 1_980, activeMinutes = 44),
            DailySeed(daysAgo = 3, steps = 8_360, distanceM = 6_100, activeEnergyKj = 1_720, activeMinutes = 39),
            DailySeed(daysAgo = 4, steps = 11_840, distanceM = 9_200, activeEnergyKj = 2_980, activeMinutes = 58),
            DailySeed(daysAgo = 5, steps = 5_240, distanceM = 3_800, activeEnergyKj = 1_010, activeMinutes = 18),
            DailySeed(daysAgo = 6, steps = 9_120, distanceM = 6_900, activeEnergyKj = 2_180, activeMinutes = 46),
        )

        // Energy consumed in kJ (the UI shows kcal); ~1,800–2,200 kcal/day. Hydration in mL.
        private val SEED_NUTRITION = listOf(
            NutritionSeed(daysAgo = 0, energyKj = 7_740, hydrationMl = 1_900),
            NutritionSeed(daysAgo = 1, energyKj = 8_560, hydrationMl = 2_300),
            NutritionSeed(daysAgo = 2, energyKj = 7_120, hydrationMl = 1_600),
            NutritionSeed(daysAgo = 3, energyKj = 9_040, hydrationMl = 2_500),
            NutritionSeed(daysAgo = 4, energyKj = 8_200, hydrationMl = 2_100),
            NutritionSeed(daysAgo = 5, energyKj = 6_680, hydrationMl = 1_400),
            NutritionSeed(daysAgo = 6, energyKj = 8_900, hydrationMl = 2_200),
        )

        // A period ~2 weeks ago → the teaser reads "Cycle day ~15". Day 14-ago is the period start.
        private val SEED_CYCLE = listOf(
            CycleSeed(daysAgo = 14, flow = "Medium", periodStart = true),
            CycleSeed(daysAgo = 13, flow = "Heavy"),
            CycleSeed(daysAgo = 12, flow = "Medium"),
            CycleSeed(daysAgo = 11, flow = "Light"),
            CycleSeed(daysAgo = 10, flow = "Light"),
        )

        private val SEED_SLEEP = listOf(
            SleepSeed(daysAgo = 0, bedHour = 23, bedMinute = 18, awakeMin = 14, remMin = 96, lightMin = 232, deepMin = 90),
            SleepSeed(daysAgo = 1, bedHour = 23, bedMinute = 42, awakeMin = 18, remMin = 84, lightMin = 210, deepMin = 78),
            SleepSeed(daysAgo = 2, bedHour = 22, bedMinute = 56, awakeMin = 11, remMin = 104, lightMin = 246, deepMin = 96),
            SleepSeed(daysAgo = 3, bedHour = 23, bedMinute = 51, awakeMin = 22, remMin = 72, lightMin = 198, deepMin = 64),
            SleepSeed(daysAgo = 4, bedHour = 23, bedMinute = 5, awakeMin = 9, remMin = 110, lightMin = 258, deepMin = 102, source = "Recorded"),
            SleepSeed(daysAgo = 5, bedHour = 23, bedMinute = 34, awakeMin = 16, remMin = 90, lightMin = 224, deepMin = 82),
            SleepSeed(daysAgo = 6, bedHour = 22, bedMinute = 48, awakeMin = 12, remMin = 100, lightMin = 240, deepMin = 88),
        )

        /** Metrics the Body category owns; everything else in [SEED_SAMPLES] is Vitals. */
        private val BODY_METRICS = setOf("Weight", "Height")

        // Weight + resting-HR ride as a time-series (§12 B2) — distinct-time readings retain as
        // separate rows, so the Body/Vitals detail has history to chart. BloodOxygen / RespiratoryRate
        // stay single latest readings (not surfaced in a detail yet).
        private val SEED_SAMPLES = listOf(
            SampleSeed("Weight", "74.2", "kg", daysAgo = 2, hour = 7, minute = 10),
            SampleSeed("Weight", "74.6", "kg", daysAgo = 9, hour = 7, minute = 12),
            SampleSeed("Weight", "75.1", "kg", daysAgo = 16, hour = 7, minute = 5),
            SampleSeed("Weight", "74.9", "kg", daysAgo = 23, hour = 7, minute = 20),
            SampleSeed("Weight", "75.4", "kg", daysAgo = 30, hour = 7, minute = 8),
            // One height reading, not a series: an adult's height is effectively constant, so the
            // fixture mirrors what the real importers read (newest only) — FP-2, fit-app.md §18.2.
            SampleSeed("Height", "178", "cm", daysAgo = 30, hour = 7, minute = 8),
            SampleSeed("HeartRate", "62", "bpm", daysAgo = 0, hour = 8, minute = 2, resting = true),
            SampleSeed("HeartRate", "60", "bpm", daysAgo = 3, hour = 8, minute = 6, resting = true),
            SampleSeed("HeartRate", "63", "bpm", daysAgo = 7, hour = 8, minute = 1, resting = true),
            SampleSeed("HeartRate", "61", "bpm", daysAgo = 14, hour = 7, minute = 58, resting = true),
            SampleSeed("HeartRate", "64", "bpm", daysAgo = 21, hour = 8, minute = 4, resting = true),
            SampleSeed("BloodOxygen", "98", "%", daysAgo = 0, hour = 6, minute = 31),
            SampleSeed("RespiratoryRate", "14", "/min", daysAgo = 0, hour = 6, minute = 31),
        )

        private val SEED_MEDICAL = listOf(
            MedicalSeed("seed-med-cetirizine", "Medication", "Cetirizine", "10 mg tablet · once daily", "2026-03-02", "Dr. A. Neumann · Charité", "HealthConnect"),
            MedicalSeed("seed-med-vitd3", "Medication", "Vitamin D3", "1000 IU · once daily", "2025-11-20", "Self-reported", "File"),
            MedicalSeed("seed-imm-influenza", "Immunization", "Influenza (seasonal)", "Administered", "2025-10-14", "Apotheke am Markt", "HealthConnect"),
            MedicalSeed("seed-imm-tdap", "Immunization", "Tetanus / Tdap booster", "Administered", "2023-06-08", "Hausarztpraxis Kreuzberg", "HealthConnect"),
            MedicalSeed("seed-alg-pollen", "Allergy", "Pollen (grass)", "Seasonal rhinitis · mild", "2019-04-01", "Dr. A. Neumann · Charité", "HealthConnect"),
            MedicalSeed("seed-cond-asthma", "Condition", "Mild asthma", "Exercise-induced · inhaler as needed", "2018-09-12", "Charité Pulmonology", "HealthConnect"),
        )

        private val SEED_WORKOUTS = listOf(
            WorkoutSeed(WORKOUT_TODAY_RUN, "Running", "Recorded", "Morning run", daysAgo = 0, hour = 7, minute = 14, durationSec = 1_920, distanceM = 5_200, activeEnergyKj = 1_360, avgHr = 152),
            WorkoutSeed(WORKOUT_YESTERDAY_WALK, "Walking", "HealthConnect", "Evening walk", daysAgo = 1, hour = 18, minute = 5, durationSec = 2_820, distanceM = 3_100, activeEnergyKj = 640, avgHr = 98),
            WorkoutSeed(WORKOUT_CYCLE, "Cycling", "Recorded", "River loop", daysAgo = 2, hour = 9, minute = 30, durationSec = 4_320, distanceM = 24_800, activeEnergyKj = 2_480, avgHr = 141),
            WorkoutSeed(WORKOUT_STRENGTH, "Strength", "Manual", "Upper body", daysAgo = 4, hour = 19, minute = 20, durationSec = 2_280, activeEnergyKj = 820, avgHr = 118),
        )
    }
}
