package app.tenshu.contacts.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * `BitmapFactory` is the platform decoder, so Android reads whatever the OS reads — including
 * HEIC from API 28.
 *
 * No downsampling pass, unlike Drive's: an avatar is capped at
 * [org.tenshu.contact.PersonAvatar.MAX_IMAGE_BYTES], and a 256 KiB JPEG cannot reach the
 * canvas-size ceiling Drive's two-pass decode exists to avoid.
 */
actual fun decodeAvatar(data: ByteArray): ImageBitmap? {
    if (data.isEmpty()) return null
    return BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
}
