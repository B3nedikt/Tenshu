package org.tenshu.health.common

import kotlinx.serialization.Serializable

/**
 * The dense per-sample streams of a workout — the GPS **route** + the **heart-rate** series.
 * This is the high-fidelity payload a first-party recorder (GPS / BLE HR strap) or a
 * high-fidelity import (a FIT file) produces, and a bare summary import (plain GPX / Health
 * Connect session header) does not. It is kept **off** the [org.tenshu.core.health.Workout]
 * metadata composite (which carries only the scalar session summary) and stored as an opaque,
 * self-describing `TByteArray` blob on the eager metadata plane via `Workout.Streams` — the
 * `File.Thumbnail` pattern: it rides the entity's sealed (AEAD) block to every paired device,
 * is excluded from list selects (byte-less selection), and is decoded only when a detail
 * surface asks.
 *
 * ⚠ This is deliberately **not** the file *content plane* (ADR 0017). That channel is for large,
 * opaque file bytes reassembled into a `FileDatabase` at a path; a workout's streams are small
 * (~tens of KB), structured, and written once, so a stored property is the honest fit — not a
 * chunked, lazily-fetched content blob. See `docs/proposals/fit-app.md` §9.7 for the decision.
 *
 * Storage is **SI truth**: real GPS as fixed-point E7 degrees (the `:place-service` idiom,
 * `timeline-services.md` §3 — the schema DSL has no float kind and E7 ints are the standard
 * GPS-storage form: ~1 cm, exact on the wire, box-queryable), altitude in centimetres, time in
 * whole seconds from the workout start. The abstract 0..1 polyline the UI renders is a
 * *presentation* transform the projector derives (a bounding-box fit) — never stored.
 *
 * A route reveals home/work/patterns and heart rate is GDPR Article 9 special-category data
 * (threat-model **A34**); this blob is sealed like every other entity field and never
 * relay-visible.
 */
data class WorkoutStreams(
    val route: List<RouteSample> = emptyList(),
    val heartRate: List<HrSample> = emptyList(),
) {
    val isEmpty: Boolean get() = route.isEmpty() && heartRate.isEmpty()

    companion object {
        val EMPTY = WorkoutStreams()
    }
}

/**
 * One GPS fix. [latE7] / [lngE7] are degrees × 1e7 (both fit a signed 32-bit int: 90e7 and
 * 180e7 are < 2.1e9). [altCm] is metres × 100, or [NO_ALT] when the fix carried no altitude.
 * [tSec] is whole seconds from the workout start.
 */
// @Serializable is for **local, app-private journals only** (Fit's workout outbox) — the wire
// form stays WorkoutStreamsCodec's versioned binary (House Rule #4 unaffected).
@Serializable
data class RouteSample(
    val tSec: Int,
    val latE7: Int,
    val lngE7: Int,
    val altCm: Int = NO_ALT,
) {
    companion object {
        /** Sentinel for "no altitude on this fix". */
        const val NO_ALT: Int = Int.MIN_VALUE
    }
}

/** One heart-rate sample: [tSec] whole seconds from the workout start, [bpm]. */
@Serializable
data class HrSample(
    val tSec: Int,
    val bpm: Int,
)
