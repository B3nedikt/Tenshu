package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared geo helpers for the capture pipeline (I3), used by both the batch [VisitDetector] and the
 * streaming [detectVisits] operator so the two clusterings agree bit-for-bit. Pure `commonMain`.
 */

/** Great-circle distance between two E7 coordinates, in metres (haversine). */
internal fun capturedDistanceMeters(a: CapturedFix, b: CapturedFix): Double =
    distanceMetersE7(a.latE7, a.lngE7, b.latE7, b.lngE7)

/**
 * Great-circle distance between two E7 coordinate *pairs*, in metres — the coordinate-only form,
 * for callers that hold raw E7 values rather than a [CapturedFix] (place identity, §8.4 F1/F2).
 */
internal fun distanceMetersE7(aLatE7: Long, aLngE7: Long, bLatE7: Long, bLngE7: Long): Double {
    val earthRadiusM = 6_371_000.0
    val lat1 = aLatE7 / 1e7
    val lon1 = aLngE7 / 1e7
    val lat2 = bLatE7 / 1e7
    val lon2 = bLngE7 / 1e7
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadiusM * 2 * atan2(sqrt(h), sqrt(1 - h))
}

/**
 * The **anchor radius for one (anchor, candidate) pair** — the single rule all three clusterings
 * share (S6a, `timeline-movement-capture.md` §18.2/§18.7).
 *
 * ```
 * (anchorAccuracyFactor × max(anchor.accuracyM, fix.accuracyM)).coerceIn(radiusM, maxAnchorRadiusM)
 * ```
 *
 * ⚠⚠ **The factor is what makes this rule bite at all.** Without it the expression is
 * `max(accuracy).coerceIn(100, 250)`, which at the ±100 m accuracy that *motivated* the whole slice
 * returns exactly 100 — the floor — so S6a was a **no-op on its own report**. Nor is lowering the
 * floor a fix: `coerceIn(60, 250)` of 100 is still 100. Lowering the floor only tightens the radius
 * for *good* fixes, which is the opposite of what §18.2 asks for.
 *
 * The factor is the same correction [MovementDetectionConfig.extentAccuracyFactor] already carries on
 * the other half of S6, for one reason: **`accuracyM` is a ~68 % confidence radius**, so two fixes on
 * opposite sides of one stationary point are routinely ~2× that apart with neither having moved. A
 * 1× bar therefore admits exactly the cloud it is supposed to hold together. This is a statistical
 * property of independent errors, not a constant fitted to one device's reported number.
 *
 * **Why it cannot be a bare constant.** `radiusM` = 100 m was applied to indoor NETWORK fixes whose
 * own accuracy is ±100 m, so a *stationary* phone left its own anchor **by construction**: the first
 * outlier closed the cluster, the next one opened a fresh anchor, and five minutes later a second
 * dwell was minted with a new arrival timestamp — hence a new content-addressed row. One stay, three
 * rows. This mirrors F1's rule one layer up (`timeline-recognition-and-place-naming.md` §8.4): *two
 * dwells can only be told apart to within the accuracy of the fixes that produced them.* F1 applied
 * it to place identity — the symptom; this applies it to the clustering that generates the dwells.
 *
 * ⚠ **`accuracyM == 0` means *not reported*, never *perfect*** — the existing convention (see
 * `confidenceOf`, which treats 0 as neutral rather than as a flawless fix). Both unreported ⇒ we
 * know nothing about fix quality ⇒ fall back to the [VisitDetectionConfig.radiusM] floor, never to
 * the ceiling. One reported ⇒ that one governs, because `max` ignores the 0.
 *
 * ⚠ Widening costs resolution between genuinely adjacent places. The accuracy scaling is precisely
 * what bounds that cost: a clean ±10 m outdoor fix scales to 20 m and is floored back to the tight
 * 100 m, and only the coarse indoor case — where the coordinate never distinguished the two places
 * anyway — widens.
 *
 * ⚠⚠ **All three call sites must use this** (`MovementSegmenter.step`, [VisitDetector.detect],
 * [detectVisits]) or the streaming and batch detectors diverge — `MovementSegmenterTest` pins them
 * bit-for-bit.
 */
internal fun VisitDetectionConfig.anchorRadiusM(anchor: CapturedFix, fix: CapturedFix): Double {
    val reported = maxOf(anchor.accuracyM, fix.accuracyM)
    // Explicit, though `0.coerceIn(radiusM, …)` would also land on the floor: the fallback is a
    // decision about unknown accuracy, not an accident of the current default being the floor.
    if (reported <= 0) return radiusM
    return (anchorAccuracyFactor * reported).coerceIn(radiusM, maxAnchorRadiusM)
}

/**
 * **Does [fix] belong to the run anchored at [anchor]?** — the one containment rule all three
 * clusterings share (`MovementSegmenter.step`, [VisitDetector.detect], [detectVisits]).
 *
 * Two ways to belong:
 *  1. inside the accuracy-scaled [anchorRadiusM] (S6a), or
 *  2. the **still-here veto** (S3 / §18.6) — outside the radius, but the pedometer counted **zero**
 *     steps and the displacement is within [VisitDetectionConfig.stillHereRadiusFactor] radii. A fix
 *     that left the circle without anybody walking did not walk out of it.
 *
 * ⚠⚠ **Only a COUNTED zero vetoes.** `stepsSinceLastFix == null` means no pedometer reported, which
 * is not evidence of stillness; treating it as such would hold a dwell open through every drive on a
 * device without the sensor.
 *
 * ⚠⚠ **Bounded, and the bound is the whole safety argument.** Zero steps is equally what a driver, a
 * cyclist and a train passenger report. Inside a couple of anchor radii the honest reading is jitter;
 * beyond it something carried you and the dwell must close.
 *
 * ⚠⚠ **All three clusterings must use this.** `MovementSegmenterTest` pins the streaming dwell stream
 * bit-for-bit against the batch detector, and a veto applied in only one of them diverges the moment
 * a fixture carries a real step count — silently, because a null-cadence fixture cannot see it.
 */
internal fun VisitDetectionConfig.holdsInCluster(anchor: CapturedFix, fix: CapturedFix): Boolean {
    val radius = anchorRadiusM(anchor, fix)
    val distance = capturedDistanceMeters(anchor, fix)
    if (distance <= radius) return true
    if (fix.stepsSinceLastFix != 0) return false
    return distance <= radius * stillHereRadiusFactor
}

/**
 * The **extent** of an ordered path: the greatest distance any fix reaches from the first, in metres.
 *
 * ⚠⚠ **Extent, deliberately, not net first→last displacement** (S6b, §18.3). A genuine out-and-back
 * walk ends where it started — near-zero displacement, large extent — so a displacement gate would
 * delete exactly the real trips it is supposed to protect. A jitter cloud is the opposite shape:
 * **large path length, small extent**, and that is the whole discriminator.
 */
internal fun List<CapturedFix>.pathExtentMeters(): Double {
    if (size < 2) return 0.0
    val origin = first()
    var max = 0.0
    for (i in 1 until size) {
        val d = capturedDistanceMeters(origin, this[i])
        if (d > max) max = d
    }
    return max
}

/** Mean of the *reported* horizontal accuracies (metres); `0.0` when no fix reported one. */
internal fun List<CapturedFix>.meanReportedAccuracyM(): Double {
    val reported = filter { it.accuracyM > 0 }
    if (reported.isEmpty()) return 0.0
    return reported.sumOf { it.accuracyM.toDouble() } / reported.size
}

/**
 * Initial bearing from [a] to [b], in degrees clockwise from north (0..360).
 *
 * ⚠ Meaningless for a very short leg: at a 10 m step with ±20 m fixes the direction is mostly noise,
 * so callers must gate on leg length (see `TRANSIT_MIN_BEARING_LEG_M`) before trusting it.
 */
internal fun bearingDegrees(a: CapturedFix, b: CapturedFix): Double {
    val lat1 = (a.latE7 / 1e7).toRadians()
    val lat2 = (b.latE7 / 1e7).toRadians()
    val dLon = ((b.lngE7 - a.lngE7) / 1e7).toRadians()
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val deg = atan2(y, x) * 180.0 / PI
    return (deg + 360.0) % 360.0
}

/** The smaller angle between two bearings, in degrees (0..180) — direction-agnostic. */
internal fun bearingDeltaDegrees(from: Double, to: Double): Double {
    val raw = ((to - from) + 360.0) % 360.0
    return if (raw > 180.0) 360.0 - raw else raw
}

/** Total great-circle length of an ordered path (sum of consecutive haversine legs), in metres. */
internal fun List<CapturedFix>.pathDistanceMeters(): Double {
    if (size < 2) return 0.0
    var total = 0.0
    for (i in 1 until size) total += capturedDistanceMeters(this[i - 1], this[i])
    return total
}

/**
 * A dwell over a run of fixes: centroid coordinate + arrival/departure bounds. Assumes time order.
 *
 * **Accuracy-weighted** (Phase 2, `timeline-movement-capture.md` §11.3): each fix contributes
 * `1/accuracy²`, so a precise fix (small `accuracyM`) pulls the centroid toward it while a coarse
 * ±100 m network fix barely moves it — a dwell that caught even one good GPS fix (e.g. walking in
 * past a window) isn't dragged off by the coarser indoor fixes. Fixes with no reported accuracy
 * (`accuracyM == 0`) are treated as coarse ([UNKNOWN_ACCURACY_M]); when **no** fix reports accuracy
 * the weights are uniform and this is exactly the plain mean (so accuracy-free fixtures are unchanged).
 */
internal fun List<CapturedFix>.toCentroidVisit(): CapturedVisit {
    val arrivalMs = first().timestampMs
    val departureMs = last().timestampMs
    // Representative dwell accuracy (4-B, timeline-recognition-and-place-naming.md §4.3): the median
    // of the *reported* per-fix accuracies — the typical GPS quality during the stay, which indoors
    // is floored by the ±100 m network provider. 0 (no fix reported accuracy) = unknown → never
    // triggers coarsening (we only coarsen a name when we *know* the fix is poor).
    val repAccuracyM = mapNotNull { it.accuracyM.takeIf { a -> a > 0 } }.sorted()
        .let { if (it.isEmpty()) 0 else it[it.size / 2] }
    if (none { it.accuracyM > 0 }) {
        return CapturedVisit(
            latE7 = map { it.latE7 }.average().roundToLong(),
            lngE7 = map { it.lngE7 }.average().roundToLong(),
            arrivalMs = arrivalMs,
            departureMs = departureMs,
            accuracyM = repAccuracyM,
        )
    }
    fun weight(f: CapturedFix): Double {
        val acc = (if (f.accuracyM > 0) f.accuracyM.toDouble() else UNKNOWN_ACCURACY_M)
            .coerceAtLeast(ACCURACY_FLOOR_M)
        return 1.0 / (acc * acc)
    }
    val totalWeight = sumOf { weight(it) }
    val latE7 = (sumOf { it.latE7 * weight(it) } / totalWeight).roundToLong()
    val lngE7 = (sumOf { it.lngE7 * weight(it) } / totalWeight).roundToLong()
    return CapturedVisit(
        latE7 = latE7,
        lngE7 = lngE7,
        arrivalMs = arrivalMs,
        departureMs = departureMs,
        accuracyM = maxOf(repAccuracyM, spreadFromCentroidM(latE7, lngE7)),
    )
}

/**
 * How far this cluster actually reaches from its centroid, in metres (§32.10).
 *
 * ### ⚠⚠ Why a place's accuracy cannot be the fixes' accuracy alone
 *
 * [repAccuracyM] above answers *"how good were the fixes?"*. Every consumer of
 * `Place.accuracyM` asks a different question — *"how well does this dwell localise a place?"* —
 * and the two come apart precisely when they matter. Measured on the reported 2026-08-12 stay: a
 * cluster of **±14 m fixes** whose centroid sits **165 m from the first fix of the walk that begins
 * one second later**. It was stamped `accuracyM = 14`, which sent it down `PlaceLabelEnricher`'s
 * *precise* branch, and it was duly named after a bar 30 m from a centroid the user demonstrably
 * was not standing on.
 *
 * A stay whose fixes span 300 m localises a place to ±150 m however crisp each fix was. So the
 * stored figure is the **worse** of the two claims.
 *
 * ⚠ `max`, deliberately **not** a sum: an indoor jitter cloud's spread *is* its fix error, so adding
 * them would count the same uncertainty twice and coarsen every ordinary indoor dwell. This is the
 * same reasoning `MovementDetectionConfig.extentAccuracyFactor` carries on the movement side — a
 * cloud's extent scales with the fixes' accuracy, so extent past that is the part that means
 * something.
 *
 * ⚠ **Radius, not median distance.** The median under-reports a cluster the user walked through,
 * which is the whole case this exists for; the radius over-reports a cluster with one stray fix. The
 * errors are not symmetric — over-reporting only ever moves a place *down* the naming tiers
 * (venue → stop → street → city), while under-reporting leaves the bar name in place. It is also
 * bounded by construction: `holdsInCluster` admits nothing beyond ~2× the anchor radius.
 *
 * ⚠ Monotone in the safe direction: this can only ever make a place **less** precise, never more, so
 * no dwell that names a venue today loses one it was entitled to.
 */
private fun List<CapturedFix>.spreadFromCentroidM(latE7: Long, lngE7: Long): Int {
    if (size < 2) return 0
    val centroid = CapturedFix(latE7 = latE7, lngE7 = lngE7, timestampMs = 0L)
    return maxOf { capturedDistanceMeters(it, centroid) }.roundToLong().toInt()
}

/** A fix with no reported accuracy is treated as this coarse (metres) in the weighted centroid. */
private const val UNKNOWN_ACCURACY_M = 50.0

/** Accuracy is clamped to at least this (metres) so a single hyper-precise fix can't fully dominate. */
private const val ACCURACY_FLOOR_M = 5.0

private fun Double.toRadians(): Double = this * PI / 180.0
