package app.tenshu.maps.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.maps.model.PlaceCategory
import app.tenshu.maps.ui.theme.rememberCategoryAccent

/**
 * A soft-tinted category avatar — the category glyph in a circle (Ph4, handoff §5). Default
 * chrome is pure Amethyst (`primary` glyph on `secondaryContainer`); when [accentByCategory]
 * is on, the glyph + fill shift to the per-category hue (marks only — §4 accent seam).
 */
@Composable
fun CategoryAvatar(
    category: PlaceCategory,
    modifier: Modifier = Modifier,
    accentByCategory: Boolean = false,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    val accent = rememberCategoryAccent(category, enabled = accentByCategory)
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = accent.soft,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = MapsIcons.forCategory(category),
                contentDescription = null,
                tint = accent.accent,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
