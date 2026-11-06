package app.tenshu.fit.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportNotice
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.AppSharingState
import app.tenshu.fit.model.SharingReadiness
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import org.tenshu.health.common.RouteSample

/**
 * The **Android live-import actual** (P5.2, `docs/proposals/fit-app.md` §9.5): a [HealthImporter]
 * over **Health Connect** (`androidx.health.connect:connect-client`). Reads the neutral record
 * set — exercise sessions, daily steps/distance/energy, sleep + stages, and the latest
 * weight/resting-HR/SpO₂/respiratory-rate samples — under the user's explicit permission grants
 * and maps them into [ImportedHealth]; the shared [HealthImportWriter] persists them. Google Fit
 * shuts down end-2026, so Health Connect is *the* Android source (no Fit-REST replacement).
 *
 * ⚠⚠ **No fixture fallback** (`fit-app.md` §9.15). When Health Connect is *unavailable* (not
 * installed, needs a provider update, or the device cannot run it) this importer offers **no
 * source** and [import] answers [ImportOutcome.SourceUnavailable], so Home says "Health Connect
 * isn't available on this device". It used to delegate to the [ReferenceHealthImporter] fixture,
 * labelled `Sample` — which a manual import then wrote into the user's real, synced realm. The
 * fixture stays a test/preview tool. When Health Connect *is* available but the read permissions
 * aren't granted yet, `import` answers [ImportOutcome.PermissionsRequired] (awaiting consent); the
 * app populates once the user grants [PERMISSIONS] via the platform permission contract in the app
 * shell.
 *
 * ⚠ Health data is GDPR Article 9 (threat-model **A34**). This reads only under Health Connect's
 * per-permission consent; medical records are **not** a Health Connect data type, so they never
 * come from here (they stay a file/FHIR-import concern). On-device verification pending — the
 * record→DTO mapping compiles against the real SDK; live reads need a device with Health Connect.
 */
class HealthConnectImporter(
    private val context: Context,
    private val historyDays: Long = 30,
    private val zone: ZoneId = ZoneId.systemDefault(),
    // Max HR for the WHO moderate+vigorous zone floor. Age-predicted; a real per-user age (from the
    // profile's birth date) is future work — until a persisted profile lands, a default-age estimate
    // is used (docs/tech-debt.md "Fit App"). Overridable for tests / a future age wiring.
    private val maxHeartRate: Int = ActiveMinutes.maxHeartRate(DEFAULT_AGE),
    /** Test seam: Health Connect's SDK status for [context] (`HealthConnectClient.getSdkStatus`). */
    private val sdkStatusOf: (Context) -> Int = { HealthConnectClient.getSdkStatus(it) },
) : HealthImporter {

    private val sdkStatus: Int get() = sdkStatusOf(context)

    private val isAvailable: Boolean
        get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient? by lazy {
        val status = sdkStatus
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            fitLog("HC unavailable (sdkStatus=${statusName(status)}) → no import source on this device")
            return@lazy null
        }
        runCatching { HealthConnectClient.getOrCreate(context) }
            .onFailure { fitLog("HC getOrCreate FAILED despite SDK_AVAILABLE → no import source", it) }
            .getOrNull()
    }

    override fun availableSources(): List<ImportSource> =
        if (client != null) listOf(ImportSource.HealthConnect) else emptyList()

    /** Whether Health Connect is installed/available on this device. */
    fun isHealthConnectAvailable(): Boolean = client != null

    /** Human-readable SDK status, for the launch diagnostic in the app shell. */
    fun statusDescription(): String = statusName(sdkStatus)

    /**
     * Unattended launch refresh only when Health Connect is present AND fully authorised (see
     * [HealthImporter.autoRefreshReady]): grant-less would abort into a permanent banner every
     * launch, and HC-absent has nothing to read. [hasAllPermissions] is already false when HC is
     * absent.
     */
    override suspend fun autoRefreshReady(): Boolean = hasAllPermissions()

    /** Whether every read permission has been granted (the app requests [PERMISSIONS] on-device). */
    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: return false
        val granted = runCatching { c.permissionController.getGrantedPermissions() }
            .onFailure { fitLog("HC getGrantedPermissions FAILED", it) }
            .getOrDefault(emptySet())
        val missing = PERMISSIONS - granted
        fitLog("HC permissions: ${granted.size} granted, ${missing.size} missing${missing.shortNames()}")
        return missing.isEmpty()
    }

    /**
     * Health Connect's own depth gate — see [HISTORY_PERMISSION]. [HistoryAccess.Unsupported] when
     * Health Connect is absent or the installed version predates the feature (probed rather than
     * assumed: the capability ships with the Health Connect *provider*, not with our SDK
     * dependency, so a device can hold a client that knows the constant and a provider that does
     * not honour it).
     */
    override suspend fun historyAccess(): HistoryAccess {
        val c = client ?: return HistoryAccess.Unsupported
        val supported = runCatching {
            c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        if (!supported) return HistoryAccess.Unsupported

        val granted = runCatching { c.permissionController.getGrantedPermissions() }
            .onFailure { fitLog("historyAccess: getGrantedPermissions FAILED → treating as not granted", it) }
            .getOrDefault(emptySet())
        return if (HISTORY_PERMISSION in granted) HistoryAccess.Granted else HistoryAccess.Grantable
    }

    /**
     * The wizard's snapshot (§15.6) — the same four probes the import already performs, answered
     * without reading a single record.
     *
     * ⚠ `storePresent` is the *client*, not the Health Connect app's package: on Android 14+ the
     * store is a system module with no user-visible package to look for, so `getSdkStatus` is the
     * only honest question. It is also why the wizard's "install Health Connect" step is nearly
     * unreachable on a modern device, and must not be the flow's centrepiece.
     */
    override suspend fun sharingReadiness(): SharingReadiness = SharingReadiness(
        storePresent = client != null,
        canRead = hasAllPermissions(),
        historyAccess = historyAccess(),
        googleFit = when {
            !isInstalled(GOOGLE_FIT_PACKAGE) -> AppSharingState.NotInstalled
            holdsPermission(GOOGLE_FIT_PACKAGE, WRITE_EXERCISE_PERMISSION) -> AppSharingState.Permitted
            else -> AppSharingState.NotPermitted
        },
    ).also { fitLog("sharingReadiness: $it") }

    override suspend fun import(
        source: ImportSource,
        categories: Set<CategoryId>,
        window: ImportWindow,
    ): ImportOutcome {
        fitLog("import(source=$source, categories=${categories.joinToString { it.name }}) — window=$window")
        val c = client ?: run {
            // ⚠⚠ HC unavailable → no source, never the fixture: a manual import is not gated on
            // autoRefreshReady, so a fixture here went straight into the synced realm (§9.15).
            fitLog("import: no Health Connect client → SourceUnavailable")
            return ImportOutcome.SourceUnavailable
        }
        val granted = runCatching { c.permissionController.getGrantedPermissions() }
            .onFailure { fitLog("import: getGrantedPermissions FAILED → treating as none granted", it) }
            .getOrDefault(emptySet())
        val required = permissionsFor(categories)
        val missing = required - granted
        // HC is available but not yet authorised → say so (awaiting consent), no fixture pollution.
        if (missing.isNotEmpty()) {
            fitLog("import: ABORT — ${missing.size}/${required.size} required permissions not granted${missing.shortNames()}")
            return ImportOutcome.PermissionsRequired(missing)
        }

        // A full-history read is only meaningfully different once the deep-history grant is held —
        // without it Health Connect silently clamps to its own 30-day floor, and a log line claiming
        // a ten-year window would be a lie. So the widening is gated on the grant, and says which
        // window it actually used.
        val deepHistory = window == ImportWindow.FullHistory && historyAccess() == HistoryAccess.Granted
        val days = if (deepHistory) FULL_HISTORY_DAYS else historyDays
        val end = Instant.now()
        val start = end.minusSeconds(days * SECONDS_PER_DAY)
        val scan = ReadScan(TimeRangeFilter.between(start, end))
        fitLog("import: reading window $start .. $end (${if (deepHistory) "full history" else "recent"})")

        val workouts = if (CategoryId.Activity in categories) readWorkouts(c, scan) else emptyList()
        val dailyActivities =
            if (CategoryId.Activity in categories) readDailyActivities(c, scan, start, end) else emptyList()
        val dailyNutritions = if (CategoryId.Nutrition in categories) readNutrition(c, scan) else emptyList()
        val menstruationDays = if (CategoryId.Cycle in categories) readMenstruation(c, scan) else emptyList()
        val sleepSessions = if (CategoryId.Sleep in categories) readSleep(c, scan) else emptyList()
        val samples = readSamples(c, scan, categories)

        val health = ImportedHealth(
            workouts = workouts,
            dailyActivities = dailyActivities,
            dailyNutritions = dailyNutritions,
            menstruationDays = menstruationDays,
            sleepSessions = sleepSessions,
            samples = samples,
            medicalRecords = emptyList(),   // not a Health Connect data type
        )
        fitLog(
            "import: DONE — ${health.count} item(s): workouts=${health.workouts.size} " +
                "daily=${health.dailyActivities.size} nutrition=${health.dailyNutritions.size} " +
                "cycle=${health.menstruationDays.size} sleep=${health.sleepSessions.size} samples=${health.samples.size}",
        )
        // An empty Success is a real answer, not a failure: it means Health Connect holds nothing in
        // the window. On a phone whose only writer is the platform pedometer — Google Fit does not
        // feed Health Connect unless the user connects it — that is exactly what the user sees.
        // The observations only Android can make; the verdict itself is shared and tested.
        val notice = googleFitNotice(
            writers = scan.writers,
            googleFitInstalled = isInstalled(GOOGLE_FIT_PACKAGE),
            googleFitMayWrite = holdsPermission(GOOGLE_FIT_PACKAGE, WRITE_EXERCISE_PERMISSION),
        )
        if (notice != null) fitLog("import: $notice — Google Fit wrote none of this data")
        return ImportOutcome.Success(health, notice = notice)
    }

    /**
     * ⚠ Requires a `<queries>` entry for [packageName] in the manifest — from Android 11 package
     * visibility is opt-in, and without the declaration this reports an *installed* app as absent.
     */
    private fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * Whether *another* app holds a runtime permission. An ordinary, unprivileged read — the same
     * package visibility that [isInstalled] needs is all it takes.
     *
     * ⚠ Defaults to **true** on failure, and that direction is deliberate: `false` here produces the
     * "go and allow it in Health Connect" notice, which sends the user to a screen where the switch
     * may already be on. Being unable to check is not evidence that it is off.
     */
    private fun holdsPermission(packageName: String, permission: String): Boolean =
        runCatching {
            context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)

    /**
     * One import's read pass: the window every read shares, plus the writer packages seen along the
     * way. The writers are the diagnostic half — Health Connect exposes no "which apps contribute
     * here" API, so the only way to learn it is to keep what the reads already report.
     *
     * A scan object rather than a threaded-through accumulator so the reads keep their arity, and
     * rather than a field on the importer so two overlapping imports cannot pool their findings.
     */
    private class ReadScan(val filter: TimeRangeFilter) {
        private val seen = mutableSetOf<String>()
        val writers: Set<String> get() = seen
        fun saw(packages: Collection<String>) { seen += packages }
    }

    /**
     * One record type, paged, logged, and **bounded**. Replaces the per-type
     * `runCatching { … }.getOrDefault(emptyList())` that made a *thrown* read (a revoked grant, a
     * provider error) look exactly like an *empty* one.
     *
     * Paging matters beyond diagnostics: Health Connect caps a page at 1000 records, so the previous
     * single-shot read silently truncated any type with more — 30 days of heart rate, comfortably.
     *
     * [maxRecords] is the other half of that fix and is not optional. Paging *without* a ceiling
     * trades a silent truncation for an OOM: a watch streaming continuous heart rate can put tens of
     * thousands of records in a 30-day window, and this list is fully materialised. Bounded and
     * logged beats either silent failure mode.
     *
     * ⚠⚠ **`ascendingOrder = false` is load-bearing, not a style choice.** Health Connect pages
     * oldest-first by default, so a ceiling that bites truncates the *newest* records — the
     * opposite of what every caller here wants. On a real device the step read hits the 10 000
     * ceiling inside a 30-day window, which silently dropped the last few days including *today*:
     * `readDailyActivities` then wrote today's row with `steps = 0` (distance and energy, far
     * fewer records, still landed), and Home's stat strip read that 0 forever. Reading newest-first
     * makes the ceiling drop the oldest end, which is also what the log line has always claimed.
     * Nothing downstream depends on the order — [StepBoutDetector.detect] sorts, and every other
     * consumer groups or maps.
     *
     * Logs the distinct writer packages, which is the question behind this whole exercise: whether
     * the user's Google Fit data is in Health Connect *at all* (count-level only, never a value — A34).
     */
    private suspend fun <T : Record> readAll(
        c: HealthConnectClient,
        type: KClass<T>,
        scan: ReadScan,
        maxRecords: Int = MAX_RECORDS_PER_TYPE,
    ): List<T> {
        val label = type.simpleName
        return runCatching {
            val all = mutableListOf<T>()
            var token: String? = null
            var pages = 0
            do {
                val page = c.readRecords(
                    ReadRecordsRequest(type, scan.filter, ascendingOrder = false, pageToken = token),
                )
                all += page.records
                token = page.pageToken
                pages++
            } while (token != null && all.size < maxRecords)
            if (all.size >= maxRecords) {
                fitLog("read $label: hit the $maxRecords-record ceiling — older records in the window were skipped")
            }
            val origins = all.map { it.metadata.dataOrigin.packageName }.distinct()
            scan.saw(origins)
            fitLog("read $label: ${all.size} record(s) over $pages page(s), writers=$origins")
            all
        }.onFailure {
            fitLog("read $label: FAILED — this type contributes nothing to the import", it)
        }.getOrDefault(emptyList())
    }

    /**
     * The single most recent record of [type], for the latest-value-only metrics (weight, resting HR,
     * SpO₂, respiratory rate). Asks Health Connect for one descending record rather than draining the
     * whole window and calling `maxByOrNull` — the same answer in one round-trip instead of dozens.
     */
    private suspend fun <T : Record> readLatest(
        c: HealthConnectClient,
        type: KClass<T>,
        scan: ReadScan,
    ): T? {
        val label = type.simpleName
        return runCatching {
            c.readRecords(
                ReadRecordsRequest(type, scan.filter, ascendingOrder = false, pageSize = 1),
            ).records.firstOrNull().also {
                it?.let { r -> scan.saw(listOf(r.metadata.dataOrigin.packageName)) }
                fitLog("read $label (latest): ${if (it == null) "none" else "1 record, writer=${it.metadata.dataOrigin.packageName}"}")
            }
        }.onFailure {
            fitLog("read $label (latest): FAILED — this metric contributes nothing to the import", it)
        }.getOrNull()
    }

    // ── reads (each resilient: a per-type failure yields empty, not a dead import — but is LOGGED) ──

    private suspend fun readWorkouts(c: HealthConnectClient, scan: ReadScan): List<ImportedWorkout> {
        var withRoute = 0
        var consentRequired = 0
        val workouts = readAll(c, ExerciseSessionRecord::class, scan).map { r ->
            val startMs = r.startTime.toEpochMilli()
            val route = routeOf(r, startMs)
            if (route.isNotEmpty()) withRoute++
            if (r.exerciseRouteResult is ExerciseRouteResult.ConsentRequired) consentRequired++
            ImportedWorkout(
                workoutId = r.metadata.id,
                activityType = exerciseTypeName(r.exerciseType),
                source = SOURCE,
                startMs = startMs,
                endMs = r.endTime.toEpochMilli(),
                durationSec = r.endTime.epochSecond - r.startTime.epochSecond,
                title = r.title ?: "",
                route = route,
            )
        }
        fitLog(
            "readWorkouts: ${workouts.size} session(s) — $withRoute with a GPS route" +
                if (consentRequired > 0) ", $consentRequired withheld (route consent not granted)" else "",
        )
        return workouts
    }

    /**
     * The session's GPS track, when Health Connect will part with it.
     *
     * Routes are gated separately from `READ_EXERCISE` — a session read returns
     * [ExerciseRouteResult.ConsentRequired] rather than its track until [ROUTES_PERMISSION] is held,
     * which is why every Health-Connect-imported workout arrived summary-only and rendered no map.
     * [ExerciseRouteResult.NoData] is the ordinary case for an indoor or phone-in-pocket session and
     * is not a failure.
     */
    private fun routeOf(record: ExerciseSessionRecord, startMs: Long): List<RouteSample> {
        val locations = (record.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route.orEmpty()
        return locations.map { l ->
            RouteSample(
                tSec = ((l.time.toEpochMilli() - startMs) / 1000).toInt(),
                latE7 = (l.latitude * E7).roundToInt(),
                lngE7 = (l.longitude * E7).roundToInt(),
                altCm = l.altitude?.let { (it.inMeters * 100).roundToInt() } ?: RouteSample.NO_ALT,
            )
        }
    }

    /**
     * Tier-1 detection over Health Connect's step buckets (§9.2).
     *
     * ⚠ Reads the same `StepsRecord`s the daily rollup already reads, but keeps their timestamps
     * instead of collapsing them — §1.5's finding, that the granularity a detector needs was
     * being discarded in the expression that read it.
     *
     * ⚠⚠ The read is capped (`MAX_RECORDS_PER_TYPE`) and the cap is reached on real devices —
     * 10 000 records over 10 pages in a 30-day window. The kept slice is the recent end, which
     * suits a launch pass looking for recent activity, but it means a long backfill has not seen
     * everything and must not claim to.
     */
    override suspend fun readStepBouts(): List<StepBoutDetector.Bout> {
        val c = client ?: return emptyList()
        if (!hasAllPermissions()) return emptyList()
        val end = java.time.Instant.now()
        val scan = ReadScan(TimeRangeFilter.between(end.minus(java.time.Duration.ofDays(30)), end))
        val samples = readAll(c, StepsRecord::class, scan).map {
            StepBoutDetector.Sample(
                startMs = it.startTime.toEpochMilli(),
                endMs = it.endTime.toEpochMilli(),
                steps = it.count.toInt(),
            )
        }
        val bouts = StepBoutDetector.detect(samples)
        fitLog("step bouts: ${bouts.size} bout(s) from ${samples.size} step sample(s)")
        return bouts
    }

    /**
     * The per-day activity roll-up — **Health Connect's own aggregation, not a sum over records**.
     *
     * ⚠⚠ Summing raw records here was wrong twice over, and both ways were silent:
     *  - **Double counting.** A phone typically has more than one writer of `StepsRecord` (here:
     *    Google Fit *and* the platform `android` source, both mirroring the same pedometer).
     *    A raw read returns every record from every writer, so summing them counted the same steps
     *    once per writer — the maintainer's device reported 20–45 k steps a day against 4–18 km of
     *    distance, roughly 2× reality. Health Connect resolves exactly this with its per-data-type
     *    **app priority list**, but only in the aggregation API; a record read is deliberately
     *    verbatim.
     *  - **The record ceiling.** 30 days of step records comfortably exceeds
     *    [MAX_RECORDS_PER_TYPE], so the day totals were computed from a truncated set. Aggregation
     *    returns one bucket per day whatever the underlying record count, so the ceiling cannot
     *    reach these figures at all.
     *
     * ⚠ Period slicing requires a **local** time filter (Health Connect's own contract — a day is a
     * calendar day in the user's zone, which an `Instant` window cannot express), so the window is
     * re-expressed in [zone] rather than reusing [ReadScan]'s filter. The scan is still passed so
     * the writer diagnostic keeps working: an aggregation result reports its contributing
     * `dataOrigins`, which is the same question [googleFitNotice] asks.
     *
     * A failed aggregation yields **no rows**, deliberately not a fallback to the raw sum: the raw
     * sum is the inflated figure this method exists to stop reporting, and a day with no stored
     * aggregate degrades to the projection's workout-derived roll-up.
     */
    private suspend fun readDailyActivities(
        c: HealthConnectClient,
        scan: ReadScan,
        start: Instant,
        end: Instant,
    ): List<ImportedDailyActivity> {
        val buckets = runCatching {
            c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(
                        // ⚠⚠ Anchored to local MIDNIGHT, not to the window's own start instant.
                        // Period slicing counts days from wherever the filter begins, so a window
                        // starting at 15:12 produces 15:12→15:12 buckets: each one straddles two
                        // calendar days, gets labelled with the earlier of them, and the last
                        // partial day — *today*, the one Home renders — never gets a bucket at all.
                        LocalDateTime.ofInstant(start, zone).toLocalDate().atStartOfDay(),
                        LocalDateTime.ofInstant(end, zone),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )
        }.onFailure {
            fitLog("aggregate daily activity: FAILED — no daily rows from this import", it)
        }.getOrDefault(emptyList())

        val stepsByDay = mutableMapOf<String, Long>()
        val distanceByDay = mutableMapOf<String, Long>()
        val energyByDay = mutableMapOf<String, Long>()
        buckets.forEach { bucket ->
            val day = bucket.startTime.toLocalDate().toString()
            bucket.result[StepsRecord.COUNT_TOTAL]?.let { stepsByDay[day] = it }
            bucket.result[DistanceRecord.DISTANCE_TOTAL]?.let { distanceByDay[day] = it.inMeters.toLong() }
            bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { energyByDay[day] = it.inKilojoules.toLong() }
            scan.saw(bucket.result.dataOrigins.map { it.packageName })
        }
        // WHO moderate+vigorous minutes aren't a direct HC record — derived on-device from the day's
        // heart-rate series (ActiveMinutes.hrZoneMinutes). Best-effort: a missing HR permission /
        // no HR data yields an empty map, and the day falls back to workout / steps in the projection.
        val activeMinutesByDay = readActiveMinutesByDay(c, scan)

        return (stepsByDay.keys + distanceByDay.keys + energyByDay.keys + activeMinutesByDay.keys).map { day ->
            ImportedDailyActivity(
                date = day,
                steps = stepsByDay[day] ?: 0,
                distanceM = distanceByDay[day] ?: 0,
                activeEnergyKj = energyByDay[day] ?: 0,
                activeMinutes = (activeMinutesByDay[day] ?: 0).toLong(),
            )
        }.also { fitLog("readDailyActivities: ${it.size} day(s) over ${buckets.size} aggregate bucket(s) — steps=${stepsByDay.size} distance=${distanceByDay.size} energy=${energyByDay.size} activeMin=${activeMinutesByDay.size}") }
    }

    /**
     * Per-day WHO moderate+vigorous minutes, derived from the day's heart-rate series. Flattens every
     * [HeartRateRecord]'s samples, groups by calendar day, and reduces each day's series to minutes
     * in-zone via [ActiveMinutes.hrZoneMinutes] against [maxHeartRate]. Resilient: any failure (no HR
     * read permission, no data) yields an empty map so the day degrades to the projection's
     * workout / steps fallback rather than a dead import.
     */
    private suspend fun readActiveMinutesByDay(c: HealthConnectClient, scan: ReadScan): Map<String, Int> =
        // The one type that can genuinely blow up: a watch writing continuous heart rate produces far
        // more records than anything else here, and each carries its own sample list that we then
        // flatten. Held to a tighter ceiling than the rest — a partial zone-minutes estimate degrades
        // gracefully (the projection falls back to workouts / steps), an OOM does not.
        readAll(c, HeartRateRecord::class, scan, maxRecords = MAX_HEART_RATE_RECORDS)
            .flatMap { it.samples }
            .groupBy { it.time.day() }
            .mapValues { (_, daySamples) ->
                ActiveMinutes.hrZoneMinutes(
                    daySamples.map { ActiveMinutes.HrPoint(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) },
                    maxHr = maxHeartRate,
                )
            }

    private suspend fun readNutrition(c: HealthConnectClient, scan: ReadScan): List<ImportedDailyNutrition> {
        val energyByDay = readAll(c, NutritionRecord::class, scan)
            .groupBy { it.startTime.day() }.mapValues { (_, l) -> l.sumOf { it.energy?.inKilojoules ?: 0.0 }.toLong() }
        val hydrationByDay = readAll(c, HydrationRecord::class, scan)
            .groupBy { it.startTime.day() }.mapValues { (_, l) -> l.sumOf { it.volume.inMilliliters }.toLong() }
        return (energyByDay.keys + hydrationByDay.keys).map { day ->
            ImportedDailyNutrition(
                date = day,
                energyKj = energyByDay[day] ?: 0,
                hydrationMl = hydrationByDay[day] ?: 0,
            )
        }.also { fitLog("readNutrition: ${it.size} day(s) — energy=${energyByDay.size} hydration=${hydrationByDay.size}") }
    }

    private suspend fun readMenstruation(c: HealthConnectClient, scan: ReadScan): List<ImportedMenstruationDay> {
        val periodStartDays = readAll(c, MenstruationPeriodRecord::class, scan)
            .map { it.startTime.day() }.toSet()
        val flowByDay = readAll(c, MenstruationFlowRecord::class, scan)
            .associate { it.time.day() to flowName(it.flow) }
        return (flowByDay.keys + periodStartDays).map { day ->
            ImportedMenstruationDay(
                date = day,
                flow = flowByDay[day] ?: "Unspecified",
                periodStart = day in periodStartDays,
            )
        }.also { fitLog("readMenstruation: ${it.size} day(s) — flow=${flowByDay.size} periodStarts=${periodStartDays.size}") }
    }

    private fun flowName(flow: Int): String = when (flow) {
        MenstruationFlowRecord.FLOW_LIGHT -> "Light"
        MenstruationFlowRecord.FLOW_MEDIUM -> "Medium"
        MenstruationFlowRecord.FLOW_HEAVY -> "Heavy"
        else -> "Unspecified"
    }

    private suspend fun readSleep(c: HealthConnectClient, scan: ReadScan): List<ImportedSleepSession> =
        readAll(c, SleepSessionRecord::class, scan).map { r ->
            val minutesByStage = r.stages
                .groupBy { it.stage }
                .mapValues { (_, l) -> l.sumOf { (it.endTime.epochSecond - it.startTime.epochSecond) } / 60 }
            ImportedSleepSession(
                date = r.endTime.day(),
                startMs = r.startTime.toEpochMilli(),
                endMs = r.endTime.toEpochMilli(),
                durationSec = r.endTime.epochSecond - r.startTime.epochSecond,
                source = SOURCE,
                awakeMin = minutesByStage[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0,
                remMin = minutesByStage[SleepSessionRecord.STAGE_TYPE_REM] ?: 0,
                lightMin = minutesByStage[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0,
                deepMin = minutesByStage[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0,
            )
        }

    private suspend fun readSamples(
        c: HealthConnectClient,
        scan: ReadScan,
        categories: Set<CategoryId>,
    ): List<ImportedSample> {
        val samples = mutableListOf<ImportedSample>()
        // Weight + resting HR ride as a time-series (§12 B2) — read the whole window (bounded by the
        // readAll ceiling) so the Body/Vitals detail can chart history, not just one latest reading.
        if (CategoryId.Body in categories) {
            readAll(c, WeightRecord::class, scan).forEach { r ->
                samples += ImportedSample("Weight", r.weight.inKilograms.roundedString(), "kg", r.time.toEpochMilli(), SOURCE)
            }
            // Height (FP-2): the platform records it, so Profile reads a measurement instead of
            // inventing one. `readLatest` rather than `readAll` — an adult's height is effectively
            // a constant, so the history a chart would draw is noise, and the one value Profile
            // needs is the newest (fit-app.md §18.2). Centimetres, matching how it is displayed;
            // the unit travels with the sample, exactly as "kg" does for weight.
            readLatest(c, HeightRecord::class, scan)?.let { r ->
                samples += ImportedSample("Height", (r.height.inMeters * 100).roundedString(), "cm", r.time.toEpochMilli(), SOURCE)
            }
        }
        if (CategoryId.Vitals in categories) {
            readAll(c, RestingHeartRateRecord::class, scan).forEach { r ->
                samples += ImportedSample("HeartRate", r.beatsPerMinute.toString(), "bpm", r.time.toEpochMilli(), SOURCE, resting = true)
            }
            // SpO₂ / respiratory rate: one latest reading each, shown on the Vitals detail (its switcher).
            readLatest(c, OxygenSaturationRecord::class, scan)?.let { r ->
                samples += ImportedSample("BloodOxygen", r.percentage.value.roundedString(), "%", r.time.toEpochMilli(), SOURCE)
            }
            readLatest(c, RespiratoryRateRecord::class, scan)?.let { r ->
                samples += ImportedSample("RespiratoryRate", r.rate.roundedString(), "/min", r.time.toEpochMilli(), SOURCE)
            }
        }
        return samples
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun Instant.day(): String = LocalDate.ofInstant(this, zone).toString()

    private fun Double.roundedString(): String {
        // Trim a whole number to "62"; keep one decimal otherwise ("74.2").
        return if (this % 1.0 == 0.0) toLong().toString() else ((this * 10).toLong() / 10.0).toString()
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength"
        else -> "Other"
    }

    private fun permissionsFor(categories: Set<CategoryId>): Set<String> = buildSet {
        if (CategoryId.Activity in categories) {
            add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
            add(HealthPermission.getReadPermission(StepsRecord::class))
            add(HealthPermission.getReadPermission(DistanceRecord::class))
            add(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))
        }
        if (CategoryId.Nutrition in categories) {
            add(HealthPermission.getReadPermission(NutritionRecord::class))
            add(HealthPermission.getReadPermission(HydrationRecord::class))
        }
        if (CategoryId.Cycle in categories) {
            add(HealthPermission.getReadPermission(MenstruationFlowRecord::class))
            add(HealthPermission.getReadPermission(MenstruationPeriodRecord::class))
        }
        if (CategoryId.Sleep in categories) add(HealthPermission.getReadPermission(SleepSessionRecord::class))
        if (CategoryId.Body in categories) {
            add(HealthPermission.getReadPermission(WeightRecord::class))
            add(HealthPermission.getReadPermission(HeightRecord::class))
        }
        if (CategoryId.Vitals in categories) {
            add(HealthPermission.getReadPermission(RestingHeartRateRecord::class))
            add(HealthPermission.getReadPermission(HeartRateRecord::class))
            add(HealthPermission.getReadPermission(OxygenSaturationRecord::class))
            add(HealthPermission.getReadPermission(RespiratoryRateRecord::class))
        }
    }

    /** `android.permission.health.READ_STEPS` → `READ_STEPS`, so a log line stays readable. */
    private fun Set<String>.shortNames(): String =
        if (isEmpty()) "" else " ${map { it.substringAfterLast('.') }.sorted()}"

    private fun statusName(status: Int): String = when (status) {
        HealthConnectClient.SDK_AVAILABLE -> "SDK_AVAILABLE"
        HealthConnectClient.SDK_UNAVAILABLE -> "SDK_UNAVAILABLE"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED"
        else -> "UNKNOWN($status)"
    }

    companion object {
        private const val SOURCE = "HealthConnect"
        private const val SECONDS_PER_DAY = 24L * 60 * 60

        /**
         * Per-type ceiling on a paged read. Paging fixed the old silent 1000-record truncation, but
         * an *unbounded* page loop just swaps that for an OOM on a dense store — so it is bounded,
         * and says so in the log when it bites.
         */
        private const val MAX_RECORDS_PER_TYPE = 10_000

        /** Heart rate is the dense one (per-record sample lists, flattened) — held tighter still. */
        private const val MAX_HEART_RATE_RECORDS = 5_000

        /** Fallback age for the max-HR zone floor until a persisted user profile provides a real one. */
        private const val DEFAULT_AGE = 30

        /**
         * The grant that decides whether another app's **workouts** can ever reach Health Connect,
         * probed on Google Fit to tell "not allowed to share" from "allowed, and still not sharing".
         *
         * Exercise specifically, not any-of-the-write-set: workouts are what the user is looking at
         * and missing, and a Google Fit permitted to write only *steps* would still show them an
         * empty history — so treating that as "allowed" would send them to the wrong remedy.
         *
         * A literal because the AndroidX SDK publishes read constants, not write ones, for an app
         * that does not itself write.
         */
        private const val WRITE_EXERCISE_PERMISSION = "android.permission.health.WRITE_EXERCISE"

        /**
         * The **optional deep-history grant**. Health Connect returns nothing older than *30 days
         * before the app's first health-permission grant* unless this is held — so without it, a
         * wider [ImportWindow] reads exactly the same 30 days.
         *
         * ⚠ Deliberately **not** a member of [PERMISSIONS]. It is a separate toggle in the consent
         * sheet and a user may reasonably decline it; folding it into the required set would make
         * [hasAllPermissions] — and therefore [autoRefreshReady] and the whole ordinary refresh —
         * fail forever on that refusal. A declined deep history must cost the user deep history,
         * nothing else.
         *
         * ⚠ The cutoff is anchored at the **first grant**, not rolling from today: reinstalling the
         * app re-anchors it forward and permanently strands everything before the new anchor unless
         * this permission is held.
         */
        val HISTORY_PERMISSION: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

        /** Degrees → the E7 fixed-point form [RouteSample] stores. */
        private const val E7 = 1e7

        /**
         * The **exercise-route grant**: `READ_EXERCISE` yields a session's summary, never its GPS
         * track — a route needs its own permission, and without it every session reads back as
         * [ExerciseRouteResult.ConsentRequired] and imports map-less.
         *
         * A string literal because the AndroidX SDK exposes no constant for it (it publishes
         * `WRITE_EXERCISE_ROUTE` but no read twin); the name is the platform's, declared in the
         * manifest alongside the others.
         *
         * ⚠ Deliberately **not** a member of [PERMISSIONS], for [HISTORY_PERMISSION]'s reason: a user
         * may decline location history and must not thereby break [hasAllPermissions] — and with it
         * [autoRefreshReady] and every ordinary refresh — forever. A declined route grant costs
         * routes, nothing else. It is *requested* with the rest ([REQUESTED_PERMISSIONS]) so the
         * consent sheet is asked once, not twice.
         */
        const val ROUTES_PERMISSION: String = "android.permission.health.READ_EXERCISE_ROUTES"

        /**
         * How far back a [ImportWindow.FullHistory] read reaches. Ten years rather than
         * `Instant.EPOCH`: it covers any realistic personal history (the maintainer's own Google
         * Takeout export spans 2016→2026) while keeping the range filter to values Health Connect
         * and the platform handle unremarkably.
         */
        private const val FULL_HISTORY_DAYS = 3_650L

        /** The full read-permission set the app requests once, up front, via the permission contract. */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getReadPermission(MenstruationFlowRecord::class),
            HealthPermission.getReadPermission(MenstruationPeriodRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        )

        /**
         * What the consent sheet actually asks for: the required set plus the optional route grant.
         * Distinct from [PERMISSIONS] on purpose — that one is the *verdict* set ("can we import at
         * all"), this one is the *request* set. Asking for more than we require is what keeps the
         * route grant a single-sheet decision instead of a second prompt later.
         */
        val REQUESTED_PERMISSIONS: Set<String> = PERMISSIONS + ROUTES_PERMISSION
    }
}
