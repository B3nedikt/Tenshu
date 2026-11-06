package app.tenshu.vectortile

import kotlin.math.roundToLong

/**
 * **S4 — rejoin rail fragments that a tile boundary split**
 * (`docs/proposals/timeline-movement-capture.md` §17.5/§20).
 *
 * ### The problem, and why the existing dedup does not solve it
 *
 * MVT geometry is **clipped to its tile**. One physical railway crossing four tiles arrives as four
 * `RailLine`s with different endpoints — so `RailTileReader`'s endpoint-keyed dedup (which correctly
 * suppresses the *same* line re-read from a neighbouring tile) keeps all four, as it must.
 *
 * That is invisible to [RailMatcher] (coverage is per point, and a point near any fragment counts)
 * but decisive for [RailSnapper], whose whole curvature recovery is *"walk the line's own vertices
 * between two landings **on the same line**"*. With fixes ~2 km apart (§17.1) and z14 tiles ~2.4 km
 * across, consecutive landings routinely fall on different fragments of one track and are joined by
 * the straight chord §16.1 reserves for genuine line changes. Stitching the fragments back is what
 * makes that walk reach across a tile edge.
 *
 * ⚠ It gets *more* valuable as S1's shorter interval lands, not less: more landings mean more
 * opportunities for a pair to straddle a boundary.
 *
 * ### ⚠⚠ Degree-2 only — the rule that keeps this from inventing routes
 *
 * A tile boundary and a **railway junction** look identical from here: both are points where line
 * endpoints coincide. Greedily chaining at a junction would fabricate a path through a switch the
 * train may never have taken — precisely the "no routing, ever" line §16.1 draws.
 *
 * So a join happens **only where exactly two fragment ends meet** at a point. Three or more ends
 * (a switch, a crossing, a station throat) leave every fragment untouched. The cost is that a route
 * through a junction still chords; the alternative is confidently drawing the wrong branch.
 *
 * Pure `commonMain`, no I/O — the [RailMatcher] posture.
 */
object RailLineStitcher {

    /**
     * How close two fragment ends must be to count as the same point (metres).
     *
     * Tile-clipped ends land on the boundary from both sides and differ only by the tile grid's
     * coordinate quantisation, which at z14 is well under a metre. 2 m absorbs that with margin while
     * staying far below the distance at which two genuinely different tracks run beside each other.
     */
    const val JOIN_TOLERANCE_M: Double = 2.0

    /**
     * Rejoin what tiling split. Returns lines whose fragments have been chained wherever a shared end
     * is unambiguous; every input point survives in exactly one output line.
     */
    fun stitch(
        lines: List<RailMatcher.RailLine>,
        toleranceM: Double = JOIN_TOLERANCE_M,
    ): List<RailMatcher.RailLine> {
        if (lines.size < 2) return lines

        // Endpoints bucketed by a quantised key, so finding "what else ends here" is O(1) per end
        // rather than O(n) — a dense urban bbox carries thousands of fragments.
        val ends = HashMap<String, MutableList<EndRef>>()
        lines.forEachIndexed { index, line ->
            if (line.points.size < 2) return@forEachIndexed
            ends.getOrPut(key(line.points.first(), toleranceM)) { mutableListOf() } += EndRef(index, atStart = true)
            ends.getOrPut(key(line.points.last(), toleranceM)) { mutableListOf() } += EndRef(index, atStart = false)
        }

        val consumed = BooleanArray(lines.size)
        val out = ArrayList<RailMatcher.RailLine>(lines.size)

        for (seed in lines.indices) {
            if (consumed[seed]) continue
            if (lines[seed].points.size < 2) {
                out += lines[seed]
                consumed[seed] = true
                continue
            }
            consumed[seed] = true
            var points = lines[seed].points
            val kind = lines[seed].kind

            // Extend from the tail, then from the head — a fragment can be the middle of a chain.
            points = extend(points, kind, lines, ends, consumed, toleranceM, fromTail = true)
            points = extend(points, kind, lines, ends, consumed, toleranceM, fromTail = false)

            val bounds = GeoBounds.of(points)
            out += if (bounds == null) lines[seed] else RailMatcher.RailLine(kind, points, bounds)
        }
        return out
    }

    /**
     * Walk one direction, absorbing each unambiguous neighbour.
     *
     * ⚠ A neighbour may need **reversing** to join — a tile encoder digitises each fragment whichever
     * way it likes, so "the end that touches" is as often a `last` as a `first`. The same trap
     * [RailSnapper] documents for its own walk direction.
     */
    private fun extend(
        seedPoints: List<LonLat>,
        kind: String,
        lines: List<RailMatcher.RailLine>,
        ends: Map<String, List<EndRef>>,
        consumed: BooleanArray,
        toleranceM: Double,
        fromTail: Boolean,
    ): List<LonLat> {
        var points = seedPoints
        while (true) {
            val tip = if (fromTail) points.last() else points.first()
            val here = ends[key(tip, toleranceM)] ?: return points
            // ⚠⚠ Degree-2 only. `here` holds every fragment END at this point, including the seed's
            // own — so exactly two entries means "one other fragment, unambiguously". Anything else
            // is a junction (or a fragment whose two ends coincide) and must not be chained.
            if (here.size != 2) return points
            val next = here.firstOrNull { !consumed[it.index] } ?: return points
            val candidate = lines[next.index]
            if (candidate.kind != kind) return points
            if (candidate.points.size < 2) return points

            consumed[next.index] = true
            // Orient the neighbour so its touching end is the one we attach to, then drop the shared
            // point so the joined line has no zero-length segment.
            val oriented = if (next.atStart) candidate.points else candidate.points.reversed()
            points = if (fromTail) points + oriented.drop(1) else oriented.dropLast(1) + points
        }
    }

    /** A fragment end, quantised to [toleranceM] so near-coincident ends share a bucket. */
    private fun key(point: LonLat, toleranceM: Double): String {
        // Degrees per metre at the equator; latitude scaling only tightens the bucket further north,
        // which is harmless — a slightly finer grid costs a missed join, never a wrong one.
        val step = toleranceM / 111_320.0
        val lon = (point.lon / step).roundToLong()
        val lat = (point.lat / step).roundToLong()
        return "$lon:$lat"
    }

    private data class EndRef(val index: Int, val atStart: Boolean)
}
