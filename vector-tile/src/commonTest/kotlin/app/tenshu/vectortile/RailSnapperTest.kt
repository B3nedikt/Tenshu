package app.tenshu.vectortile

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Snapper unit coverage over synthetic geometry — the geometry contract stated exactly, in isolation.
 * The policy half (the coverage gate, timestamps, the store round-trip) lives in
 * `:timeline-shared-data`'s `RailRouteSnapTest` / `RailRefinementSweepTest`.
 *
 * Coordinates sit near 52°N where ~1e-5° of latitude ≈ 1.1 m, so offsets read as metres.
 */
class RailSnapperTest {

    private val baseLat = 52.0
    private val baseLon = 13.0

    /** A straight north-running line of [n] points ~11 m apart, offset east by [lonOffsetDeg]. */
    private fun northLine(n: Int, lonOffsetDeg: Double = 0.0): List<LonLat> =
        (0 until n).map { LonLat(lon = baseLon + lonOffsetDeg, lat = baseLat + it * 0.0001) }

    private fun railLine(kind: String, points: List<LonLat>) =
        RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)

    /** Metres east of the base meridian — the axis every off-track offset here is expressed on. */
    private fun eastingM(p: LonLat): Double = (p.lon - baseLon) * metresPerDegreeLon(p.lat)

    // ── projection ───────────────────────────────────────────────────────────

    @Test
    fun aPathBesideTheTrackIsPulledOntoIt() {
        val track = northLine(20)
        // ~22 m east of the track: well inside the 50 m tolerance, and unmistakably off it.
        val path = northLine(20, lonOffsetDeg = 0.0003)
        assertTrue(path.all { abs(eastingM(it) - 22.0) < 3.0 }, "precondition: the input really is ~22 m off")

        val result = RailSnapper.snap(path, listOf(railLine("rail", track)))

        assertEquals(20, result.matchedPointCount)
        assertEquals(1.0, result.coverage)
        // Every output point now sits on the track's meridian, not on the path's.
        assertTrue(
            result.points.all { abs(eastingM(it.point)) < 1.0 },
            "still off track: ${result.points.map { eastingM(it.point) }.take(3)}",
        )
    }

    @Test
    fun aPathFarFromTheTrackIsLeftAlone() {
        val track = northLine(20)
        // ~1.4 km east — beyond any tolerance.
        val path = northLine(20, lonOffsetDeg = 0.02)

        val result = RailSnapper.snap(path, listOf(railLine("rail", track)))

        assertEquals(0, result.matchedPointCount)
        assertEquals(0.0, result.coverage)
        assertEquals(path, result.points.map { it.point })
        assertTrue(result.points.none { it.snapped })
    }

    // ── ⚠⚠ street-running track is structurally excluded ─────────────────────

    @Test
    fun tramTrackIsNeverASnappingCandidate() {
        val track = northLine(20)
        val path = northLine(20, lonOffsetDeg = 0.0003)
        // Precondition: this very geometry snaps perfectly when it is tagged as heavy rail — so a
        // zero result below is the *kind* rule firing, not the fixture failing to reach the matcher.
        assertEquals(20, RailSnapper.snap(path, listOf(railLine("rail", track))).matchedPointCount)

        for (kind in listOf("tram", "light_rail")) {
            val result = RailSnapper.snap(path, listOf(railLine(kind, track)))
            assertEquals(0, result.matchedPointCount, "$kind must not be a snapping candidate")
            assertEquals(path, result.points.map { it.point }, "$kind must leave the path untouched")
        }
    }

    @Test
    fun heavyRailBesideATramWinsTheLanding() {
        // A train arriving beside a tram: the tram is nearer, but only exclusive track may be used,
        // so the landing must be on the heavy rail — and never on the tram's meridian.
        val rail = northLine(20)
        val tram = northLine(20, lonOffsetDeg = 0.0004)
        val path = northLine(20, lonOffsetDeg = 0.00035) // nearer the tram than the rail

        val result = RailSnapper.snap(path, listOf(railLine("tram", tram), railLine("rail", rail)))

        assertEquals(20, result.matchedPointCount)
        assertTrue(result.points.all { abs(eastingM(it.point)) < 1.0 }, "landed on the tram, not the rail")
    }

    // ── walking the track between landings ───────────────────────────────────

    @Test
    fun theTrackBetweenTwoLandingsIsWalked() {
        // A tunnel: the track has 21 vertices, but GPS only saw the two ends.
        val track = northLine(21)
        val path = listOf(track.first(), track.last())

        val result = RailSnapper.snap(path, listOf(railLine("rail", track)))

        assertEquals(2, result.matchedPointCount, "only two source fixes matched")
        // The 19 interior track vertices are recovered between them.
        assertEquals(21, result.points.size)
        assertEquals(track, result.points.map { it.point })
        // Positions interpolate monotonically from source 0 to source 1.
        val positions = result.points.map { it.sourcePosition }
        assertEquals(0.0, positions.first())
        assertEquals(1.0, positions.last())
        assertEquals(positions, positions.sorted(), "positions must advance with the walk")
    }

    @Test
    fun theWalkFollowsTravelDirectionNotVertexOrder() {
        // ⚠ Tile encoders emit a line in whatever direction they like. A southbound journey on a
        // northbound-digitised line must still come out southbound.
        val track = northLine(21)
        val path = listOf(track.last(), track.first())

        val result = RailSnapper.snap(path, listOf(railLine("rail", track)))

        assertEquals(track.reversed(), result.points.map { it.point })
    }

    @Test
    fun aGapNotSpannedByOneLineKeepsItsRawFixes() {
        // Two disjoint lines with a real gap between them: the middle fix matches neither, and there
        // is no single line whose walk could span it — so it survives as itself rather than being
        // replaced by invented track.
        val south = northLine(5)
        val north = (0 until 5).map { LonLat(lon = baseLon, lat = baseLat + 0.01 + it * 0.0001) }
        val stray = LonLat(lon = baseLon + 0.02, lat = baseLat + 0.005) // ~1.4 km off both
        val path = listOf(south.first(), stray, north.last())

        val result = RailSnapper.snap(
            path,
            listOf(railLine("rail", south), railLine("rail", north)),
        )

        assertEquals(2, result.matchedPointCount)
        assertTrue(result.points.any { it.point == stray && !it.snapped }, "the unmatched fix was dropped")
    }

    @Test
    fun anUnmatchedFixBetweenTwoLandingsOnOneLineIsReplacedByTheTrack() {
        // The other half of the previous rule: when one line's walk *does* span the gap, the stray
        // fix (a tunnel-exit GPS spike) is the thing we have a better answer for.
        val track = northLine(21)
        val spike = LonLat(lon = baseLon + 0.02, lat = baseLat + 0.001) // ~1.4 km off, mid-journey
        val path = listOf(track.first(), spike, track.last())

        val result = RailSnapper.snap(path, listOf(railLine("rail", track)))

        assertEquals(2, result.matchedPointCount)
        assertTrue(result.points.none { it.point == spike }, "the spike survived into the drawn route")
        assertTrue(result.points.all { it.snapped })
    }

    // ── degenerate input ─────────────────────────────────────────────────────

    @Test
    fun noLinesMeansNoSnapAndNoLoss() {
        val path = northLine(5)
        val result = RailSnapper.snap(path, emptyList())
        assertEquals(0, result.matchedPointCount)
        assertEquals(5, result.totalPointCount)
        assertEquals(path, result.points.map { it.point })
    }

    @Test
    fun anEmptyPathIsNone() {
        assertEquals(0, RailSnapper.snap(emptyList(), listOf(railLine("rail", northLine(5)))).totalPointCount)
    }
}
