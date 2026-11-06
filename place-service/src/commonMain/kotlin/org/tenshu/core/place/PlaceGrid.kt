package org.tenshu.core.place

import kotlin.math.abs

/**
 * The grid that **mints** capture place ids, and the adjacency test that undoes it
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §10.10).
 *
 * A captured place's id is its dwell centroid snapped to a fixed grid — `cap:<latE7>,<lngE7>` — so
 * two records of *one physical point* can land in neighbouring cells and become two permanent rows.
 * That is the whole fragmentation mechanism (§8.1).
 *
 * ⚠⚠ **A metric radius alone cannot undo it, and on-device that is not hypothetical.** The grid is
 * `0.001°` in **both** axes, but a degree of longitude is shorter than a degree of latitude
 * everywhere off the equator: at 48°N one cell step is **~74 m east-west and ~111 m north-south**.
 * The accuracy-scaled radius ([PlaceProximity]) floors at 60 m and defaults to 100 m, so on the
 * reporting device the 74 m longitude split merged and the **111 m latitude split did not** — the
 * rule whose entire purpose is repairing grid fragmentation could not span one step of that grid.
 *
 * Rather than widen the radius for everyone (which would merge genuinely distinct venues that no
 * grid ever split), adjacency is asked **in the grid's own terms**: two ids are candidates when their
 * cells touch. That is self-limiting by construction — it can only ever reach across *one* cell
 * boundary, which is exactly the artefact — and it is latitude-independent, which a metre radius
 * cannot be.
 */
object PlaceGrid {

    /**
     * The grid a captured centroid snaps to (E7). ⚠ The single source of truth: the capture side's
     * `VisitDetectionConfig.placeGridE7` defaults to this, so the minting grid and the repair rule
     * cannot drift — a mismatch would silently make adjacency mean the wrong thing.
     */
    const val PLACE_GRID_E7: Long = 10_000L

    /** The prefix every capture-minted id carries; other sources (imports) use their own. */
    const val CAPTURE_ID_PREFIX: String = "cap:"

    /**
     * The `(latCell, lngCell)` a capture-minted [placeId] occupies, or `null` for any other id shape
     * — an imported id was never grid-minted, so grid adjacency says nothing about it and the
     * ordinary radius applies.
     */
    fun cellOf(placeId: String): Pair<Long, Long>? {
        if (!placeId.startsWith(CAPTURE_ID_PREFIX)) return null
        val parts = placeId.removePrefix(CAPTURE_ID_PREFIX).split(',')
        if (parts.size != 2) return null
        val lat = parts[0].toLongOrNull() ?: return null
        val lng = parts[1].toLongOrNull() ?: return null
        return floorDiv(lat, PLACE_GRID_E7) to floorDiv(lng, PLACE_GRID_E7)
    }

    /**
     * True when [a] and [b] are capture-minted ids in the **same or a touching** cell (Chebyshev
     * distance ≤ 1, so the eight neighbours count — a centroid can drift diagonally as easily as
     * along an axis).
     */
    fun areAdjacent(a: String, b: String): Boolean {
        val (aLat, aLng) = cellOf(a) ?: return false
        val (bLat, bLng) = cellOf(b) ?: return false
        return abs(aLat - bLat) <= 1 && abs(aLng - bLng) <= 1
    }

    /** `Math.floorDiv` is JVM-only; negative coordinates (S/W) must floor, not truncate toward zero. */
    private fun floorDiv(value: Long, divisor: Long): Long {
        val q = value / divisor
        return if (value % divisor != 0L && (value xor divisor) < 0) q - 1 else q
    }
}
