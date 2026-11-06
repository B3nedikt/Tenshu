package app.tenshu.maps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.maps.model.PlacePhoto
import app.tenshu.maps.ui.theme.parseHexColor
import app.tenshu.ui.theme.spacing

/**
 * A faked photo (Ph4, docs/proposals/maps-app.md §6; handoff §5 `PhotoStrip`). Real images
 * replace this later; for v1 it synthesises a diagonal gradient from the placeholder `tone`
 * (a darker shade → the tone → a lighter shade), matching the mock's look. Mirrors Timeline's
 * `PhotoFrame`.
 */
@Composable
fun PhotoFrame(
    photo: PlacePhoto,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val base = parseHexColor(photo.tone) ?: Color(0xFF9E9E9E)
    val brush = Brush.linearGradient(
        colors = listOf(lerp(base, Color.Black, 0.22f), base, lerp(base, Color.White, 0.35f)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.33f), RoundedCornerShape(cornerRadius)),
    )
}

/** A horizontal strip of equal-width [PhotoFrame] placeholders (handoff §5). */
@Composable
fun PhotoStrip(
    photos: List<PlacePhoto>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    if (photos.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MaterialTheme.spacing.s),
    ) {
        photos.forEach { photo ->
            PhotoFrame(
                photo = photo,
                modifier = Modifier.weight(1f).height(height),
            )
        }
    }
}
