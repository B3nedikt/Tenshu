package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.reachability.RealmVerdict
import app.tenshu.fit.model.CategoryTeaser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.recordHealthSample
import org.tenshu.core.orThrow
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.client.healthService
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.proxy.backfillLatestSamples
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import app.tenshu.liststate.ListLoadState
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **runtime exercise** (`docs/proposals/fit-app.md` §3 D1 + `timeline-services.md` §7):
 * stand up `:health-service` in one in-process proxy + a real `TenshuClient`, seed the workout
 * fixture, and drive [FitProjectionRepository] end-to-end — proving the whole pipe (seed → service
 * → relation maintainer → client read → projector → UI model). The closest headless approximation
 * of the Fit app running over the real store. Mirrors `TimelinePipelineIntegrationTest`.
 */
class FitPipelineIntegrationTest {

    @Test
    fun seededWorkoutsProjectThroughTheRepository() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        // First-run data lands through the import seam (reference importer → shared writer),
        // exactly as the in-process app does — proving the whole import → store → projector pipe.
        val importer = ReferenceHealthImporter()
        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(importer.availableSources().first(), CategoryId.entries.toSet()).health,
        )

        val repo = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // History feed: every imported workout projects, newest first.
        val workouts = repo.workouts().first()
        val ids = workouts.map { it.workoutId }.toSet()
        assertTrue(
            ids.containsAll(
                setOf(
                    ReferenceHealthImporter.WORKOUT_TODAY_RUN, ReferenceHealthImporter.WORKOUT_YESTERDAY_WALK,
                    ReferenceHealthImporter.WORKOUT_CYCLE, ReferenceHealthImporter.WORKOUT_STRENGTH,
                ),
            ),
            "expected all imported workouts, got $ids",
        )
        assertTrue(workouts.zipWithNext().all { (a, b) -> a.start >= b.start }, "workouts not newest-first")

        // Home: the seeded today-workout gives Activity a live teaser + a today-dated aggregate.
        // assertIs, not itemsOrEmpty: over a real proxy a Failed read is a genuine possibility, and
        // flattening it here would turn "the read broke" into "the store is empty" — the exact
        // confusion ListLoadState exists to stop, re-introduced in the test that would catch it.
        val aggregates = assertIs<ListLoadState.Loaded<app.tenshu.fit.model.DailyAggregate>>(
            repo.aggregates().first(),
        ).items
        assertTrue(aggregates.isNotEmpty())
        val todayAggregate = aggregates.last()
        assertTrue(todayAggregate.date == repo.today, "latest aggregate should be today")
        // The stored DailyActivity wins over the workout-derived fallback → real steps, not 0.
        assertEquals(6_120, todayAggregate.steps, "today's steps should come from the stored DailyActivity")

        val categories = assertIs<ListLoadState.Loaded<app.tenshu.fit.model.CategorySummary>>(
            repo.categories().first(),
        ).items
        val activity = categories.first { it.id == CategoryId.Activity }
        assertTrue(activity.teaser is CategoryTeaser.ActiveMinutesToday, "Activity teaser should reflect today's minutes")

        // Sleep: the seeded week projects, newest night first, and lights up the Sleep category.
        val sleep = repo.sleepSessions().first()
        assertEquals(7, sleep.size, "expected a week of seeded sleep sessions")
        assertTrue(sleep.zipWithNext().all { (a, b) -> a.date >= b.date }, "sleep sessions not newest-first")
        val lastNight = sleep.first()
        assertEquals(repo.today, lastNight.date, "the latest sleep session should be last night")
        // Ported mockup stages: 14 + 96 + 232 + 90 = 432 min.
        assertEquals(432 * 60, lastNight.durationSec)
        assertEquals(4, lastNight.stages.size, "all four stages present for last night")
        val sleepCard = categories.first { it.id == CategoryId.Sleep }
        assertTrue(sleepCard.teaser is CategoryTeaser.SleepDuration, "Sleep teaser should reflect last night")

        // Body + Vitals: the seeded samples project (latest per metric) and light up their cards.
        val samples = repo.samplesLatest().first()
        assertTrue(samples.any { it.metric == app.tenshu.fit.model.Metric.Weight }, "weight sample expected")
        assertTrue(samples.any { it.metric == app.tenshu.fit.model.Metric.HeartRate && it.resting }, "resting HR expected")
        assertTrue(categories.first { it.id == CategoryId.Body }.teaser is CategoryTeaser.Weight, "Body teaser = weight")
        assertTrue(categories.first { it.id == CategoryId.Vitals }.teaser is CategoryTeaser.RestingHeartRate, "Vitals teaser = resting HR")

        // Medical: the seeded records project and the card counts them.
        val medical = repo.medicalRecords().first()
        assertEquals(6, medical.size, "expected the six seeded medical records")
        assertTrue(medical.zipWithNext().all { (a, b) -> a.effectiveDate >= b.effectiveDate }, "medical not newest-first")
        assertEquals(CategoryTeaser.RecordCount(6), categories.first { it.id == CategoryId.Medical }.teaser)

        // Single-entity lookup.
        val run = assertIs<WorkoutRead.Found>(repo.workout(ReferenceHealthImporter.WORKOUT_TODAY_RUN)).workout
        assertEquals("Morning run", run.title)

        // A finished recording lands through the real write path (addWorkout → recordWorkout).
        // Its step count round-trips through the Workout.STEPS field (recorder → store → detail).
        repo.addWorkout(run.copy(workoutId = "recorded-1", title = "Lunch jog", steps = 4_820))
        val recorded = assertIs<WorkoutRead.Found>(repo.workout("recorded-1")).workout
        assertEquals(4_820, recorded.steps, "steps persist through addWorkout → recordWorkout → workout()")
    }

    @Test
    fun reactiveWorkoutsFlowReflectsAddWorkout() = kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("20s")) {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // A live subscriber to the reactive History/Home list (exactly what HomeViewModel /
        // HistoryViewModel do: collect repo.workouts() for the lifetime of the screen).
        val latest = kotlinx.coroutines.flow.MutableStateFlow<List<app.tenshu.fit.model.Workout>>(emptyList())
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            repo.workouts().collect { latest.value = it }
        }

        // Record a run AFTER the screen is already subscribed — the on-device path.
        val start = kotlinx.datetime.LocalDateTime(2026, 7, 8, 9, 0, 0)
        repo.addWorkout(
            app.tenshu.fit.model.Workout(
                workoutId = "rec-live-1",
                activityType = app.tenshu.fit.model.ActivityType.Running,
                source = app.tenshu.fit.model.Source.Recorded,
                title = "", device = "", start = start, end = start, durationSec = 60,
            ),
        )
        // The reactive list must surface the recorded workout to the already-subscribed screen.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) {
                while (latest.value.none { it.workoutId == "rec-live-1" }) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    @Test
    fun latestSampleRollup_maintainedOnWrites() = runTest {
        // The LatestSample rollup (fit-read-scaling.md): samplesLatest() reads the
        // maintained one-row-per-(metric,resting) rollup, kept fresh by the proxy-side
        // maintainer on every sample save — never by scanning the time-series.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val importer = ReferenceHealthImporter()
        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(importer.availableSources().first(), CategoryId.entries.toSet()).health,
        )
        val repo = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // The teaser reflects the newest seeded weight (74.2, two days ago in the fixture).
        val seeded = repo.samplesLatest().first()
        assertEquals(74.2, seeded.first { it.metric == app.tenshu.fit.model.Metric.Weight }.value)
        assertTrue(seeded.any { it.metric == app.tenshu.fit.model.Metric.HeartRate && it.resting })

        // A newer reading updates the rollup row…
        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        client.healthService.recordHealthSample(
            HealthSample.create(
                proxy.tContext,
                metric = "Weight", value = "70.0", unit = "kg",
                time = UnixInstantDto(nowMs),
                source = "Manual",
            ),
        ).orThrow("recordHealthSample")
        assertEquals(
            70.0,
            repo.samplesLatest().first().first { it.metric == app.tenshu.fit.model.Metric.Weight }.value,
        )

        // …and an out-of-order OLDER reading (a historical import after live captures) must
        // not regress the teaser.
        client.healthService.recordHealthSample(
            HealthSample.create(
                proxy.tContext,
                metric = "Weight", value = "99.9", unit = "kg",
                time = UnixInstantDto(nowMs - 90L * 24 * 3_600_000),
                source = "Manual",
            ),
        ).orThrow("recordHealthSample")
        assertEquals(
            70.0,
            repo.samplesLatest().first().first { it.metric == app.tenshu.fit.model.Metric.Weight }.value,
        )
    }

    @Test
    fun latestSampleRollup_backfillsAPreRollupStore() = runTest {
        // The upgrade path: a store whose samples were written without the rollup has rows but no
        // rollup — the init-time backfill derives it once from the time-series.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val importer = ReferenceHealthImporter()
        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(importer.availableSources().first(), CategoryId.entries.toSet()).health,
        )

        // Simulate the pre-rollup store: drop the derived rows + their relation membership.
        val ctx = proxy.executionContext
        val latestRef = org.tenshu.core.common.proxy.ArrayDatabaseReference(
            arrayId = org.tenshu.core.entity.DataId.RelationDataId.create(
                context = proxy.tContext,
                type = org.tenshu.core.collection.list.TList.ID,
                relationId = org.tenshu.core.health.HealthService.Relations.LATEST_SAMPLES,
            ),
            isEntity = false,
            elementSchemaId = org.tenshu.core.health.LatestSample.ID,
        )
        val rows = ctx.database.getArrayData(latestRef, null)?.toList().orEmpty()
        assertTrue(rows.isNotEmpty(), "the import should have maintained rollup rows")
        rows.forEach { row ->
            val id = ctx.getDataId(row) ?: error("rollup row without a DataId")
            ctx.database.removeFromArray(latestRef, id)
            ctx.database.deleteData(id)
        }
        assertTrue(ctx.database.getArrayMemberHashes(latestRef).isNullOrEmpty())

        // The init-time backfill re-derives from the retained time-series.
        ctx.backfillLatestSamples()
        val latest = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
            .samplesLatest().first()
        assertEquals(74.2, latest.first { it.metric == app.tenshu.fit.model.Metric.Weight }.value)
        assertTrue(latest.any { it.metric == app.tenshu.fit.model.Metric.HeartRate && it.resting })
    }

    @Test
    fun sharedReadStreamsServeMultipleCollectorsAndReplay() = kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("20s")) {
        // fit-read-scaling.md §Latency: with an app scope bound, every screen rides
        // ONE shared proxy-side stream per store source instead of opening its own. Two live
        // collectors (Home + History) must both see a workout recorded after they subscribed,
        // and a late third collector (a tab revisit) must repaint from the replay cache.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val appScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val repo = FitProjectionRepository(
                client, proxy.tContext,
                importer = ReferenceHealthImporter(),
                probeScope = appScope,
                controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
            )
            val a = kotlinx.coroutines.flow.MutableStateFlow<List<app.tenshu.fit.model.Workout>>(emptyList())
            val b = kotlinx.coroutines.flow.MutableStateFlow<List<app.tenshu.fit.model.Workout>>(emptyList())
            backgroundScope.launch(kotlinx.coroutines.Dispatchers.Default) { repo.workouts().collect { a.value = it } }
            backgroundScope.launch(kotlinx.coroutines.Dispatchers.Default) { repo.workouts().collect { b.value = it } }

            val start = kotlinx.datetime.LocalDateTime(2026, 7, 8, 9, 0, 0)
            repo.addWorkout(
                app.tenshu.fit.model.Workout(
                    workoutId = "rec-shared-1",
                    activityType = app.tenshu.fit.model.ActivityType.Running,
                    source = app.tenshu.fit.model.Source.Recorded,
                    title = "", device = "", start = start, end = start, durationSec = 60,
                ),
            )
            awaitUntil("both collectors see the recorded workout") {
                a.value.any { it.workoutId == "rec-shared-1" } && b.value.any { it.workoutId == "rec-shared-1" }
            }

            // replay = 1: a late subscriber gets the cached list without a fresh store read.
            val replayed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { repo.workouts().first() }
            }
            assertTrue(replayed.any { it.workoutId == "rec-shared-1" }, "late collector repaints from replay")
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun workoutStreamsRoundTripThroughTheStore() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // A high-fidelity import: one workout carrying real GPS fixes + an HR series.
        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedHealth(
                workouts = listOf(
                    ImportedWorkout(
                        workoutId = "streamed-1",
                        activityType = "Running",
                        source = "File",
                        startMs = 1_700_000_000_000,
                        endMs = 1_700_000_600_000,
                        durationSec = 600,
                        title = "Streamed run",
                        distanceM = 2_000,
                        route = listOf(
                            RouteSample(0, 375_000_000, -1_224_000_000, altCm = 1_200),
                            RouteSample(30, 375_030_000, -1_224_020_000, altCm = 1_240),
                            RouteSample(60, 375_050_000, -1_223_980_000, altCm = 1_215),
                        ),
                        heartRate = listOf(HrSample(0, 98), HrSample(30, 132), HrSample(60, 151)),
                    ),
                ),
            ),
        )

        // The detail read decodes the blob → an abstract route + the HR curve; maxHr is derived.
        val w = assertIs<WorkoutRead.Found>(repo.workout("streamed-1")).workout
        assertEquals(3, w.route.size, "route decoded from the stored streams")
        assertTrue(w.hasRoute)
        assertTrue(w.route.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(listOf(98, 132, 151), w.hrSeries.map { it.bpm })
        assertEquals(151, w.maxHr, "maxHr derived from the HR stream")

        // The reactive History list is byte-less: it still projects the workout, with no streams.
        val listed = repo.workouts().first().first { it.workoutId == "streamed-1" }
        assertTrue(listed.route.isEmpty() && listed.hrSeries.isEmpty(), "list projection stays streams-less")
    }

    @Test
    fun recordedTrackPersistsRealRoute() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // A first-party recording: the engine hands addWorkout a summary Workout + the dense track
        // of REAL GPS fixes + HR. The real repo encodes it (real lat/lng → E7) to Workout.Streams.
        val start = kotlinx.datetime.LocalDateTime(2026, 7, 8, 9, 0, 0)
        val workout = app.tenshu.fit.model.Workout(
            workoutId = "rec-track-1",
            activityType = app.tenshu.fit.model.ActivityType.Running,
            source = app.tenshu.fit.model.Source.Recorded,
            title = "",
            device = "",
            start = start,
            end = start,
            durationSec = 60,
        )
        val track = app.tenshu.fit.model.RecordedTrack(
            route = listOf(
                app.tenshu.fit.model.GpsFix(0, 37.5, -122.4, altM = 12.0),
                app.tenshu.fit.model.GpsFix(30, 37.5003, -122.4002, altM = 14.0),
            ),
            hr = listOf(app.tenshu.fit.model.HrPoint(0, 100), app.tenshu.fit.model.HrPoint(30, 140)),
        )
        repo.addWorkout(workout, track)

        val read = assertIs<WorkoutRead.Found>(repo.workout("rec-track-1")).workout
        assertEquals(2, read.route.size, "real GPS route decoded from the stored streams")
        assertTrue(read.hasRoute)
        assertEquals(listOf(100, 140), read.hrSeries.map { it.bpm })
    }

    @Test
    fun recorderOutboxJournalsAndReplays() = runTest {
        val store = InMemoryOutboxStore()
        val start = kotlinx.datetime.LocalDateTime(2026, 7, 8, 9, 0, 0)
        fun workout(id: String) = app.tenshu.fit.model.Workout(
            workoutId = id,
            activityType = app.tenshu.fit.model.ActivityType.Running,
            source = app.tenshu.fit.model.Source.Recorded,
            title = "", device = "", start = start, end = start, durationSec = 60, steps = 900,
        )

        // Healthy proxy: the save journals, drains immediately → Synced, nothing pending.
        val proxy = stackProxy(this)
        val healthyRepo = FitProjectionRepository(
            clientOf(proxy), proxy.tContext,
            importer = ReferenceHealthImporter(), outbox = RecordedWorkoutOutbox(store),
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        assertEquals(WorkoutSaveOutcome.Synced, healthyRepo.addWorkout(workout("rec-outbox-1")))
        assertTrue(store.backing.isEmpty(), "a synced save leaves no journal entry")
        assertIs<WorkoutRead.Found>(healthyRepo.workout("rec-outbox-1"))

        // Dead proxy (no health-service implementation registered — every record op fails): the
        // save survives as a journal entry and reports SavedLocally instead of throwing.
        val deadProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
            },
            coroutineScope = this,
        )
        val deadRepo = FitProjectionRepository(
            clientOf(deadProxy), deadProxy.tContext,
            importer = ReferenceHealthImporter(), outbox = RecordedWorkoutOutbox(store),
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        assertEquals(WorkoutSaveOutcome.SavedLocally, deadRepo.addWorkout(workout("rec-outbox-2")))
        assertEquals(1, store.backing.size, "the failed save is journalled")

        // "Next launch": a repository over the healthy proxy + the SAME store replays the journal
        // (HealthImportBootstrap calls drainPendingWorkouts) — the workout lands, entry removed.
        healthyRepo.drainPendingWorkouts()
        assertTrue(store.backing.isEmpty(), "replayed entries are removed on confirmed write")
        val replayed = assertIs<WorkoutRead.Found>(healthyRepo.workout("rec-outbox-2")).workout
        assertEquals(900, replayed.steps, "recorder steps survive the journal round-trip")
    }

    @Test
    fun controlReachabilitySignalsAndRecovers() = kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
        // Two repositories over one shared journal, standing in for "the same device before and
        // after Control comes back": the DEAD one (a proxy with no health implementations —
        // every op fails like an unreachable Control) journals a recording; the probed one
        // detects the outage via the injected probe seam and, on recovery, flips the signal
        // and drains the journal. Recovery of the *read streams* is not this engine's job —
        // `TList.flow()` retries its own failed subscribe; see
        // [aReadStreamRecoversFromAFailedSubscribeWithNoFitSideResubscriber].
        // The probe is a seam because this in-process harness executes
        // ops through the shared TContext — a real transport death (reads HANGING, ops folding
        // to "Proxy not connected") cannot be simulated here; the transport's Failure folding
        // is production behaviour and the on-device pass covers it.
        var dead = true
        val store = InMemoryOutboxStore()
        val start = kotlinx.datetime.LocalDateTime(2026, 7, 8, 9, 0, 0)

        // Finished recording while Control is down → journalled (§9.13), SavedLocally.
        val deadProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
            },
            coroutineScope = this,
        )
        val deadRepo = FitProjectionRepository(
            clientOf(deadProxy), deadProxy.tContext,
            importer = ReferenceHealthImporter(), outbox = RecordedWorkoutOutbox(store),
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        val saved = deadRepo.addWorkout(
            app.tenshu.fit.model.Workout(
                workoutId = "rec-reach-1",
                activityType = app.tenshu.fit.model.ActivityType.Running,
                source = app.tenshu.fit.model.Source.Recorded,
                title = "", device = "", start = start, end = start, durationSec = 60,
            ),
        )
        assertEquals(WorkoutSaveOutcome.SavedLocally, saved)
        assertEquals(1, store.backing.size)

        // The probed repository over the healthy proxy: while [dead] the probe reports the
        // outage; flipping it is "Control returned".
        val proxy = stackProxy(this)
        val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        // Released once the "still unknown" assertion below has been made; see the probe lambda.
        val probeGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val repo = FitProjectionRepository(
                clientOf(proxy), proxy.tContext,
                importer = ReferenceHealthImporter(),
                outbox = RecordedWorkoutOutbox(store),
                probeScope = probeScope,
                probeIntervalMs = 50,
                // ⚠⚠ Gated, and the gate is what makes the next line an assertion rather than a
                // coin toss. `ControlReachability` runs its loop on [probeScope] — a REAL
                // `Dispatchers.Default` scope — so `awaitNextProbe()`'s 50 ms is real time, and
                // the verdict it then writes (`_state.value = false`, because [dead]) races the
                // test thread. Lose that 50 ms race — trivial on a loaded gate — and the state is
                // already `false` when the "no outcome yet" assertion reads it
                // (`expected null, but was:<false>`).
                //
                // Suspending inside the probe holds the loop *before* it can produce any verdict,
                // so the unknown state is observable for as long as the test needs rather than for
                // however long the scheduler happens to allow.
                probe = { probeGate.await(); !dead },
                controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
            )
            // The pre-probe state is `null`, NOT `false` — the shell shows no banner until
            // something is actually known. Asserting `false` here instead would make the test
            // pass while inverting the user-visible contract: an "offline" banner on every
            // cold start, before anything has been tried.
            assertNull(repo.controlReachable().value, "no outcome yet → unknown")

            probeGate.complete(Unit)
            awaitUntil("unreachable signalled (banner state)") { repo.controlReachable().value == false }

            // Control returns: signal flips, journal drains.
            dead = false
            awaitUntil("reachable signalled") { repo.controlReachable().value == true }
            awaitUntil("journal drained") { store.backing.isEmpty() }
            assertIs<WorkoutRead.Found>(
                repo.workout("rec-reach-1"),
                "the journalled recording landed on recovery",
            )
        } finally {
            probeScope.cancel()
        }
    }

    /**
     * ⚠⚠ ADR 0025 §Decision 8.6. A Control that is reachable but has no
     * realm answers every write, so a recorder outbox drained into it lands in a store that belongs
     * to no realm (the sync observer only holds those in memory). With the realm known absent the
     * journal must stay put — new saves journal and are not written either — and it must drain on
     * its own once a realm appears.
     */
    @Test
    fun aKnownNoRealmHoldsTheRecorderOutboxAndItDrainsWhenARealmAppears() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val store = InMemoryOutboxStore()
            val proxy = stackProxy(this)
            val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            var hasRealm = false
            try {
                val repo = FitProjectionRepository(
                    clientOf(proxy), proxy.tContext,
                    importer = ReferenceHealthImporter(),
                    outbox = RecordedWorkoutOutbox(store),
                    probeScope = probeScope,
                    probeIntervalMs = 50,
                    probe = { true },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
                )
                awaitUntil("realm known absent") { repo.reach.realmVerdict.value == RealmVerdict.ABSENT }

                // A recording finished while the wall is up: journalled, NOT written.
                assertEquals(WorkoutSaveOutcome.SavedLocally, repo.addWorkout(recorded("rec-held-1")))
                repo.drainPendingWorkouts()
                assertEquals(1, store.backing.size, "the held entry stays in the durable outbox")
                assertTrue(repo.workout("rec-held-1") !is WorkoutRead.Found, "nothing was written with no realm")

                // ⚠⚠ An organic markUnreachable() and the reconnect that follows are not a realm —
                // the journal must survive both.
                repo.reach.markUnreachable()
                awaitUntil("reconnected") { repo.reach.state.value == true }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(300) }
                assertEquals(RealmVerdict.ABSENT, repo.reach.realmVerdict.value)
                assertEquals(1, store.backing.size, "an outage + reconnect with no realm drained nothing")

                // The user sets Control up: the held drain runs by itself.
                hasRealm = true
                awaitUntil("held journal drained once a realm appeared") { store.backing.isEmpty() }
                assertIs<WorkoutRead.Found>(repo.workout("rec-held-1"), "the held recording landed")
            } finally {
                probeScope.cancel()
            }
        }

    /**
     * The pair to the hold: a realm that is present writes as before, and so does an engine with no
     * realm probe wired at all (UNKNOWN — iOS route-1, previews). ⚠ A probe that is wired but whose
     * read FAILS is not this case: that stays PENDING (see the next test).
     */
    @Test
    fun aPresentOrUnwiredRealmWritesTheRecorderOutboxAsBefore() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            for ((label, answer) in listOf("present" to true, "unwired" to null)) {
                val store = InMemoryOutboxStore()
                val proxy = stackProxy(this)
                val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                try {
                    val repo = FitProjectionRepository(
                        clientOf(proxy), proxy.tContext,
                        importer = ReferenceHealthImporter(),
                        outbox = RecordedWorkoutOutbox(store),
                        probeScope = probeScope,
                        probeIntervalMs = 50,
                        probe = { true },
                        controlHasRealm = if (answer == null) app.tenshu.reachability.RealmProbe.None("the unwired case under test") else app.tenshu.reachability.RealmProbe.Wired { answer },
                    )
                    awaitUntil("$label: realm verdict settled") { !repo.reach.realmWritesHeld }
                    assertEquals(WorkoutSaveOutcome.Synced, repo.addWorkout(recorded("rec-$label")), label)
                    assertTrue(store.backing.isEmpty(), "$label: a written save leaves no journal entry")
                    assertIs<WorkoutRead.Found>(repo.workout("rec-$label"), label)
                } finally {
                    probeScope.cancel()
                }
            }
        }

    /**
     * ⚠⚠ Right after a Control restart Fit's realm read fails —
     * its session is not admitted yet. A failed read is not an answer, so the verdict stays PENDING:
     * a recorder save is journalled and NOT written. The next read that answers releases it, and
     * the release-edge drain writes it.
     */
    @Test
    fun aFailedRealmReadHoldsWritesUntilTheNextAnswer() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val store = InMemoryOutboxStore()
            val proxy = stackProxy(this)
            val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            var admitted = false
            try {
                val repo = FitProjectionRepository(
                    clientOf(proxy), proxy.tContext,
                    importer = ReferenceHealthImporter(),
                    outbox = RecordedWorkoutOutbox(store),
                    probeScope = probeScope,
                    probeIntervalMs = 50,
                    probe = { true },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { if (admitted) true else error("session has no admitted schema announce") },
                )
                awaitUntil("reachable") { repo.reach.state.value == true }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(200) }
                assertEquals(RealmVerdict.PENDING, repo.reach.realmVerdict.value, "failed reads are not answers")

                assertEquals(WorkoutSaveOutcome.SavedLocally, repo.addWorkout(recorded("rec-unadmitted")))
                assertEquals(1, store.backing.size, "journalled, not written")
                assertTrue(repo.workout("rec-unadmitted") !is WorkoutRead.Found)

                admitted = true
                awaitUntil("the answer released the held drain") { store.backing.isEmpty() }
                assertEquals(RealmVerdict.PRESENT, repo.reach.realmVerdict.value)
                assertIs<WorkoutRead.Found>(repo.workout("rec-unadmitted"))
            } finally {
                probeScope.cancel()
            }
        }

    /**
     * ⚠⚠ The realm becomes known ABSENT while a long import is running (here:
     * Control drops mid-read and its re-read answers "no realm"). The gate is observed for the whole
     * run, so the import writes nothing, records nothing, and reports the stop for the re-run.
     */
    @Test
    fun aRealmKnownAbsentMidImportStopsItsWrites() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val proxy = stackProxy(this)
            val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            var answer: Boolean? = true
            lateinit var repo: FitProjectionRepository
            val importer = object : HealthImporter {
                override fun availableSources() = listOf(app.tenshu.fit.model.ImportSource.HealthConnect)
                override suspend fun autoRefreshReady() = true
                override suspend fun import(
                    source: app.tenshu.fit.model.ImportSource,
                    categories: Set<CategoryId>,
                    window: app.tenshu.fit.model.ImportWindow,
                ): ImportOutcome {
                    // The Health Connect read is in flight; meanwhile Control drops, and its re-read
                    // answers "no realm".
                    answer = false
                    repo.reach.markUnreachable()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        while (repo.reach.realmVerdict.value != RealmVerdict.ABSENT) kotlinx.coroutines.delay(10)
                    }
                    return ImportOutcome.Success(ImportedHealth(workouts = listOf(importedWorkout("hc-mid-1"))))
                }
            }
            try {
                repo = FitProjectionRepository(
                    clientOf(proxy), proxy.tContext,
                    importer = importer,
                    probeScope = probeScope,
                    probeIntervalMs = 50,
                    probe = { true },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer },
                )
                awaitUntil("realm present") { repo.reach.realmVerdict.value == RealmVerdict.PRESENT }
                repo.startImport(CategoryId.entries.toSet(), automatic = true)

                assertEquals(1L, repo.realmHeldStops(), "the stop is reported for the re-run")
                assertTrue(repo.workout("hc-mid-1") !is WorkoutRead.Found, "nothing written once the realm was known absent")
            } finally {
                probeScope.cancel()
            }
        }

    /**
     * A Health Connect stand-in whose read runs [duringRead] — the window in which the realm
     * verdict changes under a running pass.
     */
    private fun hcImporter(id: String, duringRead: suspend () -> Unit = {}) = object : HealthImporter {
        override fun availableSources() = listOf(app.tenshu.fit.model.ImportSource.HealthConnect)
        override suspend fun autoRefreshReady() = true
        override suspend fun import(
            source: app.tenshu.fit.model.ImportSource,
            categories: Set<CategoryId>,
            window: app.tenshu.fit.model.ImportWindow,
        ): ImportOutcome {
            duringRead()
            return ImportOutcome.Success(ImportedHealth(workouts = listOf(importedWorkout(id))))
        }
    }

    private suspend fun awaitVerdict(repo: FitProjectionRepository, verdict: RealmVerdict) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) {
                while (repo.reach.realmVerdict.value != verdict) kotlinx.coroutines.delay(10)
            }
        }

    /**
     * ⚠⚠ The exact sequence: a realm is PRESENT, the import is running, Control
     * drops and comes back — and until the realm read answers the verdict is PENDING. A batch in that
     * window is HELD (the single `holdsWrites` predicate), and the pass counts as held. Paired with the
     * same import and no reconnect, which writes.
     */
    @Test
    fun aReconnectMidImportHoldsTheBatchUntilTheRealmReadAnswers() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            for (reconnect in listOf(true, false)) {
                val proxy = stackProxy(this)
                val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                val readMayAnswer = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
                var gateRead: kotlinx.coroutines.CompletableDeferred<Unit> = readMayAnswer
                lateinit var repo: FitProjectionRepository
                val id = "hc-reconnect-$reconnect"
                try {
                    repo = FitProjectionRepository(
                        clientOf(proxy), proxy.tContext,
                        importer = hcImporter(id) {
                            if (reconnect) {
                                gateRead = kotlinx.coroutines.CompletableDeferred() // the re-read hangs
                                repo.reach.markUnreachable()
                                awaitVerdict(repo, RealmVerdict.RECHECKING)
                            }
                        },
                        probeScope = probeScope,
                        probeIntervalMs = 50,
                        probe = { true },
                        controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { gateRead.await(); true },
                    )
                    awaitVerdict(repo, RealmVerdict.PRESENT)
                    repo.startImport(CategoryId.entries.toSet(), automatic = true)

                    if (reconnect) {
                        assertEquals(RealmVerdict.RECHECKING, repo.reach.realmVerdict.value)
                        assertEquals(1L, repo.realmHeldStops(), "the pass counts as held")
                        assertTrue(repo.workout(id) !is WorkoutRead.Found, "no batch during PENDING")
                        gateRead.complete(Unit)
                    } else {
                        assertEquals(0L, repo.realmHeldStops())
                        assertIs<WorkoutRead.Found>(repo.workout(id), "with the realm present it writes")
                    }
                } finally {
                    probeScope.cancel()
                }
            }
        }

    /**
     * ⚠⚠ End to end through the real bootstrap: it starts under UNKNOWN, the
     * verdict turns held (ABSENT) while the pass is getting ready, so the import is REJECTED AT
     * ENTRY. That rejection is a held pass: setting Control up (ABSENT → PRESENT) re-runs it and the
     * workout lands — not at some later foreground. Paired with no change of verdict, which imports
     * once and is not re-run.
     */
    @Test
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun anImportRejectedAtEntryReRunsWhenTheGateReleases() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            for (turnsAbsent in listOf(true, false)) {
                val proxy = stackProxy(this)
                val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                val answer = kotlin.concurrent.atomics.AtomicReference<Boolean?>(true)
                val reads = kotlin.concurrent.atomics.AtomicInt(0) // crosses threads: the pass runs on Default
                // Held open by the test: `reads` counts a read STARTING, and the write lands only after
                // it returns, so sampling `reads` and then asserting the row would race.
                val readMayReturn = kotlinx.coroutines.CompletableDeferred<Unit>()
                lateinit var repo: FitProjectionRepository
                val id = "hc-entry-$turnsAbsent"
                val importer = object : HealthImporter {
                    override fun availableSources() = listOf(app.tenshu.fit.model.ImportSource.HealthConnect)
                    override suspend fun autoRefreshReady(): Boolean {
                        // The readiness work in flight; meanwhile Control is polled and answers ABSENT.
                        if (turnsAbsent && repo.realmHeldStops() == 0L) {
                            answer.store(false)
                            repo.reach.markUnreachable()
                            awaitVerdict(repo, RealmVerdict.ABSENT)
                        }
                        return true
                    }
                    override suspend fun import(
                        source: app.tenshu.fit.model.ImportSource,
                        categories: Set<CategoryId>,
                        window: app.tenshu.fit.model.ImportWindow,
                    ): ImportOutcome {
                        reads.addAndFetch(1)
                        readMayReturn.await()
                        return ImportOutcome.Success(ImportedHealth(workouts = listOf(importedWorkout(id))))
                    }
                }
                try {
                    repo = FitProjectionRepository(
                        clientOf(proxy), proxy.tContext,
                        importer = importer,
                        probeScope = probeScope,
                        probeIntervalMs = 50,
                        probe = { true },
                        controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer.load() },
                    )
                    awaitVerdict(repo, RealmVerdict.PRESENT)
                    HealthImportBootstrap(repo, probeScope, importer = importer)

                    if (turnsAbsent) {
                        awaitUntil("the import was rejected at entry") { repo.realmHeldStops() == 1L }
                        assertEquals(0, reads.load(), "rejected before reading Health Connect")
                        answer.store(true) // the user sets Control up
                        awaitUntil("the held pass re-ran on the release") { reads.load() == 1 }
                    } else {
                        awaitUntil("the pass imported") { reads.load() == 1 }
                    }
                    assertTrue(repo.workout(id) !is WorkoutRead.Found, "nothing lands while the read is in flight")
                    readMayReturn.complete(Unit)
                    awaitUntil("the workout landed") { repo.workout(id) is WorkoutRead.Found }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(300) }
                    assertEquals(1, reads.load(), "exactly one import — no duplicate pass")
                } finally {
                    probeScope.cancel()
                }
            }
        }

    /**
     * ⚠⚠ A MANUAL import the realm gate refuses is not folded into a held
     * automatic pass (whose re-run would import all categories over the recent window, or nothing
     * with auto-import off). It surfaces `WaitingForControl` carrying the user's exact selection and
     * window, writes nothing, and is not counted for an automatic re-run. Paired with an automatic
     * pass under the same gate (counted, silent) and with a manual import when the realm is present
     * (it writes).
     */
    @Test
    fun aManualImportUnderAHeldGateWaitsVisiblyWithItsExactRequest() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            for (case in listOf("manual-held", "automatic-held", "manual-present")) {
                val proxy = stackProxy(this)
                val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                val id = "hc-$case"
                lateinit var repo: FitProjectionRepository
                try {
                    repo = FitProjectionRepository(
                        clientOf(proxy), proxy.tContext,
                        importer = hcImporter(id),
                        probeScope = probeScope,
                        probeIntervalMs = 50,
                        probe = { true },
                        controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { case == "manual-present" },
                    )
                    awaitVerdict(
                        repo,
                        if (case == "manual-present") RealmVerdict.PRESENT else RealmVerdict.ABSENT,
                    )
                    val selection = setOf(CategoryId.Body)
                    repo.startImport(
                        selection,
                        app.tenshu.fit.model.ImportWindow.FullHistory,
                        automatic = case == "automatic-held",
                    )
                    val status = repo.importStatus().first()
                    when (case) {
                        "manual-held" -> {
                            assertEquals(
                                app.tenshu.fit.model.ImportStatus.WaitingForControl(
                                    selection, app.tenshu.fit.model.ImportWindow.FullHistory,
                                ),
                                status,
                                "visible, and it keeps the exact request",
                            )
                            assertEquals(0L, repo.realmHeldStops(), "never re-run as an automatic pass")
                            assertTrue(repo.workout(id) !is WorkoutRead.Found, "nothing written")
                        }
                        "automatic-held" -> {
                            assertEquals(app.tenshu.fit.model.ImportStatus.Idle, status)
                            assertEquals(1L, repo.realmHeldStops(), "an automatic pass is held for the re-run")
                            assertTrue(repo.workout(id) !is WorkoutRead.Found)
                        }
                        else -> assertIs<WorkoutRead.Found>(repo.workout(id), "with a realm it writes")
                    }
                } finally {
                    probeScope.cancel()
                }
            }
        }

    /**
     * ⚠⚠ Through the real bootstrap: the launch pass is suspended behind
     * PENDING (the realm read keeps failing — the session is not admitted yet); meanwhile the user
     * starts a manual import, which surfaces WaitingForControl. When the gate releases with
     * auto-import ready, the resumed launch pass must NOT run its all-category import over it: the
     * waiting status and its exact request survive, and Health Connect is never read.
     */
    @Test
    fun aWaitingManualRequestSurvivesTheLaunchPassOnRelease() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val proxy = stackProxy(this)
            val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            var admitted = false
            var readinessChecks = 0
            var reads = 0
            val importer = object : HealthImporter {
                override fun availableSources() = listOf(app.tenshu.fit.model.ImportSource.HealthConnect)
                override suspend fun autoRefreshReady(): Boolean { readinessChecks++; return true }
                override suspend fun import(
                    source: app.tenshu.fit.model.ImportSource,
                    categories: Set<CategoryId>,
                    window: app.tenshu.fit.model.ImportWindow,
                ): ImportOutcome {
                    reads++
                    return ImportOutcome.Success(ImportedHealth(workouts = listOf(importedWorkout("hc-launch"))))
                }
            }
            try {
                val repo = FitProjectionRepository(
                    clientOf(proxy), proxy.tContext,
                    importer = importer,
                    probeScope = probeScope,
                    probeIntervalMs = 50,
                    probe = { true },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { if (admitted) true else error("session has no admitted schema announce") },
                )
                HealthImportBootstrap(repo, probeScope, importer = importer) // suspends behind PENDING
                awaitUntil("reachable") { repo.reach.state.value == true }
                assertEquals(RealmVerdict.PENDING, repo.reach.realmVerdict.value)

                val request = app.tenshu.fit.model.ImportStatus.WaitingForControl(
                    setOf(CategoryId.Body), app.tenshu.fit.model.ImportWindow.FullHistory,
                )
                repo.startImport(request.selection, request.window) // manual
                assertEquals(request, repo.importStatus().value)

                admitted = true // the gate releases; the launch pass resumes
                awaitUntil("the launch pass ran") { readinessChecks >= 1 }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(300) }

                assertEquals(request, repo.importStatus().value, "the waiting request and its exact selection survive")
                assertEquals(0, reads, "no automatic import ran over it")
            } finally {
                probeScope.cancel()
            }
        }

    /**
     * ⚠⚠ The release-between-refusal-and-count race, made deterministic. An automatic import
     * started outside the bootstrap (the iOS foreground refresh shape) is refused at entry; the
     * test seam then lets the gate RELEASE — and waits until the bootstrap's release handler has
     * actually decided (and, seeing no new stop, skipped) — before the stop is counted. Without the
     * stop-driven re-run, that held import is never re-run; with it, it is.
     */
    @Test
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun aReleaseBetweenTheRefusalAndTheStopCountStillReRunsTheImport() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val proxy = stackProxy(this)
            val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val answer = kotlin.concurrent.atomics.AtomicReference<Boolean?>(true)
            val reads = kotlin.concurrent.atomics.AtomicInt(0) // crosses threads: the pass runs on Default
            val importer = object : HealthImporter {
                override fun availableSources() = listOf(app.tenshu.fit.model.ImportSource.HealthConnect)
                override suspend fun autoRefreshReady() = true
                override suspend fun import(
                    source: app.tenshu.fit.model.ImportSource,
                    categories: Set<CategoryId>,
                    window: app.tenshu.fit.model.ImportWindow,
                ): ImportOutcome {
                    val n = reads.addAndFetch(1)
                    return ImportOutcome.Success(ImportedHealth(workouts = listOf(importedWorkout("hc-race-$n"))))
                }
            }
            try {
                val repo = FitProjectionRepository(
                    clientOf(proxy), proxy.tContext,
                    importer = importer,
                    probeScope = probeScope,
                    probeIntervalMs = 50,
                    probe = { true },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer.load() },
                )
                awaitVerdict(repo, RealmVerdict.PRESENT)
                val bootstrap = HealthImportBootstrap(repo, probeScope, importer = importer)
                awaitUntil("the launch pass imported") { reads.load() == 1 }
                // `reads` flips when the read STARTS. Closing the gate before the launch pass's batch has
                // landed would hold that pass too and count a second stop. `Imported` is the last gated
                // step reachable HERE: this harness resolves no device id, so the run record after it
                // returns before its gate check, and the demographics seed reads nothing.
                awaitUntil("the launch pass landed") { repo.importStatus().value is app.tenshu.fit.model.ImportStatus.Imported }

                // Control drops and its re-read answers "no realm".
                answer.store(false)
                repo.reach.markUnreachable()
                awaitVerdict(repo, RealmVerdict.ABSENT)

                // The race window, held open: the realm appears and the release is fully handled
                // BEFORE the refused pass's stop is counted.
                repo.beforeHeldStopRecorded = {
                    val decisionsBefore = bootstrap.rerunDecisions
                    answer.store(true)
                    awaitVerdict(repo, RealmVerdict.PRESENT)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(10_000) {
                            while (bootstrap.rerunDecisions <= decisionsBefore) kotlinx.coroutines.delay(10)
                        }
                    }
                }
                repo.startImport(CategoryId.entries.toSet(), automatic = true) // refused at entry
                assertEquals(1L, repo.realmHeldStops())

                awaitUntil("the held import was re-run") { reads.load() == 2 }
            } finally {
                probeScope.cancel()
            }
        }

    /**
     * ⚠⚠ An automatic pass is already running (it holds `importLock` and has
     * published `Running`); the realm gate closes; the user starts a manual import, which is refused
     * at entry and stores WaitingForControl with its exact request. The automatic pass then reaches
     * its gated write and is held. Its held stop must not erase the user's request — the status and
     * its selection survive, and the stop is still counted for the automatic re-run.
     */
    @Test
    fun anAutomaticHeldStopKeepsAManualRequestQueuedWhileItRan() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val proxy = stackProxy(this)
            val probeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            var answer: Boolean? = true
            val reading = kotlinx.coroutines.CompletableDeferred<Unit>()
            val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val importer = object : HealthImporter {
                override fun availableSources() = listOf(app.tenshu.fit.model.ImportSource.HealthConnect)
                override suspend fun autoRefreshReady() = true
                override suspend fun import(
                    source: app.tenshu.fit.model.ImportSource,
                    categories: Set<CategoryId>,
                    window: app.tenshu.fit.model.ImportWindow,
                ): ImportOutcome {
                    reading.complete(Unit)
                    proceed.await()
                    return ImportOutcome.Success(ImportedHealth(workouts = listOf(importedWorkout("hc-auto-held"))))
                }
            }
            try {
                val repo = FitProjectionRepository(
                    clientOf(proxy), proxy.tContext,
                    importer = importer,
                    probeScope = probeScope,
                    probeIntervalMs = 50,
                    probe = { true },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer },
                )
                awaitVerdict(repo, RealmVerdict.PRESENT)

                // The automatic pass starts and is mid-read: it holds the lock and shows Running.
                val automaticPass = probeScope.launch {
                    repo.startImport(CategoryId.entries.toSet(), automatic = true)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(10_000) { reading.await() }
                }
                assertIs<app.tenshu.fit.model.ImportStatus.Running>(repo.importStatus().value)

                // The gate closes: Control drops and its re-read answers "no realm".
                answer = false
                repo.reach.markUnreachable()
                awaitVerdict(repo, RealmVerdict.ABSENT)

                // The user's manual request, refused at entry, waits with its exact selection.
                val request = app.tenshu.fit.model.ImportStatus.WaitingForControl(
                    setOf(CategoryId.Body), app.tenshu.fit.model.ImportWindow.FullHistory,
                )
                repo.startImport(request.selection, request.window) // manual
                assertEquals(request, repo.importStatus().value)

                // The automatic pass resumes, reaches its gated write, and is held.
                proceed.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(10_000) { automaticPass.join() }
                }

                assertEquals(1L, repo.realmHeldStops(), "the automatic pass is held for the re-run")
                assertEquals(request, repo.importStatus().value, "the manual request and its exact selection survive")
                assertTrue(repo.workout("hc-auto-held") !is WorkoutRead.Found, "nothing written")
            } finally {
                probeScope.cancel()
            }
        }

    private fun importedWorkout(id: String) = ImportedWorkout(
        workoutId = id,
        activityType = "Running",
        source = "HealthConnect",
        startMs = 1_780_000_000_000,
        endMs = 1_780_000_600_000,
        durationSec = 600,
    )

    private fun recorded(id: String): app.tenshu.fit.model.Workout {
        val start = kotlinx.datetime.LocalDateTime(2026, 7, 8, 9, 0, 0)
        return app.tenshu.fit.model.Workout(
            workoutId = id,
            activityType = app.tenshu.fit.model.ActivityType.Running,
            source = app.tenshu.fit.model.Source.Recorded,
            title = "", device = "", start = start, end = start, durationSec = 60,
        )
    }

    @Test
    fun aReadStreamRecoversFromAFailedSubscribeWithNoFitSideResubscriber() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("60s")) {
            // Pins that no Fit-side re-subscriber is needed: `TList.flow()` retries its own failed
            // subscribe, so a `ControlReachability.resubscribing { }` wrapper would only tear down
            // all nine streams and re-ship a full snapshot per recovery.
            //
            // The load-bearing consequence: recovery belongs entirely to the platform,
            // so the repository's own wrapping (the `shareIn` in `shared()`, `retryingOnThrow`,
            // `flowOn`) must not sit between a collector and that retry. `StreamSubscribeRetryTest`
            // pins the primitive; this pins that Fit still reaches it.
            //
            // ⚠ Deliberately NOT the probe seam used by [controlReachabilitySignalsAndRecovers]:
            // the first successful read marks the engine reachable, which stops the probe loop, so
            // over a healthy proxy no epoch flip is reachable at all and the assertion would hold
            // against either implementation. Failing the *subscribe* is the real thing.
            val subscribeFails = kotlinx.coroutines.flow.MutableStateFlow(true)
            val fileDatabase = org.tenshu.core.common.proxy.InMemoryFileDatabase()
            val proxy = org.tenshu.core.common.proxy.InProcessProxy(
                clientId = "fit-subscribe-retry",
                serviceScope = backgroundScope,
                syncCryptoManager = BootstrapSyncCryptoManager(),
                operationImplementationFactory = { id ->
                    if (id == org.tenshu.core.collection.list.Stream.ID && subscribeFails.value) null
                    else healthServiceProxyImplementationFactory(id, fileDatabase)
                        ?: implementationFactory(id)
                },
                schemaRegistryFactory = { tContextProvider ->
                    org.tenshu.core.common.proxy.createDefaultSchemaRegistry(
                        tContextProvider,
                        extraConfigs = listOf(healthServiceCommonModule, healthServiceClientModule),
                    )
                },
                initialization = { initHealthService() },
            )

            // One workout in the store, so a live subscription has something to ship.
            HealthImportWriter(proxy.client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
                ImportedHealth(
                    workouts = listOf(
                        ImportedWorkout(
                            workoutId = "retry-1",
                            activityType = "Running",
                            source = "File",
                            startMs = 1_700_000_000_000,
                            endMs = 1_700_000_600_000,
                            durationSec = 600,
                            title = "Retried run",
                        ),
                    ),
                ),
            )

            val appScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            try {
                val repo = FitProjectionRepository(
                    proxy.client, proxy.tContext,
                    importer = ReferenceHealthImporter(),
                    // The production shape: shared, replayed read streams.
                    probeScope = appScope,
                    probeIntervalMs = 50,
                    probe = { !subscribeFails.value },
                    controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
                )
                val seen = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
                backgroundScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    repo.workouts().collect { list -> seen.value = list.map { it.workoutId } }
                }

                // The subscribe cannot be dispatched: the read folds to empty and, crucially, the
                // collector is still alive — a `catch` would have *completed* it.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.delay(1_500)
                }
                assertEquals(emptyList(), seen.value, "a failed subscribe must not invent rows")

                // Control comes back. Nothing in Fit re-subscribes; `flow()`'s own retry does.
                subscribeFails.value = false
                awaitUntil("the read recovered on its own") { seen.value == listOf("retry-1") }
            } finally {
                appScope.cancel()
            }
        }

    @Test
    fun sampleHistoryIsWindowedPerMetricAndNeverDropsASparseOne() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("60s")) {
            // `sampleHistory` is a per-metric server-side window, not one stream over the whole
            // `healthSamples` relation filtered client-side.
            //
            // ⚠⚠ The load-bearing assertion is the SPARSE one. The obvious bound is a global
            // `sortByDescending { time }.take(n)` like the workout window, and it is wrong here
            // because the relation holds every metric: a metric whose readings are all older than
            // the newest N of some *other* metric disappears entirely. Weight measured monthly
            // vanishes behind daily heart rate, and the screen shows an empty history for a metric
            // the user has readings for. So this seeds exactly that shape — a dense recent metric
            // that would fill a global window, and a sparse older one that a global window would
            // evict — and asserts the sparse one still comes back whole.
            val proxy = stackProxy(this)
            val client = clientOf(proxy)

            val dayMs = 24L * 60 * 60 * 1000
            val now = 1_800_000_000_000L
            // Dense + recent: more than the window on its own.
            val heartRates = (0 until FitProjectionRepository.SAMPLE_HISTORY_WINDOW + 40).map { i ->
                ImportedSample(
                    metric = "HeartRate",
                    value = (60 + i % 20).toString(),
                    unit = "bpm",
                    timeMs = now - i * 3_600_000L,
                    source = "Manual",
                )
            }
            // Sparse + older than every heart-rate reading: a global top-N would drop all of these.
            val weights = (0 until 5).map { i ->
                ImportedSample(
                    metric = "Weight",
                    value = (70 + i).toString(),
                    unit = "kg",
                    timeMs = now - (400L + i * 30) * dayMs,
                    source = "Manual",
                )
            }
            HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
                .write(ImportedHealth(samples = heartRates + weights))

            val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

            val weightHistory = repo.sampleHistory(app.tenshu.fit.model.Metric.Weight).first()
            assertEquals(
                5,
                weightHistory.size,
                "the sparse metric was evicted — the window is global, not per-metric",
            )
            assertTrue(
                weightHistory.all { it.metric == app.tenshu.fit.model.Metric.Weight },
                "another metric's readings leaked into this one's history",
            )

            // …and the dense metric is bounded by the window rather than by what was stored.
            val hrHistory = repo.sampleHistory(app.tenshu.fit.model.Metric.HeartRate).first()
            assertEquals(
                FitProjectionRepository.SAMPLE_HISTORY_WINDOW,
                hrHistory.size,
                "the dense metric came back unwindowed",
            )
            assertTrue(
                hrHistory.zipWithNext().all { (a, b) -> a.time >= b.time },
                "history is not newest-first",
            )
        }

    @Test
    fun aggregatesCoverTheWholeYearChart_evenWhenAWorkoutCountWindowWouldNotReach() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("120s")) {
            // ⚠⚠ Pins that the aggregate read is bounded by DATE, not by count. `aggregates()`
            // merges workouts into the stored DailyActivity rows PER FIELD — notably
            // `activeMinutes`, which Health Connect always imports as 0. Under a `take(300)` bound
            // a user who trained more than 300 times in a year would have the oldest days of the
            // Year chart silently lose their corrections: the ring and the trend fall back to a
            // stored 0 on days they had actually trained.
            //
            // Two workouts a day for 200 days is 400 — comfortably past a 300 count cap, and
            // every one of them inside `ChartRange.Year`. With a count window the oldest ~150 days
            // are missing; with the date window all 200 are present.
            val proxy = stackProxy(this)
            val client = clientOf(proxy)
            val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

            val days = 200
            val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
            val workouts = (0 until days).flatMap { daysAgo ->
                val date = repo.today.minus(kotlinx.datetime.DatePeriod(days = daysAgo))
                listOf(9, 18).map { hour ->
                    val start = kotlinx.datetime.LocalDateTime(date, kotlinx.datetime.LocalTime(hour, 0))
                    val startMs = start.toInstant(zone).toEpochMilliseconds()
                    ImportedWorkout(
                        workoutId = "w-$daysAgo-$hour",
                        activityType = "Running",
                        source = "File",
                        startMs = startMs,
                        endMs = startMs + 1_800_000,
                        durationSec = 1_800,
                        title = "Session",
                        activeEnergyKj = 500,
                    )
                }
            }
            // 400 > a 300 count cap.
            assertTrue(workouts.size > 300, "fixture must exceed a 300 count cap to discriminate")
            HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(ImportedHealth(workouts = workouts))

            val aggregates = assertIs<ListLoadState.Loaded<app.tenshu.fit.model.DailyAggregate>>(
                repo.aggregates().first(),
            ).items

            // Every day the user trained carries workout-derived activity. The oldest is the one a
            // count window drops, so assert on it by name rather than on the count alone.
            val oldest = repo.today.minus(kotlinx.datetime.DatePeriod(days = days - 1))
            val oldestDay = aggregates.firstOrNull { it.date == oldest }
            assertNotNull(oldestDay, "the oldest trained day is missing from the Year window")
            assertTrue(
                oldestDay.activeMinutes > 0,
                "the oldest trained day lost its workout-derived active minutes — the read is " +
                    "bounded by workout COUNT, so the far end of the Year chart is uncorrected",
            )
            assertEquals(
                days,
                aggregates.count { it.activeMinutes > 0 },
                "every trained day inside ChartRange.Year must carry its correction",
            )
        }

    @Test
    fun pagedHistoryReachesPastTheListWindowTheListReadIsCappedAt() =
        kotlinx.coroutines.test.runTest(timeout = kotlin.time.Duration.parse("120s")) {
            // `fit-history-paging-handover.md`: the point of paging History is NOT a saved read
            // (Home fetches its own workout window at launch whatever History does) — it is REACH:
            // `workouts()` is capped at [FitProjectionRepository.WORKOUT_WINDOW], so without paging
            // a user with more sessions than that cannot scroll to the older ones.
            //
            // One workout a day for 320 days: 20 past the cap, so the oldest rows exist in the
            // store and are provably absent from the list read. That gap is what makes the paged
            // assertion discriminating — point `workoutsPaged()` back at the list read (its own
            // interface default does exactly that) and this fails on the oldest id.
            val proxy = stackProxy(this)
            val client = clientOf(proxy)
            val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

            val days = FitProjectionRepository.WORKOUT_WINDOW + 20
            val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
            val workouts = (0 until days).map { daysAgo ->
                val date = repo.today.minus(kotlinx.datetime.DatePeriod(days = daysAgo))
                val start = kotlinx.datetime.LocalDateTime(date, kotlinx.datetime.LocalTime(9, 0))
                val startMs = start.toInstant(zone).toEpochMilliseconds()
                ImportedWorkout(
                    workoutId = "w-$daysAgo",
                    activityType = "Running",
                    source = "File",
                    startMs = startMs,
                    endMs = startMs + 1_800_000,
                    durationSec = 1_800,
                    title = "Session $daysAgo",
                    activeEnergyKj = 500,
                )
            }
            HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(ImportedHealth(workouts = workouts))

            val oldest = "w-${days - 1}"

            // The premise: the list read genuinely cannot see the oldest rows. Asserted rather
            // than assumed — a fixture that never exceeded the cap would let the paged assertion
            // below pass against a feed that pages nothing.
            val listed = repo.workouts().first()
            assertEquals(
                FitProjectionRepository.WORKOUT_WINDOW, listed.size,
                "the list read must still be windowed — otherwise this test proves nothing",
            )
            assertNull(
                listed.firstOrNull { it.workoutId == oldest },
                "the oldest workout must be out of the list read's reach for this test to discriminate",
            )

            // Drive the paged feed exactly as a screen does — `TenshuPagingPresenter` is the same
            // `PagingDataPresenter` `collectAsLazyPagingItems()` is built on, and `get(size - 1)`
            // is the access that records a near-the-end read and appends the next page.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                val presenter = org.tenshu.core.paging.TenshuPagingPresenter<app.tenshu.fit.model.Workout>(
                    scope, kotlinx.coroutines.Dispatchers.Default,
                )
                try {
                    presenter.collect(repo.workoutsPaged())
                    kotlinx.coroutines.withTimeout(90_000) {
                        // ⚠ Tolerates the snapshot shrinking: `pagingSource` emits a second
                        // generation on its `emitOnSubscribe` readiness tick, and a new generation
                        // starts from one page again. Re-reading each turn rather than counting
                        // appends is what makes the loop indifferent to that.
                        //
                        // ⚠⚠ Waits on `snapshot.value.size`, which is what the assertion below
                        // reads — NOT `presenter.size`. They are two different things:
                        // `presenter.size` is the live differ, while `snapshot` is published later,
                        // from inside the suspend `presentPagingDataEvent` callback. Waiting on one
                        // and asserting on the other leaves a window where the differ holds 320
                        // rows and the published snapshot still holds 300 — exactly one page —
                        // which fails under load ("expected:<320> but was:<300>"). Synchronise on
                        // the value you assert on.
                        while (presenter.snapshot.value.size < days) {
                            presenter.get(presenter.size - 1)
                            kotlinx.coroutines.delay(25)
                        }
                    }
                    val paged = presenter.snapshot.value
                    assertEquals(days, paged.size, "the paged feed must reach every stored workout")
                    assertNotNull(
                        paged.firstOrNull { it.workoutId == oldest },
                        "the paged feed stopped at the list window — it is not paging the store",
                    )
                    assertEquals(
                        paged.map { it.workoutId }.distinct().size, paged.size,
                        "a page boundary duplicated rows — the ordered window is cut wrong",
                    )
                    assertTrue(
                        paged.zipWithNext().all { (a, b) -> a.start >= b.start },
                        "the paged feed is not newest-first across page boundaries",
                    )
                } finally {
                    scope.cancel()
                }
            }
        }

    private suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                kotlinx.coroutines.withTimeout(15_000) {
                    while (!condition()) kotlinx.coroutines.delay(50)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("timed out waiting for: " + what)
            }
        }
    }

    // ── one in-process proxy hosting the health service ──────────────────────

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy -> healthServiceProxyImplementationFactory(id, proxy.fileDatabase) },
        ).also {
            it.executionContext.initHealthService()
        }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}

/** In-memory [app.tenshu.outbox.OutboxStore] — insertion-ordered, like the file store. */
private class InMemoryOutboxStore : app.tenshu.outbox.OutboxStore {
    val backing = LinkedHashMap<String, String>()
    override fun append(id: String, payload: String) { backing[id] = payload }
    override fun entries() = backing.map { app.tenshu.outbox.OutboxEntry(it.key, it.value) }
    override fun remove(id: String) { backing.remove(id) }
}
