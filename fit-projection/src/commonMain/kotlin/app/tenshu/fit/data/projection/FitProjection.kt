package app.tenshu.fit.data.projection

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategorySummary
import app.tenshu.fit.model.CategoryTeaser
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.GeoCoord
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.LastUpdated
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.CycleEntry
import app.tenshu.fit.model.FlowLevel
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.NutritionDay
import app.tenshu.fit.model.RoutePoint
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.SleepSession
import app.tenshu.fit.model.SleepStage
import app.tenshu.fit.model.SleepStageKind
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.tenshu.health.common.RouteSample
import org.tenshu.health.common.WorkoutStreams
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Instant

/**
 * Pure mapping from the `:health-service` `Workout` store → the Fit app's presentation model
 * (`docs/proposals/fit-app.md` §3 D1 + `timeline-services.md` §6). The heart of the real
 * `FitRepository`: the *lazy* stored `Workout` entities become *materialised* projections
 * (UI [Workout]s, derived per-day [DailyAggregate]s, Browse [CategorySummary]s) — the
 * lazy-vs-materialised seam (AGENTS.md "Open items") in concrete form.
 *
 * Deliberately **pure Kotlin over the lightweight [WorkoutInput]** — no Tenshu entity types,
 * no client — so it unit-tests in isolation. `FitProjectionRepository` extracts these inputs
 * from the entities it reads over the client and calls these functions.
 *
 * **Scope note (thinner than the mock, honestly):** the scaffold `:health-service` stores
 * `Workout` only, so the health data with no workout source has no home yet:
 *  - `steps` has no source → `0` in every derived aggregate (a step/aggregate entity is a later
 *    `:health-service` slice; `0` is honest-absent, rendered as such by the UI).
 *  - `activeMinutes` is derived as **total workout minutes for the day**, not the WHO
 *    moderate+vigorous figure the mock carried (no per-second intensity in the scaffold).
 *  - `samplesLatest` / `sleep` / `medical` have their own stored entities; `laps` has no store
 *    yet → empty. `route` / `hrSeries` ride the `Workout.Streams` blob (decoded on a detail read
 *    via [projectRoute]) — empty on the list projection (byte-less) and for summary-only workouts.
 * These fill in for real as the deferred `:health-service` entities + import seam land (§8).
 */
object FitProjection {

    /** Degrees ⇄ E7 fixed-point scale (see [RouteSample]). */
    private const val E7 = 1e7

    /** Daily-living steps excluded from the active-minutes estimate (see [estimateActiveMinutes]). */
    private const val INCIDENTAL_DAILY_STEPS = 2_000

    /** Steps-per-active-minute conversion for the estimate; calibrated to the app's goal defaults. */
    private const val STEPS_PER_ACTIVE_MINUTE = 200

    /**
     * The flattened `:health-service` `Workout` fields the projection needs — extracted from
     * the read `Entity<Workout>` by the repository. Optional stats use the service's `0`
     * sentinel (unknown); the mapping folds `0` → `null` on the UI model.
     */
    data class WorkoutInput(
        val workoutId: String,
        val activityType: String,
        val source: String,
        val startMs: Long,
        val endMs: Long,
        val durationSec: Long,
        val title: String,
        val distanceM: Long,
        val activeEnergyKj: Long,
        val avgHr: Long,
        val steps: Long = 0,
    )

    /** The flattened `:health-service` `DailyActivity` fields — the stored per-day aggregate. */
    data class DailyActivityInput(
        val date: LocalDate,
        val steps: Int,
        val distanceM: Int,
        val activeEnergyKj: Int,
        val activeMinutes: Int,
    )

    /** The flattened `:health-service` `DailyNutrition` fields — the stored per-day aggregate (§12 B3a). */
    data class DailyNutritionInput(
        val date: LocalDate,
        val energyKj: Int,
        val hydrationMl: Int,
    )

    /** The flattened `:health-service` `MenstruationDay` fields — one logged bleeding day (§12 B3b). */
    data class MenstruationDayInput(
        val date: LocalDate,
        val flow: String,
        val periodStart: Boolean,
    )

    /**
     * The flattened `:health-service` `SleepSession` fields — the stored per-night session. The
     * four stage minutes are plain ints (the fixed [SleepStageKind] set is stored as four scalar
     * fields, not an embedded array); [sleep] reconstructs the `List<SleepStage>` from them.
     */
    data class SleepSessionInput(
        val date: LocalDate,
        val startMs: Long,
        val endMs: Long,
        val durationSec: Int,
        val source: String,
        val awakeMin: Int,
        val remMin: Int,
        val lightMin: Int,
        val deepMin: Int,
    )

    /**
     * The flattened `:health-service` `HealthSample` fields — one reading (the store now retains a
     * time-series per metric, §12 B2). The repository parses the stored decimal-string `value` to
     * [value] (a `Double`) before building this, keeping the projection pure.
     */
    data class SampleInput(
        val metric: String,
        val value: Double,
        val unit: String,
        val timeMs: Long,
        val source: String,
        val resting: Boolean,
    )

    /** The flattened `:health-service` `MedicalRecord` fields — one imported record. */
    data class MedicalRecordInput(
        val recordId: String,
        val kind: String,
        val title: String,
        val detail: String,
        val effectiveDate: LocalDate,
        val issuer: String,
        val source: String,
    )

    /**
     * Projects one workout. [streams] is the decoded `Workout.Streams` blob (empty for a
     * summary-only workout, or on a list projection which never reads it — see [workouts]); its
     * route is folded to the abstract 0..1 polyline the UI renders ([projectRoute]) and its HR
     * series maps straight through. `maxHr` is derived from the HR stream when present.
     */
    fun workout(
        w: WorkoutInput,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        streams: WorkoutStreams = WorkoutStreams.EMPTY,
    ): Workout {
        val route = projectRoute(streams.route)
        val geoRoute = projectGeoRoute(streams.route)
        val hr = streams.heartRate.map { HrPoint(it.tSec, it.bpm) }
        return Workout(
            workoutId = w.workoutId,
            activityType = activityType(w.activityType),
            source = source(w.source),
            title = w.title,
            device = "",                          // provenance device rides the import seam (§8); footer shows source
            start = Instant.fromEpochMilliseconds(w.startMs).toLocalDateTime(zone),
            end = Instant.fromEpochMilliseconds(w.endMs).toLocalDateTime(zone),
            durationSec = w.durationSec.toInt(),
            distanceM = w.distanceM.nonZeroInt(),
            activeEnergyKj = w.activeEnergyKj.nonZeroInt(),
            avgHr = w.avgHr.nonZeroInt(),
            maxHr = hr.maxOfOrNull { it.bpm }?.takeIf { it > 0 },
            steps = w.steps.nonZeroInt(),
            hasRoute = route.isNotEmpty(),
            route = route,
            geoRoute = geoRoute,
            hrSeries = hr,
            // ascentM / pace / laps: further import-seam fields — deferred (§8).
        )
    }

    /**
     * Real GPS ([RouteSample] E7 degrees) → the abstract, map-less 0..1 polyline the UI draws
     * (product constraint: "routes render as an abstract polyline on a neutral surface", no map
     * tiles). Longitude is compressed by `cos(mean latitude)` so the shape keeps its real aspect
     * ratio, then the bounding box is normalised into 0..1 with a **single** span (aspect
     * preserved; `ChartMath.fitRoute` re-frames within the render square). North is up → y flips.
     * Pure — unit-tested independently of any store.
     */
    /**
     * Real GPS ([RouteSample] E7 degrees) → WGS84 [GeoCoord]s for the offline basemap — the
     * map-side counterpart to [projectRoute] (which folds the same samples to the 0..1 schematic).
     * Empty for a summary-only workout, so the detail screen keeps the schematic fallback.
     */
    fun projectGeoRoute(route: List<RouteSample>): List<GeoCoord> =
        route.map { GeoCoord(latitude = it.latE7 / E7, longitude = it.lngE7 / E7) }

    fun projectRoute(route: List<RouteSample>): List<RoutePoint> {
        if (route.isEmpty()) return emptyList()
        val meanLatRad = route.map { it.latE7 / E7 }.average() * PI / 180.0
        val cosLat = cos(meanLatRad)
        val xs = route.map { (it.lngE7 / E7) * cosLat }
        val ys = route.map { it.latE7 / E7 }
        val minX = xs.min(); val minY = ys.min()
        val span = maxOf(xs.max() - minX, ys.max() - minY).takeIf { it > 0.0 } ?: 1.0
        return route.indices.map { i ->
            RoutePoint(
                x = ((xs[i] - minX) / span).toFloat(),
                y = (1.0 - (ys[i] - minY) / span).toFloat(),
            )
        }
    }

    /** All workouts, newest first (History + Home's latest-workout card read this order). */
    fun workouts(inputs: List<WorkoutInput>, zone: TimeZone = TimeZone.currentSystemDefault()): List<Workout> =
        inputs.map { workout(it, zone) }.sortedByDescending { it.start }

    /**
     * The Home/Steps daily aggregates, oldest first — the shape Home's trend bars + today-strip
     * consume. Each day merges the stored [DailyActivityInput] (real steps/distance/energy from a
     * step counter / Health-Connect daily total) with the [workoutDerived] roll-up **per field**:
     * the stored row owns steps and its non-zero stats, falling back to the workout figure where it
     * left a stat at `0`. Active minutes fall back further to a steps estimate ([estimateActiveMinutes])
     * so a workout-less but high-step day still registers. A day with a workout but no stored
     * aggregate uses the derived roll-up (steps `0`).
     */
    fun dailyAggregates(
        stored: List<DailyActivityInput>,
        workouts: List<WorkoutInput>,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<DailyAggregate> {
        val storedByDate = stored.associateBy { it.date }
        val derivedByDate = workoutDerived(workouts, zone).associateBy { it.date }
        return (storedByDate.keys + derivedByDate.keys)
            .sorted()
            .map { date ->
                val storedDay = storedByDate[date]
                val derivedDay = derivedByDate[date]
                when {
                    storedDay == null -> derivedDay!!           // a workout-only day (no stored aggregate)
                    // Stored aggregate, no same-day workout. Estimate active minutes from steps when
                    // the store left the figure at 0 (Health Connect always does) so a high-step but
                    // workout-less day still registers on the ring — see [estimateActiveMinutes].
                    derivedDay == null -> DailyAggregate(
                        date, storedDay.steps, storedDay.distanceM, storedDay.activeEnergyKj,
                        storedDay.activeMinutes.orDerived(estimateActiveMinutes(storedDay.steps)),
                    )
                    // Both a stored aggregate AND same-day workouts. Merge **per field**, not
                    // whole-row: the stored row owns steps (workouts carry none) and the whole-day
                    // distance/energy, but a store can leave a field at 0 — notably Health Connect,
                    // which has no "active minutes" record and imports it as 0 (see
                    // HealthConnectImporter). A whole-row take would then let that 0 shadow the real
                    // workout-derived minutes, zeroing the Home ring on a day the user trained. So
                    // each stat prefers the stored value when present and falls back to the
                    // workout-derived one; active minutes fall further back to the steps estimate.
                    // A logged workout is preferred over the steps proxy (higher fidelity, and it
                    // avoids double-counting the workout's own steps).
                    else -> DailyAggregate(
                        date = date,
                        steps = storedDay.steps,
                        distanceM = storedDay.distanceM.orDerived(derivedDay.distanceM),
                        activeEnergyKj = storedDay.activeEnergyKj.orDerived(derivedDay.activeEnergyKj),
                        activeMinutes = storedDay.activeMinutes
                            .orDerived(derivedDay.activeMinutes)
                            .orDerived(estimateActiveMinutes(storedDay.steps)),
                    )
                }
            }
    }

    /** Prefer this stored stat when the store actually populated it; otherwise the derived fallback. */
    private fun Int.orDerived(derived: Int): Int = if (this > 0) this else derived

    /**
     * A coarse **WHO moderate+vigorous active-minutes proxy from a day's step total** — the
     * fallback for a stored day whose source reports steps but no active-minutes figure (Health
     * Connect has no such record) and that has no logged workout to derive from. Without it, an
     * 8k-step but workout-less day reads 0 on the Home ring despite real movement.
     *
     * Model: steps below [INCIDENTAL_DAILY_STEPS] are treated as daily-living movement (not
     * purposeful moderate+ activity) and excluded; the remainder converts at [STEPS_PER_ACTIVE_MINUTE].
     * The constants are calibrated so the app's default ~8k-step goal maps near its default 30-minute
     * Active-Minutes goal — deliberately conservative, since we only have daily *totals*, not
     * per-minute cadence or heart-rate. This is a proxy: the higher-fidelity WHO figure is the
     * HR-zone derivation ([ActiveMinutes.hrZoneMinutes]), which a source computes upstream from its
     * raw HR series and stores on `DailyActivity.activeMinutes` (preferred by [dailyAggregates] when
     * present). This steps estimate remains the fallback for a day/source with no HR data. Tracked
     * in docs/tech-debt.md "Fit App".
     */
    fun estimateActiveMinutes(steps: Int): Int =
        (steps - INCIDENTAL_DAILY_STEPS).coerceAtLeast(0) / STEPS_PER_ACTIVE_MINUTE

    /**
     * Per-day roll-up derived from workouts alone (one row per day that has a workout), oldest
     * first. `steps` is `0` (a workout carries no step count — that's the [DailyActivityInput]
     * store's job); `activeMinutes` = summed workout minutes for the day. The fallback source for
     * [dailyAggregates] and the exemplar used by the pure test.
     */
    fun workoutDerived(inputs: List<WorkoutInput>, zone: TimeZone = TimeZone.currentSystemDefault()): List<DailyAggregate> =
        inputs
            .groupBy { localDate(it.startMs, zone) }
            .toList()
            .sortedBy { it.first }
            .map { (date, dayWorkouts) ->
                DailyAggregate(
                    date = date,
                    steps = 0,
                    distanceM = dayWorkouts.sumOf { it.distanceM }.toInt(),
                    activeEnergyKj = dayWorkouts.sumOf { it.activeEnergyKj }.toInt(),
                    activeMinutes = dayWorkouts.sumOf { it.durationSec / 60 }.toInt(),
                )
            }

    /**
     * All sleep sessions, newest night first (the Sleep category reads this order; the "last
     * night" teaser is `first()`). A stored [SleepSessionInput]'s four stage-minute scalars
     * become a `List<SleepStage>`, dropping any zero-minute stage (unknown breakdown).
     */
    fun sleeps(inputs: List<SleepSessionInput>, zone: TimeZone = TimeZone.currentSystemDefault()): List<SleepSession> =
        inputs.map { sleep(it, zone) }.sortedByDescending { it.date }

    fun sleep(input: SleepSessionInput, zone: TimeZone = TimeZone.currentSystemDefault()): SleepSession = SleepSession(
        date = input.date,
        start = Instant.fromEpochMilliseconds(input.startMs).toLocalDateTime(zone),
        end = Instant.fromEpochMilliseconds(input.endMs).toLocalDateTime(zone),
        durationSec = input.durationSec,
        source = source(input.source),
        stages = listOfNotNull(
            stageOrNull(SleepStageKind.Awake, input.awakeMin),
            stageOrNull(SleepStageKind.Rem, input.remMin),
            stageOrNull(SleepStageKind.Light, input.lightMin),
            stageOrNull(SleepStageKind.Deep, input.deepMin),
        ),
    )

    /**
     * All readings, mapped from the time-series store (§12 B2). Unknown metric strings (a source
     * the UI enum doesn't model yet) are dropped rather than guessed. Callers that want the Browse
     * teaser / detail current-value take [latestPerMetric]; the Body/Vitals detail history takes
     * these sorted by time.
     */
    fun samples(inputs: List<SampleInput>, zone: TimeZone = TimeZone.currentSystemDefault()): List<SampleLatest> =
        inputs.mapNotNull { sample(it, zone) }

    /**
     * The newest reading per (metric, resting) — the Browse Body/Vitals teaser + the detail's
     * current-value header. Derived, not a store invariant, now that the store retains history:
     * resting vs non-resting heart-rate are distinct logical metrics, so they key together.
     */
    fun latestPerMetric(samples: List<SampleLatest>): List<SampleLatest> =
        samples.groupBy { it.metric to it.resting }.values.map { group -> group.maxBy { it.time } }

    fun sample(input: SampleInput, zone: TimeZone = TimeZone.currentSystemDefault()): SampleLatest? {
        val metric = metric(input.metric) ?: return null
        return SampleLatest(
            metric = metric,
            value = input.value,
            unit = input.unit,
            time = Instant.fromEpochMilliseconds(input.timeMs).toLocalDateTime(zone),
            source = source(input.source),
            resting = input.resting,
        )
    }

    /** The stored nutrition days, oldest-first (the Nutrition detail trend order; §12 B3a). */
    fun nutritionDays(inputs: List<DailyNutritionInput>): List<NutritionDay> =
        inputs.map { NutritionDay(it.date, it.energyKj, it.hydrationMl) }.sortedBy { it.date }

    /** The stored menstruation days, oldest-first (§12 B3b). */
    fun cycleDays(inputs: List<MenstruationDayInput>): List<CycleEntry> =
        inputs.map { CycleEntry(it.date, flowLevel(it.flow), it.periodStart) }.sortedBy { it.date }

    /** Flow-level string → UI enum; unknown/blank folds to [FlowLevel.Unspecified]. */
    fun flowLevel(raw: String): FlowLevel = when (raw.lowercase()) {
        "light" -> FlowLevel.Light
        "medium" -> FlowLevel.Medium
        "heavy" -> FlowLevel.Heavy
        else -> FlowLevel.Unspecified
    }

    /**
     * The current **cycle day** — days since the most recent period start (its own day = 1), or, if
     * no period-start day was logged, since the most recent logged bleeding day. `null` when there is
     * no cycle data at all (the card is then hidden — §12 B3b data-driven visibility).
     */
    fun cycleDay(cycle: List<CycleEntry>, today: LocalDate): Int? {
        val anchor = cycle.filter { it.periodStart }.maxByOrNull { it.date }?.date
            ?: cycle.maxByOrNull { it.date }?.date
            ?: return null
        return anchor.daysUntil(today) + 1
    }

    /** All imported medical records, newest effective-date first (the Medical list order). */
    fun medicalRecords(inputs: List<MedicalRecordInput>): List<MedicalRecord> =
        inputs.map(::medicalRecord).sortedByDescending { it.effectiveDate }

    fun medicalRecord(input: MedicalRecordInput): MedicalRecord = MedicalRecord(
        kind = medicalKind(input.kind),
        title = input.title,
        detail = input.detail,
        effectiveDate = input.effectiveDate,
        issuer = input.issuer,
        source = source(input.source),
    )

    /**
     * The seven Browse categories with teasers + last-updated stamps. Mirrors the mock's
     * derivation but over projected data: Activity / Body / Vitals / Sleep / Medical have a source
     * once their entities land; Nutrition dims to "No entries" and Cycle to "Not set up".
     */
    fun categories(
        aggregates: List<DailyAggregate>,
        nutrition: List<NutritionDay>,
        cycle: List<CycleEntry>,
        sleep: List<SleepSession>,
        samples: List<SampleLatest>,
        medical: List<MedicalRecord>,
        today: LocalDate,
    ): List<CategorySummary> {
        val todayAggregate = aggregates.lastOrNull()
        val lastNutrition = nutrition.maxByOrNull { it.date }
        val cycleDay = cycleDay(cycle, today)
        val lastCycle = cycle.maxByOrNull { it.date }
        val lastSleep = sleep.maxByOrNull { it.date }
        // Newest reading per metric — robust whether `samples` is a full time-series or already
        // reduced to latest-per-metric (§12 B2).
        val weight = samples.filter { it.metric == Metric.Weight }.maxByOrNull { it.time }
        val vitals = vitalsTeaser(samples)
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
                updated = weight?.let { lastUpdated(it.time.date, today) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Vitals,
                teaser = vitals?.first ?: CategoryTeaser.NoEntries,
                updated = vitals?.let { lastUpdated(it.second.time.date, today) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Sleep,
                teaser = lastSleep?.let { CategoryTeaser.SleepDuration(it.durationSec / 60) }
                    ?: CategoryTeaser.NoEntries,
                updated = lastSleep?.let { lastUpdated(it.date, today) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Nutrition,
                teaser = lastNutrition?.takeIf { it.energyKj > 0 }?.let { CategoryTeaser.Energy(it.energyKj) }
                    ?: CategoryTeaser.NoEntries,
                updated = lastNutrition?.let { lastUpdated(it.date, today) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Cycle,
                teaser = cycleDay?.let { CategoryTeaser.CycleDay(it) } ?: CategoryTeaser.NotSetUp,
                updated = lastCycle?.let { lastUpdated(it.date, today) } ?: LastUpdated.Never,
            ),
            CategorySummary(
                id = CategoryId.Medical,
                teaser = if (medical.isEmpty()) CategoryTeaser.NoEntries else CategoryTeaser.RecordCount(medical.size),
                updated = if (medical.isEmpty()) LastUpdated.Never else LastUpdated.Imported,
            ),
            // Cycle is data-driven (§12 B3b): the card is present only when there is cycle data —
            // never gated on a profile sex field. ⚠ Medical likewise: no source fills it today
            // (Health Connect gives Fit no medical records, and Fit declares no READ_MEDICAL_DATA_*),
            // and an always-present empty "Medical records" card is a feature Play's Health apps
            // review sees with no permission behind it. Everything else always shows (dims when empty).
        ).filterNot { (it.id == CategoryId.Cycle && cycle.isEmpty()) || (it.id == CategoryId.Medical && medical.isEmpty()) }
    }

    /**
     * The Vitals card's teaser and the reading behind it: resting heart rate, else blood oxygen,
     * else respiratory rate — the same order as the detail's switcher. ⚠ A fallback, not just HR:
     * a user whose only vitals are SpO₂ would otherwise see a dimmed "No entries" card over data
     * they imported (and that the Health Connect declaration says we show).
     */
    fun vitalsTeaser(samples: List<SampleLatest>): Pair<CategoryTeaser, SampleLatest>? {
        fun newest(m: Metric, restingOnly: Boolean = false) =
            samples.filter { it.metric == m && (!restingOnly || it.resting) }.maxByOrNull { it.time }
        newest(Metric.HeartRate, restingOnly = true)?.let { return CategoryTeaser.RestingHeartRate(it.value.toInt()) to it }
        newest(Metric.BloodOxygen)?.let { return CategoryTeaser.BloodOxygen(kotlin.math.round(it.value).toInt()) to it }
        newest(Metric.RespiratoryRate)?.let { return CategoryTeaser.RespiratoryRate(it.value) to it }
        return null
    }

    // ── enum mapping (service strings ⇄ UI enums) ────────────────────────────

    /** Neutral activity-type string → UI enum; unknown folds to [ActivityType.Other]. */
    fun activityType(raw: String): ActivityType = when (raw) {
        "Running" -> ActivityType.Running
        "Walking" -> ActivityType.Walking
        "Cycling" -> ActivityType.Cycling
        "Strength" -> ActivityType.Strength
        else -> ActivityType.Other
    }

    /** UI enum → the neutral string the `:health-service` schema stores. */
    fun activityTypeName(type: ActivityType): String = type.name

    /**
     * Provenance string → UI [Source]. The service uses the import-seam vocabulary
     * (`HealthKit`/`HealthConnect`/`Fit`/`Tcx`/`Gpx`/`Manual`/`Recorded`); the UI enum groups
     * the file formats under [Source.File] and HealthKit under [Source.AppleHealth].
     */
    fun source(raw: String): Source = when (raw) {
        "Recorded" -> Source.Recorded
        "HealthConnect" -> Source.HealthConnect
        "HealthKit", "AppleHealth" -> Source.AppleHealth
        "Fit", "Tcx", "Gpx", "File" -> Source.File
        "Detected" -> Source.Detected
        else -> Source.Manual
    }

    /** UI [Source] → the neutral string the `:health-service` schema stores. */
    fun sourceName(source: Source): String = when (source) {
        Source.Recorded -> "Recorded"
        Source.HealthConnect -> "HealthConnect"
        Source.AppleHealth -> "HealthKit"
        Source.File -> "File"
        Source.Manual -> "Manual"
        Source.Detected -> "Detected"
    }

    /** Neutral metric string → UI [Metric]; an unmodelled metric folds to `null` (dropped). */
    fun metric(raw: String): Metric? = when (raw) {
        "HeartRate" -> Metric.HeartRate
        "Weight" -> Metric.Weight
        "Height" -> Metric.Height
        "BloodOxygen" -> Metric.BloodOxygen
        "RespiratoryRate" -> Metric.RespiratoryRate
        else -> null
    }

    /** UI [Metric] → the neutral string the `:health-service` schema stores. */
    fun metricName(metric: Metric): String = metric.name

    /** Neutral record-kind string → UI [MedicalRecordKind]; unknown folds to [MedicalRecordKind.Condition]. */
    fun medicalKind(raw: String): MedicalRecordKind = when (raw) {
        "Medication" -> MedicalRecordKind.Medication
        "Immunization" -> MedicalRecordKind.Immunization
        "Allergy" -> MedicalRecordKind.Allergy
        "Condition" -> MedicalRecordKind.Condition
        else -> MedicalRecordKind.Condition
    }

    /** UI [MedicalRecordKind] → the neutral string the `:health-service` schema stores. */
    fun medicalKindName(kind: MedicalRecordKind): String = kind.name

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun localDate(ms: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date

    /** The service's `0` sentinel means "unknown" → the UI model's nullable stat. */
    private fun Long.nonZeroInt(): Int? = if (this == 0L) null else toInt()

    /** A stage with zero minutes means "unknown / no breakdown" → dropped from the stage list. */
    private fun stageOrNull(kind: SleepStageKind, minutes: Int): SleepStage? =
        if (minutes > 0) SleepStage(kind, minutes) else null

    /** A stored entity's date → `Today` / `DaysAgo(n)` relative to [today] (mirrors the mock). */
    private fun lastUpdated(date: LocalDate, today: LocalDate): LastUpdated {
        val days = date.daysUntil(today)
        return if (days <= 0) LastUpdated.Today else LastUpdated.DaysAgo(days)
    }
}
