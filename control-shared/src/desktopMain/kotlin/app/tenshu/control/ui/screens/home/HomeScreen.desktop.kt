package app.tenshu.control.ui.screens.home

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeToImageBitmap(data: ByteArray): ImageBitmap? {
    return Image.makeFromEncoded(data).toComposeImageBitmap()
}
