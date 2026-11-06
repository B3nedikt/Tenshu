package app.tenshu.fit.model

/**
 * Pure chart geometry (Ph1, docs/proposals/fit-app.md D8). The composables in
 * `:fit-shared-ui` (`Ring`, `TrendBars`, `HrChart`, `RoutePath`) are thin Canvas
 * renderers over these functions, so the math is unit-testable without Compose.
 *
 * All outputs are normalized fractions (0..1); the renderer scales to pixels.
 */
object ChartMath {

    /** Goal-ring sweep fraction. Non-positive goals render an empty ring. */
    fun ringSweep(value: Int, goal: Int): Float {
        if (goal <= 0) return 0f
        return (value.toFloat() / goal).coerceIn(0f, 1f)
    }

    /**
     * Bar heights normalized against `max(values, goal)` so the goal line always fits
     * inside the plot. Empty input yields empty output; all-zero input yields zeros.
     */
    fun barHeights(values: List<Int>, goal: Int): List<Float> {
        if (values.isEmpty()) return emptyList()
        val reference = maxOf(values.max(), goal, 1)
        return values.map { (it.toFloat() / reference).coerceIn(0f, 1f) }
    }

    /** Goal-line position as a fraction of plot height, same reference as [barHeights]. */
    fun goalLineFraction(values: List<Int>, goal: Int): Float {
        if (goal <= 0) return 0f
        val reference = maxOf(values.maxOrNull() ?: 0, goal, 1)
        return (goal.toFloat() / reference).coerceIn(0f, 1f)
    }

    /**
     * Which of [count] equal-width bar columns, separated by [gap], sits under [x] in a
     * plot [width] wide — the touch → bar lookup behind the trend chart's callout. A touch
     * in a gap or past either edge snaps to the nearest column, so scrubbing never drops
     * the selection. Null only when there is nothing to select.
     *
     * [x] is a physical (left-origin) coordinate. With [rtl] the bars run right to left — the
     * first bar is at the right edge, as a mirrored `Row`/`HStack` lays it out.
     */
    fun barIndexAt(x: Float, width: Float, count: Int, gap: Float, rtl: Boolean = false): Int? {
        if (count <= 0 || width <= 0f) return null
        val pitch = (width + gap) / count
        val fromStart = if (rtl) width - x else x
        return (fromStart / pitch).toInt().coerceIn(0, count - 1)
    }

    /**
     * The gap to draw between [count] bars in a plot [width] wide: [preferred], narrowed for
     * dense ranges (Year = 365 bars) so the gaps never take more than half the width and every
     * column keeps a positive width. Layout and [barIndexAt] must both use this value.
     */
    fun fittedGap(width: Float, count: Int, preferred: Float): Float {
        if (count <= 1 || width <= 0f) return preferred
        return minOf(preferred, width / (2f * count))
    }

    /**
     * Physical (left-origin) horizontal centre of column [index] — where the callout's guide
     * line drops. With [rtl], column 0 is at the right edge, matching [barIndexAt].
     */
    fun barCenterX(index: Int, width: Float, count: Int, gap: Float, rtl: Boolean = false): Float {
        val column = (width - gap * (count - 1)) / count
        val fromStart = index * (column + gap) + column / 2f
        return if (rtl) width - fromStart else fromStart
    }

    /**
     * Heart-rate curve → normalized polyline points (x = elapsed fraction, y = 0 at the
     * TOP of the plot, Canvas convention). The bpm axis spans the series' own min/max
     * with [headroomBpm] of padding so the curve never kisses the plot edges.
     */
    fun hrPolyline(series: List<HrPoint>, headroomBpm: Int = 8): List<NormalizedPoint> {
        if (series.isEmpty()) return emptyList()
        val duration = series.last().t.coerceAtLeast(1)
        val lo = series.minOf { it.bpm } - headroomBpm
        val hi = series.maxOf { it.bpm } + headroomBpm
        val span = (hi - lo).coerceAtLeast(1)
        return series.map { point ->
            NormalizedPoint(
                x = (point.t.toFloat() / duration).coerceIn(0f, 1f),
                y = 1f - ((point.bpm - lo).toFloat() / span).coerceIn(0f, 1f),
            )
        }
    }

    /** Average-HR guide-line y for the same axis [hrPolyline] used. */
    fun hrAverageY(series: List<HrPoint>, avgBpm: Int, headroomBpm: Int = 8): Float {
        if (series.isEmpty()) return 0.5f
        val lo = series.minOf { it.bpm } - headroomBpm
        val hi = series.maxOf { it.bpm } + headroomBpm
        val span = (hi - lo).coerceAtLeast(1)
        return 1f - ((avgBpm - lo).toFloat() / span).coerceIn(0f, 1f)
    }

    /**
     * Fits an abstract route (already 0..1-normalized, but rarely spanning the full
     * square) into a unit frame: re-centres the bounding box and scales it uniformly
     * (aspect preserved) to fill `1 - 2*inset` of the frame.
     */
    fun fitRoute(points: List<RoutePoint>, inset: Float = 0.08f): List<NormalizedPoint> {
        if (points.isEmpty()) return emptyList()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1e-4f)
        val spanY = (maxY - minY).coerceAtLeast(1e-4f)
        val scale = (1f - 2 * inset) / maxOf(spanX, spanY)
        val offsetX = (1f - spanX * scale) / 2f
        val offsetY = (1f - spanY * scale) / 2f
        return points.map { point ->
            NormalizedPoint(
                x = offsetX + (point.x - minX) * scale,
                y = offsetY + (point.y - minY) * scale,
            )
        }
    }

    /**
     * Windows daily aggregates for the category-detail range switcher: the trailing
     * [ChartRange.days] entries, oldest-first. The mock ships one week, so D shows one
     * bar and M/Y clamp to what exists — same component, different window
     * (fit-agent.md §5).
     */
    fun windowAggregates(aggregates: List<DailyAggregate>, range: ChartRange): List<DailyAggregate> =
        aggregates.sortedBy { it.date }.takeLast(range.days)

    /** Whole-number daily average over a window; zero for an empty window. */
    fun dailyAverage(values: List<Int>): Int =
        if (values.isEmpty()) 0 else (values.sum().toDouble() / values.size + 0.5).toInt()

    /**
     * A **min–max-scaled** trend polyline for a fractional metric series (Body weight / resting HR,
     * §12 B2) — `x` evenly spaced left→right over the readings (oldest first), `y` mapping the
     * value onto its own [lo, hi] range padded by [headroom] (y = 0 at the TOP, Canvas convention).
     * Unlike [barHeights], the axis is **not** anchored at zero: a weight trend of 73–75 kg must
     * fill the plot, not sit as near-identical full bars. Fewer than two points → empty (nothing to
     * trend); an all-equal series → a flat mid-line.
     */
    fun minMaxPolyline(values: List<Double>, headroom: Float = 0.12f): List<NormalizedPoint> {
        if (values.size < 2) return emptyList()
        val n = values.size
        val lo = values.min()
        val hi = values.max()
        if (hi == lo) return values.mapIndexed { i, _ -> NormalizedPoint(i.toFloat() / (n - 1), 0.5f) }
        val pad = (hi - lo) * headroom
        val axisLo = lo - pad
        val axisSpan = (hi - lo) + 2 * pad
        return values.mapIndexed { i, v ->
            NormalizedPoint(
                x = i.toFloat() / (n - 1),
                y = (1.0 - (v - axisLo) / axisSpan).toFloat(),
            )
        }
    }
}

/** A point in unit-square plot space; y follows the Canvas convention (0 = top). */
data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

enum class ChartRange(val days: Int) {
    Day(1),
    Week(7),
    Month(30),
    Year(365),
}
