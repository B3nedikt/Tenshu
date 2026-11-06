package app.tenshu.control.ui.screens.home

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeToImageBitmap(data: ByteArray): ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
    return bitmap?.asImageBitmap()
}
