package app.tenshu.timeline.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.ui.theme.parseHexColor

/**
 * A photo. Renders [image] when there is one; otherwise a diagonal gradient synthesised from the
 * photo's `dominantColor`.
 *
 * The gradient is not a placeholder to be embarrassed about — it is the honest answer whenever
 * there is nothing to show: a moment with no photos, a device that has not fetched this file's
 * preview blob yet (`File.Thumbnail` is `OnDemand`), or an unreachable Control. `dominantColor`
 * stays load-bearing either way, because `personalise()` tints the screen's chrome from it.
 *
 * ⚠ It takes a decoded bitmap, not bytes: the caller decodes once via [rememberPhotoImage], which
 * also derives the tint `personalise()` reads. Decoding here instead would either do it twice or
 * hide the colour where nothing else could reach it.
 *
 * Named `PhotoFrame` rather than `Photo` because the parameter *is* a [Photo] — `Photo(photo:
 * Photo)` compiles but reads badly everywhere it is called. It was `TtPhoto` until T-P1: a prefix
 * this codebase uses nowhere else, from back when it drew a placeholder rather than a photograph.
 */
@Composable
fun PhotoFrame(
    photo: Photo,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
    image: ImageBitmap? = null,
    heroFidelity: Boolean = false,
) {
    val base = parseHexColor(photo.dominantColor) ?: Color(0xFF9E9E9E)
    val brush = Brush.linearGradient(
        colors = listOf(
            lerp(base, Color.Black, 0.28f),
            base,
            lerp(base, Color.White, 0.35f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    ) {
        // Ask for a preview sized to the box we are about to draw into, rather than the fixed
        // 720×480 this used to request. That constant was chosen for a phone-width carousel frame;
        // on a desktop window the same frame is two to four times wider, so the request was
        // guaranteeing an upscale before the proxy had even answered.
        //
        // ⚠ Height is not always known: a frame in a scrolling column can be measured with an
        // unbounded height, where `maxHeight` is `Dp.Infinity`. Fall back to the width — the
        // request is square anyway (see [previewRequestPx]), so an unbounded axis costs nothing.
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx =
            if (constraints.hasBoundedHeight) with(density) { maxHeight.roundToPx() } else widthPx
        val requestPx = previewRequestPx(maxOf(widthPx, heightPx))

        // A caller that has already decoded (the moment hero, which also needs the tint) passes the
        // bitmap in; everyone else — every feed row and carousel page — resolves its own, and only
        // if it is actually on screen. See [rememberLoadedPhoto] for why that is the feature.
        val resolved =
            image ?: rememberLoadedPhoto(photo.uri, requestPx, requestPx, heroFidelity)?.bitmap

        if (resolved != null) {
            Image(
                bitmap = resolved,
                // Null: the photo is decoration behind the moment's own text, and a screen reader
                // announcing "photo" on every hero adds nothing the heading has not already said.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop, not Fit: the hero is a fixed band, and letterboxing a portrait shot into it
                // shows more gradient than photograph. ⚠ This is also why the request above is a
                // square of the longest edge and not this box's shape.
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
}
