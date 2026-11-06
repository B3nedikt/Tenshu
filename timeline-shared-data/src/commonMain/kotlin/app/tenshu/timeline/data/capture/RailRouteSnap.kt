package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import app.tenshu.vectortile.RailSnapper
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * **B4 — decide whether a trace's route may be redrawn on the rails, and if so, redraw it**
 * (`docs/proposals/timeline-movement-capture.md` §14).
 *
 * [RailSnapper] owns the geometry; this owns the *policy* and the timestamps. Two jobs:
 *
 *  1. **The gate.** Under what evidence is redrawing honest at all.
 *  2. **The bridge back to a captured path.** [RailSnapper] is coordinate-only by construction, so
 *     the source fixes' timestamps and accuracies are re-attached here.
 *
 * ## The threshold, and why it is not a bigger number
 *
 * Snapping requires **[MIN_EXCLUSIVE_COVERAGE] of the path on track a road vehicle cannot occupy** —
 * numerically the same bar `RailRefinement` promotes on, and deliberately so. Raising the *number*
 * was the obvious way to be stricter and it is the wrong one: real on-device train traces reach only
 * ~0.54–0.61 exclusive coverage (two real Munich–Mühldorf journeys sat at 0.58 / 0.61 — urban
 * canyons, tunnels, station approaches, ±50–100 m fixes, decimation), so a 0.8 bar would make
 * snapping dead code in the field, and the traces that need correcting most are precisely the ones
 * with the *lowest* coverage.
 *
 * The extra strictness is bought **structurally** instead, which is also strictly better, because a
 * coverage number cannot tell a tram from a train while the candidate filter can:
 *
 *  - **Only exclusive track is a snapping candidate at all** ([RailSnapper] enforces it). Measured,
 *    not assumed: real Firenze *road* traces matched real Firenze rail completely 10 times in 240,
 *    because the tram lines run down the middle of streets — and gating on exclusive track cut that
 *    to 2 in 240. A car on a tram street *is* on tram geometry, so redrawing it onto the tram line
 *    would be a confident, visible lie.
 *  - **Only a trace the refinement concluded is [MovementMode.TRANSIT] is offered.** A drive that
 *    happens to run beside a railway for a stretch is never redrawn.
 *  - **[RailEvidence.Unavailable] is never evidence.** An undownloaded region, a missing gzip binding
 *    and a corrupt bundle all answer empty, indistinguishable from "no track here". Empty must never
 *    mean "no track", and it must never produce a snapped path — a trace we could not survey is left
 *    un-snapped *and* un-stamped so a later sweep retries it.
 *
 * ## ⚠ Display-only, and non-destructive
 *
 * The result never touches `MovementTrace.polyline` and never re-derives distance, duration or mode.
 * Those are computed at capture over the raw pre-decimation fixes (§3); re-deriving them from a
 * snapped path would silently rewrite recorded history the next time the basemap moved a curve.
 */
object RailRouteSnap {

    /**
     * The snapper's version, stamped onto every [org.tenshu.core.timeline.SnappedRoute].
     *
     * **Bump when the snapping algorithm or its threshold materially changes** — together with
     * [MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED], which is what actually re-offers already-swept
     * traces to the sweep.
     */
    const val SNAP_VERSION: Int = 1

    /**
     * Exclusive-track coverage at or above which a route may be redrawn. Equal to
     * `RailRefinement`'s promote bar by design — see the type KDoc for why raising the number is the
     * wrong lever and what is done instead.
     */
    const val MIN_EXCLUSIVE_COVERAGE: Double = 0.5

    /** The corrected path, plus the figure the decision rested on. */
    data class Snapped(
        /** The corrected path, decimated to the same bound as a stored trace polyline. */
        val fixes: List<CapturedFix>,
        /** Exclusive-track coverage of the *source* path, `0.0`–`1.0`. */
        val exclusiveCoverage: Double,
    ) {
        /** [exclusiveCoverage] as the stored integer (the schema has no float kind). */
        val coverageBp: Long get() = (exclusiveCoverage.coerceIn(0.0, 1.0) * 10_000).roundToLong()
    }

    /**
     * Snap [fixes] onto the rail geometry in [evidence], or return `null` when the evidence does not
     * justify redrawing.
     *
     * @param refined the verdict [RailRefinement.refine] reached for this trace — snapping follows
     *   the classification rather than second-guessing it.
     */
    fun snap(
        fixes: List<CapturedFix>,
        refined: ModeClassification,
        evidence: RailEvidence,
        config: MovementDetectionConfig = MovementDetectionConfig(),
        toleranceM: Double = RailMatcher.DEFAULT_TOLERANCE_M,
    ): Snapped? {
        // "We could not look" is not "there is no track" — leave the route alone and let a later
        // sweep, once the region is downloaded, get its chance.
        val lines = (evidence as? RailEvidence.Surveyed)?.lines ?: return null
        if (refined.mode != MovementMode.TRANSIT) return null
        if (fixes.size < 2) return null

        val path = fixes.map { LonLat(lon = it.lngE7 / 1e7, lat = it.latE7 / 1e7) }
        val result = RailSnapper.snap(path, lines, toleranceM)
        if (result.coverage < MIN_EXCLUSIVE_COVERAGE) return null
        // Coverage cleared but nothing survived as track — nothing to draw that the raw path doesn't
        // already say. (Unreachable given the bar; cheap to state rather than to reason about.)
        if (result.snappedPointCount == 0) return null

        val snappedFixes = result.points.map { it.toCapturedFix(fixes) }
        // Bound the blob exactly as a captured trace is bounded: walking the track inserts the line's
        // own vertices, so a snapped path can be *longer* than the raw one. The Douglas–Peucker
        // tolerance (15 m) sits well inside the 50 m match tolerance, so the correction survives it.
        val decimated = MovementTracePolyline.simplify(snappedFixes, config)
        if (decimated.size < 2) return null

        return Snapped(fixes = decimated, exclusiveCoverage = result.coverage)
    }

    /**
     * Re-attach time to a snapped coordinate.
     *
     * A point that *is* a source fix (integral `sourcePosition`) keeps that fix's own timestamp and
     * accuracy verbatim — the ⚠⚠ requirement, and the reason `sourcePosition` exists at all. A track
     * vertex walked between two landings gets both interpolated by its along-track fraction, which is
     * the honest reading: the vehicle passed that vertex somewhere between the two observations, in
     * proportion to how far along it sits.
     */
    private fun RailSnapper.SnappedPoint.toCapturedFix(source: List<CapturedFix>): CapturedFix {
        val latE7 = (point.lat * 1e7).roundToLong()
        val lngE7 = (point.lon * 1e7).roundToLong()
        val lower = sourcePosition.toInt().coerceIn(0, source.lastIndex)
        val fraction = sourcePosition - lower
        if (fraction <= 0.0 || lower == source.lastIndex) {
            val f = source[lower]
            return CapturedFix(latE7, lngE7, timestampMs = f.timestampMs, accuracyM = f.accuracyM)
        }
        val a = source[lower]
        val b = source[lower + 1]
        return CapturedFix(
            latE7 = latE7,
            lngE7 = lngE7,
            timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * fraction).roundToLong(),
            accuracyM = (a.accuracyM + (b.accuracyM - a.accuracyM) * fraction).roundToInt(),
        )
    }
}
