package app.tenshu.calendar.model

/**
 * One event, placed in a day column.
 *
 * [column] and [columnCount] are the horizontal answer: draw at
 * `column / columnCount` of the day's width, [columnSpan] columns wide.
 * [topMinute] and [bottomMinute] are the vertical one, in minutes from midnight.
 *
 * ⚠ [bottomMinute] is a **layout** extent, not the event's end. A three-minute
 * meeting is laid out at the minimum height so it is visible and tappable, so
 * this can sit past the real end — read [value] for what the event actually
 * says.
 */
data class PositionedEvent<out T>(
    val value: T,
    val topMinute: Int,
    val bottomMinute: Int,
    val column: Int,
    val columnSpan: Int,
    val columnCount: Int,
) {
    /** Fraction of the day's width this event starts at, in `0f..1f`. */
    val widthFraction: Float get() = columnSpan.toFloat() / columnCount
    val offsetFraction: Float get() = column.toFloat() / columnCount

    /** Swaps the payload, keeping the geometry — for callers that laid out a carrier type. */
    fun <R> mapValue(transform: (T) -> R): PositionedEvent<R> = PositionedEvent(
        value = transform(value),
        topMinute = topMinute,
        bottomMinute = bottomMinute,
        column = column,
        columnSpan = columnSpan,
        columnCount = columnCount,
    )
}

/** Minutes in a civil day, as the time grid counts them. */
const val MINUTES_PER_DAY: Int = 24 * 60

/**
 * Overlapping-event layout for a day column (`calendar-app.md` §5.2 (2)).
 *
 * ⭐⭐ **Pure math over `(start, end)` pairs, and that is the point.** §5.2 calls
 * this out as "the single most important structural decision in the UI layer":
 * the expensive half of a week grid is deciding *where* each event goes, not
 * drawing it, and that decision is platform-free. Written once here, unit-tested
 * exhaustively, and consumed by both the Compose renderer and the CA-8 SwiftUI
 * one — which are then reduced to "draw a rectangle where you were told". Same
 * shape as the `:basemap-core` **D7** split.
 *
 * ## The algorithm
 *
 * 1. **Cluster** into maximal runs of transitively-overlapping events. Two
 *    events in different clusters never need to know about each other, so each
 *    cluster gets its own column count and a quiet morning is not squeezed by a
 *    busy afternoon.
 * 2. **Assign columns** greedily within a cluster: each event takes the first
 *    column whose previous occupant has already finished.
 * 3. **Expand** each event rightwards over columns nothing else is using during
 *    its span. Without this, one long meeting forces *every* event in its
 *    cluster to a third of the width even where there is nothing beside them.
 *
 * ⚠ Intervals are **half-open**: an event ending at 10:00 and one starting at
 * 10:00 do not overlap. Back-to-back meetings are the common case, and treating
 * them as a conflict would halve the width of most real days.
 */
object DayLayout {

    /**
     * The shortest an event may be laid out as.
     *
     * ⚠⚠ Load-bearing beyond aesthetics: a zero-length event (which RFC 5545
     * permits, and which imports produce) has an empty half-open interval, so it
     * would overlap nothing, stack invisibly under whatever is beside it, and be
     * impossible to tap. Giving every event a floor makes it both visible and
     * part of the overlap question.
     */
    const val DEFAULT_MINIMUM_MINUTES: Int = 20

    /**
     * Lays [items] out for one day.
     *
     * [interval] yields `(startMinute, endMinute)` from midnight, already
     * clipped to the day — see [daySchedule] for the clipping.
     *
     * Returns them in input order, so a caller can zip the result back against
     * whatever it passed in.
     */
    fun <T> arrange(
        items: List<T>,
        minimumMinutes: Int = DEFAULT_MINIMUM_MINUTES,
        interval: (T) -> IntRange,
    ): List<PositionedEvent<T>> {
        if (items.isEmpty()) return emptyList()

        val blocks = items.mapIndexed { index, item ->
            val range = interval(item)
            val top = range.first.coerceIn(0, MINUTES_PER_DAY)
            // ⚠ Clamped to the end of the day rather than allowed to overflow:
            // an 23:55 event cannot be given its full minimum height because the
            // grid genuinely runs out of room, and a renderer handed a bottom of
            // 1450 would either overdraw the next day or have to re-clamp it
            // itself — two places to get it wrong instead of none.
            val bottom = maxOf(range.last, top + minimumMinutes)
                .coerceIn(top, MINUTES_PER_DAY)
            Block(index = index, item = item, top = top, bottom = bottom)
        }

        // ⚠ Sorted by start, then longest-first, then input order. The last key
        // is what makes the output deterministic — two identical intervals must
        // not swap columns between two runs over the same data, or the grid
        // flickers on every recomposition.
        val ordered = blocks.sortedWith(
            compareBy({ it.top }, { -it.bottom }, { it.index })
        )

        val placed = arrayOfNulls<PositionedEvent<T>>(items.size)
        var cluster = mutableListOf<Block<T>>()
        var clusterEnd = Int.MIN_VALUE

        fun flush() {
            if (cluster.isEmpty()) return
            layOutCluster(cluster).forEach { placed[it.first] = it.second }
            cluster = mutableListOf()
        }

        ordered.forEach { block ->
            // ⚠ `>=`, not `>`: half-open intervals, so an event starting exactly
            // when the cluster ended begins a new one.
            if (block.top >= clusterEnd) {
                flush()
                clusterEnd = block.bottom
            } else {
                clusterEnd = maxOf(clusterEnd, block.bottom)
            }
            cluster += block
        }
        flush()

        @Suppress("UNCHECKED_CAST")
        return placed.toList() as List<PositionedEvent<T>>
    }

    /** Greedy column assignment plus the rightward expansion, for one cluster. */
    private fun <T> layOutCluster(cluster: List<Block<T>>): List<Pair<Int, PositionedEvent<T>>> {
        val columnEnds = mutableListOf<Int>()
        val columnOf = IntArray(cluster.size)

        cluster.forEachIndexed { position, block ->
            val free = columnEnds.indexOfFirst { it <= block.top }
            val column = if (free >= 0) free else columnEnds.size.also { columnEnds += 0 }
            columnEnds[column] = block.bottom
            columnOf[position] = column
        }

        val columnCount = columnEnds.size

        return cluster.mapIndexed { position, block ->
            val column = columnOf[position]
            block.index to PositionedEvent(
                value = block.item,
                topMinute = block.top,
                bottomMinute = block.bottom,
                column = column,
                columnSpan = spanFrom(cluster, columnOf, position, column, columnCount),
                columnCount = columnCount,
            )
        }
    }

    /**
     * How many columns [position] may occupy before something blocks it.
     *
     * ⚠ Only ever extends **rightwards**. Growing leftwards as well would let
     * two events swap sides between renders as their neighbours change, and a
     * meeting that jumps across the column while you are looking at it reads as
     * a bug even when the geometry is defensible.
     */
    private fun <T> spanFrom(
        cluster: List<Block<T>>,
        columnOf: IntArray,
        position: Int,
        column: Int,
        columnCount: Int,
    ): Int {
        val block = cluster[position]
        var span = 1
        while (column + span < columnCount) {
            val candidate = column + span
            val blocked = cluster.indices.any { other ->
                other != position &&
                    columnOf[other] == candidate &&
                    cluster[other].top < block.bottom &&
                    block.top < cluster[other].bottom
            }
            if (blocked) break
            span++
        }
        return span
    }

    private data class Block<T>(val index: Int, val item: T, val top: Int, val bottom: Int)
}
