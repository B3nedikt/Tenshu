@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import app.tenshu.fit.data.projection.FitProjection
import app.tenshu.fit.data.projection.FitProjection.DailyActivityInput
import app.tenshu.fit.data.projection.FitProjection.SampleInput
import app.tenshu.fit.data.projection.FitProjection.WorkoutInput
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.model.HealthSourceInput
import app.tenshu.fit.model.ImportRunInput
import app.tenshu.fit.model.Workout
import app.tenshu.sdk.internal.failureResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.fold
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSource
import org.tenshu.core.inRange
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.health.common.WorkoutStreams
import org.tenshu.core.health.DailyActivity as SvcDailyActivity
import org.tenshu.core.health.HealthSample as SvcHealthSample
import org.tenshu.core.health.Workout as SvcWorkout

// The rows and rules `Fit.kt` reads by. ⚠⚠ The **projection is not here**: every fold (day
// aggregates, the workout mapping, the data-source rows) is `:fit-projection`'s `FitProjection` /
// `DataSourceProjection`, the same code native `FitProjectionRepository` calls, so the two readers
// cannot disagree about what a row says. What IS here is the half `:fit-shared-data` keeps natively
// because it needs a client: the field lists and the entity → `*Input` extraction, a field-for-field
// copy of `FitProjectionRepository.workoutInput` & co. and `DataSourceReads.kt`, pinned by
// `FitWebContractTest`. The web-only rules, each with its reason:
//  1. History is keyset-paged over a TOTAL order, `(start desc, workoutId asc)`, and the cursor names
//     the last row served — [WorkoutCursor]. Native pages by position (`:core-paging`), which is
//     fine for a list that re-reads its own window; a cursor carried across postMessages is not.
//  2. A day window is read in a reader zone: `""` is the browser's (as `TimeZone.currentSystemDefault`
//     natively), an unknown id is `error_fit_invalid_zone` — never a silent UTC.
//  3. Profile height / weight come from a bounded per-metric `healthSamples` read, not from
//     `latestSamples`: that rollup is derived per device and never synced, and a tab does not
//     derive it (`fit-app.md` §Web scope 3).

/** Most workouts one History page may carry across the bridge. */
internal const val MAX_WORKOUT_PAGE_SIZE = 200

/** The widest day window one aggregate read may span: a year plus a leap day and a margin. */
internal const val MAX_AGGREGATE_WINDOW_DAYS = 400

/**
 * Safety ceiling on the workouts one aggregate window reads — native's `AGGREGATE_WORKOUT_CAP` is
 * 1 000 per year; this window is at most [MAX_AGGREGATE_WINDOW_DAYS], so the same bound, rounded up.
 */
internal const val AGGREGATE_WORKOUT_CAP = 1_100

/** The biggest activity file the bridge accepts: a GPX of a 24-hour ultra is a few MB. */
internal const val MAX_ACTIVITY_FILE_BYTES = 32 * 1024 * 1024

// ⚠⚠ The write domains. Every one of these is written to a SYNCED row native reads (a preference, a
// `HealthSample`), so a bridge that accepts more than native can enter puts a value on every
// device. Native validates in its dialogs, not its repository, so the dialogs are the reference.

/** Native's active-minutes stepper (`GoalsDialog`, iOS's twin): 5..240. */
internal val GOAL_ACTIVE_MINUTES = 5..240

/** Native's step-goal stepper: 1 000..30 000. */
internal val GOAL_STEPS = 1_000..30_000

/** Native has no cap; 300 cm is above every recorded human. */
internal const val MAX_HEIGHT_CM = 300

/** Native has no cap; 1 000 kg is above every recorded human. */
internal const val MAX_WEIGHT_KG = 1_000.0

/** The earliest birth date the profile accepts; the latest is today. */
internal val EARLIEST_BIRTH_DATE = LocalDate(1900, 1, 1)

internal object FitWebErrorKeys {
    /** A malformed History cursor — a caller bug, said loudly. */
    val BAD_CURSOR = ErrorCopyKey("error_fit_bad_cursor")
    /** `pageSize` outside `1..MAX_WORKOUT_PAGE_SIZE`. */
    val BAD_PAGE_SIZE = ErrorCopyKey("error_fit_bad_page_size")
    /** A day window that is not two `YYYY-MM-DD` dates, is reversed, or is wider than allowed. */
    val BAD_WINDOW = ErrorCopyKey("error_fit_bad_window")
    /** A reader zone the bundled IANA set does not know (rule 2). */
    val INVALID_ZONE = ErrorCopyKey("error_fit_invalid_zone")
    /** A workout id no row has, for an op that needs one (delete). */
    val WORKOUT_NOT_FOUND = ErrorCopyKey("error_fit_workout_not_found")
    /** A goal outside native's stepper bounds ([GOAL_ACTIVE_MINUTES], [GOAL_STEPS]). */
    val INVALID_GOAL = ErrorCopyKey("error_fit_invalid_goal")
    /**
     * A profile value outside its domain: unknown units / sex token, a bad or out-of-range birth
     * date, a height outside 1..[MAX_HEIGHT_CM] cm, a weight outside (0, [MAX_WEIGHT_KG]] kg.
     */
    val INVALID_PROFILE_VALUE = ErrorCopyKey("error_fit_invalid_profile_value")
    /** A file that is neither GPX, TCX nor FIT, by name or by content. */
    val UNSUPPORTED_FORMAT = ErrorCopyKey("error_fit_unsupported_format")
    /** A binary `.FIT` file: the only decoder is the JVM Garmin SDK (`fit-app.md` §Web scope 4). */
    val BINARY_UNSUPPORTED = ErrorCopyKey("error_fit_binary_unsupported")
    /** GPX / TCX whose XML the browser cannot parse. */
    val MALFORMED_FILE = ErrorCopyKey("error_fit_malformed_file")
    /** Well-formed GPX / TCX with no track or activity the parser could use. */
    val NO_WORKOUTS = ErrorCopyKey("error_fit_no_workouts")
    /** A stored `Workout.Streams` blob the codec cannot decode. */
    val STREAMS_UNREADABLE = ErrorCopyKey("error_fit_streams_unreadable")
    /** A file over [MAX_ACTIVITY_FILE_BYTES]. */
    val FILE_TOO_LARGE = ErrorCopyKey("error_fit_file_too_large")
}

internal fun fitFailure(error: TError): dynamic = failureResult(error.errorCopyKey().value, error.toString())

internal fun fitFailure(key: ErrorCopyKey, message: String? = null): dynamic = failureResult(key.value, message)

// ── rule 1: the History cursor ───────────────────────────────────────────────────────────────

/**
 * The keyset cursor, opaque to TypeScript (`w1.<beforeMs>.<afterId>`): the position just after the
 * workout `(beforeMs, afterId)` in the total order `(start desc, workoutId asc)` — Photos' `v2.`
 * cursor (`PhotosLibrary.kt` rule 3) over workouts.
 *
 * [afterId] `null` (encoded empty) means "every workout AT [beforeMs] is still to come" — the head,
 * and a caller-built window top. A stored workout id is never empty (`RecordWorkout` keys the row by
 * it), and an id may itself contain `.`, so everything after the second separator is the id.
 */
internal data class WorkoutCursor(val beforeMs: Long, val afterId: String?) {
    fun encode(): String = "$CURSOR_PREFIX$beforeMs.${afterId.orEmpty()}"

    companion object {
        private const val CURSOR_PREFIX = "w1."
        val HEAD = WorkoutCursor(Long.MAX_VALUE, null)

        /**
         * `null`/`""` is the head. A malformed token is `null` here and a typed failure upstream.
         *
         * ⚠⚠ [beforeMs] must be exactly what [encode] writes for a real position: canonical decimal,
         * `0..Long.MAX_VALUE`. A forged `w1.-9223372036854775808.x` made the page read's
         * `beforeMs - 1` wrap to `Long.MAX_VALUE`, so History restarted at the top and a client
         * following `nextCursor` paged for ever. Negative, `+`-signed, zero-padded and `-0` tokens
         * are refused too: no read mints them (History starts at the epoch, [workoutWindow]), so
         * each is a caller bug.
         */
        fun decode(token: String?): WorkoutCursor? {
            if (token.isNullOrEmpty()) return HEAD
            if (!token.startsWith(CURSOR_PREFIX)) return null
            val rest = token.removePrefix(CURSOR_PREFIX)
            val dot = rest.indexOf('.')
            if (dot < 0) return null
            val raw = rest.substring(0, dot)
            val before = raw.toLongOrNull() ?: return null
            if (before < 0 || before.toString() != raw) return null
            return WorkoutCursor(before, rest.substring(dot + 1).ifEmpty { null })
        }
    }
}

/** One workout's position in the total order: start ms, then id. */
internal data class WorkoutKey(val startMs: Long, val workoutId: String)

/** Rule 1's order, for the rows the bridge orders itself. */
internal val WORKOUT_ORDER: Comparator<WorkoutKey> =
    compareByDescending<WorkoutKey> { it.startMs }.thenBy { it.workoutId }

/** Is [this] strictly after [cursor] in [WORKOUT_ORDER]? */
internal fun WorkoutKey.afterCursor(cursor: WorkoutCursor): Boolean = when {
    startMs < cursor.beforeMs -> true
    startMs > cursor.beforeMs -> false
    else -> cursor.afterId == null || workoutId > cursor.afterId
}

/**
 * The cursor for the page after one that answered [rows] (in [WORKOUT_ORDER]), or `null` when that
 * page was the last. ⚠ It names the last row, so it counts nothing: a workout arriving or deleted
 * at the boundary millisecond moves no other row's position relative to it.
 */
internal fun nextWorkoutCursor(rows: List<WorkoutKey>, pageSize: Int): WorkoutCursor? {
    if (rows.size < pageSize || rows.isEmpty()) return null
    val last = rows.last()
    return WorkoutCursor(last.startMs, last.workoutId)
}

/** The half-open end that makes a window closed at [beforeMs]. */
private fun exclusiveEnd(beforeMs: Long): Long = if (beforeMs == Long.MAX_VALUE) beforeMs else beforeMs + 1

/**
 * Every workout in `[fromMs, beforeMs]`, in rule 1's order. ⚠ A filter ahead of the sort does not
 * fuse (`core-ordered-window-fusion.md` gate 2), the same trade native's aggregate read takes; the
 * `take` behind it still bounds what crosses into the page.
 *
 * ⚠ History starts at the epoch ([fromMs] `0`): a pre-1970 start would mint a negative cursor,
 * which [WorkoutCursor.decode] refuses. No importer or recorder writes one.
 */
internal fun TList<SvcWorkout>.workoutWindow(beforeMs: Long, fromMs: Long = 0): TList<SvcWorkout> = this
    .searchAll<SvcWorkout> { start.inRange(fromMs, exclusiveEnd(beforeMs)) }
    .sortByDescending { start }
    .thenBy { workoutId }

// ── the row extraction (natively `FitProjectionRepository` / `DataSourceReads.kt`) ───────────

/** `FitProjectionRepository.WORKOUT_FIELDS`, same fields: metadata only, never `Workout.Streams`. */
internal val WORKOUT_FIELDS = arrayOf(
    SvcWorkout.Fields.WORKOUT_ID, SvcWorkout.Fields.ACTIVITY_TYPE, SvcWorkout.Fields.SOURCE,
    SvcWorkout.Fields.START, SvcWorkout.Fields.END, SvcWorkout.Fields.DURATION_SEC,
    SvcWorkout.Fields.TITLE, SvcWorkout.Fields.DISTANCE_M, SvcWorkout.Fields.ACTIVE_ENERGY_KJ,
    SvcWorkout.Fields.AVG_HR, SvcWorkout.Fields.STEPS,
)

internal val DAILY_ACTIVITY_FIELDS = arrayOf(
    SvcDailyActivity.Fields.DATE, SvcDailyActivity.Fields.STEPS, SvcDailyActivity.Fields.DISTANCE_M,
    SvcDailyActivity.Fields.ACTIVE_ENERGY_KJ, SvcDailyActivity.Fields.ACTIVE_MINUTES,
)

internal val HEALTH_SAMPLE_FIELDS = arrayOf(
    SvcHealthSample.Fields.SAMPLE_ID,
    SvcHealthSample.Fields.METRIC, SvcHealthSample.Fields.VALUE, SvcHealthSample.Fields.UNIT,
    SvcHealthSample.Fields.TIME, SvcHealthSample.Fields.SOURCE, SvcHealthSample.Fields.RESTING,
)

/** `DataSourceReads.kt`'s `SOURCE_FIELDS`. ⚠⚠ KEY first: the stream merge resolves each row's DataId by it. */
internal val SOURCE_FIELDS = arrayOf(
    HealthSource.Fields.KEY, HealthSource.Fields.KIND, HealthSource.Fields.DEVICE_ID,
    HealthSource.Fields.DEVICE_LABEL, HealthSource.Fields.AUTO_IMPORT, HealthSource.Fields.LAST_RUN_AT,
    HealthSource.Fields.LAST_STATUS, HealthSource.Fields.LAST_ITEM_COUNT,
)

/** `DataSourceReads.kt`'s `RUN_FIELDS`; `error` is not projected there either. */
internal val RUN_FIELDS = arrayOf(
    HealthImportRun.Fields.KEY, HealthImportRun.Fields.DEVICE_ID, HealthImportRun.Fields.KIND,
    HealthImportRun.Fields.STARTED_AT, HealthImportRun.Fields.FINISHED_AT, HealthImportRun.Fields.WINDOW,
    HealthImportRun.Fields.STATUS, HealthImportRun.Fields.ITEM_COUNT, HealthImportRun.Fields.WORKOUT_COUNT,
    HealthImportRun.Fields.BYTE_COUNT, HealthImportRun.Fields.FILE_NAME,
)

internal fun workoutInput(e: Entity<out SvcWorkout>) = WorkoutInput(
    workoutId = e.getFieldValue<TString>(SvcWorkout.Fields.WORKOUT_ID).get(),
    activityType = e.getFieldValue<TString>(SvcWorkout.Fields.ACTIVITY_TYPE).get(),
    source = e.getFieldValue<TString>(SvcWorkout.Fields.SOURCE).get(),
    startMs = e.getFieldValue<UnixInstant>(SvcWorkout.Fields.START).toEpochMilliseconds(),
    endMs = e.getFieldValue<UnixInstant>(SvcWorkout.Fields.END).toEpochMilliseconds(),
    durationSec = e.getFieldValue<TInteger>(SvcWorkout.Fields.DURATION_SEC).get(),
    title = e.getFieldValue<TString>(SvcWorkout.Fields.TITLE).get(),
    distanceM = e.getFieldValue<TInteger>(SvcWorkout.Fields.DISTANCE_M).get(),
    activeEnergyKj = e.getFieldValue<TInteger>(SvcWorkout.Fields.ACTIVE_ENERGY_KJ).get(),
    avgHr = e.getFieldValue<TInteger>(SvcWorkout.Fields.AVG_HR).get(),
    steps = e.getFieldValue<TInteger>(SvcWorkout.Fields.STEPS).get(),
)

internal fun dailyActivityInput(e: Entity<out SvcDailyActivity>) = DailyActivityInput(
    date = LocalDate.parse(e.getFieldValue<TString>(SvcDailyActivity.Fields.DATE).get()),
    steps = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.STEPS).get().toInt(),
    distanceM = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.DISTANCE_M).get().toInt(),
    activeEnergyKj = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.ACTIVE_ENERGY_KJ).get().toInt(),
    activeMinutes = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.ACTIVE_MINUTES).get().toInt(),
)

internal fun sampleInput(e: Entity<out SvcHealthSample>) = SampleInput(
    metric = e.getFieldValue<TString>(SvcHealthSample.Fields.METRIC).get(),
    value = e.getFieldValue<TString>(SvcHealthSample.Fields.VALUE).get().toDoubleOrNull() ?: 0.0,
    unit = e.getFieldValue<TString>(SvcHealthSample.Fields.UNIT).get(),
    timeMs = e.getFieldValue<UnixInstant>(SvcHealthSample.Fields.TIME).toEpochMilliseconds(),
    source = e.getFieldValue<TString>(SvcHealthSample.Fields.SOURCE).get(),
    resting = e.getFieldValue<TBoolean>(SvcHealthSample.Fields.RESTING).get(),
)

internal fun healthSourceInput(e: Entity<out HealthSource>) = HealthSourceInput(
    kind = e.getFieldValue<TString>(HealthSource.Fields.KIND).get(),
    deviceId = e.getFieldValue<TString>(HealthSource.Fields.DEVICE_ID).get(),
    deviceLabel = e.getFieldValue<TString>(HealthSource.Fields.DEVICE_LABEL).get(),
    autoImport = e.getFieldValue<TBoolean>(HealthSource.Fields.AUTO_IMPORT).get(),
    lastRunAt = e.optionalFieldValue<TInteger>(HealthSource.Fields.LAST_RUN_AT)?.get(),
    lastStatus = e.optionalFieldValue<TString>(HealthSource.Fields.LAST_STATUS)?.get()?.takeIf { it.isNotBlank() },
    lastItemCount = e.optionalFieldValue<TInteger>(HealthSource.Fields.LAST_ITEM_COUNT)?.get(),
)

internal fun importRunInput(e: Entity<out HealthImportRun>) = ImportRunInput(
    deviceId = e.getFieldValue<TString>(HealthImportRun.Fields.DEVICE_ID).get(),
    kind = e.getFieldValue<TString>(HealthImportRun.Fields.KIND).get(),
    startedAt = e.getFieldValue<TInteger>(HealthImportRun.Fields.STARTED_AT).get(),
    finishedAt = e.getFieldValue<TInteger>(HealthImportRun.Fields.FINISHED_AT).get(),
    window = e.getFieldValue<TString>(HealthImportRun.Fields.WINDOW).get(),
    status = e.getFieldValue<TString>(HealthImportRun.Fields.STATUS).get(),
    itemCount = e.getFieldValue<TInteger>(HealthImportRun.Fields.ITEM_COUNT).get(),
    workoutCount = e.getFieldValue<TInteger>(HealthImportRun.Fields.WORKOUT_COUNT).get(),
    byteCount = e.optionalFieldValue<TInteger>(HealthImportRun.Fields.BYTE_COUNT)?.get(),
    fileName = e.optionalFieldValue<TString>(HealthImportRun.Fields.FILE_NAME)?.get()?.takeIf { it.isNotBlank() },
)

internal fun <E : Data, R> TResult<TList<E>>.rowsOf(row: (E) -> R): Read<List<R>> =
    fold(onSuccess = { Read.Ok(it.toList().map(row)) }, onFailure = { Read.Failed(it) })

// ── rule 2: the day window ───────────────────────────────────────────────────────────────────

/** A parsed `[from, to]` day window, or `null` for a malformed / reversed / too-wide one. */
internal fun dayWindowOrNull(from: String, to: String): ClosedRange<LocalDate>? {
    val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(to) }.getOrNull() ?: return null
    if (end < start) return null
    if (end.toEpochDays() - start.toEpochDays() + 1 > MAX_AGGREGATE_WINDOW_DAYS) return null
    return start..end
}

/**
 * The window's days, merged exactly as native Home merges them (`FitProjection.dailyAggregates`),
 * and only the days inside the window — a workout read by instant may straddle its edges.
 */
internal fun aggregatesIn(
    window: ClosedRange<LocalDate>,
    stored: List<DailyActivityInput>,
    workouts: List<WorkoutInput>,
    zone: TimeZone,
): List<DailyAggregate> =
    FitProjection.dailyAggregates(stored.filter { it.date in window }, workouts, zone)
        .filter { it.date in window }

// ── JS rows ──────────────────────────────────────────────────────────────────────────────────

@JsExport
class FitDayJs internal constructor(
    /** `YYYY-MM-DD`, in the reader zone. */
    val date: String,
    val steps: Int,
    val distanceM: Int,
    val activeEnergyKj: Int,
    val activeMinutes: Int,
)

internal fun DailyAggregate.toJs() = FitDayJs(date.toString(), steps, distanceM, activeEnergyKj, activeMinutes)

/** A History row and the summary half of the detail: what `FitProjection.workout` says, as data. */
@JsExport
class FitWorkoutJs internal constructor(
    val workoutId: String,
    /** `Running` · `Walking` · `Cycling` · `Strength` · `Other` (`FitProjection.activityType`). */
    val activityType: String,
    /** `Recorded` · `HealthConnect` · `AppleHealth` · `File` · `Manual` · `Detected`. */
    val source: String,
    val title: String,
    /** Epoch ms. */
    val startMs: Double,
    val endMs: Double,
    /** ISO local date-time in the reader zone (`2026-07-06T07:14`), the day the row groups under. */
    val startLocal: String,
    val durationSec: Int,
    // ⚠ `null` = unknown (the store's `0` sentinel), never zero.
    val distanceM: Int?,
    val activeEnergyKj: Int?,
    val avgHr: Int?,
    /** From the HR stream; always `null` on a History row (no stream is read there). */
    val maxHr: Int?,
    val steps: Int?,
    /** `Workout.effectivePaceSecPerKm`: derived from distance and time when the store has none. */
    val paceSecPerKm: Int?,
)

internal fun Workout.toJs(input: WorkoutInput) = FitWorkoutJs(
    workoutId = workoutId,
    activityType = activityType.name,
    source = source.name,
    title = title,
    startMs = input.startMs.toDouble(),
    endMs = input.endMs.toDouble(),
    startLocal = start.toString(),
    durationSec = durationSec,
    distanceM = distanceM,
    activeEnergyKj = activeEnergyKj,
    avgHr = avgHr,
    maxHr = maxHr,
    steps = steps,
    paceSecPerKm = effectivePaceSecPerKm,
)

@JsExport
class FitHrPointJs internal constructor(
    /** Seconds from the workout's start. */
    val tSec: Int,
    val bpm: Int,
)

/** One GPS fix in WGS84 degrees — the data W-MAP draws; no 0..1 schematic crosses the bridge. */
@JsExport
class FitRoutePointJs internal constructor(
    val tSec: Int,
    val lat: Double,
    val lng: Double,
    /** Metres, or `null` when the fix carried no altitude. */
    val altM: Double?,
)

/** The detail read: the summary, plus the decoded `Workout.Streams` blob. */
@JsExport
class FitWorkoutDetailJs internal constructor(
    val workout: FitWorkoutJs,
    val heartRate: Array<FitHrPointJs>,
    val route: Array<FitRoutePointJs>,
)

internal fun WorkoutStreams.heartRateJs(): Array<FitHrPointJs> =
    heartRate.map { FitHrPointJs(it.tSec, it.bpm) }.toTypedArray()

internal fun WorkoutStreams.routeJs(): Array<FitRoutePointJs> =
    route.map {
        FitRoutePointJs(
            tSec = it.tSec,
            lat = it.latE7 / 1e7,
            lng = it.lngE7 / 1e7,
            altM = it.altCm.takeIf { cm -> cm != org.tenshu.health.common.RouteSample.NO_ALT }?.let { cm -> cm / 100.0 },
        )
    }.toTypedArray()

@JsExport
class FitGoalsJs internal constructor(
    val activeMinutes: Int,
    val steps: Int,
)

@JsExport
class FitProfileJs internal constructor(
    /** ⚠ Every field but [units] is `null` when unset — never a placeholder (`fit-app.md` §18.5). */
    val name: String?,
    val heightCm: Int?,
    val weightKg: Double?,
    /** `YYYY-MM-DD`. */
    val birthDate: String?,
    /** `female` · `male` · `other` (`BiologicalSex.token`). */
    val sex: String?,
    /** `metric` · `imperial` (`MeasurementSystem.token`). */
    val units: String,
)

/** One `DataSourceProjection` row, flattened; [state] is the sealed state's name. */
@JsExport
class FitDataSourceJs internal constructor(
    /** `TenshuHealth` · `HealthConnect` · `AppleHealth`. */
    val kind: String,
    val deviceId: String?,
    /** Another device's name; `null` for this browser's own row and for Tenshu Health. */
    val deviceLabel: String?,
    val isThisDevice: Boolean,
    /** A [FitDataSourceStateName]: the state list in `web/sdk/src/fit.ts` is the same set. */
    val state: String,
    /** Epoch ms of the run [state] describes, when it has one. */
    val atMs: Double?,
    val itemCount: Double?,
    val autoImport: Boolean,
    val needsAttention: Boolean,
)

/**
 * Every value [FitDataSourceJs.state] can carry: one per `DataSourceState` case, named in
 * [stateName]'s exhaustive `when`, so a new case does not compile until it has a name here.
 * ⚠ the state list in `web/sdk/src/fit.ts` must name exactly these, in this order
 * (`FitDataSourceStateTest` iterates [entries]; apex's `fitDataSourceStates.test.ts` diffs the files).
 * Several cases never occur on web today (`Running` needs a live import, `PermissionNeeded` and
 * `Unavailable` a platform store), but the projection can emit them, so the union names them.
 */
internal enum class FitDataSourceStateName {
    NeverRun, Imported, NoData, Failed, Running, Recording, PermissionNeeded, Unavailable, Sample,
}

internal fun DataSourceState.stateName(): FitDataSourceStateName = when (this) {
    is DataSourceState.Imported -> FitDataSourceStateName.Imported
    is DataSourceState.NoData -> FitDataSourceStateName.NoData
    is DataSourceState.Failed -> FitDataSourceStateName.Failed
    is DataSourceState.Running -> FitDataSourceStateName.Running
    is DataSourceState.Recording -> FitDataSourceStateName.Recording
    DataSourceState.NeverRun -> FitDataSourceStateName.NeverRun
    DataSourceState.PermissionNeeded -> FitDataSourceStateName.PermissionNeeded
    DataSourceState.Unavailable -> FitDataSourceStateName.Unavailable
    DataSourceState.Sample -> FitDataSourceStateName.Sample
}

internal fun DataSourceRow.toJs(): FitDataSourceJs {
    val (at, count) = when (val s = state) {
        is DataSourceState.Imported -> s.at to s.itemCount
        is DataSourceState.NoData -> s.at to null
        is DataSourceState.Failed -> s.at to null
        is DataSourceState.Running -> null to s.imported.toLong()
        else -> null to null
    }
    return FitDataSourceJs(
        kind = kind.name,
        deviceId = deviceId,
        deviceLabel = deviceLabel,
        isThisDevice = isThisDevice,
        state = state.stateName().name,
        atMs = at?.toDouble(),
        itemCount = count?.toDouble(),
        autoImport = autoImport,
        needsAttention = needsAttention,
    )
}
