package app.tenshu.vectortile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **S4a — rejoining tile-clipped rail fragments** (`timeline-movement-capture.md` §20).
 *
 * ⚠ The negative cases here carry the design: a junction must NOT chain, because a tile boundary and
 * a switch are indistinguishable from geometry alone, and guessing the branch would draw a route the
 * train never took.
 */
class RailLineStitcherTest {

    private fun line(kind: String, vararg lonLat: Pair<Double, Double>): RailMatcher.RailLine {
        val points = lonLat.map { LonLat(lon = it.first, lat = it.second) }
        return RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)
    }

    @Test
    fun twoFragmentsOfOneRailwayBecomeOneLine() {
        val west = line("rail", 11.00 to 48.0, 11.01 to 48.0)
        val east = line("rail", 11.01 to 48.0, 11.02 to 48.0)

        val stitched = RailLineStitcher.stitch(listOf(west, east))

        assertEquals(1, stitched.size)
        assertEquals(3, stitched.single().points.size, "the shared boundary point must not be doubled")
        assertEquals(11.00, stitched.single().points.first().lon, 1e-9)
        assertEquals(11.02, stitched.single().points.last().lon, 1e-9)
    }

    @Test
    fun aFragmentIsReversedWhenThatIsHowItJoins() {
        // ⚠ A tile encoder digitises each fragment whichever way it likes, so the touching end is as
        // often a `last` as a `first`. Here the eastern fragment runs east→west.
        val west = line("rail", 11.00 to 48.0, 11.01 to 48.0)
        val eastReversed = line("rail", 11.02 to 48.0, 11.01 to 48.0)

        val stitched = RailLineStitcher.stitch(listOf(west, eastReversed))

        assertEquals(1, stitched.size)
        val points = stitched.single().points
        assertEquals(3, points.size)
        // Monotonic west→east ⇒ the reversal happened.
        assertTrue(points.zipWithNext().all { (a, b) -> b.lon > a.lon }, "not ordered: ${points.map { it.lon }}")
    }

    @Test
    fun aThreeWayJunctionIsLeftAlone() {
        // ⚠⚠ The load-bearing negative. Three ends meet: a switch. Chaining any two would invent a
        // route through it — exactly what §16.1's "no routing, ever" forbids.
        val trunk = line("rail", 11.00 to 48.0, 11.01 to 48.0)
        val branchA = line("rail", 11.01 to 48.0, 11.02 to 48.01)
        val branchB = line("rail", 11.01 to 48.0, 11.02 to 47.99)

        val stitched = RailLineStitcher.stitch(listOf(trunk, branchA, branchB))

        assertEquals(3, stitched.size, "a junction must not be chained")
        // Precondition twin: the SAME trunk with only ONE branch does join — so this test cannot pass
        // by the stitcher simply never joining anything.
        assertEquals(1, RailLineStitcher.stitch(listOf(trunk, branchA)).size)
    }

    @Test
    fun fragmentsOfDifferentKindsAreNotJoined() {
        // A subway line ending where a tram line begins is two railways, not one.
        val subway = line("subway", 11.00 to 48.0, 11.01 to 48.0)
        val tram = line("tram", 11.01 to 48.0, 11.02 to 48.0)

        assertEquals(2, RailLineStitcher.stitch(listOf(subway, tram)).size)
        // Twin: same geometry, same kind ⇒ joins.
        assertEquals(1, RailLineStitcher.stitch(listOf(subway, subway.copy(
            points = listOf(LonLat(11.01, 48.0), LonLat(11.02, 48.0)),
            bounds = GeoBounds.of(listOf(LonLat(11.01, 48.0), LonLat(11.02, 48.0)))!!,
        ))).size)
    }

    @Test
    fun aChainOfFourFragmentsCollapsesToOne() {
        // The realistic case: one railway crossing four z14 tiles, handed to us out of order.
        val segments = (0..3).map { i ->
            line("rail", (11.00 + i * 0.01) to 48.0, (11.01 + i * 0.01) to 48.0)
        }.shuffled(kotlin.random.Random(7))

        val stitched = RailLineStitcher.stitch(segments)

        assertEquals(1, stitched.size)
        assertEquals(5, stitched.single().points.size)
    }

    @Test
    fun everyInputPointSurvivesAndNothingIsDuplicated() {
        // ⚠ Stitching must be lossless: it re-groups points, it never drops or invents them.
        val a = line("rail", 11.00 to 48.0, 11.01 to 48.0, 11.02 to 48.0)
        val isolated = line("rail", 12.00 to 49.0, 12.01 to 49.0)
        val b = line("rail", 11.02 to 48.0, 11.03 to 48.0)

        val stitched = RailLineStitcher.stitch(listOf(a, isolated, b))

        assertEquals(2, stitched.size, "the isolated line stays its own")
        val total = stitched.sumOf { it.points.size }
        // 3 + 2 joined minus the one shared point, plus the isolated 2.
        assertEquals(4 + 2, total)
    }

    @Test
    fun aSingleLineIsReturnedUntouched() {
        val only = line("rail", 11.0 to 48.0, 11.1 to 48.0)
        assertEquals(listOf(only), RailLineStitcher.stitch(listOf(only)))
    }
}
