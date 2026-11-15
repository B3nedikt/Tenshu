package app.tenshu.photos.ui.screens.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import app.tenshu.photos.edit.FillOp
import app.tenshu.photos.edit.LabelOp
import app.tenshu.photos.edit.MarkupOp
import app.tenshu.photos.edit.MarkupRender
import app.tenshu.photos.edit.StrokeOp

/**
 * [ops] (`MarkupRender.scene`) drawn in this canvas's coordinates — the output's pixels. The one
 * markup drawer on Android and desktop: the preview calls it with the fit-to-screen scale applied,
 * the save at full size, so what the user draws is what is saved.
 *
 * ⚠ A stroke is ONE path drawn with its alpha in the paint, so a highlighter crossing itself is one
 * coverage, not darker where it overlaps — as Core Graphics draws it on iOS and the reference
 * (`MarkupParityFixtures`) assumes.
 */
internal fun Canvas.drawMarkup(ops: List<MarkupOp>) {
    for (op in ops) when (op) {
        is StrokeOp -> drawPath(
            polyline(op.points, close = false),
            Paint().apply {
                color = Color(op.argb).copy(alpha = op.alpha)
                style = PaintingStyle.Stroke
                strokeWidth = op.width
                strokeCap = StrokeCap.Round
                strokeJoin = StrokeJoin.Round
                isAntiAlias = true
            },
        )
        is FillOp -> drawPath(polyline(op.polygon, close = true), Paint().apply { color = Color(op.argb); isAntiAlias = true })
        is LabelOp -> drawLabel(op)
    }
}

/** One line of bold text centred on the op's point, in the platform's own font. */
internal expect fun Canvas.drawLabel(op: LabelOp)

/**
 * This picture with [polygons] (source pixels, `MarkupRender.redactionsInSource`) filled black, as
 * a **new** bitmap — the redaction's real work, done before anything reads the source.
 *
 * ⚠⚠ No anti-aliasing, and an outline of [MarkupRender.SOURCE_FILL_PAD] px on top: a pixel the box
 * only touches is destroyed too, never blended. Then no later stage — the transform's filtered
 * sampling least of all — can read a single original pixel from under the box.
 */
internal fun ImageBitmap.redacted(polygons: List<FloatArray>): ImageBitmap {
    val out = ImageBitmap(width, height)
    Canvas(out).apply {
        drawImage(this@redacted, Offset.Zero, Paint())
        val fill = Paint().apply { color = Color.Black; isAntiAlias = false }
        val edge = Paint().apply {
            color = Color.Black
            isAntiAlias = false
            style = PaintingStyle.Stroke
            strokeWidth = MarkupRender.SOURCE_FILL_PAD * 2
            strokeJoin = StrokeJoin.Miter
        }
        for (p in polygons) {
            val path = polyline(p, close = true)
            drawPath(path, fill)
            drawPath(path, edge)
        }
    }
    return out
}

/** Frees a full-size bitmap now rather than at the next GC (Android's heap holds two of them). */
internal expect fun ImageBitmap.release()

private fun polyline(p: FloatArray, close: Boolean): Path = Path().apply {
    if (p.size < 2) return@apply
    moveTo(p[0], p[1])
    for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
    if (close) close()
}
