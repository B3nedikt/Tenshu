package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedMoment
import app.tenshu.timeline.data.ImportedPlace
import app.tenshu.timeline.data.ImportedTimeline
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.timeline.common.MomentSourceTag
import org.tenshu.core.place.PlaceGrid
import kotlin.math.roundToLong

/**
 * The **device-independent visit-detection engine** (the P6 stay-point clustering,
 * `docs/proposals/timeline-ingestion.md` §4 + `timeline-services.md` §5.2): turns a run of raw
 * [CapturedFix]es from a live sensor into [CapturedVisit] dwells, and maps those onto the neutral
 * [ImportedTimeline] the shared `TimelineImportWriter` persists. Pure `commonMain` math — no
 * Android/iOS dependency — so the clustering is fully unit-testable with no hardware and is reused
 * both at capture time (Android live capture, I3b) and later at merge time (the `TripMerger`, I5).
 *
 * Algorithm: the classic Li-et-al. stay-point detection. Walking the time-ordered fixes, a stay is
 * the longest consecutive run that stays within [VisitDetectionConfig.radiusM] of its anchor fix
 * and spans at least [VisitDetectionConfig.minDwellMs]; the dwell centroid is the run's mean
 * coordinate. Movement between stays yields no visit (trip material is I5, not stored as a Moment
 * here — §8 raw-ping decision). iOS `CLVisit` bypasses this — Apple already clusters — feeding
 * [capturedVisitsToTimeline] directly (§4.2).
 *
 * Idempotency mirrors the importers: a visit's place id is content-addressed off its centroid
 * (rounded to a grid so repeat visits to the same spot fold to one `Place`), and its moment id off
 * `(placeId, arrival)`, so re-running detection over overlapping capture windows converges instead
 * of duplicating.
 */
object VisitDetector {

    /**
     * Detect dwells in a set of raw fixes. Fixes need not be sorted; out-of-order / duplicate
     * timestamps are handled. Fewer than two fixes, or pure movement, yields no visits.
     */
    fun detect(
        fixes: List<CapturedFix>,
        config: VisitDetectionConfig = VisitDetectionConfig(),
    ): List<CapturedVisit> {
        if (fixes.size < 2) return emptyList()
        val sorted = fixes.sortedBy { it.timestampMs }
        val n = sorted.size
        val visits = ArrayList<CapturedVisit>()

        var i = 0
        while (i < n) {
            // Extend the run while every point stays within radius of the anchor fix i.
            var j = i + 1
            while (j < n &&
                config.holdsInCluster(sorted[i], sorted[j])
            ) j++
            val last = j - 1

            val dwellMs = sorted[last].timestampMs - sorted[i].timestampMs
            if (last > i && dwellMs >= config.minDwellMs) {
                visits += sorted.subList(i, j).toCentroidVisit()
                i = j
            } else {
                i++
            }
        }
        return visits
    }

    /**
     * Map detected dwells onto the neutral [ImportedTimeline] — a content-addressed `Place` per
     * distinct (gridded) location + a `Visit` [ImportedMoment] pointing at it. Labels are blank
     * (geocoding is a separate rail, §5); repeat visits to one place collapse to a single `Place`.
     */
    fun capturedVisitsToTimeline(
        visits: List<CapturedVisit>,
        config: VisitDetectionConfig = VisitDetectionConfig(),
        /**
         * Optional placeId substitution (Wi-Fi recognition, §4.5) — when it returns non-null, the
         * visit folds onto *that* place instead of its grid-snapped one, so repeated visits on a
         * known network join the place the user named. Defaults to the grid-snap.
         */
        placeIdOverride: (CapturedVisit) -> String? = { null },
    ): ImportedTimeline {
        val places = LinkedHashMap<String, ImportedPlace>()
        val moments = ArrayList<ImportedMoment>()

        for (v in visits) {
            val placeId = placeIdOverride(v) ?: placeIdFor(v, config.placeGridE7)
            places.getOrPut(placeId) {
                ImportedPlace(
                    placeId = placeId,
                    latE7 = v.latE7,
                    lngE7 = v.lngE7,
                    capturedAtMs = v.arrivalMs,
                    accuracyM = v.accuracyM,
                )
            }
            moments += ImportedMoment(
                momentId = "cap-visit:$placeId:${v.arrivalMs}",
                timestampMs = v.arrivalMs,
                kind = Moment.MomentKind.VISIT,
                title = "Visit",
                subjectKind = SubjectKind.PLACE,
                subjectId = placeId,
                durationSec = v.durationMs / 1000,
                sources = listOf(MomentSourceTag.CAPTURE),
            )
        }
        return ImportedTimeline(places = places.values.toList(), moments = moments)
    }

    /** Detect + map in one step — the shape a capture writer consumes. */
    fun detectTimeline(
        fixes: List<CapturedFix>,
        config: VisitDetectionConfig = VisitDetectionConfig(),
    ): ImportedTimeline = capturedVisitsToTimeline(detect(fixes, config), config)

    /**
     * The grid-snapped placeId a visit would get by coordinates alone — exposed so the capture loop
     * can compute the *candidate* placeId to offer Wi-Fi recognition (§4.5) before the write.
     */
    fun placeIdOf(v: CapturedVisit, config: VisitDetectionConfig = VisitDetectionConfig()): String =
        placeIdFor(v, config.placeGridE7)

    /** A stable place id from a centroid snapped to a grid, so repeat visits fold to one `Place`. */
    private fun placeIdFor(v: CapturedVisit, gridE7: Long): String {
        val lat = snap(v.latE7, gridE7)
        val lng = snap(v.lngE7, gridE7)
        return "cap:$lat,$lng"
    }

    private fun snap(e7: Long, gridE7: Long): Long =
        (e7.toDouble() / gridE7).roundToLong() * gridE7
}

/**
 * Tuning for [VisitDetector]. Defaults are conservative first guesses; the honest values need real
 * captured data (`timeline-ingestion.md` §8) — I2's imported history doubles as the tuning corpus.
 */
data class VisitDetectionConfig(
    /**
     * A stay must keep every fix within this radius of its anchor (metres). **The floor**, not the
     * whole rule — see [anchorRadiusM], which widens it toward [maxAnchorRadiusM] when the fixes
     * themselves are coarse.
     */
    val radiusM: Double = 100.0,
    /**
     * Ceiling on the accuracy-scaled anchor radius (metres, S6a — `timeline-movement-capture.md`
     * §18.2/§18.7).
     *
     * **Reasoned, not fitted.** It bounds the cost of widening: past this the clustering would start
     * folding places a user can genuinely tell apart, and a fix that coarse carries too little
     * information for *any* radius to be meaningful — so we stop widening rather than keep chasing
     * the reported accuracy. 250 m ≈ the coarsest indoor NETWORK fix (±100–150 m) plus the same
     * again for the anchor's own error, which is exactly the case §18.2 is about.
     *
     * ⚠ It deliberately **exceeds the projector's `SAME_PLACE_RADIUS_M` (200 m)**, and that is not
     * an inconsistency to "fix": these two numbers answer different questions. This one asks *is
     * this still the same stay?* — one row, one arrival timestamp, nothing to merge. The projector's
     * asks *are these two already-separate rows the same place?* Widening the read-time merge is the
     * thing §18.7 explicitly rejects; widening the generator is the thing it asks for.
     */
    val maxAnchorRadiusM: Double = 250.0,
    /**
     * Multiple of the reported accuracy the anchor radius scales to (S6a — see [anchorRadiusM]).
     *
     * ⚠⚠ **Without this the rule is a no-op on the very case it was written for.** `radiusM` is
     * 100 and §18.2's report is a ±100 m indoor fix, so a bare `max(accuracy).coerceIn(100, 250)`
     * returns the floor and nothing changes. Lowering the floor does not help either — `coerceIn(60,
     * 250)` of 100 is still 100; it only tightens the radius for *good* fixes.
     *
     * **Reasoned, not fitted.** `accuracyM` is a ~68 % confidence radius, so two fixes on opposite
     * sides of one stationary point sit ~2× that apart with neither having moved — a 1× bar admits
     * exactly the cloud the rule exists to hold together. This is the same correction, for the same
     * reason, that [MovementDetectionConfig.extentAccuracyFactor] carries on S6's other half; the two
     * should move together if either is ever re-tuned.
     */
    val anchorAccuracyFactor: Double = 2.0,
    /**
     * How many anchor radii the §18.6 still-here veto reaches (S3).
     *
     * ⚠⚠ **Bounded on purpose.** A counted zero steps is reported by a cyclist, a driver, a train
     * passenger and a stationary phone alike, so the veto is only honest where the *displacement* is
     * already small enough to be jitter. 2× the (already accuracy-scaled) radius covers a coarse
     * indoor fix bouncing off two walls; past that, something carried you and the dwell must close.
     * ⚠ Raising this toward "unbounded" would hold one dwell open across an entire drive.
     */
    val stillHereRadiusFactor: Double = 2.0,
    /** A stay must span at least this long to count as a visit (millis). Default 5 min. */
    val minDwellMs: Long = 5 * 60 * 1000L,
    /**
     * Grid (E7) a centroid snaps to for place identity — ~74 m east-west and ~111 m north-south at
     * mid latitudes, so repeat visits fold. ⚠ Single-sourced from [PlaceGrid.PLACE_GRID_E7]: the
     * proxy-side repair rule tests *cell adjacency* against this grid, and a drift between the two
     * would silently make adjacency mean the wrong thing.
     */
    val placeGridE7: Long = PlaceGrid.PLACE_GRID_E7,
)
