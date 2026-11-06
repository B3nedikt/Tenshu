package app.tenshu.timeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.TripSuggestion
import app.tenshu.timeline.ui.theme.TimelineType

/** A pill chip row for tags (§1.2/§1.3/§1.4). */
@Composable
fun TagChips(tags: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** A small-caps eyebrow pill overlaid on a hero ("TRIP · APR 2026", "PLACE"). */
@Composable
fun OverlayPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The Interlude — an auto-trip suggestion mid-feed (§1.1). A soft card with an "INTERLUDE"
 * pill, an italic-serif line, meta, and Not-a-trip / Read-as-a-trip actions.
 */
@Composable
fun InterludeCard(
    suggestion: TripSuggestion,
    accent: Color,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        OverlayPill("Interlude", Modifier)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The past weekend reads like a trip.",
            style = TimelineType.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${suggestion.title} · ${suggestion.dateRange} · ${suggestion.placesCount} places · ${suggestion.photosCount} photos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss) { Text("Not a trip") }
            TextButton(onClick = onAccept) { Text("Read as a trip", color = accent) }
        }
    }
}

/**
 * A full-bleed cover card for a bound/nested trip (§1.1) — gradient photo, an eyebrow, an
 * italic-serif title, meta, and a call-to-action. Used for the Home "bound chapter" and the
 * in-trip nested-trip slot.
 */
@Composable
fun TripCoverCard(
    cover: Photo,
    eyebrow: String,
    title: String,
    meta: String,
    cta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 150.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        PhotoFrame(cover, Modifier.fillMaxSize(), cornerRadius = 16.dp)
        // legibility scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = TimelineType.hero.copy(fontStyle = FontStyle.Italic),
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = meta, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
        }
        Text(
            text = cta,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}
