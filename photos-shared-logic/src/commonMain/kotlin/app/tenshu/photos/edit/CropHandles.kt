package app.tenshu.photos.edit

import kotlin.math.max
import kotlin.math.min

/** What a drag on the crop box grabbed. [MOVE] is the inside of the box. */
enum class CropHandle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, MOVE }

/**
 * The crop after dragging [handle] by ([dx], [dy]) — **view-space**, normalised to the frame.
 *
 * [lockedRatio] is the aspect lock as a ratio of *normalised* width to height (`null` = Freeform);
 * [TransformEdit.normalisedRatio] converts a pixel ratio. With a lock a corner keeps its opposite
 * corner fixed, an edge keeps the opposite edge fixed and stays centred on the other axis, and a box
 * that would leave the frame shrinks toward that fixed point rather than changing shape.
 *
 * ⚠ Shared so the Compose and SwiftUI shells resize the box identically — a handle's behaviour is
 * the editor's most-felt rule, and two copies of it would drift.
 */
fun dragCrop(start: NormRect, handle: CropHandle, dx: Float, dy: Float, lockedRatio: Float?): NormRect {
    val minEdge = TransformEdit.MIN_CROP
    if (handle == CropHandle.MOVE) {
        val ox = dx.coerceIn(-start.left, 1f - start.right)
        val oy = dy.coerceIn(-start.top, 1f - start.bottom)
        return NormRect(start.left + ox, start.top + oy, start.right + ox, start.bottom + oy)
    }
    var l = start.left
    var t = start.top
    var r = start.right
    var b = start.bottom
    val movesLeft = handle == CropHandle.LEFT || handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT
    val movesRight = handle == CropHandle.RIGHT || handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT
    val movesTop = handle == CropHandle.TOP || handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT
    val movesBottom = handle == CropHandle.BOTTOM || handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT

    if (lockedRatio == null) {
        if (movesLeft) l = (l + dx).coerceIn(0f, r - minEdge)
        if (movesRight) r = (r + dx).coerceIn(l + minEdge, 1f)
        if (movesTop) t = (t + dy).coerceIn(0f, b - minEdge)
        if (movesBottom) b = (b + dy).coerceIn(t + minEdge, 1f)
        return NormRect(l, t, r, b)
    }

    // ── locked ──
    val k = lockedRatio
    val horizontal = movesLeft || movesRight
    val vertical = movesTop || movesBottom
    // The size the drag asks for, along whichever axis it moved more in ratio terms.
    val wFromX = start.width + (if (movesLeft) -dx else if (movesRight) dx else 0f)
    val wFromY = (start.height + (if (movesTop) -dy else if (movesBottom) dy else 0f)) * k
    // ⚠ A corner takes the drag projected onto its diagonal — the mean of the two asks. `max` kept
    // the unmoved axis, so a one-axis drag toward the centre never shrank the box; picking the axis
    // moved most jumped the size where the two asks crossed. The mean does neither.
    var w = when {
        horizontal && vertical -> (wFromX + wFromY) / 2f
        horizontal -> wFromX
        else -> wFromY
    }
    w = max(w, minEdge * max(1f, k))
    var h = w / k
    // The fixed point the box grows from.
    val ax = when { movesLeft -> start.right; movesRight -> start.left; else -> start.centerX }
    val ay = when { movesTop -> start.bottom; movesBottom -> start.top; else -> start.centerY }
    // Directions from the anchor: -1 = toward 0, +1 = toward 1, 0 = centred.
    val sx = when { movesLeft -> -1; movesRight -> 1; else -> 0 }
    val sy = when { movesTop -> -1; movesBottom -> 1; else -> 0 }
    // Room available from the anchor in each direction, as a cap on w and h.
    val roomW = when (sx) { -1 -> ax; 1 -> 1f - ax; else -> 2f * min(ax, 1f - ax) }
    val roomH = when (sy) { -1 -> ay; 1 -> 1f - ay; else -> 2f * min(ay, 1f - ay) }
    val fit = min(1f, min(roomW / w, roomH / h))
    w *= fit
    h *= fit
    if (w < minEdge || h < minEdge) return start
    l = when (sx) { -1 -> ax - w; 1 -> ax; else -> ax - w / 2f }
    t = when (sy) { -1 -> ay - h; 1 -> ay; else -> ay - h / 2f }
    return NormRect(l, t, l + w, t + h)
}

/** A view-space pixel ratio (width / height) as a ratio of normalised width to normalised height. */
fun TransformEdit.normalisedRatio(pixelRatio: Float, sourceWidth: Int, sourceHeight: Int): Float {
    val (vw, vh) = viewSize(sourceWidth, sourceHeight)
    return pixelRatio * vh / vw
}

/**
 * Which handle a touch at ([x], [y]) grabbed — display pixels relative to the frame's top-left, for
 * a frame drawn [frameWidth]×[frameHeight] — or `null` for a touch outside the box. [slop] is the
 * finger's reach around an edge. ⚠ Corners win over edges, and edges over the inside.
 */
fun cropHandleAt(crop: NormRect, frameWidth: Float, frameHeight: Float, x: Float, y: Float, slop: Float): CropHandle? {
    val l = crop.left * frameWidth
    val r = crop.right * frameWidth
    val t = crop.top * frameHeight
    val b = crop.bottom * frameHeight
    val nearL = kotlin.math.abs(x - l) <= slop
    val nearR = kotlin.math.abs(x - r) <= slop
    val nearT = kotlin.math.abs(y - t) <= slop
    val nearB = kotlin.math.abs(y - b) <= slop
    val inX = x in (l - slop)..(r + slop)
    val inY = y in (t - slop)..(b + slop)
    return when {
        nearT && nearL -> CropHandle.TOP_LEFT
        nearT && nearR -> CropHandle.TOP_RIGHT
        nearB && nearR -> CropHandle.BOTTOM_RIGHT
        nearB && nearL -> CropHandle.BOTTOM_LEFT
        nearT && inX -> CropHandle.TOP
        nearB && inX -> CropHandle.BOTTOM
        nearL && inY -> CropHandle.LEFT
        nearR && inY -> CropHandle.RIGHT
        x in l..r && y in t..b -> CropHandle.MOVE
        else -> null
    }
}
