package app.tenshu.photos.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import app.tenshu.photos.ui.screens.feed.gridWidthScale

/**
 * [baseColumns] on a phone-width grid, and that count times `gridWidthScale` on a wider one — the
 * feed's column rule (`photos-app.md` §33) for the plain grids: Trash, Search, an album, Collections.
 *
 * ⚠ Not `GridCells.Adaptive`: that picks any count, so the same screen would show a different
 * number of columns from the feed beside it at every width. Scaling one base count keeps the app's
 * grids in step with each other and with what they look like on a phone.
 *
 * ⚠ The cells are sized from the width the grid is actually given, so a navigation rail or a
 * split-screen neighbour is already accounted for.
 */
class WidthScaledGridCells(private val baseColumns: Int) : GridCells {

    init {
        require(baseColumns > 0) { "baseColumns must be positive, was $baseColumns" }
    }

    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = baseColumns * gridWidthScale(availableSize.toDp().value)
        // The same distribution `GridCells.Fixed` makes: whole pixels, the remainder one per cell
        // from the start.
        val gridSize = availableSize - spacing * (count - 1)
        val slot = gridSize / count
        val remaining = gridSize % count
        return List(count) { slot + if (it < remaining) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean =
        other is WidthScaledGridCells && other.baseColumns == baseColumns

    override fun hashCode(): Int = baseColumns
}
