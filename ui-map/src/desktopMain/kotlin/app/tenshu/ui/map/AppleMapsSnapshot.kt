package app.tenshu.ui.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.tenshu.macos.MacMapSnapshotter
import app.tenshu.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * The **elected** desktop renderer: an Apple basemap fetched as a PNG by `map-snapshot-helper` and
 * drawn as an ordinary Compose `Image` (AM-5; §6.5's "pull the pixels, draw them ourselves").
 *
 * ⚠⚠ Composing this means egress to Apple. Both callers gate on
 * [DesktopMapRendererControl.usesAppleMaps] **and** [MacMapSnapshotter.isAvailable] before they get
 * here; nothing in this file re-checks, so do not call it from anywhere that has not.
 *
 * ⚠⚠ The caller's chrome runs through [AppleMapsDataContent], so [BasemapChrome]'s chip reads
 * "© Apple Maps · Legal" and opens the acknowledgements page MapKit's own Legal link opens — never "Offline · MapLibre", which would
 * be false here (no MapLibre, no OSM data). Whatever MapKit draws into the pixels is left
 * unobscured as well (§6.4); the chip sits in the bottom-start corner MapKit does not use.
 *
 * Returns the schematic when the helper yields nothing — a hero that cannot be drawn falls back
 * rather than showing a hole. ⚠ That includes the moment *before* the first snapshot arrives: a
 * snapshot is a network fetch, so the schematic is what a slow link shows, not a blank rectangle.
 */
@Composable
internal fun AppleMapsSnapshot(
    modifier: Modifier,
    height: Dp?,
    seed: Int,
    center: BasemapCoord?,
    route: List<BasemapCoord>,
    colorHex: String,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MaterialTheme.spacing.m)
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val density = LocalDensity.current
    val surfaceHeight = height ?: DEFAULT_SNAPSHOT_HEIGHT

    var bitmap by remember(center, route, dark) { mutableStateOf<ImageBitmap?>(null) }

    // ⚠ Keyed on the geometry and the palette, not on the surface size: a resize would otherwise
    // re-fetch from Apple on every drag frame. The image is scaled to fit instead, which costs a
    // little sharpness on a stretched window and no requests at all.
    LaunchedEffect(center, route, dark) {
        val widthPx = with(density) { SNAPSHOT_WIDTH.roundToPx() }
        val heightPx = with(density) { surfaceHeight.roundToPx() }
        val png = withContext(Dispatchers.IO) {
            MacMapSnapshotter.snapshot(
                centerLatitude = center?.latitude ?: 0.0,
                centerLongitude = center?.longitude ?: 0.0,
                zoom = center?.zoom ?: 14.5,
                width = widthPx,
                height = heightPx,
                dark = dark,
                route = route.map { it.latitude to it.longitude },
                colorHex = colorHex,
            )
        }
        bitmap = png?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }

    val image = bitmap
    if (image == null) {
        SchematicBasemap(modifier = modifier, height = height, seed = seed, content = content)
        return
    }

    Box(
        modifier = modifier.basemapSurfaceSize(height).clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            // ⚠ Null: the map IS the decoration here, and the caller's own screen names the place or
            // trip this illustrates. A generic "map" label would be read aloud on every hero.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(surfaceHeight),
        )
        AppleMapsDataContent(content)
    }
}

/**
 * The width asked of the snapshotter. ⚠ A fixed request rather than the measured surface, for the
 * reason the `LaunchedEffect` key records: measuring would re-fetch on every resize frame. 900 pt is
 * comfortably wider than any hero this draws, so the image is downscaled rather than stretched.
 */
private val SNAPSHOT_WIDTH = Dp(900f)
private val DEFAULT_SNAPSHOT_HEIGHT = Dp(200f)

/** Whether this background reads as dark — the desktop stand-in for `isSystemInDarkTheme()`. */
private fun androidx.compose.ui.graphics.Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
