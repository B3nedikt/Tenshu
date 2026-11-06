package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedMovementTrace
import app.tenshu.timeline.data.ImportedTimeline
import app.tenshu.timeline.data.TimelineImportWriter
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.LonLatPolyline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.fold
import org.tenshu.core.mapregion.RailLineGeometry
import org.tenshu.core.mapregion.RailLinesIn
import org.tenshu.core.number.TInteger
import org.tenshu.core.TByteArray
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.timeline.client.timelineService
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * The sweep, end-to-end over the real store: a captured `drive` along track becomes `transit`, its
 * moment's title follows, and re-running changes nothing.
 *
 * `railLinesIn` is served by a stub implementation rather than the real proxy — this test is about
 * the *sweep's* behaviour (candidate selection, evidence handling, write-back, idempotency), and the
 * real tile pipeline behind that op already has its own coverage against the shipped archive
 * (`RailTileReaderRealArchiveTest`).
 */
class RailRefinementSweepTest {

    private val baseLat = 52.0
    private val baseLon = 13.0

    /** A straight north-running path of [n] points, ~11 m apart, one fix per 30 s. */
    private fun path(n: Int) = (0 until n).map { i ->
        CapturedFix(
            latE7 = (baseLat * 1e7).toLong() + i * 1_000,
            lngE7 = (baseLon * 1e7).toLong(),
            timestampMs = 1_800_000_000_000L + i * 30_000L,
            accuracyM = 8,
        )
    }

    private fun lonLat(f: CapturedFix) = LonLat(lon = f.lngE7 / 1e7, lat = f.latE7 / 1e7)

    private suspend fun seedDriveTrace(
        client: TenshuClient,
        proxy: TestProxy,
        fixes: List<CapturedFix>,
        mode: String = MovementMode.DRIVE,
        steps: Long = -1,
    ) {
        val encoded = MovementTracePolyline.encode(fixes)
        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedTimeline(
                moments = listOf(
                    app.tenshu.timeline.data.ImportedMoment(
                        momentId = "cap-move:1",
                        timestampMs = fixes.first().timestampMs,
                        kind = Moment.MomentKind.MOVEMENT,
                        title = MovementMapper.moveTitle(mode, 1_000),
                        subjectKind = SubjectKind.TRACE,
                        subjectId = "cap-trace:1",
                        durationSec = 600,
                    ),
                ),
                traces = listOf(
                    ImportedMovementTrace(
                        traceId = "cap-trace:1",
                        mode = mode,
                        distanceM = 1_000,
                        startMs = fixes.first().timestampMs,
                        endMs = fixes.last().timestampMs,
                        pointCount = fixes.size.toLong(),
                        minLatE7 = fixes.minOf { it.latE7 },
                        minLngE7 = fixes.minOf { it.lngE7 },
                        maxLatE7 = fixes.maxOf { it.latE7 },
                        maxLngE7 = fixes.maxOf { it.lngE7 },
                        polyline = encoded,
                        classifierVersion = MOVEMENT_CLASSIFIER_VERSION.toLong(),
                        modeConfidence = 60,
                        medianSpeedMmPerS = 20_000,
                        rawPointCount = fixes.size.toLong(),
                        startLatE7 = fixes.first().latE7,
                        startLngE7 = fixes.first().lngE7,
                        endLatE7 = fixes.last().latE7,
                        endLngE7 = fixes.last().lngE7,
                        steps = steps,
                    ),
                ),
            ),
        )
    }

    private suspend fun traceMode(client: TenshuClient): Pair<String, Long> {
        val row = client.timelineService.traces
            .select { fields(MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE, MovementTrace.Fields.CLASSIFIER_VERSION) }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() }).single()
        return row.getFieldValue<TString>(MovementTrace.Fields.MODE).get() to
            row.getFieldValue<TInteger>(MovementTrace.Fields.CLASSIFIER_VERSION).get()
    }

    private suspend fun momentTitle(client: TenshuClient): String =
        client.timelineService.moments
            .select { fields(Moment.Fields.MOMENT_ID, Moment.Fields.TITLE, Moment.Fields.SUBJECT) }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
            .single().getFieldValue<TString>(Moment.Fields.TITLE).get()

    @Test
    fun aDriveAlongTrackBecomesTransitAndItsTitleFollows() = runTest {
        val fixes = path(20)
        val proxy = stackProxy(this, railLines = fixes.map(::lonLat))
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        assertEquals(MovementMode.DRIVE to MOVEMENT_CLASSIFIER_VERSION.toLong(), traceMode(client))

        val result = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep()
        assertEquals(1, result.examined)
        assertEquals(1, result.refined)

        assertEquals(
            MovementMode.TRANSIT to MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED.toLong(),
            traceMode(client),
        )
        // ⚠ The stored title carries the mode. Leaving it as "Drive · 1.0 km" while the trace says
        // transit would put a contradiction on screen — the card renders both.
        assertTrue(momentTitle(client).startsWith("Transit"), "title was '${momentTitle(client)}'")
    }

    /**
     * FA-4: the sweep re-records the trace whole, so a field it fails to carry is written back as
     * the default — `-1`, silently erasing a counted total.
     */
    @Test
    fun aRefinedTraceKeepsItsStepCount() = runTest {
        val fixes = path(20)
        val proxy = stackProxy(this, railLines = fixes.map(::lonLat))
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes, steps = 842)

        assertEquals(1, RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep().refined)

        val steps = client.timelineService.traces
            .select { fields(MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE, MovementTrace.Fields.STEPS) }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() }).single()
        assertEquals(MovementMode.TRANSIT, steps.getFieldValue<TString>(MovementTrace.Fields.MODE).get())
        assertEquals(842L, steps.getFieldValue<TInteger>(MovementTrace.Fields.STEPS).get())
    }

    @Test
    fun aSecondSweepIsANoOp() = runTest {
        val fixes = path(20)
        val proxy = stackProxy(this, railLines = fixes.map(::lonLat))
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        val sweep = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        assertEquals(1, sweep.sweep().refined)
        // The version stamp is the whole idempotency mechanism — nothing is re-examined.
        val second = sweep.sweep()
        assertEquals(0, second.examined)
        assertEquals(0, second.refined)
    }

    @Test
    fun anUnavailableSurveyLeavesTheTraceForTheNextRun() = runTest {
        // The op answers empty — which is *ambiguous*, not evidence (no covering region, no platform
        // gzip, a corrupt bundle all look like this). The trace must stay un-stamped so a later run,
        // once the region is downloaded, still gets its chance.
        val fixes = path(20)
        val proxy = stackProxy(this, railLines = emptyList())
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        val result = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep()
        assertEquals(1, result.examined)
        assertEquals(0, result.refined)
        assertEquals(1, result.unavailable)
        assertEquals(MovementMode.DRIVE to MOVEMENT_CLASSIFIER_VERSION.toLong(), traceMode(client))
    }

    @Test
    fun aDriveAwayFromTrackIsConfirmedAndStamped() = runTest {
        // Track exists but is ~1.4 km away: real evidence, so the drive is confirmed and stamped —
        // and a second sweep will not revisit it.
        val fixes = path(20)
        val elsewhere = fixes.map { LonLat(lon = (it.lngE7 / 1e7) + 0.02, lat = it.latE7 / 1e7) }
        val proxy = stackProxy(this, railLines = elsewhere)
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        assertEquals(1, RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep().refined)
        assertEquals(
            MovementMode.DRIVE to MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED.toLong(),
            traceMode(client),
        )
        assertTrue(momentTitle(client).startsWith("Drive"), "the title must not change")
    }

    // ── B4: the corrected route (§14) ────────────────────────────────────────

    /** Every stored `SnappedRoute`, decoded. */
    private suspend fun snappedRoutes(client: TenshuClient): List<StoredSnap> =
        client.timelineService.snappedRoutes
            .select {
                fields(
                    SnappedRoute.Fields.TRACE_ID, SnappedRoute.Fields.SOURCE,
                    SnappedRoute.Fields.SNAP_VERSION, SnappedRoute.Fields.COVERAGE_BP,
                    SnappedRoute.Fields.POLYLINE,
                )
            }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
            .map { row ->
                StoredSnap(
                    traceId = row.getFieldValue<TString>(SnappedRoute.Fields.TRACE_ID).get(),
                    source = row.getFieldValue<TString>(SnappedRoute.Fields.SOURCE).get(),
                    snapVersion = row.getFieldValue<TInteger>(SnappedRoute.Fields.SNAP_VERSION).get(),
                    coverageBp = row.getFieldValue<TInteger>(SnappedRoute.Fields.COVERAGE_BP).get(),
                    fixes = MovementTracePolyline.decode(
                        row.getFieldValue<TByteArray>(SnappedRoute.Fields.POLYLINE).get()
                    ),
                )
            }

    private data class StoredSnap(
        val traceId: String,
        val source: String,
        val snapVersion: Long,
        val coverageBp: Long,
        val fixes: List<CapturedFix>,
    )

    /** The trace's own stored polyline bytes — the thing snapping must never touch. */
    private suspend fun storedPolyline(client: TenshuClient): ByteArray =
        client.timelineService.traces
            .select { fields(MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.POLYLINE) }
            .flow().first().fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
            .single().getFieldValue<TByteArray>(MovementTrace.Fields.POLYLINE).get()

    /** A sawtooth path: zig-zags ~34 m east/west so decimation keeps every point (see `RailRouteSnapTest`). */
    private fun sawtoothPath(n: Int, lonOffsetDeg: Double = 0.0) = (0 until n).map { i ->
        CapturedFix(
            latE7 = ((baseLat + i * 0.0001) * 1e7).toLong(),
            lngE7 = ((baseLon + lonOffsetDeg + if (i % 2 == 0) 0.0 else 0.0005) * 1e7).toLong(),
            timestampMs = 1_800_000_000_000L + i * 30_000L,
            accuracyM = 8,
        )
    }

    @Test
    fun aPromotedRailTraceGetsItsRouteRedrawnWithoutTouchingTheRawPath() = runTest {
        val fixes = sawtoothPath(20, lonOffsetDeg = 0.0002)   // recorded ~14 m off the track
        val track = sawtoothPath(20)                           // where the train really ran
        val proxy = stackProxy(this, railLines = track.map(::lonLat))
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)
        val rawBefore = storedPolyline(client)

        val result = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep()

        assertEquals(1, result.refined)
        assertEquals(1, result.snapped)
        val snap = snappedRoutes(client).single()
        assertEquals("cap-trace:1", snap.traceId)
        assertEquals(SnappedRoute.Sources.RAIL, snap.source)
        assertEquals(RailRouteSnap.SNAP_VERSION.toLong(), snap.snapVersion)
        assertEquals(10_000L, snap.coverageBp)
        // ⚠⚠ The raw polyline is the record of what the device observed; a snap is a verdict over it.
        assertContentEquals(rawBefore, storedPolyline(client), "the raw polyline was rewritten")
        // …and it really is a *different* path, so "unchanged" is not trivially true.
        assertTrue(
            snap.fixes.map { it.lngE7 } != MovementTracePolyline.decode(rawBefore).map { it.lngE7 },
            "the snapped route is identical to the raw one — nothing was corrected",
        )
        // The journey's own clock survives the redraw.
        assertEquals(fixes.first().timestampMs, snap.fixes.first().timestampMs)
        assertEquals(fixes.last().timestampMs, snap.fixes.last().timestampMs)
    }

    @Test
    fun aSecondSweepDoesNotRedrawAnything() = runTest {
        val fixes = sawtoothPath(20, lonOffsetDeg = 0.0002)
        val proxy = stackProxy(this, railLines = sawtoothPath(20).map(::lonLat))
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        val sweep = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        assertEquals(1, sweep.sweep().snapped)
        val after = snappedRoutes(client).single()

        val second = sweep.sweep()
        assertEquals(0, second.examined)
        assertEquals(0, second.snapped)
        // Keyed by traceId, so even a re-run that *did* happen would fold rather than accumulate.
        assertEquals(1, snappedRoutes(client).size)
        assertContentEquals(
            MovementTracePolyline.encode(after.fixes),
            MovementTracePolyline.encode(snappedRoutes(client).single().fixes),
        )
    }

    @Test
    fun aStreetRunningTramRideIsNotRedrawn() = runTest {
        val fixes = sawtoothPath(20, lonOffsetDeg = 0.0002)
        val track = sawtoothPath(20).map(::lonLat)
        // ⚠ Seeded as TRANSIT, not DRIVE — a T0 kinematic promotion. That is what makes this test
        // bite: `RailRefinement`'s street-running branch keeps the verdict at `transit` (coverage
        // 1.0 with T0 corroboration), so the trace reaches the snapper and only the exclusive-track
        // rule can stop it. Seeded as DRIVE the classifier would refuse first and the snapper's own
        // rule would never be exercised.
        run {
            // Precondition: the identical geometry DOES snap when the line is heavy rail.
            val railProxy = stackProxy(this, railLines = track, railKind = "rail")
            val railClient = clientOf(railProxy)
            seedDriveTrace(railClient, railProxy, fixes, mode = MovementMode.TRANSIT)
            assertEquals(1, RailRefinementSweep(railClient, railProxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep().snapped)
        }

        val proxy = stackProxy(this, railLines = track, railKind = "tram")
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes, mode = MovementMode.TRANSIT)

        val result = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep()

        assertEquals(1, result.examined, "the tram trace must actually reach the sweep")
        // Precondition: the tram evidence really did promote — the trace is `transit` at the new
        // stamp — so a missing route below is the snapper refusing, not the sweep skipping.
        assertEquals(
            MovementMode.TRANSIT to MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED.toLong(),
            traceMode(client),
        )
        assertEquals(0, result.snapped)
        assertTrue(snappedRoutes(client).isEmpty(), "a car on a tram street would be redrawn as a train")
    }

    @Test
    fun anUnavailableSurveyWritesNoRouteAndLeavesTheTraceRetryable() = runTest {
        val fixes = sawtoothPath(20, lonOffsetDeg = 0.0002)
        val proxy = stackProxy(this, railLines = emptyList())   // the op answers empty ⇒ ambiguous
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        val result = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep()

        assertEquals(1, result.examined, "the trace must actually reach the sweep")
        assertEquals(1, result.unavailable)
        assertEquals(0, result.snapped)
        assertTrue(snappedRoutes(client).isEmpty(), "an un-surveyable region must not produce geometry")
        // Un-stamped ⇒ still a candidate, so a later run (once the region is downloaded) retries it.
        assertEquals(MovementMode.DRIVE to MOVEMENT_CLASSIFIER_VERSION.toLong(), traceMode(client))
        assertEquals(1, RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep().examined)
    }

    @Test
    fun aDriveAwayFromTrackIsStampedButNotRedrawn() = runTest {
        val fixes = sawtoothPath(20)
        val elsewhere = fixes.map { LonLat(lon = (it.lngE7 / 1e7) + 0.02, lat = it.latE7 / 1e7) }
        val proxy = stackProxy(this, railLines = elsewhere)
        val client = clientOf(proxy)
        seedDriveTrace(client, proxy, fixes)

        val result = RailRefinementSweep(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).sweep()

        assertEquals(1, result.refined, "the drive is confirmed and stamped")
        assertEquals(0, result.snapped)
        assertTrue(snappedRoutes(client).isEmpty())
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /** Serves [railLines] as one line of [railKind], so the sweep exercises the real op surface. */
    private fun stackProxy(
        scope: CoroutineScope,
        railLines: List<LonLat>,
        railKind: String = "rail",
    ): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(timelineServiceCommonModule); registerConfig(timelineServiceClientModule)
            registerConfig(mapRegionServiceCommonModule); registerConfig(mapRegionServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ ->
                timelineServiceProxyImplementationFactory(id)
                    ?: if (id == RailLinesIn.ID) StubRailLinesIn(railLines, railKind) else null
            },
        ).also { it.executionContext.initTimelineService(SemanticMergerRegistry()) }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        ),
    )

    private class StubRailLinesIn(
        private val points: List<LonLat>,
        private val kind: String = "rail",
    ) : org.tenshu.core.common.proxy.executor.AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : org.tenshu.core.Data?> execute(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            operationContext: org.tenshu.core.common.proxy.OperationContext?,
            implementationFactory: org.tenshu.core.common.proxy.executor.OperationImplementationFactory,
            caller: Any?,
            params: Map<String, org.tenshu.core.Data>?,
        ): T {
            val ctx = executionContext.context
            val rows = if (points.size < 2) emptyList() else listOf(
                RailLineGeometry.create(ctx, kind = kind, polyline = LonLatPolyline.encode(points)),
            )
            return rows.toTList(ctx) as T
        }
    }
}
