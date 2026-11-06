package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.LonLatPolyline
import app.tenshu.vectortile.RailMatcher
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.id
import org.tenshu.core.messageOrNull
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.mapregion.RailLineGeometry
import org.tenshu.core.mapregion.railLinesIn
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.recordMoment
import org.tenshu.core.timeline.recordMovementTrace
import org.tenshu.core.timeline.recordSnappedRoute
import org.tenshu.mapregion.client.mapRegionService
import org.tenshu.timeline.client.timelineService
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong
import app.tenshu.diagnostics.Diagnostics

/**
 * **The rail re-classification sweep** (M9-5, `docs/proposals/timeline-movement-capture.md` §13.3) —
 * the pass that finally turns everything M9-1…M9-4 built into labels a user sees.
 *
 * Capture cannot do this itself: `classifyMode` is synchronous inside the streaming segmenter, while
 * rail geometry arrives over an async op through Control's proxy. So traces land with a speed-based
 * verdict, and this sweep revisits them later with the map. `MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED`
 * is what makes that safe to run repeatedly — a refined trace is never re-examined, and a trace the
 * sweep *could not* judge stays un-stamped so the next run retries it.
 *
 * ### Idempotent and interruptible by construction
 *
 * Every write is a content-addressed upsert (`traceId` / `momentId`), the candidate query is derived
 * from the stored version, and there is no cursor to lose. Killing the sweep mid-run costs only the
 * work in flight.
 */
class RailRefinementSweep(
    private val client: TenshuClient,
    private val context: TContext,
    /**
     * ADR 0025 §8.6 write boundary: runs before EACH write (trace, snapped route, moment title) and
     * throws to refuse it. A refused run ends there and reports nothing refined; the sweep is a
     * catch-up that the next launch or the next recorded trace runs again. ⚠ Required, never defaulted.
     */
    private val beforeEachWrite: suspend () -> Unit,
) {

    /** What one run did. [unavailable] is the retry backlog, not a failure count. */
    data class SweepResult(
        val examined: Int,
        val refined: Int,
        val unavailable: Int,
        /** How many traces also had their route redrawn on the rails (B4). Always ≤ [refined]. */
        val snapped: Int = 0,
        /**
         * The **trace read itself** did not answer — so this run says nothing about the store.
         *
         * ⚠⚠ Distinct from `examined == 0`, and the distinction is the whole point: an empty store
         * is a settled fact a later run cannot improve on, while an unanswered read is *no
         * information* and must be retried. Collapsing the two is what let this sweep report
         * "0 candidate(s) of 0 trace(s)" against a store holding fifteen traces, for three days, and
         * burn its one-shot-per-launch doing it.
         *
         * This is [RailEvidence.Unavailable] vs `Surveyed(emptyList())` — the same rule [fetchEvidence]
         * already applies to the rail-lines read, one layer down — finally applied to the read above it.
         */
        val tracesUnreadable: Boolean = false,
    ) {
        val changedAnything: Boolean get() = refined > 0
    }

    /**
     * Refine up to [limit] un-swept traces.
     *
     * @param limit bounds one run's work — each trace costs a tile-fetching op, and a first sweep
     *   over a long history should not become an unbounded burst of egress.
     */
    suspend fun sweep(limit: Int = DEFAULT_LIMIT): SweepResult = try {
        sweepUngated(limit)
    } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
        Diagnostics.i("sweep", "rail sweep held — the realm gate refused a write", code = "rail_sweep_held")
        // Settled for this run, not "unreadable": the trigger must not spin retries on a held gate.
        SweepResult(0, 0, 0)
    }

    private suspend fun sweepUngated(limit: Int): SweepResult {
        val all = readTraces()
        if (all == null) {
            // ⚠ Its own log line, not a "0 traces" one: the two readings call for opposite responses
            // (retry vs stop), so they must never render as the same sentence in the log either.
            Diagnostics.w(
                "sweep",
                "rail sweep: could not read traces — nothing concluded, will retry",
                code = "rail_sweep_unreadable",
            )
            return SweepResult(0, 0, 0, tracesUnreadable = true)
        }
        // ⚠ S8 (§25): candidacy now reads the fixes too, because a `walk`/`run` trace qualifies only
        // when its middle is blind. Decoding the polyline here is local work over rows already in
        // memory — it buys the tier its pedestrian cases without spending a tile fetch on every walk.
        val traces = all
            .filter { RailRefinement.needsRefinement(it.classification, MovementTracePolyline.decode(it.polyline)) }
            .take(limit)
        // Emitted *before* the early return so "no log" can't hide a run that had nothing to do —
        // "0 candidate(s) of N" tells us the sweep ran and the store held N traces (§3.2 cause 4).
        Diagnostics.i(
            "sweep",
            "rail sweep: ${traces.size} candidate(s) of ${all.size} trace(s)",
            code = "rail_sweep_start",
        )
        if (traces.isEmpty()) return SweepResult(0, 0, 0)

        val momentsByTrace = readMovementMoments()

        var refined = 0
        var unavailable = 0
        var snapped = 0
        for (trace in traces) {
            // ⚠ Two different codecs are in play. The *stored* trace polyline is
            // `MovementTracePolyline` (lat/lng/time/accuracy — a captured path); `LonLatPolyline` is
            // the *wire* codec for rail lines coming back from the proxy. Decoding one with the
            // other yields plausible-looking garbage rather than an error.
            val fixes = MovementTracePolyline.decode(trace.polyline)
            val path = fixes.map { LonLat(lon = it.lngE7 / 1e7, lat = it.latE7 / 1e7) }
            if (path.isEmpty()) {
                // No geometry to match. Not "unavailable" — no future run could do better — but also
                // not evidence, so leave it un-stamped rather than confirming a verdict on nothing.
                Diagnostics.d("sweep", "trace ${traceTag(trace)} skipped: empty path", code = "rail_sweep_trace")
                continue
            }

            val evidence = fetchEvidence(path)
            if (evidence is RailEvidence.Unavailable) unavailable++

            val updated = RailRefinement.refine(
                baseline = trace.classification,
                path = path,
                evidence = evidence,
                stops = stopsIn(fixes),
                // S4 (§20): the dark-leg tier needs the TIMESTAMPS, which the lon/lat path drops.
                fixes = fixes,
            )
            // Per-trace breadcrumb: which trace, its area, what evidence came back, and the verdict.
            // This is what turns a stuck "train reads as Drive" into a decisive on-device diagnosis —
            // `evidence=unavailable` means no rail geometry reached us for that bbox (§3.2 causes
            // 1–3); `surveyed(0)` means the covering tile had no track there; `surveyed(n)` with no
            // mode change means track was found but the path did not match it.
            // When we have geometry, also record the actual match figures — the decisive detail
            // between "not on rail" (coverage ~0) and "on rail but below threshold" (coverage high but
            // < the promote bar, i.e. a tolerance/zoom tuning problem, not a data problem).
            val matchTag = (evidence as? RailEvidence.Surveyed)?.let { s ->
                val m = RailMatcher.match(path, s.lines)
                // ⚠ `exclM` is not redundant beside `excl`: since §39 it is the number that decides
                // a long leg, and a reader cannot recover it from the fraction — the line carries no
                // path length.
                " cov=${round2(m.coverage)} excl=${round2(m.exclusiveCoverage)} " +
                    "exclM=${m.exclusiveMatchedMeters.toInt()} " +
                    "pts=${m.matchedPointCount}/${m.totalPointCount} kinds=${m.matchedKinds}"
            }.orEmpty()
            Diagnostics.d(
                "sweep",
                "trace ${traceTag(trace)} ${trace.distanceM / 1000}km pts=${path.size} " +
                    "${trace.classification.mode}→${updated.mode} " +
                    "evidence=${evidenceTag(evidence)}$matchTag bbox=${bboxTag(trace.bounds)}",
                code = "rail_sweep_trace",
            )
            if (updated.version == trace.classification.version && updated.mode == trace.classification.mode) continue

            // B4 (§14): the geometry is already in memory and the verdict is already reached, so
            // redrawing the route on the rails costs one more projection pass and no second fetch.
            // ⚠ Written as its OWN row — `trace.polyline` below is passed through verbatim, and the
            // raw path stays the record of what the device actually observed (§12.0).
            RailRouteSnap.snap(fixes = fixes, refined = updated, evidence = evidence)?.let { snap ->
                writeSnappedRoute(trace.traceId, snap)
                snapped++
            }
            writeTrace(trace, updated)
            // ⚠ The moment's stored title carries the mode ("Drive · 34.2 km"). Leaving it while the
            // trace says `transit` would put a contradiction on screen — the card renders the title
            // *and* the mode label. Regenerate it from the same source the mapper used.
            if (updated.mode != trace.classification.mode) {
                momentsByTrace[trace.traceId]?.let { moment ->
                    writeMomentTitle(moment, MovementMapper.moveTitle(updated.mode, trace.distanceM))
                }
            }
            refined++
        }
        val result = SweepResult(
            examined = traces.size,
            refined = refined,
            unavailable = unavailable,
            snapped = snapped,
        )
        Diagnostics.i(
            "sweep",
            "rail refinement: examined ${result.examined}, refined ${result.refined}, " +
                "unavailable ${result.unavailable}, snapped ${result.snapped}",
            code = "rail_sweep",
        )
        return result
    }

    // ── evidence ─────────────────────────────────────────────────────────────

    /**
     * Survey rail along [path], segment by segment.
     *
     * ⚠ Why not one bbox for the whole route: a long journey's bounding rectangle is hundreds of z14
     * tiles, which forces the reader to a coarse zoom whose *simplified* geometry sits beyond the
     * match tolerance from the GPS path in curves — a real train's coverage then falls below the
     * promote bar and it is left as (or falsely demoted to) `drive` (`timeline-recognition-and-place-
     * naming.md` §3). Splitting into short segments keeps every query at full z14 precision, and — a
     * bonus — each segment resolves its *own* covering region, so a cross-region journey is surveyed
     * correctly. The lines are unioned across segments and endpoint-deduped.
     *
     * ⚠⚠ A **failed** op for a segment is skipped, not treated as "no track" — the op also answers
     * empty when it could not look (no covering region, no gzip, a corrupt bundle), indistinguishable
     * on the wire from a genuine empty. So evidence is [RailEvidence.Unavailable] only when *no*
     * segment could be surveyed at all; otherwise `RailRefinement` could demote a real train taken
     * outside downloaded coverage. Un-surveyable segments simply contribute no lines.
     */
    private suspend fun fetchEvidence(path: List<LonLat>): RailEvidence {
        if (path.size < 2) return RailEvidence.Unavailable
        val lines = LinkedHashMap<String, RailMatcher.RailLine>()
        var anySurveyed = false
        for (segment in segmentPath(path)) {
            val padded = GeoBounds.of(segment)?.expandedBy(RailMatcher.DEFAULT_TOLERANCE_M) ?: continue
            val result = runCatchingCancellable {
                client.mapRegionService.railLinesIn(
                    minLatE7 = (padded.minLat * 1e7).roundToLong(),
                    minLngE7 = (padded.minLon * 1e7).roundToLong(),
                    maxLatE7 = (padded.maxLat * 1e7).roundToLong(),
                    maxLngE7 = (padded.maxLon * 1e7).roundToLong(),
                )
            }.getOrNull() ?: continue // op threw for this segment — skip, don't poison the verdict
            val rows = result.fold(onSuccess = { it.toList() }, onFailure = { null }) ?: continue
            // An *empty* result is ambiguous by construction (no covering region / no gzip / a
            // genuine "no track here" all look identical on the wire), so it is not evidence — skip
            // it, exactly as the pre-segment reader did. Only a non-empty segment proves we surveyed.
            if (rows.isEmpty()) continue
            anySurveyed = true
            for (row in rows) {
                val points = LonLatPolyline.decode(row.polyline.get())
                if (points.size < 2) continue
                val bounds = GeoBounds.of(points) ?: continue
                val kind = row.kind.get()
                lines.getOrPut("$kind:${points.first()}:${points.last()}:${points.size}") {
                    RailMatcher.RailLine(kind, points, bounds)
                }
            }
        }
        return if (!anySurveyed) RailEvidence.Unavailable else RailEvidence.Surveyed(lines.values.toList())
    }


    /**
     * Stop positions, derived from the stored path.
     *
     * ⚠ Approximate: the polyline is **decimated**, so a brief station stop can have been simplified
     * away and T2′ will simply not see it. That direction of error is the safe one — a missed stop
     * withholds corroboration rather than inventing it — but it does mean stop-on-rail is weaker
     * over stored traces than it would be over raw fixes.
     */
    private fun stopsIn(fixes: List<CapturedFix>): List<LonLat> {
        if (fixes.size < 2) return emptyList()
        val stops = ArrayList<LonLat>()
        var inStop = false
        for (i in 1 until fixes.size) {
            val dtSec = (fixes[i].timestampMs - fixes[i - 1].timestampMs) / 1000.0
            if (dtSec <= 0.0) continue
            val speed = capturedDistanceMeters(fixes[i - 1], fixes[i]) / dtSec
            val stopped = speed <= STOP_SPEED_MPS
            if (stopped && !inStop) {
                val f = fixes[i - 1]
                stops += LonLat(lon = f.lngE7 / 1e7, lat = f.latE7 / 1e7)
            }
            inStop = stopped
        }
        return stops
    }

    // ── diagnostic tags (PII-free: trace id + rounded bbox) ──────────────────

    private fun traceTag(trace: StoredTrace): String = trace.traceId.take(16)

    private fun evidenceTag(evidence: RailEvidence): String = when (evidence) {
        is RailEvidence.Unavailable -> "unavailable"
        is RailEvidence.Surveyed -> "surveyed(${evidence.lines.size})"
    }

    private fun bboxTag(b: GeoBounds): String {
        fun r(v: Double) = (v * 100).roundToLong() / 100.0
        return "${r(b.minLat)},${r(b.minLon)}..${r(b.maxLat)},${r(b.maxLon)}"
    }

    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    // ── reads ────────────────────────────────────────────────────────────────

    private data class StoredTrace(
        val traceId: String,
        val classification: ModeClassification,
        val sourceActivityType: String,
        val distanceM: Long,
        val startAt: UnixInstant,
        val endAt: UnixInstant,
        val pointCount: Long,
        val rawPointCount: Long,
        val minLatE7: Long,
        val minLngE7: Long,
        val maxLatE7: Long,
        val maxLngE7: Long,
        val startLatE7: Long,
        val startLngE7: Long,
        val endLatE7: Long,
        val endLngE7: Long,
        val polyline: ByteArray,
        val steps: Long,
    ) {
        val bounds: GeoBounds
            get() = GeoBounds(
                minLon = minLngE7 / 1e7,
                minLat = minLatE7 / 1e7,
                maxLon = maxLngE7 / 1e7,
                maxLat = maxLatE7 / 1e7,
            )
    }

    /**
     * Every stored trace, or `null` when the read did not answer.
     *
     * ⚠⚠ `null` is **not** the empty list. This read crosses IPC to Control, so it fails for reasons
     * that have nothing to do with how many traces exist — the proxy not yet bound in a freshly
     * started process being the one that actually bit (a launch racing the binding, one second after
     * `MY_PACKAGE_REPLACED`). Reporting that as an empty store made a failure look like a settled
     * answer. See [SweepResult.tracesUnreadable].
     */
    private suspend fun readTraces(): List<StoredTrace>? =
        client.timelineService.traces
            .select { fields(*TRACE_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows -> rows.toList().map(::storedTrace) },
                onFailure = { error ->
                    Diagnostics.w(
                        "sweep",
                        "trace read failed: ${error.id}${error.messageOrNull()?.let { " — $it" }.orEmpty()}",
                        code = "rail_sweep_read_error",
                    )
                    null
                },
            )

    private fun storedTrace(e: Entity<out MovementTrace>): StoredTrace {
        fun str(f: String) = e.getFieldValue<TString>(f).get()
        fun int(f: String) = e.getFieldValue<TInteger>(f).get()
        return StoredTrace(
            traceId = str(MovementTrace.Fields.TRACE_ID),
            classification = ModeClassification(
                mode = str(MovementTrace.Fields.MODE),
                confidence = int(MovementTrace.Fields.MODE_CONFIDENCE).toInt(),
                medianSpeedMmPerS = int(MovementTrace.Fields.MEDIAN_SPEED_MM_PER_S),
                version = int(MovementTrace.Fields.CLASSIFIER_VERSION).toInt(),
            ),
            sourceActivityType = str(MovementTrace.Fields.SOURCE_ACTIVITY_TYPE),
            distanceM = int(MovementTrace.Fields.DISTANCE_M),
            startAt = e.getFieldValue(MovementTrace.Fields.START_AT),
            endAt = e.getFieldValue(MovementTrace.Fields.END_AT),
            pointCount = int(MovementTrace.Fields.POINT_COUNT),
            rawPointCount = int(MovementTrace.Fields.RAW_POINT_COUNT),
            minLatE7 = int(MovementTrace.Fields.MIN_LAT_E7),
            minLngE7 = int(MovementTrace.Fields.MIN_LNG_E7),
            maxLatE7 = int(MovementTrace.Fields.MAX_LAT_E7),
            maxLngE7 = int(MovementTrace.Fields.MAX_LNG_E7),
            startLatE7 = int(MovementTrace.Fields.START_LAT_E7),
            startLngE7 = int(MovementTrace.Fields.START_LNG_E7),
            endLatE7 = int(MovementTrace.Fields.END_LAT_E7),
            endLngE7 = int(MovementTrace.Fields.END_LNG_E7),
            polyline = e.getFieldValue<TByteArray>(MovementTrace.Fields.POLYLINE).get(),
            steps = int(MovementTrace.Fields.STEPS),
        )
    }

    private data class StoredMoment(
        val momentId: String,
        val timestamp: UnixInstant,
        val kind: String,
        val subjectId: String,
        val durationSec: Long,
        val supersededBy: String,
        val sources: List<String>,
    )

    /** `MOVEMENT` moments keyed by the trace they point at. */
    private suspend fun readMovementMoments(): Map<String, StoredMoment> =
        client.timelineService.moments
            .select { fields(*MOMENT_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows ->
                    rows.toList().mapNotNull { e ->
                        val subject = e.getFieldValue<SubjectRef>(Moment.Fields.SUBJECT)
                        if (subject.kind.get() != SubjectKind.TRACE) return@mapNotNull null
                        subject.id.get() to StoredMoment(
                            momentId = e.getFieldValue<TString>(Moment.Fields.MOMENT_ID).get(),
                            timestamp = e.getFieldValue(Moment.Fields.TIMESTAMP),
                            kind = e.getFieldValue<TString>(Moment.Fields.KIND).get(),
                            subjectId = subject.id.get(),
                            durationSec = e.getFieldValue<TInteger>(Moment.Fields.DURATION_SEC).get(),
                            supersededBy = e.getFieldValue<TString>(Moment.Fields.SUPERSEDED_BY).get(),
                            sources = emptyList(), // preserved by the Merge strategy; not re-sent
                        )
                    }.toMap()
                },
                onFailure = { emptyMap() },
            )

    // ── writes (content-addressed upserts) ───────────────────────────────────

    private suspend fun writeTrace(trace: StoredTrace, updated: ModeClassification) {
        beforeEachWrite()
        client.timelineService.recordMovementTrace(
            MovementTrace.create(
                context = context,
                traceId = trace.traceId,
                mode = updated.mode,
                classifierVersion = updated.version.toLong(),
                modeConfidence = updated.confidence.toLong(),
                sourceActivityType = trace.sourceActivityType,
                medianSpeedMmPerS = updated.medianSpeedMmPerS,
                distanceM = trace.distanceM,
                startAt = trace.startAt,
                endAt = trace.endAt,
                pointCount = trace.pointCount,
                rawPointCount = trace.rawPointCount,
                minLatE7 = trace.minLatE7,
                minLngE7 = trace.minLngE7,
                maxLatE7 = trace.maxLatE7,
                maxLngE7 = trace.maxLngE7,
                startLatE7 = trace.startLatE7,
                startLngE7 = trace.startLngE7,
                endLatE7 = trace.endLatE7,
                endLngE7 = trace.endLngE7,
                polyline = trace.polyline,
                steps = trace.steps,
            ),
        )
    }

    /**
     * B4: the corrected route, as its own `traceId`-keyed row.
     *
     * ⚠ A content-addressed upsert like every other write here, so a re-run replaces rather than
     * accumulates — and it deliberately carries **no** reference back into the trace: the trace is
     * written unchanged (raw polyline included) and the two rows converge independently.
     */
    private suspend fun writeSnappedRoute(traceId: String, snap: RailRouteSnap.Snapped) {
        beforeEachWrite()
        client.timelineService.recordSnappedRoute(
            SnappedRoute.create(
                context = context,
                traceId = traceId,
                source = SnappedRoute.Sources.RAIL,
                snapVersion = RailRouteSnap.SNAP_VERSION.toLong(),
                coverageBp = snap.coverageBp,
                polyline = MovementTracePolyline.encode(snap.fixes),
            ),
        )
    }

    private suspend fun writeMomentTitle(moment: StoredMoment, title: String) {
        beforeEachWrite()
        client.timelineService.recordMoment(
            Moment.create(
                context = context,
                momentId = moment.momentId,
                timestamp = moment.timestamp,
                kind = moment.kind,
                title = title,
                subject = SubjectRef.create(context, SubjectKind.TRACE, moment.subjectId),
                durationSec = moment.durationSec,
                sources = moment.sources,
                supersededBy = moment.supersededBy,
            ),
        )
    }

    companion object {
        /** One run's ceiling. Each trace costs a tile-fetching op, so a first sweep stays bounded. */
        const val DEFAULT_LIMIT = 25

        /**
         * Max bbox span (degrees, per dimension) for one survey segment — sized so a segment's tile
         * grid stays within the z14 budget (~0.1° ≈ 4–7 z14 tiles at mid-latitudes), keeping rail
         * queries at full precision. See [fetchEvidence].
         */
        private const val MAX_SEGMENT_SPAN_DEG = 0.1

        /**
         * Split [path] into consecutive segments whose bounding boxes stay under [MAX_SEGMENT_SPAN_DEG]
         * in each dimension, so each fits the z14 tile budget. Segments overlap by one point so the
         * unioned rail geometry has no seam at the cut.
         */
        internal fun segmentPath(path: List<LonLat>): List<List<LonLat>> {
            if (path.size < 2) return listOf(path)
            val segments = ArrayList<List<LonLat>>()
            var seg = ArrayList<LonLat>().apply { add(path[0]) }
            var minLat = path[0].lat; var maxLat = path[0].lat
            var minLon = path[0].lon; var maxLon = path[0].lon
            for (i in 1 until path.size) {
                val p = path[i]
                val wLat = maxOf(maxLat, p.lat) - minOf(minLat, p.lat)
                val wLon = maxOf(maxLon, p.lon) - minOf(minLon, p.lon)
                if (wLat > MAX_SEGMENT_SPAN_DEG || wLon > MAX_SEGMENT_SPAN_DEG) {
                    segments.add(seg)
                    val prev = seg.last() // overlap so the two segments' geometry joins
                    seg = arrayListOf(prev, p)
                    minLat = minOf(prev.lat, p.lat); maxLat = maxOf(prev.lat, p.lat)
                    minLon = minOf(prev.lon, p.lon); maxLon = maxOf(prev.lon, p.lon)
                } else {
                    seg.add(p)
                    minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
                    minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon)
                }
            }
            segments.add(seg)
            return segments
        }

        // ⚠ Every field is projected because the trace is re-recorded whole — a field omitted here
        // reads back empty and would be *written back* empty, silently destroying it.
        private val TRACE_FIELDS = arrayOf(
            MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE,
            MovementTrace.Fields.CLASSIFIER_VERSION, MovementTrace.Fields.MODE_CONFIDENCE,
            MovementTrace.Fields.SOURCE_ACTIVITY_TYPE, MovementTrace.Fields.MEDIAN_SPEED_MM_PER_S,
            MovementTrace.Fields.DISTANCE_M, MovementTrace.Fields.START_AT, MovementTrace.Fields.END_AT,
            MovementTrace.Fields.POINT_COUNT, MovementTrace.Fields.RAW_POINT_COUNT,
            MovementTrace.Fields.MIN_LAT_E7, MovementTrace.Fields.MIN_LNG_E7,
            MovementTrace.Fields.MAX_LAT_E7, MovementTrace.Fields.MAX_LNG_E7,
            MovementTrace.Fields.START_LAT_E7, MovementTrace.Fields.START_LNG_E7,
            MovementTrace.Fields.END_LAT_E7, MovementTrace.Fields.END_LNG_E7,
            MovementTrace.Fields.POLYLINE, MovementTrace.Fields.STEPS,
        )

        private val MOMENT_FIELDS = arrayOf(
            Moment.Fields.MOMENT_ID, Moment.Fields.TIMESTAMP, Moment.Fields.KIND,
            Moment.Fields.TITLE, Moment.Fields.SUBJECT, Moment.Fields.DURATION_SEC,
            Moment.Fields.SUPERSEDED_BY,
        )
    }
}
