package app.tenshu.fit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartMathTest {

    @Test
    fun ringSweepClampsAndHandlesZeroGoal() {
        assertEquals(0.5f, ChartMath.ringSweep(15, 30))
        assertEquals(1f, ChartMath.ringSweep(45, 30))
        assertEquals(0f, ChartMath.ringSweep(10, 0))
    }

    @Test
    fun barHeightsNormalizeAgainstMaxOfValuesAndGoal() {
        // Goal above every bar: goal becomes the reference (bars stay under the line).
        val below = ChartMath.barHeights(listOf(10, 20), goal = 40)
        assertEquals(listOf(0.25f, 0.5f), below)
        // A bar above goal: the tallest bar becomes the reference.
        val above = ChartMath.barHeights(listOf(80, 20), goal = 40)
        assertEquals(listOf(1f, 0.25f), above)
        assertEquals(emptyList(), ChartMath.barHeights(emptyList(), goal = 30))
    }

    @Test
    fun barIndexAtMapsATouchToItsColumnAndSnapsOutsideTheBars() {
        // 7 columns, 10 px gaps, 760 px wide → 100 px columns at a 110 px pitch.
        val w = 760f
        assertEquals(0, ChartMath.barIndexAt(5f, w, 7, 10f))
        assertEquals(0, ChartMath.barIndexAt(104f, w, 7, 10f)) // in the gap after column 0
        assertEquals(1, ChartMath.barIndexAt(115f, w, 7, 10f))
        assertEquals(6, ChartMath.barIndexAt(755f, w, 7, 10f))
        assertEquals(0, ChartMath.barIndexAt(-40f, w, 7, 10f)) // dragged off the left edge
        assertEquals(6, ChartMath.barIndexAt(2_000f, w, 7, 10f)) // and off the right
        assertEquals(null, ChartMath.barIndexAt(10f, w, 0, 10f))
    }

    @Test
    fun fittedGapKeepsEveryColumnWideOnDenseRanges() {
        // A week keeps the preferred gap.
        assertEquals(10f, ChartMath.fittedGap(760f, 7, 10f))
        // A year at the preferred gap would need 364 × 10 px of gaps in 760 px: narrowed instead.
        val gap = ChartMath.fittedGap(760f, 365, 10f)
        assertTrue(gap < 10f)
        val column = (760f - gap * 364) / 365
        assertTrue(column > 0f, "column width $column")
        // And the hit-test round-trips at that gap, first and last bar included.
        listOf(0, 182, 364).forEach { i ->
            assertEquals(i, ChartMath.barIndexAt(ChartMath.barCenterX(i, 760f, 365, gap), 760f, 365, gap))
        }
        assertEquals(10f, ChartMath.fittedGap(760f, 1, 10f))
    }

    @Test
    fun rtlCountsBarsFromTheRightEdge() {
        // 7 columns, 10 px gaps, 760 px wide: in RTL, bar 0 sits at the right edge.
        val w = 760f
        assertEquals(6, ChartMath.barIndexAt(5f, w, 7, 10f, rtl = true))   // leftmost touch → last bar
        assertEquals(0, ChartMath.barIndexAt(755f, w, 7, 10f, rtl = true)) // rightmost touch → first bar
        assertEquals(710f, ChartMath.barCenterX(0, w, 7, 10f, rtl = true))
        assertEquals(50f, ChartMath.barCenterX(6, w, 7, 10f, rtl = true))
        (0 until 7).forEach { i ->
            assertEquals(i, ChartMath.barIndexAt(ChartMath.barCenterX(i, w, 7, 10f, rtl = true), w, 7, 10f, rtl = true))
        }
    }

    @Test
    fun barCenterXIsTheMiddleOfTheColumn() {
        assertEquals(50f, ChartMath.barCenterX(0, 760f, 7, 10f))
        assertEquals(710f, ChartMath.barCenterX(6, 760f, 7, 10f))
        // Every centre maps back to its own column.
        (0 until 7).forEach { i ->
            assertEquals(i, ChartMath.barIndexAt(ChartMath.barCenterX(i, 760f, 7, 10f), 760f, 7, 10f))
        }
    }

    @Test
    fun goalLineSharesTheBarReference() {
        assertEquals(0.5f, ChartMath.goalLineFraction(listOf(80, 20), goal = 40))
        assertEquals(1f, ChartMath.goalLineFraction(listOf(10, 20), goal = 40))
        assertEquals(0f, ChartMath.goalLineFraction(listOf(10), goal = 0))
    }

    @Test
    fun hrPolylineSpansTheSeriesWithHeadroom() {
        val series = listOf(HrPoint(0, 100), HrPoint(60, 150), HrPoint(120, 200))
        val points = ChartMath.hrPolyline(series, headroomBpm = 0)
        assertEquals(3, points.size)
        assertEquals(0f, points[0].x)
        assertEquals(1f, points[2].x)
        // Min bpm sits at the bottom (y=1), max at the top (y=0) — Canvas convention.
        assertEquals(1f, points[0].y)
        assertEquals(0f, points[2].y)
        // Average line lands mid-span.
        assertEquals(0.5f, ChartMath.hrAverageY(series, avgBpm = 150, headroomBpm = 0))
    }

    @Test
    fun fitRouteCentersAndPreservesAspect() {
        // A flat horizontal line: x spans fully (inset to inset), y centres at 0.5.
        val route = listOf(RoutePoint(0.2f, 0.5f), RoutePoint(0.6f, 0.5f))
        val fitted = ChartMath.fitRoute(route, inset = 0.1f)
        assertEquals(0.1f, fitted[0].x, absoluteTolerance = 1e-4f)
        assertEquals(0.9f, fitted[1].x, absoluteTolerance = 1e-4f)
        assertTrue(fitted.all { it.y in 0.45f..0.55f })
        assertEquals(emptyList(), ChartMath.fitRoute(emptyList()))
    }

    @Test
    fun windowAggregatesTakesTrailingRangeOldestFirst() {
        val days = (1..10).map { day ->
            DailyAggregate(
                date = kotlinx.datetime.LocalDate(2026, 5, day),
                steps = day * 1000,
                distanceM = 0,
                activeEnergyKj = 0,
                activeMinutes = day,
            )
        }
        val week = ChartMath.windowAggregates(days.shuffled(), ChartRange.Week)
        assertEquals(7, week.size)
        assertEquals(4, week.first().date.day)
        assertEquals(10, week.last().date.day)
        assertEquals(1, ChartMath.windowAggregates(days, ChartRange.Day).size)
        // Month clamps to what exists (the mock ships one week).
        assertEquals(10, ChartMath.windowAggregates(days, ChartRange.Month).size)
    }

    @Test
    fun minMaxPolylineScalesToTheValueRangeNotZero() {
        // Increasing values (oldest→newest) → decreasing y (y=0 is the top). The min sits below the
        // max, and neither pins to 0/1 because of the headroom padding — a 73→75 trend fills the plot.
        val pts = ChartMath.minMaxPolyline(listOf(73.0, 74.0, 75.0))
        assertEquals(3, pts.size)
        assertEquals(0f, pts.first().x)
        assertEquals(1f, pts.last().x)
        assertTrue(pts[0].y > pts[1].y && pts[1].y > pts[2].y, "rising values trend upward (smaller y)")
        assertTrue(pts.last().y > 0f && pts.first().y < 1f, "headroom keeps the extremes off the edges")
    }

    @Test
    fun minMaxPolylineHandlesFlatAndTooFewPoints() {
        assertEquals(emptyList(), ChartMath.minMaxPolyline(listOf(70.0))) // nothing to trend
        val flat = ChartMath.minMaxPolyline(listOf(72.0, 72.0, 72.0))
        assertTrue(flat.all { it.y == 0.5f }, "an all-equal series is a flat mid-line")
    }

    @Test
    fun dailyAverageRounds() {
        assertEquals(0, ChartMath.dailyAverage(emptyList()))
        assertEquals(8, ChartMath.dailyAverage(listOf(7, 8, 9)))
        // The mock week (fit-data.js): 54170 steps over 7 days.
        assertEquals(7739, ChartMath.dailyAverage(listOf(9120, 5240, 11840, 8360, 7010, 6480, 6120)))
    }
}
