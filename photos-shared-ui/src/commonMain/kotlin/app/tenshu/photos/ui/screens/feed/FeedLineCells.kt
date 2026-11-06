package app.tenshu.photos.ui.screens.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Places one feed line's cells on the feed's column grid (`photos-app.md` §32) — a row side by side,
 * or a feature's blocks, each a hero across 2×2 cells beside the two that stack.
 *
 * ⚠⚠ **Every cell is the same whole number of pixels, in every line.** The lines are separate lazy
 * items, so nothing aligns them but arithmetic: a `Row` with weights distributes its remainder
 * pixels per line, and a hero's edge would then miss the tiles beside it by a pixel that shows as a
 * ragged seam. The width left over by rounding — under one pixel per column — sits at the end.
 *
 * ⚠ The hero is exactly two cells and a gap, so it lines up with the rows above and below it and
 * stays square.
 */
@Composable
internal fun FeedLineCells(
    columns: Int,
    spacing: Dp,
    modifier: Modifier = Modifier,
    /**
     * The feature this line draws, or `null` for a row. ⚠ The children arrive in paged order either
     * way; see `FeedLine.Feature` for which of them is a block's hero.
     */
    feature: FeedLine.Feature? = null,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gap = spacing.roundToPx()
        val cell = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val step = cell + gap
        val rows = if (feature == null) 1 else 2
        val block = FeedLine.Feature.BLOCK

        val placeables = measurables.mapIndexed { child, measurable ->
            val isHero = feature != null && feature.isHero(feature.start + child)
            val edge = if (isHero) cell + step else cell
            measurable.measure(Constraints.fixed(edge, edge))
        }
        layout(width, rows * cell + (rows - 1) * gap) {
            placeables.forEachIndexed { child, placeable ->
                if (feature == null) {
                    placeable.place(child * step, 0)
                    return@forEachIndexed
                }
                val offset = child % block
                val heroLeft = feature.heroLeftIn(child / block)
                val column = child / block * block + when {
                    heroLeft -> if (offset == 0) 0 else 2
                    else -> if (offset == 1) 1 else 0
                }
                // A block's last child is always the lower of its two small cells.
                val row = if (offset == 2) 1 else 0
                placeable.place(column * step, row * step)
            }
        }
    }
}
