@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.fit.model.DataSourceProjection
import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.WorkoutStreamsJournal
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.fold
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordDailyActivity
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.health.registerHealthSource
import org.tenshu.health.client.healthService
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `Fit` (`web-app-suite.md` Wave 3),
 * against the **real** in-process runtime with workouts, day rows, samples, sources and goals seeded
 * through the real `:health-service` / `:preference-service` ops.
 *
 * ⚠ `GlobalScope.promise`, not `runTest`: the service ops never complete on the test dispatcher.
 * ⚠ The jsTest suite shares ONE `JsRuntime`, and the workouts relation is the whole realm. So each
 * test seeds its own rows in its own random far-future window (years 2300–2900, which nothing else
 * in the realm can sit inside) and asserts only on rows inside it.
 *
 * They assert the keys `web/sdk/src/fit.ts` publishes and the values native
 * `FitProjectionRepository` would produce — not "returns something".
 */
class FitWebContractTest : SdkRuntimeTest() {

    private val client get() = JsRuntime.tenshuClient
    private val ctx get() = client.environment.tContext

    private fun stamp() = "${Date.now().toLong()}-${(0..999_999).random()}"

    /** A day no other test (or real row) shares. */
    private fun farDay(): LocalDate =
        LocalDate.fromEpochDays(LocalDate(2300, 1, 1).toEpochDays() + (0..220_000).random())

    private fun assertOk(result: TResult<Data?>, what: String) =
        result.fold(onSuccess = {}, onFailure = { throw AssertionError("$what failed: $it") })

    private suspend fun ok(promise: Promise<dynamic>, what: String): dynamic {
        val result = promise.await()
        assertEquals(true, result.ok, "$what must succeed; got ${JSON.stringify(result)}")
        return result
    }

    private suspend fun failureKind(promise: Promise<dynamic>, what: String): String {
        val result = promise.await()
        assertEquals(false, result.ok, "$what must fail; got ${JSON.stringify(result)}")
        return result.error.kind as String
    }

    private suspend fun seedWorkout(id: String, startMs: Long, minutes: Int, distanceM: Long = 0) = assertOk(
        client.healthService.recordWorkout(
            Workout.create(
                ctx, id, "Running", "Manual", UnixInstantDto(startMs), UnixInstantDto(startMs + minutes * 60_000L),
                durationSec = minutes * 60L, distanceM = distanceM,
            ),
        ),
        "recordWorkout($id)",
    )

    /** Every page from [top] down until a page reaches below [floor]: ids at or above it, per page. */
    private suspend fun pagesDownTo(top: Long, floor: Long, pageSize: Int): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        var at: String? = WorkoutCursor(top, null).encode()
        while (true) {
            // ⚠ Bounded: a cursor that re-serves its own row would otherwise page forever, and a hung
            // karma run reports no failure at all.
            assertTrue(pages.size < MAX_PAGES, "paging did not terminate: ${pages.flatten().take(20)}")
            val page = ok(Fit.workoutsPage(at, pageSize, "UTC"), "workoutsPage")
            val rows = page.workouts.unsafeCast<Array<dynamic>>()
            pages += rows.filter { (it.startMs as Double) >= floor }.map { it.workoutId as String }
            val next = page.nextCursor as String?
            if (next == null || rows.any { (it.startMs as Double) < floor }) return pages
            at = next
        }
    }

    // ── dashboard ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun dayAggregation_splitsAtLocalMidnight_inANonUtcZone() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val day = farDay()
        val utcMidnight = day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        // Tokyo is UTC+9: 14:30Z is 23:30 on `day`, 15:30Z is 00:30 on the next day.
        seedWorkout("late-$s", utcMidnight + (14 * 60 + 30) * 60_000L, minutes = 30, distanceM = 5_000)
        seedWorkout("early-$s", utcMidnight + (15 * 60 + 30) * 60_000L, minutes = 45, distanceM = 7_000)
        // A stored day row that owns steps but left active minutes at 0 (Health Connect does): the
        // shared projection fills the minutes from the workout, per field.
        assertOk(
            client.healthService.recordDailyActivity(DailyActivity.create(ctx, day.toString(), steps = 9_000)),
            "recordDailyActivity",
        )
        val next = LocalDate.fromEpochDays(day.toEpochDays() + 1)

        val tokyo = ok(Fit.dailyAggregates(day.toString(), next.toString(), "Asia/Tokyo"), "Tokyo")
            .days.unsafeCast<Array<dynamic>>()
        assertEquals(listOf(day.toString(), next.toString()), tokyo.map { it.date as String })
        assertEquals(9_000, tokyo[0].steps)
        assertEquals(30, tokyo[0].activeMinutes, "only the 23:30 workout is on the first Tokyo day")
        assertEquals(5_000, tokyo[0].distanceM, "stored 0 falls back to the workout's distance")
        assertEquals(0, tokyo[1].steps)
        assertEquals(45, tokyo[1].activeMinutes, "the 00:30 workout is the next Tokyo day's")

        // The same rows in UTC are one day: the zone, not the data, moved the boundary.
        val utc = ok(Fit.dailyAggregates(day.toString(), next.toString(), "UTC"), "UTC")
            .days.unsafeCast<Array<dynamic>>()
        assertEquals(listOf(day.toString()), utc.map { it.date as String })
        assertEquals(75, utc[0].activeMinutes)
        assertEquals(12_000, utc[0].distanceM)
    }

    @Test
    fun dayAggregation_refusesABadWindowOrZone() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertEquals("error_fit_bad_window", failureKind(Fit.dailyAggregates("2026-02-01", "2026-01-01", ""), "reversed"))
        assertEquals("error_fit_bad_window", failureKind(Fit.dailyAggregates("2024-01-01", "2026-01-01", ""), "too wide"))
        assertEquals("error_fit_bad_window", failureKind(Fit.dailyAggregates("yesterday", "2026-01-01", ""), "not a date"))
        assertEquals("error_fit_invalid_zone", failureKind(Fit.dailyAggregates("2026-01-01", "2026-01-02", "Mars/Olympus"), "zone"))
    }

    /** What an `observe…` call answered within a bound: its terminal error, or every push it made. */
    private suspend fun observeEnds(subscribe: ((dynamic) -> Unit, (String) -> Unit) -> () -> Unit): String {
        val ended = kotlinx.coroutines.CompletableDeferred<String>()
        val pushed = mutableListOf<String>()
        val stop = subscribe({ r -> pushed += JSON.stringify(r) }, { m -> ended.complete(m) })
        try {
            return kotlinx.coroutines.withTimeoutOrNull(5_000) { ended.await() }
                ?: throw AssertionError("the subscription never ended; it pushed $pushed")
        } finally {
            stop()
            assertEquals(emptyList(), pushed, "bad input ends the stream; it pushes nothing first")
        }
    }

    @Test
    fun observeDailyAggregates_withBadInput_endsThroughOnError() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val window = observeEnds { cb, err -> Fit.observeDailyAggregates("2026-02-01", "2026-01-01", "", cb, err) }
        assertTrue(window.startsWith("error_fit_bad_window"), window)
        val zone = observeEnds { cb, err -> Fit.observeDailyAggregates("2026-01-01", "2026-01-02", "Mars/Olympus", cb, err) }
        assertTrue(zone.startsWith("error_fit_invalid_zone"), zone)
    }

    // ── History ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun keysetPaging_neverRepeatsOrSkips_withEqualStartTimes_andAnArrivalAtTheBoundary() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val top = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 12 * 3_600_000L
        val floor = top - 10_000L
        // Four at the same millisecond (ids order them), two older, one older still.
        listOf("b", "d", "a", "c").forEach { seedWorkout("tie-$s-$it", top, minutes = 20) }
        seedWorkout("old-$s-x", top - 1_000, minutes = 20)
        seedWorkout("old-$s-y", top - 1_000, minutes = 20)
        seedWorkout("older-$s", top - 5_000, minutes = 20)

        val expected = listOf("a", "b", "c", "d").map { "tie-$s-$it" } + listOf("old-$s-x", "old-$s-y", "older-$s")
        for (size in listOf(1, 2, 3, 7)) {
            val pages = pagesDownTo(top, floor, size)
            assertEquals(expected, pages.flatten(), "pageSize $size")
        }

        // A page boundary inside the tie, then a row arriving on each side of the cursor.
        val first = ok(Fit.workoutsPage(WorkoutCursor(top, null).encode(), 2, "UTC"), "first page")
        assertEquals(listOf("tie-$s-a", "tie-$s-b"), first.workouts.unsafeCast<Array<dynamic>>().map { it.workoutId as String })
        seedWorkout("tie-$s-0", top, minutes = 20) // sorts before the cursor: not served again
        seedWorkout("tie-$s-bb", top, minutes = 20) // after the cursor, same millisecond: served
        val rest = mutableListOf<String>()
        var at = first.nextCursor as String?
        while (at != null) {
            assertTrue(rest.size < MAX_PAGES, "paging did not terminate: $rest")
            val page = ok(Fit.workoutsPage(at, 2, "UTC"), "next page")
            val rows = page.workouts.unsafeCast<Array<dynamic>>()
            rest += rows.filter { (it.startMs as Double) >= floor }.map { it.workoutId as String }
            at = if (rows.any { (it.startMs as Double) < floor }) null else page.nextCursor as String?
        }
        assertEquals(listOf("tie-$s-bb", "tie-$s-c", "tie-$s-d", "old-$s-x", "old-$s-y", "older-$s"), rest)
    }

    @Test
    fun aHistoryRow_isTheProjectedWorkout() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val top = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 7 * 3_600_000L
        seedWorkout("row-$s", top, minutes = 30, distanceM = 6_000)
        val row = ok(Fit.workoutsPage(WorkoutCursor(top, null).encode(), 1, "Europe/Berlin"), "page")
            .workouts.unsafeCast<Array<dynamic>>().single()
        assertEquals("row-$s", row.workoutId)
        assertEquals("Running", row.activityType)
        assertEquals("Manual", row.source)
        assertEquals(top.toDouble(), row.startMs)
        assertEquals(1_800, row.durationSec)
        assertEquals(6_000, row.distanceM)
        assertNull(row.avgHr, "the store's 0 sentinel is unknown, never zero")
        assertEquals(300, row.paceSecPerKm, "derived from distance and time, as natively")
        assertTrue((row.startLocal as String).endsWith("T08:00") || (row.startLocal as String).endsWith("T09:00"))
    }

    @Test
    fun aMalformedCursorOrPageSize_isATypedFailure() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertEquals("error_fit_bad_cursor", failureKind(Fit.workoutsPage("v2.5./a", 10, ""), "photo cursor"))
        assertEquals("error_fit_bad_cursor", failureKind(Fit.workoutsPage("w1.x.a", 10, ""), "no ms"))
        // ⚠⚠ `Long.MIN_VALUE - 1` wrapped to the top of History, so a client following `nextCursor`
        // paged for ever. Every non-canonical spelling of a position is refused with it.
        for (forged in listOf(
            "w1.-9223372036854775808.x", "w1.-1.x", "w1.-0.x", "w1.+5.x", "w1.05.x", "w1. 5.x",
            "w1.9223372036854775808.x",
        )) {
            assertEquals("error_fit_bad_cursor", failureKind(Fit.workoutsPage(forged, 10, ""), forged))
        }
        // The edges a real page can mint still decode.
        assertEquals(WorkoutCursor(0, "a"), WorkoutCursor.decode("w1.0.a"))
        assertEquals(WorkoutCursor(Long.MAX_VALUE, null), WorkoutCursor.decode("w1.9223372036854775807."))
        ok(Fit.workoutsPage("w1.0.a", 10, ""), "the epoch cursor")
        assertEquals("error_fit_bad_page_size", failureKind(Fit.workoutsPage(null, 0, ""), "0"))
        assertEquals("error_fit_bad_page_size", failureKind(Fit.workoutsPage(null, 201, ""), "201"))
    }

    // ── detail, not-found, delete ─────────────────────────────────────────────────────────────

    @Test
    fun aMissingWorkout_isNull_andDeletingIt_isNotFound() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val read = ok(Fit.workout("nope-$s", ""), "workout")
        assertNull(read.workout)
        assertEquals("error_fit_workout_not_found", failureKind(Fit.deleteWorkout("nope-$s"), "delete"))
    }

    @Test
    fun delete_removesTheWorkout_fromDetailAndHistory() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val top = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        seedWorkout("del-$s", top, minutes = 10)
        val before = ok(Fit.workout("del-$s", ""), "before")
        assertNotNull(before.workout)
        ok(Fit.deleteWorkout("del-$s"), "delete")
        val after = ok(Fit.workout("del-$s", ""), "after")
        assertNull(after.workout)
        assertEquals(emptyList(), pagesDownTo(top, top - 1, 5).flatten())
        assertEquals("error_fit_workout_not_found", failureKind(Fit.deleteWorkout("del-$s"), "twice"))
    }

    // ── GPX / TCX import ──────────────────────────────────────────────────────────────────────

    private fun gpx(startMs: Long): ByteArray {
        val t0 = Date(startMs.toDouble()).toISOString()
        val t1 = Date((startMs + 600_000).toDouble()).toISOString()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk>
                <name>Morning run</name>
                <type>running</type>
                <trkseg>
                  <trkpt lat="52.5200" lon="13.4050"><ele>34</ele><time>$t0</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
                  <trkpt lat="52.5230" lon="13.4090"><ele>35</ele><time>$t1</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().encodeToByteArray()
    }

    /** Two `<trk>`s in one file, each a two-fix track starting at its own instant. */
    private fun twoTracks(firstMs: Long, secondMs: Long, secondName: String = "Evening run"): ByteArray {
        fun trk(name: String, startMs: Long, hr: Int): String {
            val t0 = Date(startMs.toDouble()).toISOString()
            val t1 = Date((startMs + 600_000).toDouble()).toISOString()
            return "<trk><name>$name</name><type>running</type><trkseg>" +
                "<trkpt lat=\"52.5200\" lon=\"13.4050\"><time>$t0</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$hr</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>" +
                "<trkpt lat=\"52.5230\" lon=\"13.4090\"><time>$t1</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$hr</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>" +
                "</trkseg></trk>"
        }
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<gpx version=\"1.1\" creator=\"test\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">" +
            trk("Morning run", firstMs, 120) + trk(secondName, secondMs, 160) + "</gpx>").encodeToByteArray()
    }

    /**
     * Runs [body] with a realm, as a signed-in browser has one. ⚠ `Realm.create` answers `ok: false`
     * here (the relay is the unreachable sink) but has enabled sync locally first, which is the state
     * under test: a route import's content emit then really runs, and really fails. Asserted, so a
     * change that stops this from making a realm fails the test instead of quietly testing the
     * realm-less case.
     *
     * ⚠⚠ Locked again afterwards when this made it: the suite shares ONE runtime, and the other
     * suites assume no realm. While a refused `fetch` still escaped the content emit
     * (`TransportFailureMapping`), a leaked realm turned the Drive and Photos suites red, 22 cases.
     */
    private suspend fun withRealm(body: suspend () -> Unit) {
        val created = !JsRuntime.syncManager.isEnabled()
        if (created) {
            // ⚠ A real BIP-39 phrase: `SyncManager.initialize` refuses anything else, and then no
            // realm exists — the case this test is NOT about.
            Realm.create(Bip39.generateMnemonic(), "fit-test-device").await()
        }
        try {
            assertTrue(JsRuntime.syncManager.isEnabled(), "precondition: the runtime holds a realm")
            body()
        } finally {
            if (created) JsRuntime.syncManager.lock()
        }
    }

    /**
     * ⚠⚠ With a realm and no reachable relay, `RecordWorkout` saves the row, the local blob and the
     * journalled upload, and the refused chunk `PUT` is left to the drain. It is saved, so the answer
     * is `ok`, with the id in `streamsPending` — never `error_internal` for a workout History shows
     * (a `fetch` failure escaping the op).
     */
    @Test
    fun gpxImport_withARealmAndNoRelay_isSaved_withItsStreamsPending_andARetryQueuesNothingMore() = GlobalScope.promise {
        JsRuntime.awaitReady()
        withRealm { gpxImportWithARealm() }
    }

    private suspend fun gpxImportWithARealm() {
        val start = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 6 * 3_600_000L
        val id = "gpx-$start"

        val first = ok(Fit.importActivityFile("run.gpx", gpx(start), "UTC"), "import with a realm")
        assertEquals(listOf(id), first.workouts.unsafeCast<Array<dynamic>>().map { it.workoutId as String })
        assertEquals(listOf(id), first.streamsPending.unsafeCast<Array<String>>().toList(), "saved, upload queued")
        val ec = JsRuntime.executionContext
        assertEquals(1, WorkoutStreamsJournal.journalled(ec, id), "the drain holds exactly one upload for it")
        // Stored, and readable here with its route, which is what History and detail will show.
        assertEquals(listOf(listOf(id)), pagesDownTo(start, start - 1, 5))
        assertEquals(2, ok(Fit.workout(id, "UTC"), "detail").workout.route.unsafeCast<Array<dynamic>>().size)

        // A retry (the user did not believe it) is safe: still one row, still one queued upload.
        val contentBefore = WorkoutStreamsJournal.storedContentId(ec, id)
        val retry = ok(Fit.importActivityFile("run (1).gpx", gpx(start), "UTC"), "retry")
        assertEquals(listOf(id), retry.streamsPending.unsafeCast<Array<String>>().toList())
        assertEquals(1, WorkoutStreamsJournal.journalled(ec, id), "a retry replaces the intent, never adds one")
        assertTrue(WorkoutStreamsJournal.storedContentId(ec, id) != contentBefore, "precondition: the retry re-recorded")
        assertEquals(listOf(listOf(id)), pagesDownTo(start, start - 1, 5))
    }

    /** The multi-workout half: both stored workouts are reported, neither as a failure. */
    @Test
    fun aTwoWorkoutGpx_withARealmAndNoRelay_reportsBothStored() = GlobalScope.promise {
        JsRuntime.awaitReady()
        withRealm { twoWorkoutGpxWithARealm() }
    }

    private suspend fun twoWorkoutGpxWithARealm() {
        val a = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 7 * 3_600_000L
        val b = a + 3_600_000L
        val result = ok(Fit.importActivityFile("two.gpx", twoTracks(a, b), "UTC"), "two tracks")
        assertEquals(listOf("gpx-$a", "gpx-$b"), result.workouts.unsafeCast<Array<dynamic>>().map { it.workoutId as String })
        assertEquals(listOf("gpx-$a", "gpx-$b"), result.streamsPending.unsafeCast<Array<String>>().toList())
        assertEquals(listOf(listOf("gpx-$b", "gpx-$a")), pagesDownTo(b, a - 1, 5))
    }

    /**
     * Native has the same id rule: two tracks with the same first fix are ONE stored row, the later
     * one's. The answer lists that row once, as stored.
     */
    @Test
    fun twoTracksWithTheSameStart_areOneStoredWorkout_andTheAnswerSaysSo() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val start = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 8 * 3_600_000L
        val result = ok(Fit.importActivityFile("dup.gpx", twoTracks(start, start, "Second"), "UTC"), "same start")
        val listed = result.workouts.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("gpx-$start"), listed.map { it.workoutId as String })
        assertEquals("Second", listed[0].title, "the answer carries the write that won")
        assertEquals(listOf(listOf("gpx-$start")), pagesDownTo(start, start - 1, 5))
        assertEquals("Second", ok(Fit.workout("gpx-$start", "UTC"), "detail").workout.workout.title)
    }

    @Test
    fun gpxImport_createsExactlyOneWorkout_withItsRouteAndHeartRate() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val start = farDay().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 7 * 3_600_000L
        val bytes = gpx(start)

        val imported = ok(Fit.importActivityFile("run.gpx", bytes, "UTC"), "import")
            .workouts.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("gpx-$start"), imported.map { it.workoutId as String })
        assertEquals("File", imported[0].source)
        assertEquals("Running", imported[0].activityType)
        // The same file twice is one workout: the parser's id is `gpx-<start ms>`.
        ok(Fit.importActivityFile("run (1).gpx", bytes, "UTC"), "re-import")
        assertEquals(listOf(listOf("gpx-$start")), pagesDownTo(start, start - 1, 5))

        val detail = ok(Fit.workout("gpx-$start", "UTC"), "detail").workout
        assertEquals(600, detail.workout.durationSec)
        assertEquals(135, detail.workout.avgHr)
        assertEquals(150, detail.workout.maxHr, "max HR comes from the decoded stream")
        val route = detail.route.unsafeCast<Array<dynamic>>()
        assertEquals(2, route.size)
        assertEquals(52.52, route[0].lat as Double, 1e-6)
        assertEquals(13.409, route[1].lng as Double, 1e-6)
        assertEquals(34.0, route[0].altM as Double, 1e-6)
        assertEquals(listOf(0, 600), route.map { it.tSec as Int })
        assertEquals(listOf(120, 150), detail.heartRate.unsafeCast<Array<dynamic>>().map { it.bpm as Int })
    }

    @Test
    fun aBrokenFile_isATypedFailure_neverAnEmptySuccess() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val malformed = "<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><trkseg></gpx>".encodeToByteArray()
        assertEquals("error_fit_malformed_file", failureKind(Fit.importActivityFile("a.gpx", malformed, ""), "malformed"))
        val empty = "<?xml version=\"1.0\"?><gpx version=\"1.1\"></gpx>".encodeToByteArray()
        assertEquals("error_fit_no_workouts", failureKind(Fit.importActivityFile("b.gpx", empty, ""), "no track"))
        val fit = ByteArray(16).also { b -> ".FIT".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() } }
        assertEquals("error_fit_binary_unsupported", failureKind(Fit.importActivityFile("c.fit", fit, ""), "fit"))
        val text = "hello".encodeToByteArray()
        assertEquals("error_fit_unsupported_format", failureKind(Fit.importActivityFile("d.txt", text, ""), "txt"))
    }

    // ── goals, profile, data sources ──────────────────────────────────────────────────────────

    @Test
    fun aGoalEdit_persists_andAnInvalidGoal_isRefused() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val minutes = 40 + (0..50).random()
        val steps = 9_000 + (0..5_000).random()
        ok(Fit.setGoals(minutes, steps), "setGoals")
        val goals = ok(Fit.goals(), "goals").goals
        assertEquals(minutes, goals.activeMinutes)
        assertEquals(steps, goals.steps)
        assertEquals("error_fit_invalid_goal", failureKind(Fit.setGoals(0, steps), "zero"))
        val unchanged = ok(Fit.goals(), "goals again").goals
        assertEquals(minutes, unchanged.activeMinutes, "a refused write changed nothing")
    }

    /** Native's stepper bounds, at and one past each edge. */
    @Test
    fun goals_areBoundedByNativesSteppers() = GlobalScope.promise {
        JsRuntime.awaitReady()
        ok(Fit.setGoals(5, 1_000), "both minimums")
        ok(Fit.setGoals(240, 30_000), "both maximums")
        for ((m, st) in listOf(4 to 8_000, 241 to 8_000, 30 to 999, 30 to 30_001, 30 to 1_000_000_000, Int.MAX_VALUE to 8_000)) {
            assertEquals("error_fit_invalid_goal", failureKind(Fit.setGoals(m, st), "goals $m / $st"))
        }
        val kept = ok(Fit.goals(), "goals").goals
        assertEquals(240, kept.activeMinutes, "every refused write changed nothing")
        assertEquals(30_000, kept.steps)
    }

    /** The body and birth-date domains, at and one past each edge. */
    @Test
    fun profileValues_areBounded() = GlobalScope.promise {
        JsRuntime.awaitReady()
        ok(Fit.recordHeight(300), "300 cm")
        ok(Fit.recordWeight(1_000.0), "1000 kg")
        for (cm in listOf(0, 301, Int.MAX_VALUE)) {
            assertEquals("error_fit_invalid_profile_value", failureKind(Fit.recordHeight(cm), "height $cm"))
        }
        for (kg in listOf(0.0, 1_000.1, 1e300, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertEquals("error_fit_invalid_profile_value", failureKind(Fit.recordWeight(kg), "weight $kg"))
        }
        val p = ok(Fit.profile(), "profile").profile
        assertEquals(1_000.0, p.weightKg as Double, 1e-9, "the refused 1e300 stored nothing")

        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        ok(Fit.setBirthDate("1900-01-01"), "the earliest")
        ok(Fit.setBirthDate(today.toString()), "today")
        for (d in listOf("1899-12-31", today.plus(DatePeriod(days = 1)).toString(), "0001-01-01")) {
            assertEquals("error_fit_invalid_profile_value", failureKind(Fit.setBirthDate(d), "birthDate $d"))
        }
        assertEquals(today.toString(), ok(Fit.profile(), "profile").profile.birthDate)
        ok(Fit.setBirthDate(today.minus(DatePeriod(years = 30)).toString()), "restore a plausible date")
    }

    @Test
    fun profile_joinsThePreferencesWithTheNewestBodySamples() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        ok(Fit.setName("  Ada $s "), "setName")
        ok(Fit.setBirthDate("1990-05-30"), "setBirthDate")
        ok(Fit.setSex("female"), "setSex")
        ok(Fit.setUnits("imperial"), "setUnits")
        ok(Fit.recordHeight(171), "recordHeight")
        ok(Fit.recordWeight(70.04), "recordWeight")
        ok(Fit.recordWeight(74.25), "recordWeight newer")
        val p = ok(Fit.profile(), "profile").profile
        assertEquals("Ada $s", p.name)
        assertEquals("1990-05-30", p.birthDate)
        assertEquals("female", p.sex)
        assertEquals("imperial", p.units)
        assertEquals(171, p.heightCm)
        assertEquals(74.3, p.weightKg as Double, 1e-9, "the newest reading, one decimal")

        assertEquals("error_fit_invalid_profile_value", failureKind(Fit.setUnits("furlongs"), "units"))
        assertEquals("error_fit_invalid_profile_value", failureKind(Fit.setBirthDate("30.05.1990"), "date"))
        assertEquals("error_fit_invalid_profile_value", failureKind(Fit.recordWeight(0.0), "weight"))
        ok(Fit.setUnits("metric"), "restore units")
    }

    @Test
    fun dataSources_areTheProjectedRows_readOnly() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        assertOk(
            client.healthService.registerHealthSource(DataSourceProjection.KIND_HEALTH_CONNECT, "phone-$s", "Pixel $s"),
            "registerHealthSource",
        )
        val result = ok(Fit.dataSources(), "dataSources")
        val rows = result.sources.unsafeCast<Array<dynamic>>()
        val phone = rows.single { it.deviceId == "phone-$s" }
        assertEquals("HealthConnect", phone.kind)
        assertEquals(false, phone.isThisDevice, "a browser is never the phone")
        assertEquals("Pixel $s", phone.deviceLabel)
        assertEquals("NeverRun", phone.state)
        // The Tenshu Health row reads the realm-wide detection preference.
        assertEquals("TenshuHealth", rows.first().kind)
        assertEquals("Recording", rows.first().state)
    }

    @Test
    fun observeGoals_pushesAnEdit() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val target = 7_000 + (0..999).random()
        val seen = kotlinx.coroutines.CompletableDeferred<Int>()
        val stop = Fit.observeGoals({ r -> if (r.ok == true && r.goals.steps == target) seen.complete(target) }, { m -> seen.completeExceptionally(AssertionError(m)) })
        try {
            ok(Fit.setGoals(30, target), "setGoals")
            assertEquals(target, kotlinx.coroutines.withTimeout(10_000) { seen.await() })
        } finally {
            stop()
        }
    }

    private companion object {
        const val MAX_PAGES = 30
    }
}
