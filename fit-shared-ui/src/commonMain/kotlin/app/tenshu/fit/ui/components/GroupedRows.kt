package app.tenshu.fit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * One [FitCard]'s worth of rows, emitted as **individual lazy items** that still read as a single
 * card.
 *
 * ## Why this exists
 *
 * Fit's list screens share a shape: a card wrapping an unbounded row list, dividers between. The
 * obvious lazy conversion — one item per *card* — is what History could do, because there the card
 * is the repeating unit (one per day). Medical and Category-detail are the other way round: a
 * **fixed, small** number of cards (four medical kinds; one history card) each holding an unbounded
 * number of rows. Making those cards lazy is a no-op — a user with 500 readings still composes 500
 * rows inside one item.
 *
 * So the rows have to be the items, which means the card's surface has to span them. Each row draws
 * its own surface with a position-dependent shape — rounded at the top of the group, rounded at the
 * bottom, square in between — and the group is emitted with **no** inter-item spacing, so the
 * seams are invisible and the result is pixel-equivalent to the `FitCard { Column { … } }` it
 * replaces.
 *
 * ⚠ The enclosing `LazyColumn` must therefore NOT set `verticalArrangement = spacedBy(...)`: that
 * applies uniformly and would prise the group apart into separate cards. Give block-level items
 * their own bottom padding instead. This is the one contract a caller can get wrong and still
 * compile — and it fails *visually*, not by assertion, so it is worth checking by eye on any new
 * caller. Both current callers (`MedicalScreen`, `CategoryDetailScreen`'s Latest mode) omit the
 * arrangement deliberately and say so at the call site.
 */
fun <T> LazyListScope.groupedRows(
    items: List<T>,
    key: (T) -> Any,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp),
    row: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        GroupedRow(
            first = index == 0,
            last = index == items.lastIndex,
            padding = padding,
        ) { row(item) }
    }
}

/** A single row of a [groupedRows] group — the card surface for its position in the group. */
@Composable
private fun GroupedRow(
    first: Boolean,
    last: Boolean,
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val corner = MaterialTheme.shapes.large
    val shape: Shape = when {
        first && last -> corner
        first -> corner.copy(bottomStart = ZeroCorner, bottomEnd = ZeroCorner)
        last -> corner.copy(topStart = ZeroCorner, topEnd = ZeroCorner)
        else -> RectangleShape
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.padding(padding)) {
            // The divider belongs to the row *below* it, so it lands inside the same surface and
            // cannot fall in the seam between two lazy items.
            androidx.compose.foundation.layout.Column {
                if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

private val ZeroCorner = androidx.compose.foundation.shape.CornerSize(0.dp)
