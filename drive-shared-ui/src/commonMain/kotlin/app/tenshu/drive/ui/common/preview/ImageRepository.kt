package app.tenshu.drive.ui.common.preview

import androidx.compose.ui.graphics.ImageBitmap

class ImageRepository {

    private val images = mutableMapOf<String, Image>()

    fun putImage(path: String, bitmap: ImageBitmap) {
        images[path] = Image(path = path, bitmap = bitmap)
    }

    fun getImage(path: String): Image? {
        return images[path]
    }
}