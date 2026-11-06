package app.tenshu.contacts.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Skia, and nothing else.
 *
 * ⚠ Skiko ships no HEIF/HEVC codec, so a HEIC embedded in a `PHOTO` will not decode here —
 * it falls back to the monogram rather than failing loudly, which is the right outcome for a
 * list glyph. Drive needs the `:macos-helpers` ImageIO bridge because it *displays* photos
 * and a blank viewer is a bug; a contact without a picture is not.
 */
actual fun decodeAvatar(data: ByteArray): ImageBitmap? {
    if (data.isEmpty()) return null
    return try {
        Image.makeFromEncoded(data).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
