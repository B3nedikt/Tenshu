package app.tenshu.vectortile

/**
 * **B4 — snap a matched path onto the rail geometry it ran on**
 * (`docs/proposals/timeline-movement-capture.md` §14).
 *
 * [RailMatcher] answers *whether* a path followed track. This answers *where the track goes*, which
 * is a different question with a different output: a recorded train ride is drawn from raw GPS, and
 * raw GPS wanders off the rails — through tunnels it stops entirely, in an urban canyon it walks
 * along the wrong side of a building, and decimation cuts the corner off every curve. We ship the
 * exact rail linework, so the drawn route can be corrected to the track the train actually ran on.
 *
 * ### The algorithm, in two moves
 *
 *  1. **Project.** Each source point is projected onto the nearest candidate line within tolerance —
 *     the nearest position *on* the line, not just its distance (that is what [projectOntoPolyline]
 *     adds over [pointToPolylineMeters]).
 *  2. **Walk the track between.** Two consecutive matched points that landed on the *same* line have
 *     the line's own vertices between them emitted in travel order. This is where tunnels and curves
 *     are recovered: the geometry between the two landings is the real track, not the straight chord
 *     the GPS suggested. Source points that matched nothing are **dropped when a same-line walk
 *     already spans them** (the track is the better answer) and **kept raw otherwise** (the walk to
 *     the platform is genuinely not on rails, and inventing track for it would be a lie).
 *
 * No routing, ever: two landings on *different* lines are simply joined directly. Filling across
 * lines would need a graph search over the rail network, which invents geometry the tiles never
 * asserted — a train changing tracks and a tile boundary look identical from here.
 *
 * ### ⚠⚠ Street-running track is structurally excluded
 *
 * Only track a road vehicle physically cannot occupy ([RailMatcher.isExclusiveTrack]) is a snapping
 * candidate. This is not a tuning knob — it is measured: matching real Firenze *road* traces against
 * real Firenze rail found **10 of 240 completely matched**, because the T1/T2/T3 tram lines run down
 * the middle of streets. A car on a tram street *is* on tram geometry, so projecting onto tram track
 * would redraw a drive as a confident, visible lie. Excluding it here makes that unrepresentable
 * per-point rather than vetoing a whole trace: a train arriving beside a tram simply does not use the
 * tram line for its own geometry.
 *
 * The cost is that a genuine **tram ride never snaps**. That is the honest outcome — nothing in the
 * geometry distinguishes it from a car on the same street.
 *
 * Pure `commonMain`, **no I/O**: the caller supplies already-decoded lines, exactly as [RailMatcher]
 * does, because tile fetching is async through Control's proxy.
 */
object RailSnapper {

    /**
     * One point of the corrected path.
     *
     * [sourcePosition] is the position in the **source** path this point corresponds to: `3.0` is
     * source point 3 itself; `3.4` is 40% of the along-track distance from source point 3 to source
     * point 4. ⚠ This is the field that carries time through snapping — this object has no clock and
     * no timestamps, so the caller interpolates the source fixes' own timestamps against it. Baking
     * timestamps in here would drag the capture DTOs into `:vector-tile`; the codecs on the two sides
     * of that boundary are famously easy to confuse (`LonLatPolyline` is lon/lat only, while a stored
     * trace carries lat/lng/time/accuracy), so the seam stays coordinate-only and explicit.
     */
    data class SnappedPoint(
        val point: LonLat,
        val sourcePosition: Double,
        /** `true` when [point] is a position on the track; `false` when it is the raw source fix. */
        val snapped: Boolean,
    )

    /**
     * The corrected path plus the figures the decision rests on.
     *
     * [matchedPointCount] counts **source** points that landed on exclusive track, so [coverage] is
     * comparable with [RailMatcher.RailMatch.exclusiveCoverage] over the same input — deliberately,
     * because the caller's threshold is stated against that number.
     */
    data class SnapResult(
        val points: List<SnappedPoint>,
        val matchedPointCount: Int,
        val totalPointCount: Int,
    ) {
        val coverage: Double
            get() = if (totalPointCount == 0) 0.0 else matchedPointCount.toDouble() / totalPointCount

        /** How many output points came from the track rather than from a raw fix. */
        val snappedPointCount: Int get() = points.count { it.snapped }

        companion object {
            val NONE = SnapResult(emptyList(), 0, 0)
        }
    }

    /**
     * Snap [path] onto the exclusive track among [railLines].
     *
     * Returns every source point accounted for: matched points as their projection, unmatched ones as
     * themselves unless a same-line walk covers them. An empty [railLines], or one holding only
     * street-running track, yields the input path unchanged with `coverage == 0.0` — so a caller that
     * gates on coverage never mistakes "nothing to snap to" for "snapped".
     */
    fun snap(
        path: List<LonLat>,
        railLines: List<RailMatcher.RailLine>,
        toleranceM: Double = RailMatcher.DEFAULT_TOLERANCE_M,
    ): SnapResult {
        if (path.isEmpty()) return SnapResult.NONE
        val candidates = railLines.filter { RailMatcher.isExclusiveTrack(it.kind) && it.points.size >= 2 }
        if (candidates.isEmpty()) return unsnapped(path)

        val cumulatives = candidates.map { cumulativeMeters(it.points) }
        val landings = arrayOfNulls<Landing>(path.size)
        for (i in path.indices) {
            landings[i] = landingFor(path[i], candidates, cumulatives, toleranceM)
        }

        val matched = landings.count { it != null }
        if (matched == 0) return unsnapped(path)

        // Nearest matched index at or before / at or after each position — the bracket test that
        // decides whether an unmatched point is covered by a track walk or has to be kept raw.
        val previousMatched = IntArray(path.size)
        var seen = -1
        for (i in path.indices) {
            if (landings[i] != null) seen = i
            previousMatched[i] = seen
        }
        val nextMatched = IntArray(path.size)
        seen = -1
        for (i in path.indices.reversed()) {
            if (landings[i] != null) seen = i
            nextMatched[i] = seen
        }

        val out = ArrayList<SnappedPoint>(path.size)
        var lastMatched = -1
        for (i in path.indices) {
            val landing = landings[i]
            if (landing == null) {
                val before = previousMatched[i]
                val after = nextMatched[i]
                val spannedByTrack = before >= 0 && after >= 0 &&
                    landings[before]!!.lineIndex == landings[after]!!.lineIndex
                if (!spannedByTrack) out += SnappedPoint(path[i], i.toDouble(), snapped = false)
                continue
            }
            if (lastMatched >= 0) {
                val from = landings[lastMatched]!!
                if (from.lineIndex == landing.lineIndex) {
                    appendTrackWalk(
                        out = out,
                        line = candidates[landing.lineIndex].points,
                        cumulative = cumulatives[landing.lineIndex],
                        from = from,
                        to = landing,
                        fromIndex = lastMatched,
                        toIndex = i,
                    )
                }
            }
            out += SnappedPoint(landing.point, i.toDouble(), snapped = true)
            lastMatched = i
        }

        return SnapResult(points = out, matchedPointCount = matched, totalPointCount = path.size)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private data class Landing(val lineIndex: Int, val alongM: Double, val point: LonLat)

    /** The input path, untouched — "we could not correct this", never confused with "corrected". */
    private fun unsnapped(path: List<LonLat>) = SnapResult(
        points = path.mapIndexed { i, p -> SnappedPoint(p, i.toDouble(), snapped = false) },
        matchedPointCount = 0,
        totalPointCount = path.size,
    )

    private fun landingFor(
        p: LonLat,
        candidates: List<RailMatcher.RailLine>,
        cumulatives: List<DoubleArray>,
        toleranceM: Double,
    ): Landing? {
        // A per-point box keeps the inner loop honest: a long line's overall bounds can intersect the
        // whole path while the line itself is far from this particular point (the [RailMatcher] note).
        val pointBounds = GeoBounds(p.lon, p.lat, p.lon, p.lat).expandedBy(toleranceM)
        var best: Landing? = null
        var bestDistance = toleranceM
        for (k in candidates.indices) {
            val line = candidates[k]
            if (!line.bounds.intersects(pointBounds)) continue
            val projection = projectOntoPolyline(p, line.points, cumulatives[k]) ?: continue
            if (projection.distanceM <= bestDistance) {
                bestDistance = projection.distanceM
                best = Landing(k, projection.alongM, projection.point)
            }
        }
        return best
    }

    /**
     * Emit the line's own vertices strictly between two landings, in travel order.
     *
     * ⚠ Travel order is *not* vertex order: a line's points run in whatever direction the tile
     * encoder emitted them, and a southbound train on a northbound-digitised line must still be drawn
     * southbound. The along-distances decide it.
     */
    private fun appendTrackWalk(
        out: MutableList<SnappedPoint>,
        line: List<LonLat>,
        cumulative: DoubleArray,
        from: Landing,
        to: Landing,
        fromIndex: Int,
        toIndex: Int,
    ) {
        val span = to.alongM - from.alongM
        if (span == 0.0) return
        val indices = if (span > 0) line.indices else line.indices.reversed()
        for (v in indices) {
            val along = cumulative[v]
            if (span > 0 && (along <= from.alongM || along >= to.alongM)) continue
            if (span < 0 && (along >= from.alongM || along <= to.alongM)) continue
            val fraction = (along - from.alongM) / span
            val position = fromIndex + (toIndex - fromIndex) * fraction
            val vertex = line[v]
            // A vertex that coincides with the landing we just emitted adds nothing but a duplicate.
            if (out.isNotEmpty() && distanceMeters(out.last().point, vertex) < DUPLICATE_EPSILON_M) continue
            out += SnappedPoint(vertex, position, snapped = true)
        }
    }

    /** Two consecutive output points closer than this are the same place; the second is dropped. */
    private const val DUPLICATE_EPSILON_M = 0.5
}
