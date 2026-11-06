package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CycleEntry
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.FlowLevel
import app.tenshu.fit.model.NutritionDay
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.RoutePoint
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.SleepSession
import app.tenshu.fit.model.SleepStage
import app.tenshu.fit.model.SleepStageKind
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Units
import app.tenshu.fit.model.GeoCoord
import app.tenshu.fit.model.Workout
import app.tenshu.fit.model.WorkoutLap
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import org.tenshu.preference.BiologicalSex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The mock dataset (Ph2, docs/proposals/fit-app.md §6), ported from the design handoff's
 * `mockup/fit-data.js` — same six workouts, same week (Mon 2026-05-18 → Sun 2026-05-24,
 * today = Sunday), same deterministic LCG generators for the dense HR / route / lap
 * series so the Compose screens render the same believable shapes as the mock.
 *
 * One extension over the JS: three additional synthesized weeks of [DailyAggregate]s
 * *before* the mock week, so Home's "vs last week" delta and the category-detail
 * Month range have real content.
 */
object FitMockData {

    val today: LocalDate = LocalDate(2026, 5, 24)

    // ── deterministic generators (ported 1:1 from fit-data.js) ──────────────────────

    /** The mock's tiny LCG — deterministic across runs and platforms. */
    private class Rnd(private var s: Long) {
        fun next(): Double {
            s = (s * 9301 + 49297) % 233280
            return s.toDouble() / 233280
        }
    }

    /** Heart-rate curve: warmup ramp, a body with optional intervals, a cooldown tail. */
    fun hrSeries(durationSec: Int, base: Int, peak: Int, intervals: Int = 0, seed: Long = 1): List<HrPoint> {
        val points = mutableListOf<HrPoint>()
        val step = max(15, (durationSec / 90.0).roundToInt())
        val rnd = Rnd(seed)
        var t = 0
        while (t <= durationSec) {
            val p = t.toDouble() / durationSec
            var v = when {
                p < 0.12 -> base + (peak - base) * 0.55 * (p / 0.12)
                p > 0.9 -> peak - (peak - base) * 0.7 * ((p - 0.9) / 0.1)
                else -> {
                    var body = base + (peak - base) * (0.72 + 0.18 * sin(p * PI))
                    if (intervals > 0) body += (peak - base) * 0.22 * sin(p * intervals * PI * 2)
                    body
                }
            }
            v += (rnd.next() - 0.5) * 6
            points += HrPoint(t, min(peak + 4.0, max(base - 8.0, v)).roundToInt())
            t += step
        }
        return points
    }

    /** Abstract route polyline: a wandering walk pulled gently to centre, 0..1 space. */
    fun route(seed: Long = 1, n: Int = 46): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        val rnd = Rnd(seed)
        var x = 0.12
        var y = 0.5
        var ang = 0.3
        repeat(n) {
            ang += (rnd.next() - 0.5) * 1.1
            x += cos(ang) * 0.028
            y += sin(ang) * 0.028
            x += (0.5 - x) * 0.02
            y += (0.5 - y) * 0.02
            points += RoutePoint(
                x = max(0.05, min(0.95, x)).toFloat(),
                y = max(0.08, min(0.92, y)).toFloat(),
            )
        }
        return points
    }

    /** Even per-km laps with slight pace variance, derived from total distance/time. */
    fun evenLaps(distanceM: Int, durationSec: Int, paceVarSec: Int = 8, seed: Long = 3): List<WorkoutLap> {
        val fullKm = floor(distanceM / 1000.0).toInt()
        val basePace = durationSec / (distanceM / 1000.0)
        val rnd = Rnd(seed)
        val laps = mutableListOf<WorkoutLap>()
        var hr = 138
        for (i in 0 until fullKm) {
            val pace = (basePace + (rnd.next() - 0.45) * paceVarSec * 2).roundToInt()
            hr += ((rnd.next() - 0.4) * 6).roundToInt()
            laps += WorkoutLap(index = i + 1, distanceM = 1000, durationSec = pace, avgHr = hr, paceSecPerKm = pace)
        }
        val remainder = distanceM - fullKm * 1000
        if (remainder > 60) {
            val pace = basePace.roundToInt()
            laps += WorkoutLap(
                index = fullKm + 1,
                distanceM = remainder,
                durationSec = (pace * (remainder / 1000.0)).roundToInt(),
                avgHr = hr + 4,
                paceSecPerKm = pace,
                partial = true,
            )
        }
        return laps
    }

    // A compact real-GPS demo route: an Arno-embankment loop through central Firenze, sited inside
    // the bundled Firenze extract so the offline/streamed basemap (WorkoutRouteMap) renders real
    // tiles under the line. Only one mock workout carries GPS — the rest stay schematic (Health
    // Connect / Google Fit rarely expose per-fix tracks, so an empty geoRoute is the common case).
    private val firenzeArnoRun: List<GeoCoord> = listOf(
        GeoCoord(43.7690, 11.2470),
        GeoCoord(43.7685, 11.2490),
        GeoCoord(43.7682, 11.2510),
        GeoCoord(43.7679, 11.2531),
        GeoCoord(43.7677, 11.2552),
        GeoCoord(43.7674, 11.2573),
        GeoCoord(43.7688, 11.2585),
        GeoCoord(43.7705, 11.2570),
        GeoCoord(43.7712, 11.2545),
        GeoCoord(43.7708, 11.2515),
        GeoCoord(43.7700, 11.2490),
        GeoCoord(43.7695, 11.2472),
    )

    // ── the mock week: Mon 2026-05-18 → Sun 2026-05-24 ──────────────────────────────

    val workouts: List<Workout> = listOf(
        Workout(
            workoutId = "w-sun-run",
            activityType = ActivityType.Running,
            source = Source.Recorded,
            title = "Morning run",
            device = "Recorded in Fit · Polar H10",
            start = LocalDateTime(2026, 5, 24, 7, 14),
            end = LocalDateTime(2026, 5, 24, 7, 46),
            durationSec = 32 * 60,
            distanceM = 5200,
            activeEnergyKj = 1747,
            avgHr = 148,
            maxHr = 172,
            ascentM = 38,
            avgPaceSecPerKm = (32 * 60 / 5.2).roundToInt(),
            hasRoute = true,
            route = route(7),
            geoRoute = firenzeArnoRun,
            hrSeries = hrSeries(32 * 60, base = 96, peak = 172, seed = 7),
            laps = evenLaps(5200, 32 * 60, paceVarSec = 10, seed = 5),
        ),
        Workout(
            workoutId = "w-sat-ride",
            activityType = ActivityType.Cycling,
            source = Source.HealthConnect,
            title = "Coast loop",
            device = "Wahoo ELEMNT · via Health Connect",
            start = LocalDateTime(2026, 5, 23, 9, 30),
            end = LocalDateTime(2026, 5, 23, 10, 42),
            durationSec = 72 * 60,
            distanceM = 28400,
            activeEnergyKj = 2436,
            avgHr = 132,
            maxHr = 158,
            ascentM = 214,
            avgSpeedKmh = 23.7,
            hasRoute = true,
            route = route(19),
            hrSeries = hrSeries(72 * 60, base = 92, peak = 158, seed = 19),
        ),
        Workout(
            workoutId = "w-fri-strength",
            activityType = ActivityType.Strength,
            source = Source.HealthConnect,
            title = "Full body",
            device = "Health Connect · Hevy",
            start = LocalDateTime(2026, 5, 22, 18, 5),
            end = LocalDateTime(2026, 5, 22, 18, 52),
            durationSec = 47 * 60,
            activeEnergyKj = 1130,
            avgHr = 118,
            maxHr = 146,
            hrSeries = hrSeries(47 * 60, base = 84, peak = 146, intervals = 6, seed = 22),
        ),
        Workout(
            workoutId = "w-thu-walk",
            activityType = ActivityType.Walking,
            source = Source.Recorded,
            title = "Evening walk",
            device = "Recorded in Fit",
            start = LocalDateTime(2026, 5, 21, 19, 20),
            end = LocalDateTime(2026, 5, 21, 19, 58),
            durationSec = 38 * 60,
            distanceM = 3100,
            activeEnergyKj = 640,
            avgHr = 104,
            maxHr = 121,
            ascentM = 12,
            hasRoute = true,
            route = route(31),
            hrSeries = hrSeries(38 * 60, base = 78, peak = 121, seed = 31),
            laps = evenLaps(3100, 38 * 60, paceVarSec = 14, seed = 8),
        ),
        Workout(
            workoutId = "w-wed-run",
            activityType = ActivityType.Running,
            source = Source.File,
            title = "Interval session",
            device = "Imported · intervals.gpx",
            start = LocalDateTime(2026, 5, 20, 6, 40),
            end = LocalDateTime(2026, 5, 20, 7, 14),
            durationSec = 34 * 60,
            distanceM = 6400,
            activeEnergyKj = 1890,
            avgHr = 154,
            maxHr = 181,
            ascentM = 22,
            avgPaceSecPerKm = (34 * 60 / 6.4).roundToInt(),
            hasRoute = true,
            route = route(41),
            hrSeries = hrSeries(34 * 60, base = 98, peak = 181, intervals = 8, seed = 41),
            laps = evenLaps(6400, 34 * 60, paceVarSec = 22, seed = 12),
        ),
        Workout(
            workoutId = "w-mon-ride",
            activityType = ActivityType.Cycling,
            source = Source.Recorded,
            title = "Commute",
            device = "Recorded in Fit",
            start = LocalDateTime(2026, 5, 18, 17, 35),
            end = LocalDateTime(2026, 5, 18, 18, 16),
            durationSec = 41 * 60,
            distanceM = 15200,
            activeEnergyKj = 1310,
            avgHr = 126,
            maxHr = 149,
            ascentM = 96,
            avgSpeedKmh = 22.2,
            hasRoute = true,
            route = route(55),
            hrSeries = hrSeries(41 * 60, base = 88, peak = 149, seed = 55),
        ),
    )

    private val mockWeek: List<DailyAggregate> = listOf(
        DailyAggregate(LocalDate(2026, 5, 18), steps = 9120, distanceM = 6900, activeEnergyKj = 2180, activeMinutes = 46),
        DailyAggregate(LocalDate(2026, 5, 19), steps = 5240, distanceM = 3800, activeEnergyKj = 1010, activeMinutes = 18),
        DailyAggregate(LocalDate(2026, 5, 20), steps = 11840, distanceM = 9200, activeEnergyKj = 2980, activeMinutes = 58),
        DailyAggregate(LocalDate(2026, 5, 21), steps = 8360, distanceM = 6100, activeEnergyKj = 1720, activeMinutes = 39),
        DailyAggregate(LocalDate(2026, 5, 22), steps = 7010, distanceM = 5000, activeEnergyKj = 1980, activeMinutes = 44),
        DailyAggregate(LocalDate(2026, 5, 23), steps = 6480, distanceM = 4700, activeEnergyKj = 2510, activeMinutes = 51),
        // Today, in progress.
        DailyAggregate(LocalDate(2026, 5, 24), steps = 6120, distanceM = 4500, activeEnergyKj = 1940, activeMinutes = 42),
    )

    /** Three synthesized weeks before the mock week, then the mock week. Oldest first. */
    val dailyAggregates: List<DailyAggregate> = buildList {
        val rnd = Rnd(97)
        val firstMockDay = mockWeek.first().date
        for (daysBack in 21 downTo 1) {
            val steps = (4200 + rnd.next() * 7400).roundToInt()
            add(
                DailyAggregate(
                    date = firstMockDay.minus(daysBack, DateTimeUnit.DAY),
                    steps = steps,
                    distanceM = (steps * 0.72).roundToInt(),
                    activeEnergyKj = (850 + rnd.next() * 2100).roundToInt(),
                    activeMinutes = (11 + rnd.next() * 48).roundToInt(),
                ),
            )
        }
        addAll(mockWeek)
    }

    /** A week of nutrition (energy kJ + hydration mL), newest dated [today] (§12 B3a). */
    val nutrition: List<NutritionDay> = listOf(
        7_740 to 1_900, 8_560 to 2_300, 7_120 to 1_600, 9_040 to 2_500,
        8_200 to 2_100, 6_680 to 1_400, 8_900 to 2_200,
    ).mapIndexed { i, (energyKj, hydrationMl) ->
        NutritionDay(today.minus(i, DateTimeUnit.DAY), energyKj, hydrationMl)
    }

    /** A period ~2 weeks ago (§12 B3b) — the day-14 entry is the period start (cycle-day anchor). */
    val cycle: List<CycleEntry> = listOf(
        14 to FlowLevel.Medium, 13 to FlowLevel.Heavy, 12 to FlowLevel.Medium,
        11 to FlowLevel.Light, 10 to FlowLevel.Light,
    ).map { (daysAgo, flow) ->
        CycleEntry(today.minus(daysAgo, DateTimeUnit.DAY), flow, periodStart = daysAgo == 14)
    }

    val sleepSessions: List<SleepSession> = listOf(
        SleepSession(
            date = LocalDate(2026, 5, 24),
            start = LocalDateTime(2026, 5, 23, 23, 18),
            end = LocalDateTime(2026, 5, 24, 6, 30),
            durationSec = 7 * 3600 + 12 * 60,
            source = Source.HealthConnect,
            stages = listOf(
                SleepStage(SleepStageKind.Awake, 14),
                SleepStage(SleepStageKind.Rem, 96),
                SleepStage(SleepStageKind.Light, 232),
                SleepStage(SleepStageKind.Deep, 90),
            ),
        ),
    )

    val samplesLatest: List<SampleLatest> = listOf(
        SampleLatest(Metric.HeartRate, 62.0, "bpm", LocalDateTime(2026, 5, 24, 8, 2), Source.HealthConnect, resting = true),
        SampleLatest(Metric.Weight, 74.2, "kg", LocalDateTime(2026, 5, 22, 7, 10), Source.HealthConnect),
        SampleLatest(Metric.BloodOxygen, 98.0, "%", LocalDateTime(2026, 5, 24, 6, 31), Source.HealthConnect),
        SampleLatest(Metric.RespiratoryRate, 14.0, "/min", LocalDateTime(2026, 5, 24, 6, 31), Source.HealthConnect),
    )

    /**
     * A time-series of readings per metric (§12 B2) — the Body/Vitals detail history. Newest reading
     * per metric matches [samplesLatest] (weight 74.2 kg, resting HR 62 bpm) so the teaser + the
     * detail header agree; earlier readings give the history list something to show.
     */
    val sampleHistory: List<SampleLatest> = listOf(
        // One reading each, matching [samplesLatest] — the importer keeps only the newest.
        SampleLatest(Metric.BloodOxygen, 98.0, "%", LocalDateTime(2026, 5, 24, 6, 31), Source.HealthConnect),
        SampleLatest(Metric.RespiratoryRate, 14.0, "/min", LocalDateTime(2026, 5, 24, 6, 31), Source.HealthConnect),
        SampleLatest(Metric.Weight, 74.2, "kg", LocalDateTime(2026, 5, 22, 7, 10), Source.HealthConnect),
        SampleLatest(Metric.Weight, 74.6, "kg", LocalDateTime(2026, 5, 15, 7, 12), Source.HealthConnect),
        SampleLatest(Metric.Weight, 75.1, "kg", LocalDateTime(2026, 5, 8, 7, 5), Source.HealthConnect),
        SampleLatest(Metric.Weight, 74.9, "kg", LocalDateTime(2026, 5, 1, 7, 20), Source.HealthConnect),
        SampleLatest(Metric.Weight, 75.4, "kg", LocalDateTime(2026, 4, 24, 7, 8), Source.HealthConnect),
        SampleLatest(Metric.HeartRate, 62.0, "bpm", LocalDateTime(2026, 5, 24, 8, 2), Source.HealthConnect, resting = true),
        SampleLatest(Metric.HeartRate, 60.0, "bpm", LocalDateTime(2026, 5, 21, 8, 6), Source.HealthConnect, resting = true),
        SampleLatest(Metric.HeartRate, 63.0, "bpm", LocalDateTime(2026, 5, 17, 8, 1), Source.HealthConnect, resting = true),
        SampleLatest(Metric.HeartRate, 61.0, "bpm", LocalDateTime(2026, 5, 13, 7, 58), Source.HealthConnect, resting = true),
        SampleLatest(Metric.HeartRate, 64.0, "bpm", LocalDateTime(2026, 5, 9, 8, 4), Source.HealthConnect, resting = true),
    )

    val medicalRecords: List<MedicalRecord> = listOf(
        MedicalRecord(MedicalRecordKind.Medication, "Cetirizine", "10 mg tablet · once daily", LocalDate(2026, 3, 2), "Dr. A. Neumann · Charité", Source.HealthConnect),
        MedicalRecord(MedicalRecordKind.Medication, "Vitamin D3", "1000 IU · once daily", LocalDate(2025, 11, 20), "Self-reported", Source.File),
        MedicalRecord(MedicalRecordKind.Immunization, "Influenza (seasonal)", "Administered", LocalDate(2025, 10, 14), "Apotheke am Markt", Source.HealthConnect),
        MedicalRecord(MedicalRecordKind.Immunization, "Tetanus / Tdap booster", "Administered", LocalDate(2023, 6, 8), "Hausarztpraxis Kreuzberg", Source.HealthConnect),
        MedicalRecord(MedicalRecordKind.Allergy, "Pollen (grass)", "Seasonal rhinitis · mild", LocalDate(2019, 4, 1), "Dr. A. Neumann · Charité", Source.HealthConnect),
        MedicalRecord(MedicalRecordKind.Condition, "Mild asthma", "Exercise-induced · inhaler as needed", LocalDate(2018, 9, 12), "Charité Pulmonology", Source.HealthConnect),
    )

    val goals = Goals(activeMinutes = 30, steps = 8000)

    val profile = FitProfile(
        name = "You",
        heightCm = 178,
        weightKg = 74.2,
        birthDate = LocalDate(1992, 5, 30),
        sex = BiologicalSex.MALE,
        units = Units.Metric,
    )

    /** Estimated per-category counts on the onboarding permissions step. */
    val importCategories: List<ImportCategory> = listOf(
        ImportCategory(CategoryId.Activity, estimatedCount = 1284),
        ImportCategory(CategoryId.Body, estimatedCount = 96),
        ImportCategory(CategoryId.Vitals, estimatedCount = 412),
        ImportCategory(CategoryId.Sleep, estimatedCount = 208),
        ImportCategory(CategoryId.Nutrition, estimatedCount = 0),
        ImportCategory(CategoryId.Cycle, estimatedCount = 0),
        ImportCategory(CategoryId.Medical, estimatedCount = 6),
    )
}
