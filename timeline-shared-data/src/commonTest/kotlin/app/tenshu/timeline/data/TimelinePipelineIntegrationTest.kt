package app.tenshu.timeline.data

import app.tenshu.timeline.data.capture.CaptureEvent
import app.tenshu.timeline.data.capture.CapturedFix
import app.tenshu.timeline.data.capture.CapturedMoveWriter
import app.tenshu.timeline.data.capture.CapturedVisit
import app.tenshu.timeline.data.capture.CapturedVisitWriter
import app.tenshu.timeline.data.capture.LocationCaptureEngine
import app.tenshu.timeline.data.capture.LocationCaptureSource
import app.tenshu.timeline.data.capture.MovementMode
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_EXTERNAL
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION
import app.tenshu.timeline.data.capture.ModeClassification
import app.tenshu.timeline.data.capture.MovementTracePolyline
import app.tenshu.timeline.data.capture.OngoingMove
import app.tenshu.timeline.data.capture.OngoingMoveState
import app.tenshu.timeline.data.capture.OngoingVisit
import app.tenshu.timeline.data.capture.OngoingVisitState
import app.tenshu.timeline.data.capture.segment
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.fold
import org.tenshu.core.orThrow
import org.tenshu.core.number.TInteger
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.timeline.common.MomentSourceTag
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.recordPlace
import org.tenshu.place.client.placeService
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.recordMoment
import org.tenshu.core.timeline.recordSnappedRoute
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.client.timelineService
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.core.place.Place as SvcPlace
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * The **P3b runtime-exercise** (`docs/proposals/timeline-services.md` §7): stand up all three
 * backend services in one in-process proxy + a real `TenshuClient`, import a service-shaped
 * fixture *through the ingestion seam* ([ReferenceTimelineSource] → [TimelineImportWriter],
 * `timeline-ingestion.md` §2), and drive [TimelineProjectionRepository] end-to-end — proving
 * the whole pipe (import → services → relation maintainers → client read → projector → UI
 * model) that the projector's unit tests cannot cover. The closest headless approximation of
 * the app running over the real store.
 */
class TimelinePipelineIntegrationTest {

    @Test
    fun importedFixtureProjectsThroughTheRepository() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(ReferenceTimelineSource().load())

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // Home feed: the two imported moments group into their day, projected from the store.
        val days = repo.homeDays().first()
        val moments = days.flatMap { it.moments }.associateBy { it.id }
        assertEquals(setOf(ReferenceTimelineSource.MOMENT_RUN, ReferenceTimelineSource.MOMENT_COFFEE), moments.keys)

        // Subject hydration: the Visit moment resolves its place name across services, the
        // Activity moment its workout's activity — the reference `{kind, id}` survives the wire.
        assertEquals("Blue Bottle", moments.getValue(ReferenceTimelineSource.MOMENT_COFFEE).place)
        assertEquals("Running", moments.getValue(ReferenceTimelineSource.MOMENT_RUN).place)

        // Places tab: the café's visit history is derived from the Visit-moment pointing at it.
        val places = repo.places().first()
        val cafe = places.single { it.id == ReferenceTimelineSource.PLACE_CAFE }
        assertEquals("Blue Bottle", cafe.name)
        assertEquals(1, cafe.visitsCount)
        assertEquals(listOf(ReferenceTimelineSource.MOMENT_COFFEE), cafe.history.map { it.id })

        // Activities tab: the imported Running workout rolls up.
        val activities = repo.activities().first()
        assertTrue(activities.any { it.kind == "Running" && it.count == 1 })

        // Single-entity lookups.
        assertEquals("Lisbon", repo.trip(ReferenceTimelineSource.TRIP_LISBON)?.title)
        assertEquals("Blue Bottle", repo.moment(ReferenceTimelineSource.MOMENT_COFFEE)?.place)
        assertEquals(1, repo.place(ReferenceTimelineSource.PLACE_CAFE)?.visitsCount)
    }

    @Test
    fun importsAGoogleTimelineExportThroughTheRepository() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        // A legacy Semantic Location History export (the vintage that names its places): one café
        // visit + a drive. Imported through the real desktop entry point (I2b) — parser → writer.
        val export = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {
                      "latitudeE7": 525200000, "longitudeE7": 134050000,
                      "placeId": "ChIJcafe", "name": "Blue Bottle"
                    },
                    "duration": {
                      "startTimestamp": "2021-04-07T09:00:00Z",
                      "endTimestamp": "2021-04-07T10:00:00Z"
                    }
                  }
                },
                {
                  "activitySegment": {
                    "duration": {
                      "startTimestamp": "2021-04-07T10:00:00Z",
                      "endTimestamp": "2021-04-07T10:30:00Z"
                    },
                    "activityType": "IN_PASSENGER_VEHICLE"
                  }
                }
              ]
            }
        """.trimIndent()

        val written = FileTimelineImporter(client, proxy.tContext).importFiles(
            listOf(TimelineImportFile("Semantic Location History.json", export.encodeToByteArray())),
        )
        // 1 place + 1 visit moment + 1 movement moment + its trace. The trace is the M7 addition:
        // an activity segment used to write a single subjectless Activity moment and no geometry.
        assertEquals(4, written)

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // The Visit moment resolves its place name across services (subject hydration); the drive
        // now lands as a MOVEMENT moment pointing at a real trace.
        val moments = repo.homeDays().first().flatMap { it.moments }
        assertTrue(moments.any { it.place == "Blue Bottle" })

        // The imported trace persisted through the real store, carrying the two things the old
        // subjectless-Activity mapping destroyed: Google's raw travel type, and the fact that our
        // own classifier did *not* produce this label (so a future re-classification can tell).
        val importedTrace = client.timelineService.traces
            .select {
                fields(
                    MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE,
                    MovementTrace.Fields.SOURCE_ACTIVITY_TYPE, MovementTrace.Fields.CLASSIFIER_VERSION,
                )
            }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() }).single()
        assertEquals(
            "IN_PASSENGER_VEHICLE",
            importedTrace.getFieldValue<TString>(MovementTrace.Fields.SOURCE_ACTIVITY_TYPE).get(),
        )
        assertEquals(
            MOVEMENT_CLASSIFIER_EXTERNAL.toLong(),
            importedTrace.getFieldValue<TInteger>(MovementTrace.Fields.CLASSIFIER_VERSION).get(),
        )

        // The café shows up in the Places tab, named from the legacy export — and re-importing the
        // same file is idempotent (no duplicate place / visit), because the ids are content-addressed.
        FileTimelineImporter(client, proxy.tContext).importFiles(
            listOf(TimelineImportFile("Semantic Location History.json", export.encodeToByteArray())),
        )
        val cafes = repo.places().first().filter { it.name == "Blue Bottle" }
        assertEquals(1, cafes.size)
        assertEquals(1, cafes.single().visitsCount)
    }

    @Test
    fun liveCapturedVisitsLandInTheRepository() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val cafeLat = 525_200_000L
        val cafeLng = 134_050_000L

        // A source that emits two dwells at the same café on different days (the second a few
        // metres off) — the live-capture analog of two visits to one place.
        val source = FakeCaptureSource(
            listOf(
                CaptureEvent.Dwell(CapturedVisit(cafeLat, cafeLng, t0, t0 + 20 * min)),
                CaptureEvent.Dwell(CapturedVisit(cafeLat + 300, cafeLng + 300, t0 + 24 * 60 * min, t0 + 24 * 60 * min + 15 * min)),
            ),
        )

        // The engine drains the (completing, in-test) flow and persists through the shared writer.
        LocationCaptureEngine(source, client, proxy.tContext, writesHeld = { false }).run()

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // Both captured visits fold to one content-addressed place (gridded centroid) with a
        // two-visit history — read back through the same projector the importers feed.
        val cafe = repo.places().first().single { it.id == "cap:525200000,134050000" }
        assertEquals(2, cafe.visitsCount)

        // The visits surface on the home feed as Visit moments (label blank until geocoded).
        val moments = repo.homeDays().first().flatMap { it.moments }
        assertEquals(2, moments.count { it.id.startsWith("cap-visit:") })
    }

    @Test
    fun directlyWrittenVisitsLandInTheRepository() = runTest {
        // The iOS event path (I4): a CLVisit delegate hands each visit straight to the shared
        // CapturedVisitWriter, no Flow/collector. Same store, same projector as every other source.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val cafeLat = 525_200_000L
        val cafeLng = 134_050_000L

        val writer = CapturedVisitWriter(client, proxy.tContext, writesHeld = { false })
        writer.write(CapturedVisit(cafeLat, cafeLng, t0, t0 + 20 * min))
        // A batch call + a re-write of the first visit — both fold idempotently to one place.
        writer.writeAll(
            listOf(
                CapturedVisit(cafeLat, cafeLng, t0, t0 + 20 * min), // duplicate → same ids
                CapturedVisit(cafeLat + 300, cafeLng + 300, t0 + 24 * 60 * min, t0 + 24 * 60 * min + 15 * min),
            ),
        )

        val cafe = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).places().first()
            .single { it.id == "cap:525200000,134050000" }
        assertEquals(2, cafe.visitsCount)
    }

    @Test
    fun capturedMovementLandsAsAMomentAndTrace() = runTest {
        // M2 (timeline-movement-capture.md §6): a captured walk → segmenter → CapturedMoveWriter →
        // services → projector. The trigger report's other half: movement now produces a feed entry.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val originLat = 525_000_000L
        val originLng = 134_000_000L

        // A 20-min walk with no dwell (~134 m/min → one continuous move, reads as a walk).
        val walk = (0 until 20).map { CapturedFix(originLat + it * 12_000, originLng, t0 + it * min) }
        val moves = walk.asFlow().segment().toList().filterIsInstance<CaptureEvent.Move>().map { it.move }
        assertEquals(1, moves.size)

        CapturedMoveWriter(client, proxy.tContext, writesHeld = { false }).writeAll(moves)

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // The move surfaces on the home feed as a MOVEMENT moment titled "Walk · N km".
        val moment = repo.homeDays().first().flatMap { it.moments }.single { it.title.startsWith("Walk") }
        assertTrue(moment.title.startsWith("Walk · "))

        // M5-lite: it projects as its own kind and its trace subject is resolved into the fields
        // the card renders — proving the whole read path (traces query → TRACE_FIELDS → traceInput
        // → traceResolver), not just the mapper. Before M5-lite this was MomentKind.Event with no
        // mode and no distance, and TRACE_FIELDS omitting a field would silently reproduce that.
        assertEquals(MomentKind.Movement, moment.kind)
        assertEquals(MovementModes.WALK, moment.movementMode)
        assertNotNull(moment.distanceM)
        assertTrue(moment.distanceM!! > 0, "distance should reach the card")

        // Its trace subject persisted — summary + a decodable polyline (the byte field survived the
        // real store round-trip, not just the codec unit test).
        val traces = client.timelineService.traces
            .select {
                fields(
                    MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE,
                    MovementTrace.Fields.POINT_COUNT, MovementTrace.Fields.POLYLINE,
                )
            }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(MovementMode.WALK, trace.getFieldValue<TString>(MovementTrace.Fields.MODE).get())
        val decoded = MovementTracePolyline.decode(trace.getFieldValue<TByteArray>(MovementTrace.Fields.POLYLINE).get())
        assertEquals(trace.getFieldValue<TInteger>(MovementTrace.Fields.POINT_COUNT).get().toInt(), decoded.size)
        assertTrue(decoded.isNotEmpty())

        // M5: the moment carries its trace subject id, and the repository's dedicated route read
        // resolves it through the store to the decoded path the detail hero draws — the one read
        // that projects the polyline blob. An unknown id degrades to empty, never a failure.
        val traceId = moment.traceId
        assertNotNull(traceId)
        val route = repo.movementRoute(traceId)
        assertEquals(decoded.size, route.size)
        assertEquals(decoded.first().latE7 / 1e7, route.first().latitude)
        assertEquals(decoded.last().lngE7 / 1e7, route.last().longitude)
        assertTrue(repo.movementRoute("cap-trace:unknown").isEmpty())

        // Idempotent: re-writing the same move folds to one moment + one trace (content-addressed).
        CapturedMoveWriter(client, proxy.tContext, writesHeld = { false }).writeAll(moves)
        assertEquals(1, repo.homeDays().first().flatMap { it.moments }.count { it.title.startsWith("Walk") })
        assertEquals(
            1,
            client.timelineService.traces.select { fields(MovementTrace.Fields.TRACE_ID) }
                .flow().first().fold(onSuccess = { it.toList().size }, onFailure = { 0 }),
        )
    }

    @Test
    fun theRouteReadPrefersTheSnappedPathPerLegAndFallsBackToRaw() = runTest {
        // B4 (§14): a leg that has a `SnappedRoute` is drawn from it; a leg that does not still draws
        // its raw path. Both halves in one journey, because a ride and the walk to the platform are
        // exactly the mixed case the per-leg (not per-journey) substitution exists for.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        val rawA = (0 until 6).map { i ->
            CapturedFix(
                latE7 = (52.0 * 1e7).toLong() + i * 1_000,
                lngE7 = (13.0 * 1e7).toLong() + 2_000,     // ~14 m east of where the train ran
                timestampMs = 1_800_000_000_000L + i * 30_000L,
            )
        }
        val rawB = (0 until 4).map { i ->
            CapturedFix(
                latE7 = (52.1 * 1e7).toLong() + i * 1_000,
                lngE7 = (13.0 * 1e7).toLong(),
                timestampMs = 1_800_000_600_000L + i * 30_000L,
            )
        }
        seedTrace(client, proxy, "cap-trace:a", rawA)
        seedTrace(client, proxy, "cap-trace:b", rawB)

        // Only leg A gets a corrected route — the same shape, moved onto the track's meridian.
        val snappedA = rawA.map { it.copy(lngE7 = it.lngE7 - 2_000) }
        client.timelineService.recordSnappedRoute(
            SnappedRoute.create(
                context = proxy.tContext,
                traceId = "cap-trace:a",
                source = SnappedRoute.Sources.RAIL,
                snapVersion = 1,
                coverageBp = 9_000,
                polyline = MovementTracePolyline.encode(snappedA),
            ),
        ).orThrow("recording snapped route")

        val route = repo.movementRoute(listOf("cap-trace:a", "cap-trace:b"))

        assertEquals(rawA.size + rawB.size, route.size)
        // Leg A is drawn from the corrected path…
        assertEquals(snappedA.map { it.lngE7 / 1e7 }, route.take(rawA.size).map { it.longitude })
        // …and that is genuinely different from what leg A was captured as, so "prefers snapped" is
        // not vacuously satisfied by the two paths being identical.
        assertTrue(
            rawA.map { it.lngE7 / 1e7 } != route.take(rawA.size).map { it.longitude },
            "the raw path was drawn — the snapped route did not win",
        )
        // Leg B has no corrected route, so it still draws raw. Order follows the caller's list.
        assertEquals(rawB.map { it.lngE7 / 1e7 }, route.drop(rawA.size).map { it.longitude })
        assertEquals(rawB.map { it.latE7 / 1e7 }, route.drop(rawA.size).map { it.latitude })

        // ⚠ The trace's own polyline is untouched by any of this — the raw path stays reconstructable.
        val storedA = client.timelineService.traces
            .select { fields(MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.POLYLINE) }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
            .single { it.getFieldValue<TString>(MovementTrace.Fields.TRACE_ID).get() == "cap-trace:a" }
        assertEquals(
            rawA.map { it.lngE7 },
            MovementTracePolyline.decode(storedA.getFieldValue<TByteArray>(MovementTrace.Fields.POLYLINE).get())
                .map { it.lngE7 },
        )
    }

    /** A bare `MovementTrace` row carrying [fixes] as its polyline — enough for the route read. */
    private suspend fun seedTrace(
        client: TenshuClient,
        proxy: TestProxy,
        traceId: String,
        fixes: List<CapturedFix>,
    ) {
        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedTimeline(
                traces = listOf(
                    ImportedMovementTrace(
                        traceId = traceId,
                        mode = MovementMode.TRANSIT,
                        distanceM = 1_000,
                        startMs = fixes.first().timestampMs,
                        endMs = fixes.last().timestampMs,
                        pointCount = fixes.size.toLong(),
                        minLatE7 = fixes.minOf { it.latE7 },
                        minLngE7 = fixes.minOf { it.lngE7 },
                        maxLatE7 = fixes.maxOf { it.latE7 },
                        maxLngE7 = fixes.maxOf { it.lngE7 },
                        polyline = MovementTracePolyline.encode(fixes),
                        classifierVersion = MOVEMENT_CLASSIFIER_VERSION.toLong(),
                        modeConfidence = 60,
                        medianSpeedMmPerS = 25_000,
                        rawPointCount = fixes.size.toLong(),
                        startLatE7 = fixes.first().latE7,
                        startLngE7 = fixes.first().lngE7,
                        endLatE7 = fixes.last().latE7,
                        endLngE7 = fixes.last().lngE7,
                    ),
                ),
            ),
        )
    }

    @Test
    fun traceCarriesClassifierEvidenceAndDirection() = runTest {
        // M6 (§12.1): the fidelity fields must survive the real store, not just the mapper. Without
        // classifierVersion a retuned classifier could never re-label old rows, and without the
        // start/end coordinates the summary plane cannot tell an A→B walk from the B→A return.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val originLat = 525_000_000L
        val originLng = 134_000_000L

        val walk = (0 until 20).map { CapturedFix(originLat + it * 12_000, originLng, t0 + it * min, accuracyM = 8) }
        val moves = walk.asFlow().segment().toList().filterIsInstance<CaptureEvent.Move>().map { it.move }
        CapturedMoveWriter(client, proxy.tContext, writesHeld = { false }).writeAll(moves)

        val trace = client.timelineService.traces
            .select {
                fields(
                    MovementTrace.Fields.TRACE_ID,
                    MovementTrace.Fields.CLASSIFIER_VERSION, MovementTrace.Fields.MODE_CONFIDENCE,
                    MovementTrace.Fields.MEDIAN_SPEED_MM_PER_S, MovementTrace.Fields.RAW_POINT_COUNT,
                    MovementTrace.Fields.POINT_COUNT,
                    MovementTrace.Fields.START_LAT_E7, MovementTrace.Fields.START_LNG_E7,
                    MovementTrace.Fields.END_LAT_E7, MovementTrace.Fields.END_LNG_E7,
                )
            }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() }).single()

        fun int(field: String) = trace.getFieldValue<TInteger>(field).get()

        assertEquals(MOVEMENT_CLASSIFIER_VERSION.toLong(), int(MovementTrace.Fields.CLASSIFIER_VERSION))
        assertTrue(int(MovementTrace.Fields.MEDIAN_SPEED_MM_PER_S) > 0, "median speed persisted")
        assertTrue(int(MovementTrace.Fields.MODE_CONFIDENCE) in 0..100, "confidence in range")

        // Direction: this walk runs strictly north, so end latitude must exceed start latitude —
        // the assertion the order-agnostic bounding box is incapable of making.
        assertEquals(originLat, int(MovementTrace.Fields.START_LAT_E7))
        assertTrue(
            int(MovementTrace.Fields.END_LAT_E7) > int(MovementTrace.Fields.START_LAT_E7),
            "end must be north of start",
        )
        assertEquals(originLng, int(MovementTrace.Fields.START_LNG_E7))

        // rawPointCount is the pre-decimation count, so it must be ≥ the stored point count.
        assertEquals(walk.size.toLong(), int(MovementTrace.Fields.RAW_POINT_COUNT))
        assertTrue(int(MovementTrace.Fields.RAW_POINT_COUNT) >= int(MovementTrace.Fields.POINT_COUNT))
    }

    @Test
    fun capturePreferredOverGoogleWhenBothSawTheSameVisit() = runTest {
        // §12.3: `Moment.sources` existed and was Merge-wired, but nothing ever set it — so the
        // reconciler's source-priority tie-break had no signal and silently fell through to
        // earliest-start. Here Google's row starts *earlier*, so earliest-start would elect it;
        // only a live `sources` value makes the capture row win, as the priority intends.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val lat = 525_200_000L
        val lng = 134_050_000L
        val min = 60_000L
        // Sit well inside a 2 h reconciliation bucket — 1_800_000_000_000 is exactly on a boundary,
        // so a ±10 min pair straddles two buckets and never clusters in the first place.
        val captured = 1_800_000_000_000L + 30 * min
        val google = captured - 10 * min

        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedTimeline(
                places = listOf(ImportedPlace("cell", lat, lng, google, "Cafe")),
                moments = listOf(
                    // Same place cell, same 2 h bucket → one cluster; Google's is 10 min earlier.
                    ImportedMoment(
                        "g-visit:cell:$google", google, Moment.MomentKind.VISIT,
                        "Google visit", SubjectKind.PLACE, "cell", durationSec = 600,
                        sources = listOf(MomentSourceTag.GOOGLE),
                    ),
                    ImportedMoment(
                        "cap-visit:cell:$captured", captured, Moment.MomentKind.VISIT,
                        "Captured visit", SubjectKind.PLACE, "cell", durationSec = 600,
                        sources = listOf(MomentSourceTag.CAPTURE),
                    ),
                ),
            ),
        )

        val surviving = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).homeDays().first()
            .flatMap { it.moments }.filter { it.title.endsWith("visit") }

        assertEquals(1, surviving.size, "the pair should reconcile to one moment")
        assertEquals("Captured visit", surviving.single().title)
    }

    @Test
    fun currentPlaceShowsLiveTickingDuration() = runTest {
        // Option B (§6.2): the place you're currently at ticks its duration from OngoingVisitState in
        // the UI (now - arrival), overriding the value written at confirmation — no per-minute re-sync.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val arrival = nowMs - 6 * 60_000L // arrived 6 min ago, still here
        val lat = 525_200_000L
        val lng = 134_050_000L
        val momentId = "cap-visit:here:$arrival"

        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedTimeline(
                places = listOf(ImportedPlace("here", lat, lng, arrival, "Here")),
                moments = listOf(
                    ImportedMoment(momentId, arrival, Moment.MomentKind.VISIT, "Visit", SubjectKind.PLACE, "here", durationSec = 90),
                ),
            ),
        )
        try {
            OngoingVisitState.set(OngoingVisit(lat, lng, arrival))
            val moment = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).homeDays().first()
                .flatMap { it.moments }.single { it.id == momentId }
            assertTrue(moment.isCurrent, "the ongoing place is marked current (● now)")
            // Live duration ≈ 6 min (now - arrival), overriding the stored 90 s.
            assertTrue((moment.durationMin ?: 0) >= 5, "live duration ticks from arrival, was ${moment.durationMin}")
        } finally {
            OngoingVisitState.clear()
        }
    }

    @Test
    fun theEngineTakesTheRideLiveAndHandsItBackWhenTheLegCloses() = runTest {
        // ⚠ The **real** LocationCaptureEngine dispatch, not a re-implementation of it. A mutation
        // test showed that asserting the invariant against a hand-copied `when` in the test file
        // leaves the shipping dispatcher completely uncovered — deleting its clear-on-flush changed
        // nothing that failed. This drives the actual engine over the actual event sequence.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val walk = (0 until 20).map { CapturedFix(525_000_000L + it * 12_000, 134_000_000L, t0 + it * min) }
        val events = walk.asFlow().segment().toList()
        val progress = events.filterIsInstance<CaptureEvent.MoveProgress>()
        val move = events.filterIsInstance<CaptureEvent.Move>().single()
        assertTrue(progress.isNotEmpty(), "precondition: the walk produced open-leg progress")

        try {
            // ⚠ Observed *during* collection, not after: `run()` returning means capture STOPPED,
            // and its `finally` correctly clears both states. So the source stays open — a live
            // capture session never completes on its own.
            OngoingVisitState.set(OngoingVisit(525_000_000L, 134_000_000L, t0))
            val open = OpenCaptureSource(progress)
            val session = backgroundScope.launch {
                LocationCaptureEngine(open, client, proxy.tContext, writesHeld = { false }).run()
            }
            open.emitted.first() // the engine has consumed everything queued

            assertEquals(progress.last().open, OngoingMoveState.current.value, "the ride is live")
            assertNull(OngoingVisitState.current.value, "and you are no longer at a place")

            // The leg closes: it is a row now, so the live overlay must go, before the write even.
            open.send(move)
            open.emitted.first()
            assertNull(OngoingMoveState.current.value, "the flushed leg is no longer live")
            session.cancel()

            val moments = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).homeDays().first().flatMap { it.moments }
            assertEquals(1, moments.count { it.id.startsWith("cap-move:") }, "exactly one movement row")
            assertTrue(moments.none { it.isCurrent }, "and nothing is still ongoing")
        } finally {
            OngoingMoveState.clear()
            OngoingVisitState.clear()
        }
    }

    @Test
    fun theRideYouAreOnShowsInTheFeedWithoutAnyRowBeingWritten() = runTest {
        // §12.6's deferred half, end to end through the real repository. The whole point of the
        // live overlay is that it costs no writes: §9's emit-on-close decision keeps movement
        // moments immutable, so an in-flight leg must surface without a Moment or a MovementTrace.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val departed = nowMs - 25 * 60_000L

        // Precondition: nothing stored, nothing shown.
        assertTrue(repo.homeDays().first().flatMap { it.moments }.isEmpty(), "precondition: empty feed")

        try {
            OngoingMoveState.set(
                OngoingMove(
                    startMs = departed,
                    lastFixMs = nowMs - 60_000L, // a fix a minute ago: live, not stale
                    distanceM = 8_450.0,
                    classification = ModeClassification(MovementMode.CYCLE, confidence = 90, medianSpeedMmPerS = 5_600),
                ),
            )

            val ride = repo.homeDays().first().flatMap { it.moments }.single()
            assertTrue(ride.isCurrent, "the ride you are on is current")
            assertEquals(MomentKind.Movement, ride.kind)
            assertEquals(MovementModes.CYCLE, ride.movementMode)
            assertEquals(8_450L, ride.distanceM)
            assertTrue((ride.durationMin ?: 0) >= 24, "duration ticks from departure, was ${ride.durationMin}")

            // ⚠⚠ The assertion this test exists for: the stores are still empty. A live ride that
            // wrote a row would be a mutating, re-syncing moment — exactly what Option A rejected.
            assertEquals(
                0,
                client.timelineService.moments.select { fields(Moment.Fields.MOMENT_ID) }
                    .flow().first().fold(onSuccess = { it.toList().size }, onFailure = { -1 }),
                "no Moment row was written for the ongoing ride",
            )
            assertEquals(
                0,
                client.timelineService.traces.select { fields(MovementTrace.Fields.TRACE_ID) }
                    .flow().first().fold(onSuccess = { it.toList().size }, onFailure = { -1 }),
                "no MovementTrace row was written either",
            )
        } finally {
            OngoingMoveState.clear()
        }
    }

    @Test
    fun aStaleOngoingMoveStopsBeingCurrent() = runTest {
        // The state cannot expire itself: capture dying while fixes stop (indoors, revoked
        // permission, an OEM kill) leaves a well-formed OngoingMove that would otherwise tick for
        // ever. Past the segmenter's own resume window it must simply stop being shown.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val nowMs = Clock.System.now().toEpochMilliseconds()

        try {
            // Precondition: the identical move with a recent fix DOES show.
            OngoingMoveState.set(
                OngoingMove(nowMs - 25 * 60_000L, nowMs - 60_000L, 8_450.0, ModeClassification(MovementMode.CYCLE, 90, 5_600)),
            )
            assertEquals(1, repo.homeDays().first().flatMap { it.moments }.size, "precondition: a fresh ride shows")

            // Same leg, but the last fix landed well past maxResumeGapMs ago.
            OngoingMoveState.clear()
            OngoingMoveState.set(
                OngoingMove(nowMs - 3 * 60 * 60_000L, nowMs - 60 * 60_000L, 8_450.0, ModeClassification(MovementMode.CYCLE, 90, 5_600)),
            )
            assertTrue(repo.homeDays().first().flatMap { it.moments }.isEmpty(), "a stalled ride is not current")
        } finally {
            OngoingMoveState.clear()
        }
    }

    @Test
    fun captureEngineDispatchesBothMovesAndVisits() = runTest {
        // M3 (timeline-movement-capture.md §6): the widened engine collects a CaptureEvent stream and
        // routes a Move to the move writer and a Dwell to the visit writer — both land, one loop.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L

        val walk = (0 until 20).map { CapturedFix(525_000_000L + it * 12_000, 134_000_000L, t0 + it * min) }
        val move = walk.asFlow().segment().toList().filterIsInstance<CaptureEvent.Move>().single().move
        val visit = CapturedVisit(525_200_000L, 134_050_000L, t0 + 30 * 60 * min, t0 + 30 * 60 * min + 20 * min)

        val source = FakeCaptureSource(listOf(CaptureEvent.Move(move), CaptureEvent.Dwell(visit)))
        LocationCaptureEngine(source, client, proxy.tContext, writesHeld = { false }).run()

        val moments = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).homeDays().first().flatMap { it.moments }
        assertTrue(moments.any { it.title.startsWith("Walk") }, "the move landed as a MOVEMENT moment")
        assertEquals(1, moments.count { it.id.startsWith("cap-visit:") }) // the dwell landed as a Visit
    }

    @Test
    fun tripSuggestionSurfacesFromMomentsAndAcceptRecordsATrip() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val day = 24 * 60 * 60 * 1000L

        // Home: five visits to Berlin (the most-frequented place → home). Away: a three-day Lisbon trip.
        val places = listOf(
            ImportedPlace("home", 525_200_000L, 134_050_000L, t0, "Home"),
            ImportedPlace("lisbon", 387_200_000L, -91_400_000L, t0, "Lisbon"),
        )
        val moments = buildList {
            repeat(5) { i -> add(visit("h$i", t0 + i * day, "home")) }
            add(visit("l1", t0 + 10 * day, "lisbon"))
            add(visit("l2", t0 + 11 * day, "lisbon"))
            add(visit("l3", t0 + 12 * day, "lisbon"))
        }
        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
            .write(ImportedTimeline(places = places, moments = moments))

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // The detector surfaces the Lisbon journey as the Interlude suggestion.
        val suggestion = repo.tripSuggestion().first()
        assertNotNull(suggestion)
        assertEquals("Lisbon", suggestion.title)
        assertEquals(1, suggestion.placesCount)

        // Accepting materialises a Trip (which would sync) and clears the suggestion.
        repo.acceptTripSuggestion()
        assertNotNull(repo.trip("suggested-trip:${t0 + 10 * day}"))
        assertNull(repo.tripSuggestion().first())
    }

    @Test
    fun nearDuplicateMomentsCollapseAndSupersededAreHidden() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L

        // Two near-duplicate captures of one café visit — same place, same time bucket, different ids.
        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedTimeline(
                places = listOf(ImportedPlace("cafe", 525_200_000L, 134_050_000L, t0, "Blue Bottle")),
                moments = listOf(
                    visit("v1", t0, "cafe"),
                    visit("v2", t0 + 30 * min, "cafe"),
                ),
            ),
        )
        // A moment a reconciliation pass already folded into a canonical — must be hidden.
        client.timelineService.recordMoment(
            Moment.create(
                context = proxy.tContext,
                momentId = "superseded",
                timestamp = UnixInstantDto(t0 + 5 * 60 * min),
                kind = Moment.MomentKind.VISIT,
                title = "Old",
                subject = SubjectRef.none(proxy.tContext),
                supersededBy = "some-canonical",
            ),
        )

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val momentIds = repo.homeDays().first().flatMap { it.moments }.map { it.id }.toSet()

        // The near-duplicates collapse to one canonical; the superseded moment is hidden.
        assertEquals(setOf("v1"), momentIds)
    }

    @Test
    fun aRepeatVisitNeverClobbersAUserRenamedPlace() = runTest {
        // 4-C / §4.4: `recordPlace` replaces the placeId-keyed row, so a blank-labelled capture —
        // and, worse, a Wi-Fi-recognised re-visit that deliberately folds onto the *named* place
        // (§4.5) — would erase the name the user chose. The guard in TimelineImportWriter must keep
        // the name across every later blank capture. Without it, P3c would wipe the name it reuses.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val lat = 525_200_000L
        val lng = 134_050_000L
        val placeId = "cap:525200000,134050000" // the grid-snapped id these coords fold to

        val writer = CapturedVisitWriter(client, proxy.tContext, writesHeld = { false })
        writer.write(CapturedVisit(lat, lng, t0, t0 + 20 * min))

        val repo = TimelineProjectionRepository(client, proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        repo.renamePlace(placeId, "Office") // user-authored → labelSource = USER
        assertEquals("Office", repo.place(placeId)?.name)

        // A later visit to the same place (a few metres off → same grid cell) writes a *blank*
        // ImportedPlace for that id — the exact shape a Wi-Fi override produces.
        writer.write(CapturedVisit(lat + 200, lng + 200, t0 + 24 * 60 * min, t0 + 24 * 60 * min + 15 * min))

        val place = repo.place(placeId)
        assertEquals("Office", place?.name, "the user's name must survive a repeat/recognised visit")
        assertEquals(2, place?.visitsCount, "but the visit history still grows")
    }

    @Test
    fun aRepeatVisitRefreshesTheAccuracyThatNamedThePlaceWrongly() = runTest {
        // ⚠⚠ §32.16, the reported "the bar row is back". A place is named from its dwell accuracy: below
        // COARSE_LABEL_ACCURACY_M the enricher names a *venue*, above it a stop/street/city. The row
        // that mis-named Odeonsplatz claimed **±14 m**, written under the pre-§32.10 rule that read a
        // walked-through cluster as precise.
        //
        // §32.10 fixed the measurement — the same dwell now measures **128 m** — but the row never
        // moved, because a blank-labelled capture onto a *named* place hit the `wouldErase` guard and
        // was skipped ENTIRELY: name and accuracy together. So the stale figure was unreachable by
        // construction, the enricher kept reading 14, and kept re-deriving the wrong venue. The guard
        // was right about the name and wrong about its blast radius.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val ctx = proxy.tContext

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val placeId = "cap:490280000,209030000"

        // The row as it exists today: a machine-derived venue name and an over-confident accuracy.
        client.placeService.recordPlace(
            SvcPlace.create(
                ctx, placeId, 490_278_823L, 209_030_761L, UnixInstantDto(t0),
                label = "Bar", labelSource = LabelSource.GEOCODED, accuracyM = 14,
            ),
        ).orThrow("seed")

        // Days later: the same dwell, honestly measured this time.
        CapturedVisitWriter(client, proxy.tContext, writesHeld = { false })
            .write(CapturedVisit(490_281_141L, 209_032_939L, t0 + 72 * 60 * min, t0 + 72 * 60 * min + 12 * min, accuracyM = 128))

        val row = placeRow(client, placeId)
        assertEquals(
            128, row.accuracyM,
            "the re-measured accuracy must land — otherwise the place can never be re-named correctly",
        )
        assertEquals("Bar", row.label, "…and the name is still not this write's business to change")
        assertEquals(LabelSource.GEOCODED, row.labelSource)
    }

    @Test
    fun theUsersOwnNameSurvivesThatAccuracyRefresh() = runTest {
        // The other half of the same guard: refreshing the measurement must not become a back door
        // that lets a blank capture overwrite a name the user chose.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val placeId = "cap:525200000,134050000"
        val writer = CapturedVisitWriter(client, proxy.tContext, writesHeld = { false })
        val repo = TimelineProjectionRepository(client, proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        writer.write(CapturedVisit(525_200_000L, 134_050_000L, t0, t0 + 20 * min, accuracyM = 20))
        repo.renamePlace(placeId, "Office")

        writer.write(CapturedVisit(525_200_100L, 134_050_100L, t0 + 24 * 60 * min, t0 + 24 * 60 * min + 15 * min, accuracyM = 150))

        val row = placeRow(client, placeId)
        assertEquals("Office", row.label, "a user label is authoritative, refresh or not")
        assertEquals(LabelSource.USER, row.labelSource)
        assertEquals(150, row.accuracyM, "but the measurement is nobody's authored claim")
    }

    /** The stored `Place` row's fields, read straight rather than through the projection. */
    private data class PlaceRow(val label: String, val labelSource: String, val accuracyM: Int)

    private suspend fun placeRow(client: TenshuClient, placeId: String): PlaceRow =
        client.placeService.places
            .select {
                fields(
                    SvcPlace.Fields.PLACE_ID, SvcPlace.Fields.LABEL,
                    SvcPlace.Fields.LABEL_SOURCE, SvcPlace.Fields.ACCURACY_M,
                )
            }
            .flow().first()
            .fold(
                onSuccess = { list ->
                    list.toList()
                        .first { it.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get() == placeId }
                        .let {
                            PlaceRow(
                                label = it.getFieldValue<TString>(SvcPlace.Fields.LABEL).get(),
                                labelSource = it.getFieldValue<TString>(SvcPlace.Fields.LABEL_SOURCE).get(),
                                accuracyM = it.getFieldValue<TInteger>(SvcPlace.Fields.ACCURACY_M).get().toInt(),
                            )
                        }
                },
                onFailure = { error("could not read place rows") },
            )

    @Test
    fun aVisitAcrossTheGridBoundaryFoldsOntoTheRenamedPlaceItReallyIs() = runTest {
        // F1 / §8.4, the reported bug end-to-end: a second visit to one venue whose centroid drifted
        // across the ~73 m grid cell boundary used to mint a *second* Place — so the user saw
        // "Office" listed twice and Home kept the old name on half the visits.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val t0 = 1_800_000_000_000L
        val min = 60_000L
        val writer = CapturedVisitWriter(client, proxy.tContext, writesHeld = { false })
        val repo = TimelineProjectionRepository(client, proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // Visit 1 — 49.0320/20.9030, a poor indoor fix (±90 m). Named by the user.
        val firstId = "cap:490320000,209030000"
        writer.write(CapturedVisit(490_320_000L, 209_030_000L, t0, t0 + 30 * min, accuracyM = 90))
        repo.renamePlace(firstId, "Office")

        // Visit 2, a day later — same building, centroid 73 m east ⇒ the *next* grid cell.
        writer.write(
            CapturedVisit(
                490_320_000L, 209_040_000L,
                t0 + 24 * 60 * min, t0 + 24 * 60 * min + 30 * min,
                accuracyM = 90,
            ),
        )

        val places = repo.places().first()
        assertEquals(1, places.size, "one venue must stay one place across the grid boundary")
        assertEquals("Office", places.single().name)
        assertEquals(2, places.single().visitsCount, "and both visits belong to it")
    }

    @Test
    fun twoRowsOfOneVenueReadAsOnePlaceOnceTheProxyMergesThem() = runTest {
        // P4f end-to-end: the proxy's PlaceReconciler writes a PlaceMerge, and the read model folds
        // it — the whole chain the reported "office listed twice" needed. Without the reader the
        // merge row exists and the Places tab still shows two rows, which is exactly what the
        // on-device pass found.
        //
        // ⚠ Both places are recorded through `recordPlace` DIRECTLY, bypassing `TimelineImportWriter`
        // — otherwise F1's write-time snap folds the second capture onto the first id and only one
        // row is ever created, so the test would pass without a reader at all (it did, first time
        // round). Two rows must genuinely exist for the merge path to be under test.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val ctx = proxy.tContext

        val north = "cap:490150000,209310000"
        val south = "cap:490140000,209310000" // one LATITUDE cell — 111 m, no metre radius reached it
        val t0 = 1_800_000_000_000L

        client.placeService.recordPlace(
            SvcPlace.create(ctx, north, 490_150_000L, 209_310_000L, UnixInstantDto(t0), label = "Home", labelSource = LabelSource.USER),
        ).orThrow("north")
        client.placeService.recordPlace(
            SvcPlace.create(ctx, south, 490_140_000L, 209_310_000L, UnixInstantDto(t0)),
        ).orThrow("south")
        recordVisit(client, ctx, "v-north", t0, north)
        recordVisit(client, ctx, "v-south", t0 + 86_400_000L, south)

        val repo = TimelineProjectionRepository(client, ctx, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // ⚠ Poll rather than reading once. The reconciliation pass is a *save observer*, so its
        // contract is "eventually one place", not "synchronously one place" — and a fixed-instant
        // read couples the test to observer dispatch order (`RelationMaintainer` must have added the
        // second row before `PlaceReconciler` clusters). Asserting the instant made this flaky under
        // full-suite load while passing in isolation, which is the worst kind of green.
        //
        // ⚠⚠ And the predicate must be the WHOLE expected state, not just its first clause. Waiting
        // on `size == 1` alone let the poll return the instant the merge landed but before the second
        // visit was counted — so the *next* assertion (visitsCount) flaked instead. A partial
        // predicate just moves the race one line down.
        val places = awaitPlaces(repo) { it.size == 1 && it.single().visitsCount == 2 }

        assertEquals(1, places.size, "one venue must read as one place once the proxy merged them")
        assertEquals("Home", places.single().name, "and it keeps the name the user gave it")
        assertEquals(2, places.single().visitsCount, "with BOTH rows' visits folded in")
    }

    /**
     * Read [TimelineRepository.places] until [predicate] holds, or give up after a bounded wall-clock
     * wait and return the last read so the assertion reports what it actually saw. Real time on
     * [Dispatchers.Default], because the work being awaited runs outside the test scheduler.
     */
    private suspend fun awaitPlaces(
        repo: TimelineProjectionRepository,
        predicate: (List<app.tenshu.timeline.model.Place>) -> Boolean,
    ): List<app.tenshu.timeline.model.Place> = withContext(Dispatchers.Default) {
        var last = repo.places().first()
        withTimeoutOrNull(5_000) {
            while (!predicate(last)) {
                delay(25)
                last = repo.places().first()
            }
        }
        last
    }

    @Test
    fun aManualMergeFoldsOntoTheDeterministicRootAndCarriesTheName() = runTest {
        // ⚠ The user names the row that does NOT survive: the survivor is min(a, b), the same root the
        // automatic pass elects, so a hand-made merge can never contradict an automatic one.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val ctx = proxy.tContext
        val repo = TimelineProjectionRepository(client, ctx, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // Far apart, so nothing auto-merges them — this is the case only a human can decide.
        client.placeService.recordPlace(
            SvcPlace.create(ctx, "cap:b-second", 490_320_000L, 209_030_000L, UnixInstantDto(1_000L), label = "The Office", labelSource = LabelSource.USER),
        ).orThrow("b")
        client.placeService.recordPlace(
            SvcPlace.create(ctx, "cap:a-first", 410_320_000L, 199_030_000L, UnixInstantDto(1_000L)),
        ).orThrow("a")

        val root = repo.mergePlaces("cap:b-second", "cap:a-first")

        assertEquals("cap:a-first", root, "the survivor is the deterministic root, not the caller's pick")
        // ⚠ The read model is stream-backed, so a just-written merge is visible *eventually*, not
        // instantly — await the whole expected state (§10.14's lesson: a partial predicate only moves
        // the race). The same shape applies in the app: the merge navigates to the survivor.
        val places = awaitPlaces(repo) { it.size == 1 && it.single().name == "The Office" }
        assertEquals(1, places.size)
        assertEquals("The Office", places.single().name, "the name must travel to the survivor")
    }

    @Test
    fun unmergingRestoresBothRows() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val ctx = proxy.tContext
        val repo = TimelineProjectionRepository(client, ctx, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        client.placeService.recordPlace(
            SvcPlace.create(ctx, "cap:a-first", 410_320_000L, 199_030_000L, UnixInstantDto(1_000L)),
        ).orThrow("a")
        client.placeService.recordPlace(
            SvcPlace.create(ctx, "cap:b-second", 490_320_000L, 209_030_000L, UnixInstantDto(1_000L)),
        ).orThrow("b")

        repo.mergePlaces("cap:a-first", "cap:b-second")
        assertEquals(1, awaitPlaces(repo) { it.size == 1 }.size)

        repo.unmergePlace("cap:b-second")

        // Nothing was ever rewritten, so nothing is "recovered" — both rows were there all along.
        assertEquals(
            listOf("cap:a-first", "cap:b-second"),
            awaitPlaces(repo) { it.size == 2 }.map { it.id }.sorted(),
        )
    }

    @Test
    fun aSurvivorThatAlreadyHasANameKeepsIt() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val ctx = proxy.tContext
        val repo = TimelineProjectionRepository(client, ctx, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        client.placeService.recordPlace(
            SvcPlace.create(ctx, "cap:a-first", 410_320_000L, 199_030_000L, UnixInstantDto(1_000L), label = "Home", labelSource = LabelSource.USER),
        ).orThrow("a")
        client.placeService.recordPlace(
            SvcPlace.create(ctx, "cap:b-second", 490_320_000L, 209_030_000L, UnixInstantDto(1_000L), label = "Flat", labelSource = LabelSource.USER),
        ).orThrow("b")

        repo.mergePlaces("cap:a-first", "cap:b-second")

        assertEquals("Home", awaitPlaces(repo) { it.size == 1 }.single().name)
    }

    private suspend fun recordVisit(
        client: TenshuClient,
        ctx: org.tenshu.core.core.TContext,
        momentId: String,
        atMs: Long,
        placeId: String,
    ) = client.timelineService.recordMoment(
        Moment.create(
            context = ctx,
            momentId = momentId,
            timestamp = UnixInstantDto(atMs),
            kind = Moment.MomentKind.VISIT,
            title = "Visit",
            subject = SubjectRef.create(ctx, SubjectKind.PLACE, placeId),
            durationSec = 1_800,
        ),
    ).orThrow(momentId)

    private fun visit(id: String, atMs: Long, placeId: String) = ImportedMoment(
        momentId = id,
        timestampMs = atMs,
        kind = Moment.MomentKind.VISIT,
        title = "Visit",
        subjectKind = SubjectKind.PLACE,
        subjectId = placeId,
    )

    /** A deterministic in-test [LocationCaptureSource] — stands in for the Android sensor (I3b-2 / M3). */
    private class FakeCaptureSource(private val captured: List<CaptureEvent>) : LocationCaptureSource {
        override fun isAvailable(): Boolean = true
        override fun events(): Flow<CaptureEvent> = captured.asFlow()
    }

    /**
     * A capture source that **never completes**, like a real one. Needed to observe the engine's
     * live state at all: `LocationCaptureEngine.run()` returning means capture stopped, and its
     * `finally` then (correctly) clears every live flag — so a completing source can only ever
     * show the cleared end state.
     *
     * [emitted] counts events the engine has consumed, so a test can wait for the dispatch to have
     * happened instead of guessing with a delay.
     */
    private class OpenCaptureSource(initial: List<CaptureEvent>) : LocationCaptureSource {
        private val channel = kotlinx.coroutines.channels.Channel<CaptureEvent>(capacity = 64)
        private val count = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** Emits once every queued event has been dispatched by the engine. */
        val emitted: Flow<Int> get() = count.filter { it >= expected }
        private var expected = initial.size

        init {
            initial.forEach { channel.trySend(it) }
        }

        fun send(event: CaptureEvent) {
            expected += 1
            channel.trySend(event)
        }

        override fun isAvailable(): Boolean = true
        override fun events(): Flow<CaptureEvent> = kotlinx.coroutines.flow.flow {
            for (event in channel) {
                emit(event)
                count.value += 1
            }
        }
    }


    /**
     * **One-shot reads must not ride the shared upstreams.**
     *
     * ⚠⚠ The repository shares its reactive reads (`shared()`, `replay = 1`) so N consumers cost one
     * read. That is safe for a flow the UI *collects* and unsafe for a one-shot `.first()`: a
     * replayed value is by definition from before the call, so a mutate-then-reload — exactly what
     * `PlaceDetailViewModel.rename` does — could re-read a pre-write snapshot and show the write as
     * having silently failed. Sharing therefore covers the reactive paths only, and every one-shot
     * reader goes through a `…Fresh()` builder.
     *
     * Pinning that split by *timing* proved impossible in this harness (a write is not visible to a
     * subsequent read here even with sharing switched off entirely — a property of the fixture, not
     * of sharing). So it is pinned structurally instead: **cancel the sharing scope**, which kills
     * every shared upstream, and require the one-shot reads to keep working. Point `place()` or
     * `trip()` at a shared flow and this test fails.
     *
     * ⚠ The sharing scope is deliberately NOT the `runTest` scope: `shareIn` launches a coroutine
     * that never completes, and `runTest` waits for its children — passing the test's own scope
     * hangs the test rather than failing it.
     */
    @Test
    fun oneShotReadsKeepWorkingWhenTheSharedUpstreamsAreGone() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val sharingScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(ReferenceTimelineSource().load())
        val repo = TimelineProjectionRepository(client, probeScope = sharingScope, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // Warm the shared upstreams, so there is something to kill.
        assertEquals(2, repo.homeDays().first().flatMap { it.moments }.size)
        assertEquals("Blue Bottle", repo.places().first().single { it.id == ReferenceTimelineSource.PLACE_CAFE }.name)

        sharingScope.cancel()

        // Every one-shot read must still answer from its own fresh read.
        //
        // ⚠ No `withTimeoutOrNull` guard here: these reads hop to `Dispatchers.Default`, and a
        // `runTest` timeout runs on VIRTUAL time — with nothing pending on the test scheduler it
        // advances the clock instantly and the timeout fires while the real work is still running,
        // failing the test for a reason that has nothing to do with what it checks. A genuine hang
        // still fails, via `runTest`'s own wall-clock timeout.
        val place = repo.place(ReferenceTimelineSource.PLACE_CAFE)
        assertTrue(place != null, "place() must not depend on a shared upstream")
        assertEquals("Blue Bottle", place.name)
        assertEquals(
            1,
            repo.place(ReferenceTimelineSource.PLACE_CAFE)?.history?.size,
            "the place's derived visit history reads moments freshly too",
        )
    }

    // ── one in-process proxy hosting all three services ──────────────────────

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(timelineServiceCommonModule); registerConfig(timelineServiceClientModule)
            registerConfig(placeServiceCommonModule); registerConfig(placeServiceClientModule)
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                timelineServiceProxyImplementationFactory(id)
                    ?: placeServiceProxyImplementationFactory(id)
                    ?: healthServiceProxyImplementationFactory(id, proxy.fileDatabase)
            },
        ).also {
            it.executionContext.initTimelineService(SemanticMergerRegistry())
            it.executionContext.initPlaceService(SemanticMergerRegistry())
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
