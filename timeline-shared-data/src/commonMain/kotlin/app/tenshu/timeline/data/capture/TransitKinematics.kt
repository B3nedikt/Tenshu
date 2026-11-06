package app.tenshu.timeline.data.capture

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * **T0 — kinematic transit detection** (`docs/proposals/timeline-movement-capture.md` §13.3).
 *
 * The problem: median speed alone cannot separate a train from a car. Both cruise in the same band,
 * and §13.1 established that the platform activity-recognition APIs do not help — Google's needs
 * Play Services (which this app deliberately does without) and Apple's `CMMotionActivity` reports
 * `automotive` for a train anyway. So the distinction has to come from the *shape* of the movement.
 *
 * Two signals do the work, and neither is sufficient alone:
 *
 *  - **Turn density** (degrees of heading change per kilometre). Rail alignment is built for
 *    high-speed running: curve radii are enormous, so a train's heading changes slowly and
 *    continuously. Road driving turns *discretely* — junctions, roundabouts, slip roads — so an
 *    urban drive accumulates heading change far faster per kilometre. ⚠ This separates rail from
 *    *urban* driving but **not from motorway driving**, which is also gently curving.
 *  - **Stop structure.** A train makes complete stops at stations, kilometres apart, and *runs fast
 *    between them*. Motorway driving makes no stops at all; congested driving stops constantly but
 *    only metres apart; a rural drive stops at junctions ~1 km apart but crawls between them. So
 *    "several full stops, widely spaced, with a fast cruise between" is what closes the road gap —
 *    all three conjuncts are load-bearing, and dropping any one readmits a road shape.
 *
 * Plus two speed shortcuts. **Sustained speed above [RAIL_ONLY_SPEED_MPS]** (~198 km/h) is not
 * achievable by road in practice, so it is near-conclusive for rail on its own; above
 * [IMPLAUSIBLE_GROUND_SPEED_MPS] (~432 km/h) it is not ground travel at all and classifies as
 * [MovementMode.FLIGHT], which needs no shape analysis — nothing on the ground goes that fast.
 *
 * And a third shape rule for the blind spot between them (§13.4): a **fast, near-non-stop
 * intercity express** cruises well below 198 km/h yet makes zero or one full stop, so it fits
 * neither the speed shortcut nor the stop-shape rule. The [STRAIGHT_RAIL_DEG_PER_KM] rule catches
 * it by its one unmistakable trait — near-arrow-straight running over a long distance at rail
 * speed ([FAST_RAIL_CRUISE_MPS], [LONG_RAIL_TRIP_M]), no stop required. It is the most eager rule
 * here, which is safe precisely because the T1 rail map-match *demotes* an off-rail `transit`
 * back to `drive`: be a little eager at T0, let T1 be the judge.
 *
 * ### Design posture — bias hard toward the status quo
 *
 * A false "Train" on someone's daily commute is worse than a missed one: it is a visible, confident
 * lie about their life, whereas a missed train just leaves the existing "Drive". So every rule here
 * is a *promotion* from an already-established [MovementMode.DRIVE]; nothing is ever demoted, and
 * anything ambiguous stays `drive`. [refineWithTransitKinematics] returns its input unchanged unless
 * the evidence is affirmative.
 *
 * ### What this tier cannot do (documented, not worked around)
 *
 *  - **Buses read as `drive`.** A bus follows roads (high turn density) and stops every few hundred
 *    metres. Neither signal fires. §13.3's T2 (station/stop POIs from the shipped Protomaps `pois`
 *    layer) is the tier that would catch it — T0 structurally cannot.
 *  - **A tunnelled or subway segment starves fixes entirely**, so the geometry it needs is missing.
 *    [transitEvidence] weights its verdict by delivered fix quality and quantity, which means a
 *    subway ride will usually *fail* to be promoted rather than be wrongly promoted.
 *  - **A station stop long enough to be a dwell splits the journey** upstream, in the segmenter — so
 *    a long-distance trip can arrive here already cut into legs, each with fewer stops than the
 *    whole ride had. That is a known §13.4 interaction; journey-level grouping belongs to the
 *    existing I5 `TripMerger`, not here.
 *
 * Pure `commonMain`, no dependencies, deterministic — the whole point of T0 being the first tier.
 */

/** Sustained speed above this (m/s ≈ 198 km/h) is effectively rail-only on the ground. */
internal const val RAIL_ONLY_SPEED_MPS = 55.0

/**
 * Above this (m/s ≈ 432 km/h) it is not *ground* transit at all — it is airborne, or a GPS artefact.
 * The transit shortcut is bounded here at the top; above it, [MovementMode.FLIGHT] takes over.
 */
internal const val IMPLAUSIBLE_GROUND_SPEED_MPS = 120.0

/**
 * Above this (m/s ≈ 1440 km/h) nothing civil flies either — it is a GPS artefact, so no claim is made.
 *
 * A ceiling matters because cruise is the 85th-percentile leg speed: on a short path a couple of
 * teleport spikes can drag it arbitrarily high, and "Flight" on a corrupted walk would be a worse
 * error than the `unknown` it replaces.
 */
internal const val IMPLAUSIBLE_FLIGHT_SPEED_MPS = 400.0

/** At or below this heading change per kilometre, the path is rail-like rather than road-like. */
internal const val RAIL_TURN_DENSITY_DEG_PER_KM = 25.0

/**
 * The **floor** for the bearing-leg bar (metres) — used as-is only when fixes are good.
 *
 * ⚠ Not the whole rule since §32.15: see [bearingLegBarFor], which raises it in proportion to the
 * trace's own fix accuracy. A fixed 30 m was the bar while accuracy was assumed to be a handful of
 * metres; on a real train it is not.
 */
internal const val TRANSIT_MIN_BEARING_LEG_M = 30.0

/**
 * Multiple of a trace's **median fix accuracy** that a leg must span before its bearing is trusted
 * (§32.15).
 *
 * ### ⚠⚠ Why turn density needed a scaled bar at all: the estimator is one-sided
 *
 * Turn density sums the **absolute** heading change between consecutive legs. Noise in the endpoint
 * fixes perturbs each bearing independently, and `|Δbearing|` can only ever *grow* from that — there
 * is no cancelling term. So fix noise does not blur turn density symmetrically, it **inflates** it,
 * without bound as the leg length falls toward the fix uncertainty. A bar *below* the noise floor
 * therefore does not merely add scatter; it systematically pushes every trace toward "road-like".
 *
 * Measured on a real 79 km regional-train journey (median reported accuracy **64 m** — twice the old
 * 30 m bar), varying only this bar:
 *
 * | min leg | 30 m | 100 m | 150 m | 200 m | **300 m** | 500 m |
 * |---|---|---|---|---|---|---|
 * | turn density °/km | **54.7** | 36.9 | 31.3 | 31.2 | **22.4** | 17.4 |
 * | share of path kept | 100 % | 99 % | 98 % | 98 % | **95 %** | 88 % |
 *
 * Every other conjunct of the stop-shape rule passed on that journey — 105 km/h cruise, **10**
 * well-spaced stops — and turn density alone held it at `drive`.
 *
 * ⚠ **The factor is calibrated, not derived.** A physical error model (bearing σ ≈ atan(σ√2 / L))
 * predicts far more noise than the journey actually shows, because consecutive fixes share satellite
 * geometry and multipath, so their errors are strongly correlated and largely cancel in a *difference*
 * of bearings. That makes any closed form an overestimate, so this is set from measurement: 5 × the
 * median accuracy puts the reported journey at ~21 °/km while keeping 95 % of its path, and leaves
 * every good-fix trace untouched because the floor dominates below ~6 m accuracy. It wants T3-corpus
 * calibration like [STRAIGHT_RAIL_DEG_PER_KM].
 *
 * ⚠ The cost, deliberately accepted: a **short** leg with poor fixes now yields fewer usable bearings
 * and can fall below [MIN_TRANSIT_LEGS], so it stays `drive`. That is the conservative direction —
 * §13's standing posture is that a missed promotion leaves a speed label while a wrong one is a
 * confident lie.
 */
internal const val BEARING_LEG_ACCURACY_FACTOR = 5.0

/**
 * The minimum leg length whose bearing this trace is allowed to trust.
 *
 * ⚠ Median, not mean: a handful of 200 m-accuracy fixes in a cutting or a tunnel mouth must not drag
 * the bar up for the whole journey, and the median is what "the fixes this trace is made of" means.
 *
 * ⚠ `accuracyM <= 0` is **not reported**, never "poor" — the convention `anchorRadiusM`,
 * `confidenceOf` and `FixHygiene.geometryPath` all follow. A trace with no reported accuracy at all
 * keeps the floor, which is exactly the old behaviour.
 */
internal fun bearingLegBarFor(fixes: List<CapturedFix>): Double {
    val reported = fixes.mapNotNull { fix -> fix.accuracyM.takeIf { it > 0 } }.sorted()
    if (reported.isEmpty()) return TRANSIT_MIN_BEARING_LEG_M
    val median = reported[reported.size / 2].toDouble()
    return maxOf(TRANSIT_MIN_BEARING_LEG_M, BEARING_LEG_ACCURACY_FACTOR * median)
}

/** At or below this speed (m/s) the vehicle counts as stopped. */
internal const val STOP_SPEED_MPS = 1.0

/**
 * Fraction of the trace's own cruise at or below which a leg counts as a **station-like slowdown**
 * (§32.11), when a complete stop is not observable at the delivered cadence.
 *
 * ### ⚠⚠ Why a complete stop is the wrong thing to look for
 *
 * [STOP_SPEED_MPS] asks for a leg whose *endpoints* are both inside the stop. At a cadence comparable
 * to the stop that is a coin flip, and the verdict stops being about the journey. Measured on an
 * identical synthetic ride — same route, same two 60 s station stops, same 60 s sampling — varying
 * only the sampling **phase**:
 *
 * | phase | 0 s | 10 s | 20 s | 30 s | 40 s | 50 s |
 * |---|---|---|---|---|---|---|
 * | well-spaced stops | 2 | 0 | 0 | 0 | 0 | 0 |
 * | verdict | `transit` | `drive` | `drive` | `drive` | `drive` | `drive` |
 *
 * ⚠ And the cadence is **not ours to fix**. S1 requests 10 s in a vehicle, but the request is a floor
 * the platform may ignore, promotion to `VEHICLE` waits on the first `MoveProgress` (≥3 min, ≥250 m),
 * and the reported journey was delivered at ~60 s throughout. A rule that only works when sampling is
 * fast relative to the stop is a rule that works by luck.
 *
 * **Reasoned, not fitted.** A leg of duration `T` containing a stop of duration `S` covers
 * `(T − S)·v` of ground, so its speed is `v·(1 − S/T)`. Requiring the stop to occupy at least *half*
 * the sampling interval — the weakest claim that still means "a stop happened here rather than a
 * slight slowing" — gives exactly `0.5·v`. The constant is the half, and it is a statement about
 * observability, not a tuned threshold.
 *
 * ⚠ It only ever *widens* what counts as a stop, so every guard around it is doing more work than
 * before: the shape rule still demands rail-like straightness, ≥ [MIN_STATION_SPACING_M] spacing and
 * a ≥ [SHAPE_RULE_MIN_CRUISE_MPS] cruise. The four road controls in `TransitKinematicsTest`
 * (motorway, rural junctions, stop-and-go, urban grid) are what pin that this is still refused.
 */
internal const val STATION_SLOWDOWN_FRACTION = 0.5

/**
 * Consecutive stops must be at least this far apart (metres) to read as *stations* rather than road
 * furniture. Sized against the realistic adversary: **a rural drive with junctions**. Traffic lights
 * and stop signs on a straight country road can easily sit ~1 km apart, so 800 m was not enough
 * separation to distinguish that from a stopping train — this is the constant that keeps such a
 * drive from being promoted.
 */
internal const val MIN_STATION_SPACING_M = 1500.0

/**
 * The stop-shape rule additionally requires this cruise speed (m/s ≈ 72 km/h) *between* stops.
 *
 * A train covers the gap between stations fast; a car pausing at rural junctions does not sustain
 * anywhere near this. Speed alone proves nothing (that is the whole premise of §13), but as a
 * *conjunct* with straightness and station-like spacing it removes the last road shape that fits.
 *
 * ⚠ The cost is deliberate: slow urban rail — trams, some subway lines — cruises below this and so
 * stays `drive` under T0. That is the conservative side of the trade, and it is what §13.3's T1/T2
 * map-matching tiers exist to recover.
 */
internal const val SHAPE_RULE_MIN_CRUISE_MPS = 20.0

/**
 * The straight-fast-long rule's straightness ceiling (°/km) — much tighter than
 * [RAIL_TURN_DENSITY_DEG_PER_KM]. A fast express that never fully stops fits neither the speed
 * shortcut nor the stop-shape rule, so this third path recognises it by near-arrow-straight
 * running. The gate sits below typical motorway turn density (interchanges, lane changes and
 * gentler-radius curves all add heading variance) and above mainline rail. Deliberately eager:
 * a false promotion here is corrected by the T1 rail map-match (which demotes an off-rail
 * `transit` back to `drive`), so the exposure is only a straight-fast-long *road* trip in an area
 * with no rail-tile coverage — bounded by keeping this gate tight. Wants T3-corpus calibration.
 */
internal const val STRAIGHT_RAIL_DEG_PER_KM = 12.0

/** The straight-fast-long rule requires at least this cruise (m/s ≈ 100 km/h) — excludes urban/short drives. */
internal const val FAST_RAIL_CRUISE_MPS = 28.0

/**
 * ...and at least this total path length (m ≈ 150 km) — the "definitely a train" distance.
 *
 * Deliberately high. A straight fast run of 30–50 km is genuinely ambiguous (car, coach, or
 * train), and by design that band is left to the T1 rail map-match, not guessed at here — a 30 km
 * straight motorway commute must stay `drive` (that is exactly what `motorwayDrivingWithNoStops`
 * pins). But no road runs near-arrow-straight (≤ [STRAIGHT_RAIL_DEG_PER_KM]) at rail speed for
 * *150 km*: over that distance a road accrues far more heading change following terrain and
 * connecting towns, so a path this long, straight and fast is overwhelmingly rail. This makes the
 * rule a coverage-independent fast-path for the unambiguous long-haul express, while the ambiguous
 * middle (and the reported 116 km case) stays T1's job.
 */
internal const val LONG_RAIL_TRIP_M = 150_000.0

/** Below this many usable legs there is not enough shape to judge; stay with the baseline. */
internal const val MIN_TRANSIT_LEGS = 8

/** The stop-based rule needs at least this many full stops to see a *pattern* rather than an event. */
internal const val MIN_STATION_STOPS = 2

/** Mean accuracy at or above this (metres) makes the geometry untrustworthy for shape analysis. */
internal const val TRANSIT_USELESS_ACCURACY_M = 120.0

/**
 * The measurements [refineWithTransitKinematics] decides on, exposed so the decision is inspectable
 * and testable independently of the thresholds it is compared against (§12.1's "store the inputs"
 * rule applied to the classifier's own internals).
 */
internal data class TransitEvidence(
    /** Heading change per kilometre, over legs long enough to have a meaningful bearing. */
    val turnDensityDegPerKm: Double,
    /** 85th-percentile leg speed — the sustained cruise, resistant to a single GPS spike. */
    val cruiseSpeedMps: Double,
    /** Complete-stop episodes (not individual fixes). */
    val stopCount: Int,
    /** Stops separated by at least [MIN_STATION_SPACING_M] — station-like rather than traffic-like. */
    val wellSpacedStopCount: Int,
    /** Legs with a usable bearing; the sample the shape signals actually rest on. */
    val usableLegCount: Int,
    /** Total path length (metres) — the straight-fast-long rule's "long" conjunct. */
    val tripDistanceM: Double,
    /** 0..1 quality weight from reported fix accuracy. */
    val fixQuality: Double,
)

/**
 * Promote an already-classified movement to [MovementMode.TRANSIT] when its *shape* says so.
 *
 * Returns [baseline] unchanged unless every guard passes — see the class KDoc for why the bias runs
 * that way. Only a [MovementMode.DRIVE] baseline is eligible: a walk, run or ride is never a train,
 * and an [MovementMode.UNKNOWN] baseline had too little data to classify at all.
 *
 * The returned classification carries [MOVEMENT_CLASSIFIER_VERSION] like any other verdict, so a
 * later tier (T1 rail map-matching) can tell a T0-era `transit` from its own — which is exactly the
 * capability M6 added `classifierVersion` for.
 */
internal fun refineWithTransitKinematics(
    fixes: List<CapturedFix>,
    baseline: ModeClassification,
): ModeClassification {
    if (baseline.mode != MovementMode.DRIVE) return baseline

    val evidence = transitEvidence(fixes) ?: return baseline
    if (evidence.usableLegCount < MIN_TRANSIT_LEGS) return baseline

    // Airborne first: the bands are disjoint, and a flight is not a rail verdict to be argued about.
    // No shape analysis is needed — nothing on the ground sustains this speed.
    if (evidence.cruiseSpeedMps > IMPLAUSIBLE_GROUND_SPEED_MPS) {
        if (evidence.cruiseSpeedMps >= IMPLAUSIBLE_FLIGHT_SPEED_MPS) return baseline // GPS artefact
        val sample = (evidence.usableLegCount.toDouble() / (MIN_TRANSIT_LEGS * 3)).coerceIn(0.0, 1.0)
        val confidence = (0.95 * sample * evidence.fixQuality * 100).roundToInt().coerceIn(0, 100)
        return baseline.copy(mode = MovementMode.FLIGHT, confidence = confidence)
    }

    val speedSaysRail = evidence.cruiseSpeedMps > RAIL_ONLY_SPEED_MPS

    val shapeSaysRail = evidence.turnDensityDegPerKm <= RAIL_TURN_DENSITY_DEG_PER_KM &&
        evidence.wellSpacedStopCount >= MIN_STATION_STOPS &&
        evidence.cruiseSpeedMps >= SHAPE_RULE_MIN_CRUISE_MPS

    // The blind-spot rule: a fast, near-non-stop express — very straight, fast, and long, no stop.
    val straightFastLongSaysRail = evidence.turnDensityDegPerKm <= STRAIGHT_RAIL_DEG_PER_KM &&
        evidence.cruiseSpeedMps >= FAST_RAIL_CRUISE_MPS &&
        evidence.tripDistanceM >= LONG_RAIL_TRIP_M

    if (!speedSaysRail && !shapeSaysRail && !straightFastLongSaysRail) return baseline

    // The speed shortcut is near-conclusive; the stop-shape rule is a genuine inference; the
    // straight-fast-long rule is the weakest (T1 is its safety net) and says so.
    val strength = when {
        speedSaysRail -> 0.9
        shapeSaysRail -> 0.6
        else -> 0.5
    }
    val sample = (evidence.usableLegCount.toDouble() / (MIN_TRANSIT_LEGS * 3)).coerceIn(0.0, 1.0)
    val confidence = (strength * sample * evidence.fixQuality * 100).roundToInt().coerceIn(0, 100)

    return baseline.copy(mode = MovementMode.TRANSIT, confidence = confidence)
}

/** Measure the shape signals. `null` when the path is too short to measure anything. */
internal fun transitEvidence(fixes: List<CapturedFix>): TransitEvidence? {
    if (fixes.size < 3) return null

    // ── legs: distance, duration, speed, and (when long enough) a bearing ────────
    val speeds = ArrayList<Double>()
    val bearings = ArrayList<Double>()      // bearings of legs long enough to trust
    var totalBearingLegM = 0.0              // distance those legs cover — turn density's denominator
    var totalTripM = 0.0                    // whole path length — the straight-fast-long "long" conjunct
    // §32.15: scaled to this trace's own fix quality, because a bearing measured over a leg shorter
    // than the fix uncertainty carries no information — and turn density can only be inflated by it.
    val bearingLegBarM = bearingLegBarFor(fixes)

    for (i in 1 until fixes.size) {
        val a = fixes[i - 1]
        val b = fixes[i]
        val dtSec = (b.timestampMs - a.timestampMs) / 1000.0
        if (dtSec <= 0.0) continue
        val legM = capturedDistanceMeters(a, b)
        speeds += legM / dtSec
        totalTripM += legM
        if (legM >= bearingLegBarM) {
            bearings += bearingDegrees(a, b)
            totalBearingLegM += legM
        }
    }
    if (speeds.isEmpty()) return null

    // ── turn density: summed heading change over the distance those bearings span ──
    var totalTurnDeg = 0.0
    for (i in 1 until bearings.size) {
        totalTurnDeg += abs(bearingDeltaDegrees(bearings[i - 1], bearings[i]))
    }
    val turnDensity = if (totalBearingLegM <= 0.0) {
        Double.MAX_VALUE // no trustworthy geometry → must not read as "straight"
    } else {
        totalTurnDeg / (totalBearingLegM / 1000.0)
    }

    // ── cruise speed: 85th percentile, so one spike can't manufacture a rail verdict ──
    val sorted = speeds.sorted()
    val cruise = sorted[((sorted.size - 1) * 85) / 100]

    // ── stop episodes: runs of stopped legs, collapsed, then measured for spacing ──
    // §32.11: a complete stop is only observable when sampling is fast relative to it, so the bar is
    // whichever is *looser* — an actual standstill, or a leg dragged to half the trace's own cruise,
    // which is what a station stop looks like once it is smeared across a long sampling interval.
    val stopSpeedBar = maxOf(STOP_SPEED_MPS, STATION_SLOWDOWN_FRACTION * cruise)
    val stopIndices = ArrayList<Int>()   // index into `fixes` where each stop episode began
    var inStop = false
    var legIndex = 0
    for (i in 1 until fixes.size) {
        val a = fixes[i - 1]
        val b = fixes[i]
        if ((b.timestampMs - a.timestampMs) <= 0L) continue
        val stopped = speeds[legIndex] <= stopSpeedBar
        if (stopped && !inStop) stopIndices += i - 1
        inStop = stopped
        legIndex++
    }

    var wellSpaced = 0
    for (i in 1 until stopIndices.size) {
        val gapM = capturedDistanceMeters(fixes[stopIndices[i - 1]], fixes[stopIndices[i]])
        if (gapM >= MIN_STATION_SPACING_M) wellSpaced++
    }
    // n gaps ⇒ n+1 stops bounding them; a single well-spaced gap is two station-like stops.
    val wellSpacedStops = if (wellSpaced > 0) wellSpaced + 1 else 0

    // ── fix quality: coarse fixes make every shape signal unreliable ──
    val reported = fixes.filter { it.accuracyM > 0 }
    val fixQuality = if (reported.isEmpty()) {
        // No accuracy reported at all — neutral, matching classifyMode's treatment.
        1.0
    } else {
        val mean = reported.sumOf { it.accuracyM.toDouble() } / reported.size
        (1.0 - mean / TRANSIT_USELESS_ACCURACY_M).coerceIn(0.0, 1.0)
    }

    return TransitEvidence(
        turnDensityDegPerKm = turnDensity,
        cruiseSpeedMps = cruise,
        stopCount = stopIndices.size,
        wellSpacedStopCount = wellSpacedStops,
        usableLegCount = bearings.size,
        tripDistanceM = totalTripM,
        fixQuality = fixQuality,
    )
}

/** Millimetres/second, for the [ModeClassification.medianSpeedMmPerS] convention. */
internal fun Double.toMmPerS(): Long = (this * 1000.0).roundToLong()
