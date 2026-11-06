package app.tenshu.timeline.data

import app.tenshu.timeline.data.capture.CapturedFix
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_EXTERNAL
import app.tenshu.timeline.data.capture.MovementDetectionConfig
import app.tenshu.timeline.data.capture.MovementMode
import app.tenshu.timeline.data.capture.MovementTracePolyline
import app.tenshu.timeline.data.capture.pathDistanceMeters
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.timeline.common.MomentSourceTag
import kotlin.math.roundToLong

/**
 * The **device-independent Google Timeline bulk-import parser** (I2,
 * `docs/proposals/timeline-ingestion.md` §3): sniffs a picked [TimelineImportFile] as one of
 * Google's two export vintages and normalises it into the neutral [ImportedTimeline] the shared
 * [TimelineImportWriter] persists. Because Google exposes no API, the file the user exports from
 * the Maps app is the only ingestion path — and once it's bytes, parsing is pure `commonMain`,
 * fully unit-testable with no device. Mirrors Fit's `ActivityFileParser` recipe.
 *
 * Two vintages, sniffed by top-level structure (§3.1):
 * - **New on-device export** (2024+) — a single JSON with a top-level `semanticSegments` array;
 *   each segment is either a `visit` (dwell at a place) or an `activity` (movement between places).
 *   Coordinates arrive as a degree string (`"52.5200000°, 13.4050000°"`).
 * - **Legacy Semantic Location History** (pre-2024 Takeout, already unzipped) — a top-level
 *   `timelineObjects` array of `placeVisit` / `activitySegment` entries; coordinates are E7 ints
 *   and place visits carry a `name`.
 *
 * The mapping (§2): a **visit → an [ImportedPlace] + a `Visit` [ImportedMoment]** pointing at it;
 * an **activity segment → a `MOVEMENT` [ImportedMoment] + its [ImportedMovementTrace]** — the same
 * pair live capture writes (M7, `timeline-movement-capture.md` §12.2). Raw signals / `Records.json`
 * dense pings are **not** ingested (§8 decided — too dense). Place labels come only from
 * the legacy `name`; new-format visits are label-less until geocoded (§5) — we **never** persist
 * Google *content* beyond the foreign-key `placeId` (§5, ToS).
 *
 * ### What M7 changed, and why it mattered
 *
 * An activity segment used to become a **subjectless `Activity` moment** carrying only a start time,
 * a duration and a humanised title. Everything spatial was discarded — and most of it was never even
 * declared on the DTOs, so `ignoreUnknownKeys` dropped it silently:
 *
 *  - **`timelinePath`** — the actual movement geometry in modern exports — appeared *nowhere* in the
 *    codebase. Every imported trip was pathless.
 *  - legacy `simplifiedRawPath` / `waypointPath` / `startLocation` / `endLocation` / `distance`, all
 *    undeclared.
 *  - the raw **`activityType`** was bucketed into nine English display labels and kept only as the
 *    moment *title*, so `IN_BUS`, `IN_TRAM` and `IN_SUBWAY` collapsed together and
 *    `IN_PASSENGER_VEHICLE` was indistinguishable from `MOTORCYCLING` afterwards.
 *
 * That last one is the expensive loss: Google's label already draws the transit-vs-car distinction
 * **our own classifier cannot** (§13.1), so it is now preserved verbatim on
 * `MovementTrace.sourceActivityType` and also drives [MovementMode.TRANSIT]. It doubles as the
 * labelled tuning corpus §9 flags as missing.
 *
 * ⚠ **Deliberately still not imported.** `transitPath` (line name "U8", ordered stop list) is
 * Google *content*, not a classification — the §5 ToS line above blocks it even though it is exactly
 * what a transit feature would want (§12.2, D-F3). `childVisits` (sub-visits inside a mall or
 * airport) are skipped rather than flattened: they would land as look-alike duplicates the
 * reconciler cannot distinguish from their parent, and modelling them needs a `parentMomentId` that
 * does not exist yet. `parkingEvent`, `otherCandidateActivities` and the visit-confidence fields
 * have no home either.
 *
 * Idempotency (the whole game for re-import, §2): ids are content-addressed off the export's own
 * invariants — `placeId`/coordinates for a place, `(placeRef, startMs)` for a visit, `startMs` for
 * an activity — so re-importing the same file folds to the same rows instead of duplicating.
 *
 * Imported files are **untrusted input** (§6): malformed / unknown JSON yields an empty result,
 * never throws.
 */
object GoogleTimelineParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Sniff + parse one export file. Unknown/garbage → empty (never throws). */
    fun parse(file: TimelineImportFile): ImportedTimeline = parse(file.bytes.decodeToString())

    /**
     * Parse many exports, merged and de-duplicated by content-addressed id.
     *
     * ⚠ This used to merge **only** `places` and `moments`, silently dropping `traces`, `workouts`
     * and `trips` whenever more than one file was imported — harmless while the Google path emitted
     * none of them, and a data-loss bug the moment it does (M7 emits traces). Every list the
     * [ImportedTimeline] carries is merged here now, so adding a producer cannot silently lose rows.
     */
    fun parseAll(files: List<TimelineImportFile>): ImportedTimeline =
        files.map(::parse).fold(ImportedTimeline()) { acc, t ->
            ImportedTimeline(
                places = (acc.places + t.places).distinctBy { it.placeId },
                workouts = (acc.workouts + t.workouts).distinctBy { it.workoutId },
                moments = (acc.moments + t.moments).distinctBy { it.momentId },
                trips = (acc.trips + t.trips).distinctBy { it.tripId },
                traces = (acc.traces + t.traces).distinctBy { it.traceId },
            )
        }

    /** Parse one export's JSON text. */
    fun parse(text: String): ImportedTimeline = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        when {
            "semanticSegments" in root -> parseNew(json.decodeFromString<NewExport>(text))
            "timelineObjects" in root -> parseLegacy(json.decodeFromString<LegacyExport>(text))
            else -> ImportedTimeline()
        }
    }.getOrDefault(ImportedTimeline())

    // ── new on-device export (2024+) ─────────────────────────────────────────

    private fun parseNew(export: NewExport): ImportedTimeline {
        val places = LinkedHashMap<String, ImportedPlace>()
        val moments = ArrayList<ImportedMoment>()
        val traces = ArrayList<ImportedMovementTrace>()

        for (seg in export.semanticSegments) {
            val startMs = seg.startTime?.let(::parseInstantMs) ?: continue
            val endMs = seg.endTime?.let(::parseInstantMs) ?: startMs
            val durationSec = ((endMs - startMs) / 1000).coerceAtLeast(0)

            val visit = seg.visit?.topCandidate
            if (visit != null) {
                val coord = visit.placeLocation?.latLng?.let(::parseLatLng)
                val placeId = placeIdOf(visit.placeId, coord) ?: continue
                places.getOrPut(placeId) {
                    ImportedPlace(
                        placeId = placeId,
                        latE7 = coord?.first ?: 0,
                        lngE7 = coord?.second ?: 0,
                        capturedAtMs = startMs,
                    )
                }
                moments += ImportedMoment(
                    momentId = "gv:$placeId:$startMs",
                    timestampMs = startMs,
                    kind = Moment.MomentKind.VISIT,
                    title = "Visit",
                    subjectKind = SubjectKind.PLACE,
                    subjectId = placeId,
                    durationSec = durationSec,
                    sources = listOf(MomentSourceTag.GOOGLE),
                )
                continue
            }

            val activity = seg.activity
            val rawType = activity?.topCandidate?.type
            if (rawType != null) {
                // Prefer the sampled path; fall back to the activity's own endpoints so at least the
                // direction of travel survives.
                val path = pathFromTimelinePath(seg.timelinePath, startMs).ifEmpty {
                    endpointPath(
                        activity.start?.latLng?.let(::parseLatLng),
                        activity.end?.latLng?.let(::parseLatLng),
                        startMs,
                        endMs,
                    )
                }
                val (moment, trace) = movementRows(
                    startMs = startMs,
                    endMs = endMs,
                    durationSec = durationSec,
                    rawType = rawType,
                    path = path,
                    distanceM = activity.distanceMeters?.roundToLong(),
                    confidence = confidenceOf(null, activity.topCandidate?.probability),
                )
                moments += moment
                traces += trace
            }
        }
        return ImportedTimeline(places = places.values.toList(), moments = moments, traces = traces)
    }

    // ── legacy Semantic Location History (already unzipped) ──────────────────

    private fun parseLegacy(export: LegacyExport): ImportedTimeline {
        val places = LinkedHashMap<String, ImportedPlace>()
        val moments = ArrayList<ImportedMoment>()
        val traces = ArrayList<ImportedMovementTrace>()

        for (obj in export.timelineObjects) {
            val pv = obj.placeVisit
            if (pv != null) {
                val loc = pv.location
                val duration = pv.duration ?: continue
                val startMs = duration.startMs() ?: continue
                val endMs = duration.endMs() ?: startMs
                val coord = loc?.latitudeE7?.let { lat -> loc.longitudeE7?.let { lng -> lat to lng } }
                val placeId = placeIdOf(loc?.placeId, coord) ?: continue
                places.getOrPut(placeId) {
                    ImportedPlace(
                        placeId = placeId,
                        latE7 = coord?.first ?: 0,
                        lngE7 = coord?.second ?: 0,
                        capturedAtMs = startMs,
                        label = loc?.name.orEmpty(),
                    )
                }
                moments += ImportedMoment(
                    momentId = "gv:$placeId:$startMs",
                    timestampMs = startMs,
                    kind = Moment.MomentKind.VISIT,
                    title = loc?.name?.ifEmpty { null } ?: "Visit",
                    subjectKind = SubjectKind.PLACE,
                    subjectId = placeId,
                    durationSec = ((endMs - startMs) / 1000).coerceAtLeast(0),
                    sources = listOf(MomentSourceTag.GOOGLE),
                )
                continue
            }

            val seg = obj.activitySegment
            if (seg?.activityType != null) {
                val duration = seg.duration ?: continue
                val startMs = duration.startMs() ?: continue
                val endMs = duration.endMs() ?: startMs

                // Path preference: timestamped samples > untimed waypoints > bare endpoints.
                val path = pathFromRawPoints(seg.simplifiedRawPath?.points.orEmpty())
                    .ifEmpty { pathFromWaypoints(seg.waypointPath?.waypoints.orEmpty(), startMs, endMs) }
                    .ifEmpty {
                        endpointPath(
                            seg.startLocation?.coordOrNull(),
                            seg.endLocation?.coordOrNull(),
                            startMs,
                            endMs,
                        )
                    }

                val (moment, trace) = movementRows(
                    startMs = startMs,
                    endMs = endMs,
                    durationSec = ((endMs - startMs) / 1000).coerceAtLeast(0),
                    rawType = seg.activityType,
                    path = path,
                    distanceM = seg.distance,
                    confidence = confidenceOf(seg.confidence, null),
                )
                moments += moment
                traces += trace
            }
        }
        return ImportedTimeline(places = places.values.toList(), moments = moments, traces = traces)
    }

    // ── shared mapping helpers ───────────────────────────────────────────────

    /**
     * A movement segment → the **same moment + trace pair live capture writes**
     * (`MovementMapper.capturedMovesToTimeline`), so imported and captured movement are one shape all
     * the way down and the projector, reconciler and (later) route UI need no import-specific branch.
     *
     * Ids are namespaced `g-move:` / `g-trace:` against capture's `cap-move:` / `cap-trace:` so the
     * same journey seen by both sources stays **two rows until `MomentReconciler` folds them** — which
     * is a decision it can only make correctly now that `sources` is populated (M6 §12.3). Sharing an
     * id namespace would instead silently overwrite one with the other.
     *
     * [path] may be empty: an activity with no geometry still becomes a movement moment (the mode,
     * span and distance are worth keeping), just with an empty polyline — the same degenerate case
     * `MovementTrace.polyline` already documents.
     */
    private fun movementRows(
        startMs: Long,
        endMs: Long,
        durationSec: Long,
        rawType: String,
        path: List<CapturedFix>,
        distanceM: Long?,
        confidence: Int,
    ): Pair<ImportedMoment, ImportedMovementTrace> {
        val traceId = "g-trace:$startMs"
        val simplified = MovementTracePolyline.simplify(path, MovementDetectionConfig())
        // Prefer Google's own distance; fall back to summing the path we were given.
        val distance = distanceM ?: path.pathDistanceMeters().roundToLong()

        val moment = ImportedMoment(
            momentId = "g-move:$startMs",
            timestampMs = startMs,
            kind = Moment.MomentKind.MOVEMENT,
            title = movementTitle(rawType, distance),
            subjectKind = SubjectKind.TRACE,
            subjectId = traceId,
            durationSec = durationSec,
            sources = listOf(MomentSourceTag.GOOGLE),
        )
        val trace = ImportedMovementTrace(
            traceId = traceId,
            mode = movementMode(rawType),
            // Google labelled this, not us — so the verdict is not attributable to any version of our
            // classifier, and a future re-classification pass must be able to tell that apart.
            classifierVersion = MOVEMENT_CLASSIFIER_EXTERNAL.toLong(),
            modeConfidence = confidence.toLong(),
            sourceActivityType = rawType,
            medianSpeedMmPerS = 0, // not derived — Google gives a label, not a speed distribution
            distanceM = distance,
            startMs = startMs,
            endMs = endMs,
            pointCount = simplified.size.toLong(),
            rawPointCount = path.size.toLong(),
            minLatE7 = path.minOfOrNull { it.latE7 } ?: 0,
            minLngE7 = path.minOfOrNull { it.lngE7 } ?: 0,
            maxLatE7 = path.maxOfOrNull { it.latE7 } ?: 0,
            maxLngE7 = path.maxOfOrNull { it.lngE7 } ?: 0,
            startLatE7 = path.firstOrNull()?.latE7 ?: 0,
            startLngE7 = path.firstOrNull()?.lngE7 ?: 0,
            endLatE7 = path.lastOrNull()?.latE7 ?: 0,
            endLngE7 = path.lastOrNull()?.lngE7 ?: 0,
            polyline = MovementTracePolyline.encode(simplified),
            steps = -1, // Takeout carries no step count — unknown, not zero
        )
        return moment to trace
    }

    /** `"Train · 34.2 km"`, or just the label when the export carried no usable distance. */
    private fun movementTitle(rawType: String, distanceM: Long): String {
        val label = humanActivity(rawType)
        return if (distanceM > 0) "$label · ${distanceLabel(distanceM)}" else label
    }

    private fun distanceLabel(distanceM: Long): String =
        if (distanceM >= 1000) {
            val tenthsKm = (distanceM + 50) / 100 // round to 0.1 km
            "${tenthsKm / 10}.${tenthsKm % 10} km"
        } else {
            "$distanceM m"
        }

    /**
     * Google's motion type → our [MovementMode] bucket.
     *
     * ⚠ Lossy **by design** — `mode` is the coarse bucket our own classifier could also produce, so
     * bus / tram / subway / rail all fold to [MovementMode.TRANSIT]. The distinction is not thrown
     * away: the raw type rides `MovementTrace.sourceActivityType` verbatim (§12.2).
     */
    private fun movementMode(raw: String): String {
        val t = raw.lowercase().replace('_', ' ')
        return when {
            "run" in t -> MovementMode.RUN
            "walk" in t || "hik" in t || "on foot" in t -> MovementMode.WALK
            // ⚠ Must precede the cycling branch: "motorcycling" *contains* "cycl".
            "motorcycl" in t -> MovementMode.DRIVE
            "cycl" in t || "bicycl" in t || "biking" in t -> MovementMode.CYCLE
            "bus" in t || "train" in t || "rail" in t || "subway" in t ||
                "tram" in t || "ferry" in t || "boat" in t -> MovementMode.TRANSIT
            "vehicle" in t || "driv" in t || "car" in t -> MovementMode.DRIVE
            "fly" in t || "flight" in t || "air" in t -> MovementMode.FLIGHT
            else -> MovementMode.UNKNOWN
        }
    }

    /** Google's `HIGH`/`MEDIUM`/`LOW` (legacy) or 0..1 `probability` (new) → our 0–100 scale. */
    private fun confidenceOf(legacy: String?, probability: Double?): Int = when {
        probability != null -> (probability * 100).roundToLong().toInt().coerceIn(0, 100)
        legacy == null -> 0
        else -> when (legacy.uppercase()) {
            "HIGH" -> 90
            "MEDIUM" -> 60
            "LOW" -> 30
            else -> 0
        }
    }

    /**
     * A stable import id for a place: prefer Google's `placeId` foreign key, else fall back to its
     * E7 coordinates. Null when neither is present (the visit is then skipped).
     */
    private fun placeIdOf(googlePlaceId: String?, coord: Pair<Long, Long>?): String? = when {
        !googlePlaceId.isNullOrEmpty() -> "g:$googlePlaceId"
        coord != null -> "g:${coord.first},${coord.second}"
        else -> null
    }

    /** Google motion types (new `"walking"`, legacy `"IN_PASSENGER_VEHICLE"`) → a readable title. */
    private fun humanActivity(raw: String): String {
        val t = raw.lowercase().replace('_', ' ')
        return when {
            "run" in t -> "Running"
            "walk" in t || "hik" in t -> "Walking"
            // ⚠ Must precede the cycling branch: "motorcycling" *contains* "cycl", so
            // `MOTORCYCLING` used to render as "Cycling".
            "motorcycl" in t -> "Motorcycling"
            "cycl" in t || "bicycl" in t || "biking" in t -> "Cycling"
            "vehicle" in t || "driv" in t || "car" in t -> "Driving"
            "fly" in t || "flight" in t -> "Flying"
            "bus" in t -> "Bus"
            "tram" in t -> "Tram"
            "train" in t || "rail" in t || "subway" in t -> "Train"
            "boat" in t || "ferry" in t -> "Boat"
            else -> t.split(' ').filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
                .ifEmpty { "Moving" }
        }
    }

    // ── path extraction ──────────────────────────────────────────────────────

    /**
     * New-format `timelinePath` → [CapturedFix]es. Each point is timestamped either absolutely
     * (`time`) or as a minute offset from the segment start; a point we cannot place in time is
     * dropped rather than guessed, since a wrong timestamp corrupts every derived speed.
     */
    private fun pathFromTimelinePath(points: List<NewPathPoint>, startMs: Long): List<CapturedFix> =
        points.mapNotNull { p ->
            val coord = p.point?.let(::parseLatLng) ?: return@mapNotNull null
            val ts = p.time?.let(::parseInstantMs)
                ?: p.durationMinutesOffsetFromStartTime?.toDoubleOrNull()
                    ?.let { startMs + (it * 60_000).roundToLong() }
                ?: return@mapNotNull null
            CapturedFix(latE7 = coord.first, lngE7 = coord.second, timestampMs = ts)
        }.sortedBy { it.timestampMs }

    /** Legacy `simplifiedRawPath` → [CapturedFix]es. The one legacy path with real timestamps. */
    private fun pathFromRawPoints(points: List<LegacyRawPoint>): List<CapturedFix> =
        points.mapNotNull { p ->
            val lat = p.latE7 ?: return@mapNotNull null
            val lng = p.lngE7 ?: return@mapNotNull null
            val ts = (p.timestampMs ?: p.timestamp)?.let(::parseInstantMs) ?: return@mapNotNull null
            CapturedFix(latE7 = lat, lngE7 = lng, timestampMs = ts, accuracyM = p.accuracyMeters ?: 0)
        }.sortedBy { it.timestampMs }

    /**
     * Legacy `waypointPath` → [CapturedFix]es, **with synthesised timestamps**.
     *
     * ⚠ Waypoints carry no time at all, but the codec needs one per point, so they are spread evenly
     * across `[startMs, endMs]`. That is a *shape* claim, not a speed claim: the resulting per-leg
     * speeds are an artefact of the interpolation, not measurements. Harmless because an imported
     * trace's `mode` comes from Google's label rather than from our speed classifier
     * (`classifierVersion = MOVEMENT_CLASSIFIER_EXTERNAL`) and `medianSpeedMmPerS` is left 0 — but it
     * is exactly why a future re-classification pass must not treat every trace as speed-derivable.
     */
    private fun pathFromWaypoints(
        waypoints: List<LegacyWaypoint>,
        startMs: Long,
        endMs: Long,
    ): List<CapturedFix> {
        val coords = waypoints.mapNotNull { w ->
            val lat = w.latE7 ?: return@mapNotNull null
            val lng = w.lngE7 ?: return@mapNotNull null
            lat to lng
        }
        if (coords.isEmpty()) return emptyList()
        val span = (endMs - startMs).coerceAtLeast(0)
        val lastIndex = (coords.size - 1).coerceAtLeast(1)
        return coords.mapIndexed { i, (lat, lng) ->
            CapturedFix(latE7 = lat, lngE7 = lng, timestampMs = startMs + span * i / lastIndex)
        }
    }

    /** A single E7 coordinate from a legacy `startLocation`/`endLocation`, if it has one. */
    private fun LegacyLocation.coordOrNull(): Pair<Long, Long>? {
        val lat = latitudeE7 ?: return null
        val lng = longitudeE7 ?: return null
        return lat to lng
    }

    /**
     * Fall back to a two-point path from the segment's endpoints when no sampled path survived.
     * Not a route — but it preserves **direction of travel**, which the bbox alone cannot express
     * (§12.1) and which is the difference between a renderable arrow and nothing.
     */
    private fun endpointPath(
        start: Pair<Long, Long>?,
        end: Pair<Long, Long>?,
        startMs: Long,
        endMs: Long,
    ): List<CapturedFix> = when {
        start != null && end != null -> listOf(
            CapturedFix(start.first, start.second, startMs),
            CapturedFix(end.first, end.second, endMs),
        )
        else -> emptyList()
    }

    /** Epoch-ms as string (older legacy) or an ISO-8601 instant (new + newer legacy). */
    private fun parseInstantMs(s: String): Long? =
        s.toLongOrNull() ?: runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull()

    /**
     * Parse a new-format `latLng` degree string — `"52.5200000°, 13.4050000°"` — into E7 integers.
     * Tolerant of the degree symbol, extra whitespace, and a bare `"lat, lng"`.
     */
    private fun parseLatLng(raw: String): Pair<Long, Long>? {
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val lat = parts[0].filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: return null
        val lng = parts[1].filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: return null
        return (lat * 1e7).roundToLong() to (lng * 1e7).roundToLong()
    }

    // ── wire DTOs (a subset; unknown keys ignored) ───────────────────────────

    @Serializable
    private data class NewExport(val semanticSegments: List<NewSegment> = emptyList())

    @Serializable
    private data class NewSegment(
        val startTime: String? = null,
        val endTime: String? = null,
        val visit: NewVisit? = null,
        val activity: NewActivity? = null,
        /**
         * The segment's sampled path — the movement geometry modern exports actually carry. Present
         * alongside an `activity`, and sometimes on its own (a path with no classified activity).
         */
        val timelinePath: List<NewPathPoint> = emptyList(),
    )

    @Serializable
    private data class NewVisit(val topCandidate: NewVisitCandidate? = null)

    @Serializable
    private data class NewVisitCandidate(
        val placeId: String? = null,
        val placeLocation: NewLatLng? = null,
    )

    @Serializable
    private data class NewLatLng(val latLng: String? = null)

    @Serializable
    private data class NewActivity(
        val topCandidate: NewActivityCandidate? = null,
        val start: NewLatLng? = null,
        val end: NewLatLng? = null,
        val distanceMeters: Double? = null,
    )

    @Serializable
    private data class NewActivityCandidate(
        val type: String? = null,
        /** Google's own 0..1 confidence in [type] → our 0–100 `modeConfidence`. */
        val probability: Double? = null,
    )

    /**
     * One sampled point. Newer exports timestamp each point with an ISO-8601 `time`; older ones give
     * `durationMinutesOffsetFromStartTime` relative to the segment's `startTime`. Both are handled;
     * neither carries accuracy.
     */
    @Serializable
    private data class NewPathPoint(
        val point: String? = null,
        val time: String? = null,
        val durationMinutesOffsetFromStartTime: String? = null,
    )

    @Serializable
    private data class LegacyExport(val timelineObjects: List<LegacyObject> = emptyList())

    @Serializable
    private data class LegacyObject(
        val placeVisit: LegacyPlaceVisit? = null,
        val activitySegment: LegacyActivitySegment? = null,
    )

    @Serializable
    private data class LegacyPlaceVisit(
        val location: LegacyLocation? = null,
        val duration: LegacyDuration? = null,
    )

    @Serializable
    private data class LegacyLocation(
        val latitudeE7: Long? = null,
        val longitudeE7: Long? = null,
        val placeId: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class LegacyActivitySegment(
        val duration: LegacyDuration? = null,
        val activityType: String? = null,
        val startLocation: LegacyLocation? = null,
        val endLocation: LegacyLocation? = null,
        /** Google's own path length in metres — preferred over re-summing a decimated path. */
        val distance: Long? = null,
        /** `HIGH` / `MEDIUM` / `LOW` → our 0–100 `modeConfidence`. */
        val confidence: String? = null,
        /**
         * The denser of the two legacy paths: E7 points **with** timestamps and accuracy, so it maps
         * onto [CapturedFix] exactly. Preferred over [waypointPath] when present.
         */
        val simplifiedRawPath: LegacySimplifiedPath? = null,
        /**
         * The sparser fallback — route waypoints with **no timestamps at all**, so the mapper has to
         * synthesise them across the segment span (see `pathFromWaypoints`).
         */
        val waypointPath: LegacyWaypointPath? = null,
    )

    @Serializable
    private data class LegacySimplifiedPath(val points: List<LegacyRawPoint> = emptyList())

    @Serializable
    private data class LegacyRawPoint(
        val latE7: Long? = null,
        val lngE7: Long? = null,
        val timestampMs: String? = null,
        val timestamp: String? = null,
        val accuracyMeters: Int? = null,
    )

    @Serializable
    private data class LegacyWaypointPath(val waypoints: List<LegacyWaypoint> = emptyList())

    /** ⚠ Note the short field names — waypoints use `latE7`, not `latitudeE7` like `location` does. */
    @Serializable
    private data class LegacyWaypoint(
        val latE7: Long? = null,
        val lngE7: Long? = null,
    )

    @Serializable
    private data class LegacyDuration(
        val startTimestamp: String? = null,
        val endTimestamp: String? = null,
        val startTimestampMs: String? = null,
        val endTimestampMs: String? = null,
    )

    /** Prefer the epoch-ms string (older legacy), else the ISO-8601 timestamp. */
    private fun LegacyDuration.startMs(): Long? = (startTimestampMs ?: startTimestamp)?.let(::parseInstantMs)
    private fun LegacyDuration.endMs(): Long? = (endTimestampMs ?: endTimestamp)?.let(::parseInstantMs)
}
