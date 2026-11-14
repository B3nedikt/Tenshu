package app.tenshu.photos.ui.screens.editor

import android.graphics.Typeface
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import app.tenshu.photos.edit.LabelOp

internal actual fun Canvas.drawLabel(op: LabelOp) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = op.argb
        textSize = op.size
        typeface = Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val m = paint.fontMetrics
    nativeCanvas.drawText(op.text, op.x, op.y - (m.ascent + m.descent) / 2f, paint)
}

internal actual fun ImageBitmap.release() = asAndroidBitmap().recycle()
