package app.tenshu.timeline.data

import kotlinx.serialization.Serializable
import org.tenshu.core.timeline.SubjectKind

/**
 * The neutral, platform-free result of a Timeline ingestion read — the acquisition side of the
 * ingestion seam (`docs/proposals/timeline-ingestion.md` §2). A source (the in-process
 * [ReferenceTimelineSource], a Google `Timeline.json` parser (I2), or a live
 * `LocationCaptureSource` (I3)) maps its origin model into these plain DTOs in `commonMain`;
 * [TimelineImportWriter] then maps them onto the `:place-service` / `:health-service` /
 * `:timeline-service` schemas and persists via the record ops. Split this way, *acquisition* is
 * the only source-specific step — the mapping and the write path stay shared and testable.
 *
 * Mirrors `fit-app.md`'s `ImportedHealth` recipe. Instants are epoch-millis and coordinates are
 * E7 integers (degrees × 1e7), matching the service create factories. Empty lists are fine.
 *
 * ⚠ [workouts] here is a **fixture affordance**: an `Activity` moment's subject is a `Workout`
 * that in production is ingested via `:health-service` (Fit's `HealthImportWriter`) and merely
 * *referenced* by a captured moment. The reference source carries a workout so the in-process
 * fixture is self-contained; a real location capture/import leaves the [workouts] list empty and
 * points its Activity moments at already-present workout ids.
 *
 * `@Serializable` is for the **local, app-private visit outbox only** (`CapturedVisitOutbox` —
 * durable buffering of captured visits until the proxy accepts them). These DTOs never cross the
 * wire, so House Rule #4 (schema-first) does not apply; the schema mapping stays
 * [TimelineImportWriter]'s job.
 */
@Serializable
data class ImportedTimeline(
    val places: List<ImportedPlace> = emptyList(),
    val workouts: List<ImportedWorkout> = emptyList(),
    val moments: List<ImportedMoment> = emptyList(),
    val trips: List<ImportedTrip> = emptyList(),
    val traces: List<ImportedMovementTrace> = emptyList(),
) {
    val isEmpty: Boolean
        get() = places.isEmpty() && workouts.isEmpty() && moments.isEmpty() &&
            trips.isEmpty() && traces.isEmpty()

    val count: Int
        get() = places.size + workouts.size + moments.size + trips.size + traces.size
}

/** A place-of-interest (`:place-service`). Coordinates are E7 integers. */
@Serializable
data class ImportedPlace(
    val placeId: String,
    val latE7: Long,
    val lngE7: Long,
    val capturedAtMs: Long,
    val label: String = "",
    /** Representative capture accuracy in metres (0 = unknown / imported) — feeds 4-B naming. */
    val accuracyM: Int = 0,
)

/** A workout — an `Activity` moment's subject (`:health-service`). See [ImportedTimeline] KDoc. */
@Serializable
data class ImportedWorkout(
    val workoutId: String,
    val activityType: String,
    val source: String,
    val startMs: Long,
    val endMs: Long,
    val durationSec: Long,
)

/**
 * A timeline entry (`:timeline-service`). [kind] is a `Moment.MomentKind` string
 * (`Activity` / `Visit` / `Event` / `Quiet`); [subjectKind] is a [SubjectKind] string
 * (`place` / `workout` / `none`) and [subjectId] the owning service's id (blank for a subjectless
 * `Event`/`Quiet`).
 */
@Serializable
data class ImportedMoment(
    val momentId: String,
    val timestampMs: Long,
    val kind: String,
    val title: String,
    val subjectKind: String = SubjectKind.NONE,
    val subjectId: String = "",
    val durationSec: Long = 0,
    /**
     * Provenance tags (`capture` / `google` / `import`) → `Moment.sources` (I6, §12.3).
     *
     * ⚠ This existed nowhere before M6, so **every moment from every source was written with an empty
     * `sources` list** — which left `MomentReconciler.electCanonical`'s source-priority tie-break with
     * no signal at all, silently falling back to earliest-start. Harmless while only one source was in
     * play; load-bearing the moment Google activity import (M7) makes captured and imported moments
     * coexist.
     */
    val sources: List<String> = emptyList(),
)

/** A curated trip (`:timeline-service`). */
@Serializable
data class ImportedTrip(
    val tripId: String,
    val title: String,
    val dateRange: String = "",
    val year: String = "",
)

/**
 * A captured movement path — the subject of a `MOVEMENT` moment (`:timeline-service`,
 * `docs/proposals/timeline-movement-capture.md` §2.1). The companion `MOVEMENT` [ImportedMoment]
 * (kind `Movement`, subjectKind `trace`, subjectId = [traceId]) rides the ordinary [moments] list;
 * this carries the trace record's summary + the decimated, encoded [polyline] blob. E7 coordinates.
 */
@Serializable
data class ImportedMovementTrace(
    val traceId: String,
    val mode: String,
    val distanceM: Long,
    val startMs: Long,
    val endMs: Long,
    val pointCount: Long,
    val minLatE7: Long,
    val minLngE7: Long,
    val maxLatE7: Long,
    val maxLngE7: Long,
    val polyline: ByteArray,
    /** Which classifier produced [mode] — see `MovementTrace.classifierVersion` (§12.1). */
    val classifierVersion: Long = 0,
    /** 0–100 confidence in [mode]. */
    val modeConfidence: Long = 0,
    /** The source's own travel-type label verbatim (`IN_SUBWAY`, …); empty for live capture. */
    val sourceActivityType: String = "",
    /** Median per-leg speed in millimetres/second. */
    val medianSpeedMmPerS: Long = 0,
    /** Fix count *before* decimation ([pointCount] is after). */
    val rawPointCount: Long = 0,
    /** First/last point of the path — direction of travel, which the bbox cannot express (§12.1). */
    val startLatE7: Long = 0,
    val startLngE7: Long = 0,
    val endLatE7: Long = 0,
    val endLngE7: Long = 0,
    /**
     * Total pedometer steps over the move, or `-1` when not counted — see `MovementTrace.steps`.
     * ⚠ Defaults to `-1`, never `0`: an absent count must not read as a counted zero.
     */
    val steps: Long = -1,
) {
    // Content addressed by traceId; equality is by that key, so the ByteArray's reference identity
    // (which the data-class default would use) doesn't matter for dedupe / set membership.
    override fun equals(other: Any?): Boolean = other is ImportedMovementTrace && other.traceId == traceId
    override fun hashCode(): Int = traceId.hashCode()
}
