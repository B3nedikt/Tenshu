package app.tenshu.photos.ui.screens.editor

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.nativeCanvas
import app.tenshu.photos.edit.LabelOp
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface

private val bold: Typeface by lazy {
    FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD) ?: Typeface.makeEmpty()
}

internal actual fun Canvas.drawLabel(op: LabelOp) {
    val font = Font(bold, op.size)
    val line = TextLine.make(op.text, font)
    val m = font.metrics
    val paint = org.jetbrains.skia.Paint().apply {
        color = op.argb
        isAntiAlias = true
    }
    nativeCanvas.drawTextLine(line, op.x - line.width / 2f, op.y - (m.ascent + m.descent) / 2f, paint)
}

internal actual fun ImageBitmap.release() = asSkiaBitmap().close()
