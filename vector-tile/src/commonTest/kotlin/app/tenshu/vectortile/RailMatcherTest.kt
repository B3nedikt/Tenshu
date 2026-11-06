package app.tenshu.vectortile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matcher unit coverage over synthetic geometry — the rules stated exactly, in isolation. The
 * complement is `:ui-map`'s `RailMatchRealArchiveTest`, which runs the same code against real
 * Firenze rail and real Firenze roads; these pin the contract, that one pins reality.
 *
 * Coordinates sit near 52°N where ~1e-5° of latitude ≈ 1.1 m, so offsets read as metres.
 */
class RailMatcherTest {

    private val baseLat = 52.0
    private val baseLon = 13.0

    /** A straight north-running line of [n] points spaced ~11 m apart. */
    private fun line(n: Int, lonOffsetDeg: Double = 0.0): List<LonLat> =
        (0 until n).map { LonLat(lon = baseLon + lonOffsetDeg, lat = baseLat + it * 0.0001) }

    private fun railLine(kind: String, points: List<LonLat>) =
        RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)

    private fun layerOf(vararg features: MvtFeature) =
        MvtLayer(name = "transit", version = 2, extent = 4096, features = features.toList())

    private fun lineFeature(vararg tags: Pair<String, String>) = MvtFeature(
        id = 1,
        type = MvtGeometryType.LINESTRING,
        tags = tags.associate { it.first to MvtValue.Str(it.second) },
        geometry = listOf(listOf(MvtPoint(0, 0), MvtPoint(100, 100))),
    )

    // ── tag rules (§13.2a) ───────────────────────────────────────────────────

    @Test
    fun keepsRunningTrackAndDropsServiceAndAeroway() {
        val layer = layerOf(
            lineFeature("railway" to "rail"),
            lineFeature("railway" to "tram"),
            lineFeature("railway" to "rail", "service" to "yard"),      // depot track
            lineFeature("railway" to "rail", "service" to "siding"),
            lineFeature("railway" to "rail", "aeroway" to "runway"),    // airport ground
            lineFeature("railway" to "proposed"),                        // not a running line
            lineFeature("highway" to "primary"),                         // not rail at all
        )
        val kinds = RailMatcher.railLinesFrom(layer, z = 14, tileX = 0, tileY = 0).map { it.kind }
        assertEquals(listOf("rail", "tram"), kinds)
    }

    @Test
    fun readsRailFromTheProductionBasemapRoadsSchema() {
        // The production DACH tiles carry rail in the `roads` layer as `kind=rail` with the subtype in
        // `kind_detail` — a `transit`+`railway` reader found nothing for them (the reported bug).
        val layer = layerOf(
            lineFeature("kind" to "rail", "kind_detail" to "rail"),        // heavy rail
            lineFeature("kind" to "rail", "kind_detail" to "subway"),      // exclusive
            lineFeature("kind" to "rail", "kind_detail" to "tram"),        // street-running
            lineFeature("kind" to "rail", "kind_detail" to "funicular"),   // unknown subtype → heavy-rail fallback
            lineFeature("kind" to "rail", "kind_detail" to "rail", "service" to "siding"), // depot → dropped
            lineFeature("kind" to "major_road"),                            // a road → not rail
            lineFeature("kind" to "path"),                                  // a path → not rail
        )
        val kinds = RailMatcher.railLinesFrom(layer, z = 14, tileX = 0, tileY = 0).map { it.kind }
        assertEquals(listOf("rail", "subway", "tram", "rail"), kinds)
        // And the tram-vs-heavy-rail split still holds over the basemap schema.
        assertTrue(!RailMatcher.isExclusiveTrack("tram"))
        assertTrue(RailMatcher.isExclusiveTrack("subway"))
    }

    @Test
    fun theRailwayTagWinsWhenAFeatureCarriesBothSchemas() {
        // A demo-schema feature that also happens to carry a kind tag resolves via `railway`.
        val layer = layerOf(lineFeature("railway" to "tram", "kind" to "rail", "kind_detail" to "rail"))
        assertEquals(listOf("tram"), RailMatcher.railLinesFrom(layer, z = 14, tileX = 0, tileY = 0).map { it.kind })
    }

    @Test
    fun exclusiveTrackIsTheTrackACarCannotOccupy() {
        assertTrue(RailMatcher.isExclusiveTrack("rail"))
        assertTrue(RailMatcher.isExclusiveTrack("subway"))
        assertTrue(RailMatcher.isExclusiveTrack("narrow_gauge"))
        // Street-running: a car on these is genuinely on the geometry.
        assertTrue(!RailMatcher.isExclusiveTrack("tram"))
        assertTrue(!RailMatcher.isExclusiveTrack("light_rail"))
        assertTrue(!RailMatcher.isExclusiveTrack("nonsense"))
    }

    // ── coverage ─────────────────────────────────────────────────────────────

    @Test
    fun aPathOnTheTrackHasFullCoverage() {
        val track = line(20)
        val m = RailMatcher.match(track, listOf(railLine("rail", track)))
        assertEquals(1.0, m.coverage)
        assertEquals(1.0, m.exclusiveCoverage)
        assertTrue(m.isOnExclusiveRail)
        assertEquals(setOf("rail"), m.matchedKinds)
    }

    @Test
    fun aPathBeyondToleranceHasNoCoverage() {
        // ~0.01° of longitude at 52°N ≈ 685 m — far outside the 50 m default.
        val m = RailMatcher.match(line(20, lonOffsetDeg = 0.01), listOf(railLine("rail", line(20))))
        assertEquals(0.0, m.coverage)
        assertTrue(!m.isOnAnyRail)
    }

    @Test
    fun toleranceIsRespectedAtTheBoundary() {
        val track = line(20)
        // ~0.0004° lon at 52°N ≈ 27 m: inside 50 m, outside 10 m.
        val near = line(20, lonOffsetDeg = 0.0004)
        assertTrue(RailMatcher.match(near, listOf(railLine("rail", track)), toleranceM = 50.0).isOnExclusiveRail)
        assertTrue(!RailMatcher.match(near, listOf(railLine("rail", track)), toleranceM = 10.0).isOnAnyRail)
    }

    @Test
    fun partialCoverageIsReportedRatherThanRoundedToAVerdict() {
        // Half the path on track, half far away — the number a caller thresholds on.
        val track = line(10)
        val path = line(10) + line(10, lonOffsetDeg = 0.01)
        val m = RailMatcher.match(path, listOf(railLine("rail", track)))
        assertEquals(0.5, m.coverage)
        assertTrue(!m.isOnExclusiveRail, "half-covered must not read as on-rail at the default bar")
    }

    // ── the tram hazard (the reason the verdict is split) ────────────────────

    @Test
    fun tramCoverageDoesNotCountAsExclusive() {
        val track = line(20)
        val m = RailMatcher.match(track, listOf(railLine("tram", track)))
        assertEquals(1.0, m.coverage)
        assertEquals(0.0, m.exclusiveCoverage, "a car on a tram street must not read as exclusive rail")
        assertTrue(m.isOnAnyRail)
        assertTrue(!m.isOnExclusiveRail)
    }

    @Test
    fun heavyRailWinsOverAnAdjacentTram() {
        // Both in range of the path. Scanning order must not decide the verdict — otherwise a tram
        // running beside heavy rail would mask the stronger signal.
        val path = line(20)
        val tramFirst = listOf(railLine("tram", line(20, lonOffsetDeg = 0.0002)), railLine("rail", path))
        val m = RailMatcher.match(path, tramFirst)
        assertEquals(1.0, m.exclusiveCoverage)
        assertEquals(setOf("rail"), m.matchedKinds)
    }

    // ── T2′ stop-on-rail ─────────────────────────────────────────────────────

    @Test
    fun stopsOnRailCountsOnlyStopsOnTrack() {
        val track = line(20)
        val stops = listOf(track[5], LonLat(baseLon + 0.05, baseLat), track[15])
        assertEquals(2, RailMatcher.stopsOnRail(stops, listOf(railLine("rail", track))))
        assertEquals(0, RailMatcher.stopsOnRail(emptyList(), listOf(railLine("rail", track))))
        assertEquals(0, RailMatcher.stopsOnRail(stops, emptyList()))
    }

    // ── degenerate input ─────────────────────────────────────────────────────

    @Test
    fun emptyInputsYieldNoMatch() {
        assertEquals(RailMatcher.RailMatch.NONE, RailMatcher.match(emptyList(), listOf(railLine("rail", line(5)))))
        assertEquals(RailMatcher.RailMatch.NONE, RailMatcher.match(line(5), emptyList()))
    }

    @Test
    fun aPathWithNoNearbyCandidatesReportsZeroWithoutScanning() {
        val far = listOf(railLine("rail", (0 until 5).map { LonLat(lon = -100.0, lat = -40.0 + it * 0.001) }))
        val m = RailMatcher.match(line(10), far)
        assertEquals(0.0, m.coverage)
        assertEquals(0, m.candidateLineCount, "the bounding-box prefilter should reject everything")
        assertEquals(10, m.totalPointCount)
    }

    // ── metres on exclusive track (§39) ──────────────────────────────────────

    @Test
    fun fullExclusiveCoverageAttributesTheWholePathLength() {
        // ⚠⚠ The invariant that makes exclusiveMatchedMeters a length rather than an approximation:
        // with every point matched, the half-segment weights must sum to the path's own length —
        // no more (double-counting interior segments) and no less (dropping the ends).
        val track = line(21)
        val m = RailMatcher.match(track, listOf(railLine("rail", track)))
        val pathLength = track.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        assertEquals(1.0, m.exclusiveCoverage)
        assertTrue(
            kotlin.math.abs(m.exclusiveMatchedMeters - pathLength) < 0.001,
            "expected ${'$'}pathLength m, got ${'$'}{m.exclusiveMatchedMeters}",
        )
    }

    @Test
    fun aPartialMatchReportsTheMatchedLengthNotTheWholePath() {
        // Track under the first 11 of 21 points ⇒ the reported length is the half-segment weight of
        // those points, which is strictly less than half the path (point 0 carries only its inner half).
        val track = line(21)
        val m = RailMatcher.match(track, listOf(railLine("rail", track.take(11))))
        val pathLength = track.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        assertTrue(m.exclusiveMatchedMeters < pathLength, "a partial match cannot claim the whole path")
        assertTrue(m.exclusiveMatchedMeters > pathLength / 3, "…nor lose most of what it did match")
    }

    @Test
    fun tramTrackContributesNoExclusiveMetres() {
        // The kind filter applies to the length exactly as it does to the fraction — otherwise a long
        // enough drive down a tram street would buy a promotion the coverage rule refuses.
        val track = line(21)
        val m = RailMatcher.match(track, listOf(railLine("tram", track)))
        assertEquals(1.0, m.coverage)
        assertEquals(0.0, m.exclusiveCoverage)
        assertEquals(0.0, m.exclusiveMatchedMeters)
    }
}
