@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.fit.data.ActivityFile
import app.tenshu.fit.data.ActivityFileParser
import app.tenshu.fit.data.FitPreferences
import app.tenshu.fit.data.ImportedWorkout
import app.tenshu.fit.data.projection.FitProjection
import app.tenshu.fit.data.xml.JsXmlReader
import app.tenshu.fit.model.DataSourceProjection
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.TenshuHealthInput
import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.WorkoutChanges
import app.tenshu.sdk.internal.WorkoutStreamsJournal
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.Streams
import org.tenshu.core.health.deleteWorkout
import org.tenshu.core.health.recordHealthSample
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.inRange
import org.tenshu.core.resolve
import org.tenshu.health.client.healthService
import org.tenshu.health.common.WorkoutStreams
import org.tenshu.health.common.WorkoutStreamsCodec
import org.tenshu.preference.BiologicalSex
import org.tenshu.preference.MeasurementSystem
import org.tenshu.preference.OwnerPreferences
import org.tenshu.preference.Preference
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import kotlin.js.Promise
import org.tenshu.core.health.HealthSample as SvcHealthSample
import org.tenshu.core.health.Workout as SvcWorkout

/**
 * Fit on web (`web-app-suite.md` Wave 3): the `@JsExport` surface behind `@tenshu/sdk/fit`,
 * mirroring the parts of the native `FitRepository` (its real implementation is
 * `FitProjectionRepository`) that `fit-app.md` §Web scope leaves undisputed: the dashboard's daily
 * aggregates, the paged History, workout detail with its streams, goals, profile and the data-source
 * rows; and the pure-data writes — goals, profile, a hand-entered height / weight, delete, and a
 * GPX / TCX import from bytes the app hands over.
 *
 * ⚠⚠ **The projection is the native one.** Every fold runs through `:fit-projection`
 * (`FitProjection`, `DataSourceProjection`), the module native Fit calls; only the entity → input
 * extraction is copied, in `FitBook.kt`. What is **not** here, on purpose (§Web scope): recording (1,
 * 2), Takeout and binary `.FIT` (4, the latter a typed `error_fit_binary_unsupported`), the
 * auto-import switch and every Health Connect / HealthKit action (5), edit (6b: native has none),
 * onboarding, detection promotion, Browse categories. ⚠ Nothing here starts background work:
 * `HealthPlugin` runs no rollup maintainer, and no read below writes (`NoBackgroundWorkInATabTest`).
 *
 * Every call resolves `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, with `kind` a
 * catalogue key (`error_…`: [FitWebErrorKeys] or the service error's derived key). ⚠ **A failed read
 * is a failure, never an empty list or a default** — natively the reachability banner explains a
 * blank screen; the web has none. The goal and profile reads are strict for the same reason: a
 * failed preference read is `ok: false`, not 30 minutes / 8 000 steps.
 *
 * **Not-found:** [workout] of a missing id answers `ok: true` with `workout: null` (an answer, as
 * native `WorkoutRead.NotFound`). [deleteWorkout] of a missing id is `error_fit_workout_not_found`:
 * the op is idempotent and would succeed having deleted nothing, and a delete that touched nothing
 * must not read as done.
 *
 * **Reactivity:** where native has a Flow there is an `observe…` push form whose `onError` is
 * terminal ([observeDailyAggregates], [observeGoals], [observeProfile], [observeDataSources]).
 * History is snapshot + refetch over a keyset cursor (`FitBook.kt` rule 1); [observeWorkoutChanges]
 * says *when* to refetch, from the `WorkoutChanges` counter `HealthPlugin` already keeps.
 *
 * **Zones:** `zone` `""` is the browser's own zone; any other must be an IANA id the bundled
 * database knows, else `error_fit_invalid_zone` (rule 2).
 */
@JsExport
object Fit {

    // ── dashboard ─────────────────────────────────────────────────────────────────────────────

    /**
     * The day aggregates of `[from, to]` (`YYYY-MM-DD`, inclusive, at most 400 days), oldest first:
     * `{ ok, days }`. Exactly native Home's merge (`FitProjection.dailyAggregates`): the stored
     * `DailyActivity` row owns steps and its non-zero stats, workouts fill the rest, and active
     * minutes fall back to the steps estimate. A day with no data has no row.
     */
    fun dailyAggregates(from: String, to: String, zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val window = dayWindowOrNull(from, to) ?: return@promise badWindow(from, to)
        val tz = projectionZone(zone) ?: return@promise invalidZone(zone)
        val (startMs, endMs) = window.instantBounds(tz)
        val stored = health().dailyActivities.select { fields(*DAILY_ACTIVITY_FIELDS) }.getAll()
            .rowsOf(::dailyActivityInput)
        val workouts = health().aggregateWorkouts(startMs, endMs).getAll().rowsOf(::workoutInput)
        aggregatesResult(window, stored, workouts, tz)
    }

    /** [dailyAggregates], live: a sync-arriving day or a new workout re-emits. */
    fun observeDailyAggregates(
        from: String,
        to: String,
        zone: String,
        callback: (dynamic) -> Unit,
        onError: (String) -> Unit,
    ): () -> Unit {
        // ⚠ Bad input ENDS the subscription, through the terminal onError: a stream that pushed
        // one `{ ok: false }` and then held open would be a subscriber waiting on nothing, for ever.
        val window = dayWindowOrNull(from, to) ?: return refuse(onError, badWindow(from, to))
        val tz = projectionZone(zone) ?: return refuse(onError, invalidZone(zone))
        val (startMs, endMs) = window.instantBounds(tz)
        return observeJob(onError) {
            combine(
                health().dailyActivities.select { fields(*DAILY_ACTIVITY_FIELDS) }.flow()
                    .map { it.rowsOf(::dailyActivityInput) },
                health().aggregateWorkouts(startMs, endMs).flow().map { it.rowsOf(::workoutInput) },
            ) { stored, workouts -> aggregatesResult(window, stored, workouts, tz) }
                .collect { callback(it) }
        }
    }

    // ── History ───────────────────────────────────────────────────────────────────────────────

    /**
     * One keyset page of History, newest first: `{ ok, workouts, nextCursor }`.
     *
     * [cursor] is `null` (or `""`) for the head, else a previous page's `nextCursor`; `nextCursor` is
     * `null` after the last page. The token is opaque. [pageSize] must be `1..200`.
     *
     * ⚠ Keyset over the TOTAL order `(start desc, workoutId asc)`, the cursor naming the last row
     * served (`FitBook.kt` rule 1). Against workouts arriving or deleted between two calls: a row
     * served once is never served again and a row still present after the cursor is never skipped,
     * ties at the boundary millisecond included. A row arriving BEFORE the cursor is not in later
     * pages — refetch the head ([observeWorkoutChanges] says when). Not a snapshot.
     *
     * Rows carry no streams (`maxHr` is `null`); that is the detail read's job.
     */
    fun workoutsPage(cursor: String?, pageSize: Int, zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val at = WorkoutCursor.decode(cursor)
            ?: return@promise fitFailure(FitWebErrorKeys.BAD_CURSOR, "cursor '$cursor'")
        if (pageSize !in 1..MAX_WORKOUT_PAGE_SIZE) {
            return@promise fitFailure(FitWebErrorKeys.BAD_PAGE_SIZE, "pageSize $pageSize")
        }
        val tz = projectionZone(zone) ?: return@promise invalidZone(zone)
        // ⚠ The chain has no string `>` predicate, so the rest of the boundary millisecond is read
        // whole (one millisecond's workouts: a handful at most) and cut by id here. Everything older
        // is one bounded walk below it.
        val rows = mutableListOf<FitProjection.WorkoutInput>()
        if (at.afterId != null) {
            when (val r = workoutRows { workoutWindow(at.beforeMs, fromMs = at.beforeMs) }) {
                is Read.Failed -> return@promise fitFailure(r.error)
                is Read.Ok -> rows += r.value.filter { it.key().afterCursor(at) }.take(pageSize)
            }
        }
        if (rows.size < pageSize) {
            val olderThan = if (at.afterId != null) at.beforeMs - 1 else at.beforeMs
            when (val r = workoutRows { workoutWindow(olderThan).take(pageSize - rows.size) }) {
                is Read.Failed -> return@promise fitFailure(r.error)
                is Read.Ok -> rows += r.value
            }
        }
        val next = nextWorkoutCursor(rows.map { it.key() }, pageSize)
        okResult {
            it.workouts = rows.map { input -> FitProjection.workout(input, tz).toJs(input) }.toTypedArray()
            it.nextCursor = next?.encode()
        }
    }

    /**
     * Fires with a new version number whenever a workout is saved (here or by sync) or deleted —
     * the signal to refetch History's head. ⚠ A counter, not the rows: the pages stay keyset reads.
     */
    fun observeWorkoutChanges(callback: (Double) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) { WorkoutChanges.version.collect { callback(it.toDouble()) } }

    /**
     * One workout with its streams: `{ ok, workout: { workout, heartRate, route } | null }`. Native's
     * detail read (`FitProjectionRepository.workout`): a one-shot `resolve()` that materialises the
     * content-plane `Workout.Streams` blob (fetching it from the relay when it is not local yet), then
     * `FitProjection.workout(…, streams)` — `maxHr` comes from the HR stream. A summary-only workout
     * has empty streams. ⚠ `workout: null` = no such workout (an answer); `ok: false` = the read or
     * the stream decode failed.
     */
    fun workout(workoutId: String, zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val tz = projectionZone(zone) ?: return@promise invalidZone(zone)
        val found = health().workouts
            .search<SvcWorkout> { this.workoutId equalTo workoutId }
            .select { fields(*WORKOUT_FIELDS); relations(SvcWorkout.Streams) }
            .resolve()
        // ⚠ `if`, not a `when` over `TResult`: `TResult` is not sealed, so a `when` without `else`
        // compiles as a STATEMENT here and the promise resolves to `Unit` (`{}`) — pinned by
        // `FitWebContractTest`.
        if (found is TResult.Failure) return@promise fitFailure(found.error)
        val entity = (found as TResult.Success).value ?: return@promise okResult { it.workout = null }
        val streams = try {
            WorkoutStreamsCodec.decode(entity.get(SvcWorkout.Streams)?.get())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // ⚠ Native folds a decode failure into `WorkoutRead.Failed`. Here it is a typed
            // failure, never a workout with no route.
            return@promise fitFailure(FitWebErrorKeys.STREAMS_UNREADABLE, "streams of $workoutId: ${e.message}")
        }
        val input = workoutInput(entity)
        okResult { it.workout = detailJs(input, tz, streams) }
    }

    // ── goals + profile ───────────────────────────────────────────────────────────────────────

    /** `{ ok, goals: { activeMinutes, steps } }`; an unset goal reads its declared default. */
    fun goals(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        goalsResult(realmPref(FitPreferences.GoalActiveMinutes), realmPref(FitPreferences.GoalSteps))
    }

    fun observeGoals(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            combine(realmPrefFlow(FitPreferences.GoalActiveMinutes), realmPrefFlow(FitPreferences.GoalSteps)) { a, s ->
                goalsResult(a, s)
            }.collect { callback(it) }
        }

    /**
     * Both goals at once, as native `updateGoals`, inside native's own bounds: the goals dialog's
     * steppers (`GoalsDialog`, and iOS's, which it mirrors) hold [GOAL_ACTIVE_MINUTES] and
     * [GOAL_STEPS]. ⚠ The rows sync, so a value native cannot enter is a value native must not read.
     */
    fun setGoals(activeMinutes: Int, steps: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (activeMinutes !in GOAL_ACTIVE_MINUTES || steps !in GOAL_STEPS) {
            return@promise fitFailure(FitWebErrorKeys.INVALID_GOAL, "activeMinutes $activeMinutes, steps $steps")
        }
        writePrefs(
            { it.set(FitPreferences.GoalActiveMinutes, activeMinutes) },
            { it.set(FitPreferences.GoalSteps, steps) },
        )
    }

    /**
     * `{ ok, profile }`: the owner's preference rows joined with the newest `Height` / `Weight`
     * sample, as native `profile()` joins them (§18.2). ⚠ The samples are read per metric, newest
     * one, from `healthSamples` — not `latestSamples`, which no tab maintains (`FitBook.kt` rule 3).
     */
    fun profile(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        profileResult(
            OwnerReads(
                realmPref(OwnerPreferences.Name), realmPref(OwnerPreferences.BirthDate),
                realmPref(OwnerPreferences.Sex), realmPref(OwnerPreferences.Units),
            ),
            health().latestOf(Metric.Height).getAll().rowsOf(::sampleInput),
            health().latestOf(Metric.Weight).getAll().rowsOf(::sampleInput),
        )
    }

    fun observeProfile(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            val owner = combine(
                realmPrefFlow(OwnerPreferences.Name), realmPrefFlow(OwnerPreferences.BirthDate),
                realmPrefFlow(OwnerPreferences.Sex), realmPrefFlow(OwnerPreferences.Units),
            ) { n, b, s, u -> OwnerReads(n, b, s, u) }
            combine(
                owner,
                health().latestOf(Metric.Height).flow().map { it.rowsOf(::sampleInput) },
                health().latestOf(Metric.Weight).flow().map { it.rowsOf(::sampleInput) },
            ) { o, h, w -> profileResult(o, h, w) }.collect { callback(it) }
        }

    /** `units` is `metric` or `imperial`. */
    fun setUnits(units: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val system = MeasurementSystem.entries.firstOrNull { it.token == units }
            ?: return@promise invalidProfileValue("units '$units'")
        writePrefs({ it.set(OwnerPreferences.Units, system.token) })
    }

    /** Trimmed, as `TenshuOwnerProfile.setName`; `""` clears it. */
    fun setName(name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        writePrefs({ it.set(OwnerPreferences.Name, name.trim()) })
    }

    /** `YYYY-MM-DD` from [EARLIEST_BIRTH_DATE] to today (in the browser's zone), or `""` to clear. */
    fun setBirthDate(date: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (date.isNotEmpty()) {
            val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            if (parsed == null || parsed < EARLIEST_BIRTH_DATE || parsed > today) {
                return@promise invalidProfileValue("birthDate '$date'")
            }
        }
        writePrefs({ it.set(OwnerPreferences.BirthDate, date) })
    }

    /** `female` · `male` · `other`, or `""` to clear. */
    fun setSex(sex: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (sex.isNotEmpty() && BiologicalSex.fromToken(sex) == null) {
            return@promise invalidProfileValue("sex '$sex'")
        }
        writePrefs({ it.set(OwnerPreferences.Sex, sex) })
    }

    /**
     * A hand-entered height: a `Manual` `HealthSample` stamped now, the row Health Connect writes —
     * never a preference (§18.2). A correction is a new measurement, as natively.
     */
    fun recordHeight(centimetres: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (centimetres !in 1..MAX_HEIGHT_CM) return@promise invalidProfileValue("height $centimetres")
        recordBodySample("Height", centimetres.toString(), "cm")
    }

    /**
     * As [recordHeight]; one decimal, as every importer stores a weight. ⚠ Above 0 and at most
     * [MAX_WEIGHT_KG]: unbounded, `roundToInt` saturates `1e300` into a stored 214748364.7 kg.
     */
    fun recordWeight(kilograms: Double): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (!(kilograms > 0.0 && kilograms <= MAX_WEIGHT_KG)) return@promise invalidProfileValue("weight $kilograms")
        recordBodySample("Weight", ((kilograms * 10).roundToInt() / 10.0).toString(), "kg")
    }

    // ── data sources (read-only, §Web scope 5) ────────────────────────────────────────────────

    /**
     * `{ ok, sources, batchImport }`: the `DataSourceProjection` rows (native's `dataSources()`) and
     * the batch-import summary (`batchImport()`), read-only. `isThisDevice` is against this
     * browser's sync id, so in practice every Health Connect / Apple Health row is another device's.
     * No live import status exists on web. The Tenshu Health row reads the realm-wide auto-detect
     * preference.
     */
    fun dataSources(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        dataSourcesResult(
            health().sources.select { fields(*SOURCE_FIELDS) }.getAll().rowsOf(::healthSourceInput),
            health().importRuns.select { fields(*RUN_FIELDS) }.getAll().rowsOf(::importRunInput),
            realmPref(FitPreferences.AutoDetectActivities),
        )
    }

    fun observeDataSources(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            combine(
                health().sources.select { fields(*SOURCE_FIELDS) }.flow().map { it.rowsOf(::healthSourceInput) },
                health().importRuns.select { fields(*RUN_FIELDS) }.flow().map { it.rowsOf(::importRunInput) },
                realmPrefFlow(FitPreferences.AutoDetectActivities),
            ) { s, r, a -> dataSourcesResult(s, r, a) }.collect { callback(it) }
        }

    // ── writes ────────────────────────────────────────────────────────────────────────────────

    /**
     * Deletes one workout: its row, its streams blob and a tombstone, so it leaves every device
     * (`DeleteWorkout`). A missing id is `error_fit_workout_not_found`, checked first.
     */
    fun deleteWorkout(workoutId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val existing = health().workouts
            .search<SvcWorkout> { this.workoutId equalTo workoutId }
            .select { fields(SvcWorkout.Fields.WORKOUT_ID) }
            .resolve()
        when (existing) {
            is TResult.Failure -> return@promise fitFailure(existing.error)
            is TResult.Success -> if (existing.value == null) {
                return@promise fitFailure(FitWebErrorKeys.WORKOUT_NOT_FOUND, workoutId)
            }
        }
        done(health().deleteWorkout(workoutId))
    }

    /**
     * Imports one GPX or TCX file from [bytes] the app read from an `<input type="file">`:
     * `{ ok, workouts, streamsPending }`: the stored rows as History shows them (in [zone]), and the
     * ids among them whose route / HR upload has not reached the relay yet.
     *
     * The parser is native's (`:fit-shared-file` `ActivityFileParser` over the browser `DOMParser`);
     * the write is native's `HealthImportWriter` mapping, one `RecordWorkout` per workout, with its
     * route + HR streams when it has any. ⚠ Idempotent: a GPX workout's id is `gpx-<start ms>`, so the same
     * file twice is one workout. ⚠ So are two tracks (or two files) with the same first fix: the
     * later upserts over the earlier, as natively, and `workouts` lists each stored id once, with
     * the values of the write that won (`fit-app.md` §Web scope, as built).
     *
     * ⚠⚠ **`streamsPending`** (never absent; usually empty): the workout IS saved here, with its
     * streams readable on this device, and its upload is journalled for the sync tick to send once
     * the relay answers. Peers see the row first and the route later. A retry is safe but not
     * needed. The op succeeds in that state (a refused chunk `PUT` is the drain's to retry), so the
     * bridge reads the content journal for it (`WorkoutStreamsJournal`). A workout that did NOT
     * save stops the import with the service error's key, naming the ones that already did.
     *
     * ⚠⚠ The parser folds every failure into "nothing", so the cases are told apart here first:
     * `error_fit_unsupported_format` (neither GPX, TCX nor FIT), `error_fit_binary_unsupported`
     * (`.FIT`: no decoder on web), `error_fit_malformed_file` (the XML does not parse),
     * `error_fit_no_workouts` (it parses, but holds no track or activity), `error_fit_file_too_large`.
     * A file that imported nothing never answers `ok: true`.
     *
     * ⚠ Not recorded as a `HealthImportRun` (§Web scope 5, open): a tab runs no retention observer.
     */
    fun importActivityFile(name: String, bytes: ByteArray, zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val tz = projectionZone(zone) ?: return@promise invalidZone(zone)
        if (bytes.size > MAX_ACTIVITY_FILE_BYTES) {
            return@promise fitFailure(FitWebErrorKeys.FILE_TOO_LARGE, "${bytes.size} bytes")
        }
        val xml = JsXmlReader()
        val parser = ActivityFileParser(xml = xml)
        val file = ActivityFile(name, bytes)
        when (parser.detectFormat(file)) {
            ActivityFileParser.Format.Unknown -> return@promise fitFailure(FitWebErrorKeys.UNSUPPORTED_FORMAT, name)
            ActivityFileParser.Format.Fit -> return@promise fitFailure(FitWebErrorKeys.BINARY_UNSUPPORTED, name)
            ActivityFileParser.Format.Gpx, ActivityFileParser.Format.Tcx ->
                if (xml.parse(bytes) == null) return@promise fitFailure(FitWebErrorKeys.MALFORMED_FILE, name)
        }
        val workouts = parser.parse(file).workouts
        if (workouts.isEmpty()) return@promise fitFailure(FitWebErrorKeys.NO_WORKOUTS, name)
        // ⚠ Keyed by id, last write wins: two tracks with the same first fix are ONE `gpx-<ms>` row
        // (the second upserts over the first, as natively), so the answer lists what is stored.
        val stored = LinkedHashMap<String, ImportedWorkout>()
        val streamsPending = LinkedHashSet<String>()
        for (w in workouts) {
            // ⚠ No streams argument for a summary-only workout, as `HealthImportWriter` partitions:
            // an empty blob would still be stored and emitted to the content plane for nothing.
            val dense = w.route.isNotEmpty() || w.heartRate.isNotEmpty()
            val ec = JsRuntime.executionContext
            val contentBefore = if (dense) WorkoutStreamsJournal.storedContentId(ec, w.workoutId) else null
            val written = health().recordWorkout(
                w.toWorkout(),
                streams = if (dense) {
                    TByteArrayDto(WorkoutStreamsCodec.encode(WorkoutStreams(route = w.route, heartRate = w.heartRate)))
                } else {
                    null
                },
            )
            // A re-record (a retried import) supersedes the streams content the row named before.
            val contentNow = if (dense) WorkoutStreamsJournal.storedContentId(ec, w.workoutId) else null
            if (contentBefore != null && contentNow != null && contentNow != contentBefore) {
                WorkoutStreamsJournal.discardSuperseded(ec, w.workoutId, contentBefore)
            }
            if (written is TResult.Failure) return@promise importFailure(written.error, stored.keys)
            // ⚠ Saved, and its upload journalled before any byte was sent: a relay that refused the
            // `PUT` leaves the entry for the sync tick's drain, and the op still succeeds.
            if (dense && WorkoutStreamsJournal.uploadJournalled(ec, w.workoutId)) {
                streamsPending += w.workoutId
            } else {
                streamsPending -= w.workoutId
            }
            stored.remove(w.workoutId)
            stored[w.workoutId] = w
        }
        okResult {
            it.workouts = stored.values.map { w ->
                val input = w.toInput()
                FitProjection.workout(input, tz).toJs(input)
            }.toTypedArray()
            it.streamsPending = streamsPending.toTypedArray()
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────

    private fun client(): TenshuClient = JsRuntime.tenshuClient

    private fun health(): HealthService = client().healthService

    private fun deviceIdOrNull(): String? =
        runCatching { JsRuntime.syncManager.currentDeviceId }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun workoutRows(
        window: org.tenshu.core.collection.list.TList<SvcWorkout>.() -> org.tenshu.core.collection.list.TList<SvcWorkout>,
    ): Read<List<FitProjection.WorkoutInput>> =
        health().workouts.window().select { fields(*WORKOUT_FIELDS) }.getAll().rowsOf(::workoutInput)

    /**
     * The workouts an aggregate window needs: started inside it, by instant. Native bounds the same
     * read by date and caps it (`buildAggregateWorkoutInputs`).
     */
    private fun HealthService.aggregateWorkouts(startMs: Long, endMs: Long) = workouts
        .searchAll<SvcWorkout> { start.inRange(startMs, endMs) }
        .sortByDescending { start }
        .take(AGGREGATE_WORKOUT_CAP)
        .select { fields(*WORKOUT_FIELDS) }

    /** The newest reading of one metric — `sampleInputsFor` with a window of one (rule 3). */
    private fun HealthService.latestOf(metric: Metric) = FitProjection.metricName(metric).let { name ->
        healthSamples
            .searchAll<SvcHealthSample> { this.metric equalTo name }
            .sortByDescending { time }
            .take(1)
            .select { fields(*HEALTH_SAMPLE_FIELDS) }
    }

    private suspend fun recordBodySample(metric: String, value: String, unit: String): dynamic {
        val sample = SvcHealthSample.create(
            context = client().environment.tContext,
            metric = metric,
            value = value,
            unit = unit,
            time = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
            source = FitProjection.sourceName(app.tenshu.fit.model.Source.Manual),
        )
        return done(health().recordHealthSample(sample))
    }

    private fun ImportedWorkout.toWorkout(): SvcWorkout = SvcWorkout.create(
        context = client().environment.tContext,
        workoutId = workoutId,
        activityType = activityType,
        source = source,
        start = UnixInstantDto(startMs),
        end = UnixInstantDto(endMs),
        durationSec = durationSec,
        title = title,
        distanceM = distanceM,
        activeEnergyKj = activeEnergyKj,
        avgHr = avgHr,
        steps = steps,
    )

    private fun ImportedWorkout.toInput() = FitProjection.WorkoutInput(
        workoutId = workoutId, activityType = activityType, source = source, startMs = startMs,
        endMs = endMs, durationSec = durationSec, title = title, distanceM = distanceM,
        activeEnergyKj = activeEnergyKj, avgHr = avgHr, steps = steps,
    )

    private fun detailJs(input: FitProjection.WorkoutInput, tz: TimeZone, streams: WorkoutStreams) =
        FitWorkoutDetailJs(
            workout = FitProjection.workout(input, tz, streams).toJs(input),
            heartRate = streams.heartRateJs(),
            route = streams.routeJs(),
        )

    private fun aggregatesResult(
        window: ClosedRange<LocalDate>,
        stored: Read<List<FitProjection.DailyActivityInput>>,
        workouts: Read<List<FitProjection.WorkoutInput>>,
        tz: TimeZone,
    ): dynamic = when {
        stored is Read.Failed -> fitFailure(stored.error)
        workouts is Read.Failed -> fitFailure(workouts.error)
        else -> okResult {
            it.days = aggregatesIn(window, (stored as Read.Ok).value, (workouts as Read.Ok).value, tz)
                .map { day -> day.toJs() }.toTypedArray()
        }
    }

    private fun goalsResult(activeMinutes: Read<Int>, steps: Read<Int>): dynamic = when {
        activeMinutes is Read.Failed -> fitFailure(activeMinutes.error)
        steps is Read.Failed -> fitFailure(steps.error)
        else -> okResult { it.goals = FitGoalsJs((activeMinutes as Read.Ok).value, (steps as Read.Ok).value) }
    }

    private fun profileResult(
        owner: OwnerReads,
        height: Read<List<FitProjection.SampleInput>>,
        weight: Read<List<FitProjection.SampleInput>>,
    ): dynamic {
        val failed = listOf(owner.name, owner.birthDate, owner.sex, owner.units, height, weight)
            .filterIsInstance<Read.Failed>().firstOrNull()
        if (failed != null) return fitFailure(failed.error)
        fun <T> Read<T>.value() = (this as Read.Ok).value
        val heightCmRaw = height.value().firstOrNull()?.value
        return okResult {
            it.profile = FitProfileJs(
                name = owner.name.value().takeIf { n -> n.isNotBlank() },
                // Whole centimetres, as native `profile()` rounds them.
                heightCm = heightCmRaw?.roundToInt(),
                weightKg = weight.value().firstOrNull()?.value,
                birthDate = owner.birthDate.value().takeIf { d -> d.isNotBlank() }
                    ?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.toString() },
                sex = BiologicalSex.fromToken(owner.sex.value())?.token,
                units = MeasurementSystem.fromToken(owner.units.value()).token,
            )
        }
    }

    private suspend fun dataSourcesResult(
        sources: Read<List<app.tenshu.fit.model.HealthSourceInput>>,
        runs: Read<List<app.tenshu.fit.model.ImportRunInput>>,
        autoDetect: Read<Boolean>,
    ): dynamic {
        if (sources is Read.Failed) return fitFailure(sources.error)
        if (runs is Read.Failed) return fitFailure(runs.error)
        if (autoDetect is Read.Failed) return fitFailure(autoDetect.error)
        val rows = DataSourceProjection.dataSourceRows(
            sources = (sources as Read.Ok).value,
            thisDeviceId = deviceIdOrNull(),
            tenshu = TenshuHealthInput(autoDetect = (autoDetect as Read.Ok).value),
        )
        // ⚠ `canImportFiles = true`: this surface can import a GPX / TCX file, unlike a platform
        // with no parser — the summary's "import a file" affordance is honest here.
        val batch = DataSourceProjection.batchImportSummary((runs as Read.Ok).value, canImportFiles = true)
        return okResult {
            it.sources = rows.map { row -> row.toJs() }.toTypedArray()
            val b: dynamic = js("({})")
            b.lastRunAtMs = batch?.lastRunAt?.toDouble()
            b.lastStatus = batch?.lastStatus?.name
            b.runCount = batch?.runCount ?: 0
            it.batchImport = if (batch == null) null else b
        }
    }

    private suspend fun writePrefs(vararg writes: suspend (org.tenshu.preference.PreferenceService) -> TResult<*>): dynamic {
        for (write in writes) {
            val r = write(client().preferenceService)
            if (r is TResult.Failure) return fitFailure(r.error)
        }
        return okResult()
    }

    private fun done(result: TResult<*>): dynamic = when (result) {
        is TResult.Failure -> fitFailure(result.error)
        else -> okResult()
    }

    /**
     * An import that stopped at a workout that did not save. ⚠ The service error's own key, with
     * the workouts this file had already stored named in the message: they stay, and a retry is
     * safe, because an import is idempotent by id.
     */
    private fun importFailure(error: org.tenshu.core.TError, saved: Collection<String>): dynamic =
        if (saved.isEmpty()) {
            fitFailure(error)
        } else {
            failureResult(
                error.errorCopyKey().value,
                "$error (already saved from this file: ${saved.joinToString()}; a retry is safe)",
            )
        }

    private fun badWindow(from: String, to: String): dynamic =
        fitFailure(FitWebErrorKeys.BAD_WINDOW, "[$from, $to] (at most $MAX_AGGREGATE_WINDOW_DAYS days)")

    private fun invalidZone(zone: String): dynamic = fitFailure(FitWebErrorKeys.INVALID_ZONE, zone)

    private fun invalidProfileValue(what: String): dynamic = fitFailure(FitWebErrorKeys.INVALID_PROFILE_VALUE, what)

    private fun ClosedRange<LocalDate>.instantBounds(tz: TimeZone): Pair<Long, Long> =
        start.atStartOfDayIn(tz).toEpochMilliseconds() to
            endInclusive.plus(DatePeriod(days = 1)).atStartOfDayIn(tz).toEpochMilliseconds()

    /**
     * An `observe…` call refused before it subscribed: [onError] fires once, with the refusal's
     * `kind: message`; the returned unsubscribe only cancels a call not yet fired. ⚠ Asynchronously, as every other
     * end of a stream is, so a caller that wires `onError` after the call returns still sees it.
     */
    private fun refuse(onError: (String) -> Unit, failure: dynamic): () -> Unit {
        val kind = failure.error.kind as String
        val message = failure.error.message as String?
        val job = GlobalScope.launch { onError(if (message == null) kind else "$kind: $message") }
        return { job.cancel() }
    }

    /**
     * Runs one push stream. ⚠ [onError] is terminal and fires for every end but the subscriber's
     * own cancel — a throw, or a flow that completed (the `Contacts.observeJob` contract).
     */
    private fun observeJob(onError: (String) -> Unit, body: suspend () -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                body()
                FIT_OBSERVE_ENDED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            onError(failure)
        }
        return { job.cancel() }
    }
}

/** The four owner-preference reads a profile joins (outside [Fit]: an exported object's nested type would be exported). */
private class OwnerReads(
    val name: Read<String>,
    val birthDate: Read<String>,
    val sex: Read<String>,
    val units: Read<String>,
)

/** The terminal diagnostic for a Fit observe stream that completed rather than failed. */
internal const val FIT_OBSERVE_ENDED = "fit stream ended"

private fun FitProjection.WorkoutInput.key() = WorkoutKey(startMs, workoutId)
