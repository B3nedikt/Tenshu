package app.tenshu.photos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.tenshu.photos.model.FaceRegion
import app.tenshu.ui.image.decodePreviewBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A person's face, cut out of a photograph and drawn in a circle (IN-6 F6a) — the header that says
 * WHO defines a People group, the way Google Photos' person page does.
 *
 * ⚠ Cropped from the ordinary preview rather than a new artifact: the box is a fraction of the
 * upright photograph, so it maps onto whatever size the preview ladder returns. The request is
 * scaled up by how small the face is, or a face filling a tenth of the frame would be drawn from a
 * handful of pixels.
 *
 * ⚠⚠ [FilterQuality.High] explicitly — Skia's default sampling is NEAREST, which turns an upscaled
 * crop into visible blocks.
 *
 * @param region `null` draws the whole photograph, centre-cropped — the fallback for a person whose
 *   face this device cannot place (a name synced from another device, no cluster here).
 */
@Composable
fun FaceAvatar(
    path: String?,
    region: FaceRegion?,
    loadPreview: PreviewLoader,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val edgePx = with(LocalDensity.current) { size.roundToPx() }
    val requestPx = faceRequestPx(edgePx, region)
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, path, requestPx) {
        value = path?.let { loadPreview(it, requestPx) }?.decodePreviewBitmap()
    }

    Box(
        modifier
            .testTag(FACE_AVATAR_TAG)
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val image = bitmap ?: return@Box
        Canvas(Modifier.size(size)) {
            val (offset, cropSize) = faceSourceRect(image.width, image.height, region)
            drawImage(
                image = image,
                srcOffset = offset,
                srcSize = cropSize,
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
    }
}

/**
 * How large a preview to ask for: enough that the FACE, not the photograph, covers the avatar.
 * Capped, because a tiny detected face would otherwise ask for the original.
 */
internal fun faceRequestPx(edgePx: Int, region: FaceRegion?): Int {
    if (region == null) return edgePx * 2
    val faceFraction = max(region.right - region.left, region.bottom - region.top).coerceIn(0.05f, 1f)
    return (edgePx * 2 / (faceFraction * (1f + 2 * FACE_MARGIN))).roundToInt().coerceIn(edgePx, MAX_REQUEST_PX)
}

/**
 * The square of the bitmap to draw: the face box grown by a margin (hair and chin read as a face;
 * the detector's box alone reads as a mask), made square around its centre, and kept inside the
 * image. `null` region → the centred square of the whole photograph.
 */
internal fun faceSourceRect(width: Int, height: Int, region: FaceRegion?): Pair<IntOffset, IntSize> {
    if (region == null || region.area <= 0f) {
        val side = min(width, height)
        return IntOffset((width - side) / 2, (height - side) / 2) to IntSize(side, side)
    }
    val cx = (region.left + region.right) / 2f * width
    val cy = (region.top + region.bottom) / 2f * height
    val faceSide = max((region.right - region.left) * width, (region.bottom - region.top) * height)
    val side = (faceSide * (1f + 2 * FACE_MARGIN)).roundToInt().coerceIn(1, min(width, height))
    val left = (cx - side / 2f).roundToInt().coerceIn(0, width - side)
    val top = (cy - side / 2f).roundToInt().coerceIn(0, height - side)
    return IntOffset(left, top) to IntSize(side, side)
}

/** Every face drawn by [FaceAvatar] — the person header and the People cards alike. */
const val FACE_AVATAR_TAG = "faceAvatar"

/** A quarter of the face's size on each side. */
private const val FACE_MARGIN = 0.25f
private const val MAX_REQUEST_PX = 2048
