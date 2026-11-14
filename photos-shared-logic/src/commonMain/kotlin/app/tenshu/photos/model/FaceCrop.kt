package app.tenshu.photos.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The square of a decoded preview that a person's face is drawn from, in that bitmap's pixels.
 *
 * ⚠ Shared so Compose's `FaceAvatar` and the SwiftUI person card cut the SAME square from the same
 * preview (`ios-face-grouping.md` F7): the rule lived in Compose alone, and iOS centred the photograph.
 */
data class FaceCropSquare(val left: Int, val top: Int, val side: Int)

/**
 * The square to draw: the face box grown by a margin (hair and chin read as a face; the detector's
 * box alone reads as a mask), made square around its centre, and kept inside the image.
 * `null` region (or an empty one) → the centred square of the whole photograph.
 *
 * ⚠ [region] is a fraction of the UPRIGHT photograph, so [width] and [height] are the decoded
 * bitmap's upright size — after its orientation is applied.
 */
fun faceCropSquare(width: Int, height: Int, region: FaceRegion?): FaceCropSquare {
    if (region == null || region.area <= 0f) {
        val side = min(width, height)
        return FaceCropSquare((width - side) / 2, (height - side) / 2, side)
    }
    val cx = (region.left + region.right) / 2f * width
    val cy = (region.top + region.bottom) / 2f * height
    val faceSide = max((region.right - region.left) * width, (region.bottom - region.top) * height)
    val side = (faceSide * (1f + 2 * FACE_CROP_MARGIN)).roundToInt().coerceIn(1, min(width, height))
    val left = (cx - side / 2f).roundToInt().coerceIn(0, width - side)
    val top = (cy - side / 2f).roundToInt().coerceIn(0, height - side)
    return FaceCropSquare(left, top, side)
}

/**
 * How large a preview to ask for so the FACE, not the photograph, covers an avatar [edgePx] wide.
 * Capped, because a tiny detected face would otherwise ask for the original.
 */
fun faceCropRequestPx(edgePx: Int, region: FaceRegion?): Int {
    if (region == null) return edgePx * 2
    val faceFraction = max(region.right - region.left, region.bottom - region.top).coerceIn(0.05f, 1f)
    return (edgePx * 2 / (faceFraction * (1f + 2 * FACE_CROP_MARGIN))).roundToInt().coerceIn(edgePx, FACE_CROP_MAX_REQUEST_PX)
}

/** A quarter of the face's size on each side. */
private const val FACE_CROP_MARGIN = 0.25f
private const val FACE_CROP_MAX_REQUEST_PX = 2048
