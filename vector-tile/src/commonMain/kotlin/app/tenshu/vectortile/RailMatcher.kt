package app.tenshu.vectortile

/**
 * **T1 — rail map-matching** (`docs/proposals/timeline-movement-capture.md` §13.3): does this path
 * run along railway track?
 *
 * The decisive tier. T0 infers rail from the *shape* of a movement and is deliberately conservative
 * because a false "Train" is worse than a missed one; matching against the actual rail geometry we
 * already ship removes the inference entirely — a trace that follows a railway *is* on a railway.
 *
 * ### Why the tag rules are load-bearing
 *
 * The §13.2a survey of real tiles found the `transit` layer carries more than running track:
 *
 *  - `service=yard` / `siding` / `crossover` — **388 features in one small city extract.** These are
 *    depot and manoeuvring track. A car park beside a depot, or a road crossing a yard, would match
 *    them and produce a "Train" for a walk across a rail bridge. Excluded.
 *  - `aeroway=*` — runways and taxiways share this layer. A drive along an airport perimeter road
 *    should not become a train. Excluded.
 *  - `railway=rail`/`tram`/`light_rail`/`subway`/`narrow_gauge` are the running lines we accept.
 *    Trams are included deliberately — T0 structurally cannot catch them (they cruise below its
 *    speed conjunct and follow streets), so map-matching is the only tier that can. ⚠ But that is
 *    also why the verdict is split in two: see [RailMatch.exclusiveCoverage].
 *
 * ### The verdict is coverage, not proximity
 *
 * A single point near a railway means nothing — cities are full of them. What distinguishes a train
 * journey is that *most of the path* stays within GPS error of the same kind of track. So the output
 * is a **coverage fraction**, and the caller applies a threshold. Reporting the fraction rather than
 * a boolean is the same "store the inputs, not just the verdict" rule §12.1 established: a later
 * tuning pass can move the threshold without re-running the expensive matching.
 *
 * Pure `commonMain`. **No I/O** — the caller supplies already-decoded features, because tile fetching
 * is async and goes through Control's proxy (the Timeline app has no `INTERNET`), while the capture
 * classifier is synchronous. That mismatch is why T1 runs as a later re-classification pass over
 * stored traces rather than inside `classifyMode`, and is exactly what `classifierVersion` enables.
 */
object RailMatcher {

    /** Running-line values we accept as rail. */
    private val RUNNING_RAILWAY = setOf("rail", "tram", "light_rail", "subway", "narrow_gauge")

    /** Heavy-rail sentinel — the basemap `roads` layer's `kind` value for any railway, and our fallback subtype. */
    private const val RAIL_KIND = "rail"

    /**
     * Track that **shares street space with road traffic**.
     *
     * ⚠ Found empirically, not assumed: matching real Firenze road geometry against real Firenze
     * rail, **10 of 240 road traces matched track completely** and 72 had some proximity — because
     * `Linea T1/T2/T3` run down the middle of streets. A car on a tram street is genuinely on tram
     * geometry, so tram coverage alone can never establish "this was a train".
     *
     * Heavy rail is different in kind: a road vehicle physically cannot follow it for any distance.
     * Hence the two coverage figures in [RailMatch] — collapsing them into one number is what would
     * turn a drive through town into a train journey.
     */
    private val STREET_RUNNING = setOf("tram", "light_rail")

    /** How far a trace point may sit from track and still count as on it (metres). */
    const val DEFAULT_TOLERANCE_M = 50.0

    /** Below this coverage the path is not meaningfully following rail. */
    const val DEFAULT_MIN_COVERAGE = 0.8

    /**
     * The outcome of matching a path against rail geometry.
     *
     * [coverage] is the fraction of path points within tolerance of accepted track — the number a
     * threshold is applied to. [matchedKinds] records *which* `railway` values were hit, so a caller
     * can tell a tram from heavy rail (the distinction T0 cannot make and imports get for free).
     */
    data class RailMatch(
        val coverage: Double,
        /**
         * Coverage against track a road vehicle **cannot** occupy — heavy rail, subway, narrow gauge.
         *
         * This is the figure a promotion should rest on. [coverage] includes street-running tram
         * track, which a car on a tram street matches perfectly well (see [STREET_RUNNING]).
         */
        val exclusiveCoverage: Double,
        /**
         * How much of the path, **in metres**, lay on track a road vehicle cannot occupy.
         *
         * ⚠⚠ Not derivable from [exclusiveCoverage] by the caller, and that is the point.
         * [exclusiveCoverage] is a fraction of *points*, and its denominator counts every point the
         * trace happens to contain — including the coarse ones displaced off the path and the
         * on-foot approach at either end, neither of which can ever match. So the fraction falls as
         * the fixes get worse while the journey stays the same: measured over ten runs of one
         * 40 km+ train, it ranged 0.39–0.67 with a standard deviation of 0.08
         * (`timeline-movement-capture.md` §39). This figure does not move that way — it says how far
         * the vehicle was demonstrably *on* rail, which is a claim about the world.
         *
         * Each matched point is weighted by half the segment on either side of it, so summing over
         * a subset yields metres of path; the weights over *all* points sum to the path's length.
         */
        val exclusiveMatchedMeters: Double,
        val matchedPointCount: Int,
        val totalPointCount: Int,
        val matchedKinds: Set<String>,
        /** Reference lines that survived tag-filtering and the bounding-box prefilter. */
        val candidateLineCount: Int,
    ) {
        /**
         * On track a road vehicle cannot occupy — **the near-conclusive signal**, and the one a
         * caller should promote on unaided.
         */
        val isOnExclusiveRail: Boolean get() = exclusiveCoverage >= DEFAULT_MIN_COVERAGE

        /**
         * On *any* accepted track, tram included.
         *
         * ⚠ Not sufficient on its own: a car driving down a tram street satisfies this. Use it only
         * with corroboration — T0's shape signals, or [stopsOnRail] landing stops on the same track.
         */
        val isOnAnyRail: Boolean get() = coverage >= DEFAULT_MIN_COVERAGE

        companion object {
            val NONE = RailMatch(0.0, 0.0, 0.0, 0, 0, emptySet(), 0)
        }
    }

    /**
     * A rail line extracted from a `transit` layer: its projected geometry plus the `railway` value
     * that classified it. Produced by [railLinesFrom]; kept separate from [MvtFeature] so a caller
     * can cache projected geometry across several matches without re-projecting.
     */
    data class RailLine(
        val kind: String,
        val points: List<LonLat>,
        val bounds: GeoBounds,
    )

    /**
     * Extract running-line geometry from a decoded `transit` **or** `roads` layer, projected to
     * lon/lat.
     *
     * ⚠ Two tile schemas carry rail, and both are handled ([railKindOf]): the demo/Protomaps-`transit`
     * archive tags a line `railway=<value>`, while the **production basemap** puts rail in the `roads`
     * layer as `kind=rail` with the subtype in `kind_detail`. The de-Googled DACH regions we ship use
     * the latter — a `transit`-only reader found *nothing* for them, so a real train stayed `drive`
     * (`timeline-recognition-and-place-naming.md` §3). In both schemas, depot/manoeuvring track
     * (`service`=yard/siding/crossover) and airport ground (`aeroway`) are dropped.
     */
    fun railLinesFrom(
        layer: MvtLayer,
        z: Int,
        tileX: Long,
        tileY: Long,
    ): List<RailLine> {
        val lines = ArrayList<RailLine>()
        for (feature in layer.features) {
            if (feature.type != MvtGeometryType.LINESTRING) continue
            val kind = railKindOf(feature) ?: continue
            // Not track a train runs on: depot/manoeuvring track, and airport ground.
            if (feature.tag("service") != null) continue
            if (feature.tags.keys.any { it.equals("aeroway", ignoreCase = true) }) continue

            for (part in feature.geometry) {
                if (part.size < 2) continue
                val projected = part.map { layer.toLonLat(it, z, tileX, tileY) }
                val bounds = GeoBounds.of(projected) ?: continue
                lines += RailLine(kind = kind, points = projected, bounds = bounds)
            }
        }
        return lines
    }

    /**
     * The running-line kind for a feature, from either tile schema, or `null` if it is not accepted
     * running track.
     *
     * - **demo `transit` schema:** `railway=<value>` — accepted only when the value is in
     *   [RUNNING_RAILWAY] (drops `railway=platform`/`abandoned`/…).
     * - **production basemap `roads` schema:** `kind=rail`, with the subtype in `kind_detail`
     *   (`rail`/`tram`/`subway`/`light_rail`/…). A rail feature whose `kind_detail` we don't recognise
     *   (funicular, monorail, or missing) is still exclusive track a road vehicle cannot occupy, so it
     *   is kept as heavy [RAIL_KIND] rather than dropped.
     */
    private fun railKindOf(feature: MvtFeature): String? {
        feature.tag("railway")?.let { return it.takeIf { v -> v in RUNNING_RAILWAY } }
        if (feature.tag("kind") == RAIL_KIND) {
            val detail = feature.tag("kind_detail")
            return if (detail != null && detail in RUNNING_RAILWAY) detail else RAIL_KIND
        }
        return null
    }

    /**
     * Match [path] against [railLines].
     *
     * Each path point is tested against every candidate line that could plausibly be near it — a
     * bounding-box prefilter, expanded by the tolerance, removes the rest before any distance work.
     * Without it a 500-point trace against a few thousand lines is millions of segment computations
     * for a result dominated by geometry nowhere near the path.
     */
    fun match(
        path: List<LonLat>,
        railLines: List<RailLine>,
        toleranceM: Double = DEFAULT_TOLERANCE_M,
    ): RailMatch {
        if (path.isEmpty() || railLines.isEmpty()) return RailMatch.NONE

        val pathBounds = GeoBounds.of(path)?.expandedBy(toleranceM) ?: return RailMatch.NONE
        val candidates = railLines.filter { it.bounds.intersects(pathBounds) }
        if (candidates.isEmpty()) {
            return RailMatch(0.0, 0.0, 0.0, 0, path.size, emptySet(), 0)
        }

        var matched = 0
        var exclusiveMatched = 0
        var exclusiveMeters = 0.0
        val kinds = LinkedHashSet<String>()
        for ((index, point) in path.withIndex()) {
            // A per-point box keeps the inner loop honest: a long rail line's overall bounds can
            // intersect the whole path while the line itself is far from this particular point.
            val pointBounds = GeoBounds(point.lon, point.lat, point.lon, point.lat).expandedBy(toleranceM)
            // Prefer exclusive track when both are in range: a tram line running beside heavy rail
            // must not mask the stronger signal just by being scanned first.
            var hitKind: String? = null
            for (line in candidates) {
                if (!line.bounds.intersects(pointBounds)) continue
                val d = pointToPolylineMeters(point, line.points) ?: continue
                if (d > toleranceM) continue
                if (line.kind !in STREET_RUNNING) { hitKind = line.kind; break }
                if (hitKind == null) hitKind = line.kind // remember the tram, keep looking for rail
            }
            if (hitKind != null) {
                matched++
                kinds += hitKind
                if (hitKind !in STREET_RUNNING) {
                    exclusiveMatched++
                    exclusiveMeters += path.spanAround(index)
                }
            }
        }

        return RailMatch(
            coverage = matched.toDouble() / path.size,
            exclusiveCoverage = exclusiveMatched.toDouble() / path.size,
            exclusiveMatchedMeters = exclusiveMeters,
            matchedPointCount = matched,
            totalPointCount = path.size,
            matchedKinds = kinds,
            candidateLineCount = candidates.size,
        )
    }

    /**
     * **T2′ — stop-on-rail** (§13.2a). Given the path's stop positions, how many fell on track?
     *
     * This replaces the original T2 ("move endpoints near a `railway=station` POI"), which the
     * demo-archive survey read as unimplementable. A full stop *while on rail* is the same signal
     * without needing a POI layer, and without depending on POI coverage quality, which varies
     * enormously by region — so it stays the rule here.
     *
     * ⚠⚠ **The stated reason was wrong and is corrected.** "The basemap emits no station POIs at all"
     * was true of the *Firenze demo* archive and false of the deployed cut: `de-bayern`'s `pois` layer
     * carries named `station`/`halt`/`stop`/`tram_stop`/`platform` (see [StopTileReader], which now
     * reads them). T2′ is kept on its own merits, not because the POIs are missing.
     *
     * Kept separate from [match] because stops are detected upstream (T0 already finds them) and a
     * caller may have none — a non-stop express is still a train.
     */
    fun stopsOnRail(
        stops: List<LonLat>,
        railLines: List<RailLine>,
        toleranceM: Double = DEFAULT_TOLERANCE_M,
    ): Int = if (stops.isEmpty() || railLines.isEmpty()) 0 else match(stops, railLines, toleranceM).matchedPointCount

    /** Convenience: is [kind] track a road vehicle cannot occupy? */
    fun isExclusiveTrack(kind: String): Boolean = kind in RUNNING_RAILWAY && kind !in STREET_RUNNING

    /**
     * The length of path the point at [index] stands for — half the segment on each side.
     *
     * ⚠ The two ends carry only their inner half, which is what makes the weights over the whole
     * path sum to its length exactly rather than over-counting it. Attributing a *whole* segment to
     * each matched point would double-count every interior one.
     */
    private fun List<LonLat>.spanAround(index: Int): Double {
        var span = 0.0
        if (index > 0) span += distanceMeters(this[index - 1], this[index]) / 2.0
        if (index < lastIndex) span += distanceMeters(this[index], this[index + 1]) / 2.0
        return span
    }
}
