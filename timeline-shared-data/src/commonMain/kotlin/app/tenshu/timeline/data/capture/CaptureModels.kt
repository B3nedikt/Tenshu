package app.tenshu.timeline.data.capture

import kotlinx.serialization.Serializable

/**
 * Neutral capture DTOs for the live-capture ingestion mode (`docs/proposals/timeline-ingestion.md`
 * §4). Platform-free `commonMain` types so the device-independent [VisitDetector] and its tests
 * carry no Android/iOS dependency; the platform capture sources (I3b Android `FusedLocation`, I4
 * iOS `CLVisit`) produce these, and the shared detector + mapping turn them into the same
 * [app.tenshu.timeline.data.ImportedTimeline] the bulk importers write.
 *
 * Coordinates are E7 integers (degrees × 1e7), matching `Place` / the import DTOs; instants are
 * epoch-millis.
 */

/**
 * A single raw position sample from a live sensor (Android `FusedLocation`).
 *
 * `@Serializable` for the **device-local [OpenMoveJournal] only** (the same carve-out
 * `ImportedTimeline` documents for the visit outbox): app-private JSON, never the wire — the
 * schema-first rule (House Rule #4) is untouched, since nothing here is a stored or synced row.
 */
@Serializable
data class CapturedFix(
    val latE7: Long,
    val lngE7: Long,
    val timestampMs: Long,
    /** Horizontal accuracy in metres, if the sensor reported it (0 = unknown). */
    val accuracyM: Int = 0,
    /**
     * Steps the hardware pedometer counted since the previous fix (S3, §17.4/§19), or `null` when
     * this device has no pedometer / the sensor is not bound.
     *
     * ⚠⚠ **`null` and `0` are completely different claims, and conflating them is the whole trap.**
     * `0` means *the pedometer counted and the user did not walk* — the decisive cycle-vs-run signal.
     * `null` means *nobody counted*, which must fall back to the speed-only rule. A phone in a bag on
     * a walk reports `0` legitimately, so `0` is never proof of a vehicle on its own; it is only ever
     * one conjunct with speed.
     *
     * ⚠⚠ **Only the move's TOTAL is persisted** — `MovementTrace.steps`, summed by
     * `MovementMapper.stepsOver` (FA-4; `-1` = not counted). The per-fix readings are not, for two
     * independent walls: (1) [MovementTracePolyline]'s codec is **unversioned** — a fifth delta
     * stream would make every already-stored blob decode as silent garbage rather than fail, since
     * the reader would take the next point's latitude as this point's step count; (2) `MovementTrace`
     * is an **operation parameter**, so after the production reset it cannot gain another field —
     * the rule `PlaceRole`/`SnappedRoute` established (§16.3); `steps` went in at v1 before it. The
     * corpus that keeps the per-fix series is the debug [RawFixJournal] (§18.9). If more ever needs
     * to ride with the trace, the escape hatch is a **sibling row**, not a field.
     */
    val stepsSinceLastFix: Int? = null,
    /**
     * Steps carried by fixes that §22 hygiene **dropped** between the previous kept fix and this one
     * (`FixHygiene`: out of time order, or a coarse interior vertex), `null` if any of them was
     * uncounted, `0` when nothing was dropped.
     *
     * ⚠ Kept apart from [stepsSinceLastFix] on purpose. Only the move's step **total**
     * (`MovementMapper.stepsOver`) reads it, so a dropped fix's pedometer delta still reaches the
     * persisted count. The classifier's per-fix cadence and stationary veto see exactly the readings
     * they were measured on.
     */
    val stepsDroppedBefore: Int? = 0,
) {
    /** Steps per minute over [sinceMs], or `null` when nobody counted. */
    fun cadenceSpm(sinceMs: Long): Double? {
        val steps = stepsSinceLastFix ?: return null
        if (sinceMs <= 0) return null
        return steps * 60_000.0 / sinceMs
    }
}

/**
 * A detected **dwell at a place** — the output of [VisitDetector] over a run of [CapturedFix]es,
 * or (on iOS) an Apple `CLVisit` mapped almost directly (Apple already clusters). The coordinate is
 * the dwell centroid; [arrivalMs]/[departureMs] bound the stay.
 */
data class CapturedVisit(
    val latE7: Long,
    val lngE7: Long,
    val arrivalMs: Long,
    val departureMs: Long,
    /**
     * Representative horizontal accuracy of the dwell in metres (the median of the fixes'
     * reported accuracy; `0` = unknown). Feeds 4-B confidence-aware naming
     * (timeline-recognition-and-place-naming.md §4.3): a wide-accuracy dwell (indoors, floored by
     * the NETWORK provider's ±100 m) is named by its area/city rather than a confidently-wrong POI.
     */
    val accuracyM: Int = 0,
) {
    val durationMs: Long get() = (departureMs - arrivalMs).coerceAtLeast(0)
}

/**
 * A detected **movement segment** — the path *between* two dwells (the walk / run / ride / drive),
 * the output of the [MovementSegmenter] over the fixes [VisitDetector] discards
 * (`docs/proposals/timeline-movement-capture.md` §2–§3). [fixes] is the ordered raw path (still to
 * be decimated + encoded for storage, §4); [distanceM], [mode], and the span are computed over that
 * raw path so they stay accurate regardless of later decimation.
 */
data class CapturedMove(
    /** The ordered raw path, arrival-of-departure to arrival-of-next-dwell. Time-ordered. */
    val fixes: List<CapturedFix>,
    val startMs: Long,
    val endMs: Long,
    /** Summed great-circle distance over [fixes], in metres. */
    val distanceM: Double,
    /** Heuristic travel mode + the inputs that produced it (§12.1 — store the inputs, not only the verdict). */
    val classification: ModeClassification,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** Convenience: the classified mode string, one of [MovementMode]. */
    val mode: String get() = classification.mode
}

/**
 * The output of [classifyMode] — the verdict **plus the evidence behind it**
 * (`docs/proposals/timeline-movement-capture.md` §12.1 / D-F2).
 *
 * The bucket alone is not enough. `MovementSegmenter`'s thresholds are self-described "conservative
 * first guesses" awaiting a tuning corpus, so a trace must record *which* classifier produced its
 * label ([version]) and *how close to a boundary* it sat ([confidence], [medianSpeedMmPerS]).
 * Without that, retuning can never be applied retroactively and pre-/post-tuning traces are
 * silently incomparable in one feed — and the §13 transit tiers could not re-label old rows.
 */
data class ModeClassification(
    /** One of [MovementMode]. */
    val mode: String,
    /** 0–100. How far the median sat from the nearest bucket boundary, discounted by sample quality. */
    val confidence: Int,
    /** The median per-leg speed in **millimetres/second** (integer — the schema has no float kind). */
    val medianSpeedMmPerS: Long,
    /** Which classifier produced this. Bump [MOVEMENT_CLASSIFIER_VERSION] whenever the rule changes. */
    val version: Int = MOVEMENT_CLASSIFIER_VERSION,
)

// ⚠ MOVEMENT_CLASSIFIER_VERSION and MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND live in `:timeline-projection` (ClassifierVersions.kt,
// same package): the feed projection reads them and is compiled for JS (timeline-app.md §Web scope 4a).

/**
 * Sentinel [ModeClassification.version] for a trace whose mode did **not** come from our classifier —
 * an import that carried its own labelled travel type (M7: Google's `activityType`).
 *
 * Kept distinct from every real version so a future re-classification pass can tell "this label is
 * ours and stale" from "this label came from the source and is probably better than anything we
 * could derive". `MovementTrace.sourceActivityType` records what that external label actually was.
 */
const val MOVEMENT_CLASSIFIER_EXTERNAL: Int = 0

/**
 * The two outputs of live capture from a single pass over the fix stream (§1, §3): a dwell at a
 * place, or the movement between dwells. The [MovementSegmenter] emits these; the write loop
 * dispatches each to the visit or the move writer (M2). The visit path is unchanged from I3.
 */
sealed interface CaptureEvent {
    /**
     * A dwell at a place. [ongoing] = `true` on the **confirmation** emit — the moment the stay first
     * crosses `minDwellMs` while the user is *still there* (drives the device-local
     * [OngoingVisitState] so the UI can tick the live duration); `false` on the **final** emit when the
     * user leaves / capture stops. Both carry the same content-addressed visit and fold to one place.
     */
    data class Dwell(val visit: CapturedVisit, val ongoing: Boolean = false) : CaptureEvent

    /** A movement leg that has **closed** — the only movement event that becomes a stored row. */
    data class Move(val move: CapturedMove) : CaptureEvent

    /**
     * The still-open leg, as it stands right now — the "you are on a ride" signal that drives
     * [OngoingMoveState] (§12.6's deferred half).
     *
     * ⚠⚠ A **distinct type**, not a `Move(ongoing = true)` flavour, and that is load-bearing. The
     * dwell path can carry its ongoing-ness on a boolean because both dwell emits are genuinely the
     * same content-addressed row; a movement's ongoing signal is the opposite — it must *never*
     * reach a writer (§9 Option A keeps movement moments immutable, written once at close). Making
     * it a separate type means no dispatcher can write it by forgetting to check a flag.
     */
    data class MoveProgress(val open: OngoingMove) : CaptureEvent
}

/**
 * Heuristic travel modes (§5); stored as `MovementTrace.mode`. Plain strings, like `MomentKind`, so
 * **adding a value is not a positional-composite change** — but an old peer must still degrade
 * gracefully on a mode it doesn't know.
 */
object MovementMode {
    const val WALK = "walk"
    const val RUN = "run"
    const val CYCLE = "cycle"
    const val DRIVE = "drive"

    /**
     * Ground public transport — train, tram, subway, bus, ferry (§13.5).
     *
     * Produced on-device by the **T0 kinematic tier** (`TransitKinematics`, §13.3) — turn density
     * plus station-like stop spacing, or a rail-only cruise speed — and from **import**, where
     * Google's own `activityType` already carries the distinction (which is why M7 retains that raw
     * value rather than discarding it into a display label).
     *
     * ⚠ T0 structurally cannot catch a **bus**: it follows roads (high turn density) and stops every
     * few hundred metres. That needs §13.3's T2 stop-POI tier.
     */
    const val TRANSIT = "transit"

    /**
     * Airborne travel.
     *
     * Unlike [TRANSIT], this one is **cheap to be sure about**: a cruising airliner sustains roughly
     * 230–250 m/s and nothing on the ground comes close, so the speed band alone is conclusive where
     * ground modes need shape analysis to separate them. `TransitKinematics` already measured cruise
     * speed and was *discarding* anything above [IMPLAUSIBLE_GROUND_SPEED_MPS] as unclassifiable.
     *
     * ⚠ In practice most flights leave **no trace at all** — airplane mode, or a phone in a pocket
     * away from a window — so this fires only for the window-seat case. The general signal is a pair
     * of airport dwells with an impossible gap between them, which is a different acquisition mode
     * (it works on dwells, not movement) and is not built.
     */
    const val FLIGHT = "flight"

    const val UNKNOWN = "unknown"
}
