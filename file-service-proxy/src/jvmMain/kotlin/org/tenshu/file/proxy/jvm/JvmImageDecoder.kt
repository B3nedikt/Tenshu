package org.tenshu.file.proxy.jvm

import app.tenshu.macos.MacImageDecoder
import org.tenshu.core.file.readMediaMetadata
import org.tenshu.file.proxy.ExifUpright
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The JVM's image decode — the one expensive step both thumbnail paths share, behind one seam so a
 * test can count it (`media-processing-pipeline-sweep.md` P1a: *"1 decode per upload"*).
 *
 * [decode] is the whole image at full size — what [JvmGetPreviewImplementation] has always done, and
 * what [JvmThumbnailLadderRenderer] now does once per upload instead of once per rung.
 *
 * ⚠⚠ No subsampled read here: `setSourceSubsampling` picks pixels without filtering them and turned
 * 1 px stripes into a solid black rung (see [JvmThumbnailLadderRenderer]).
 */
internal interface JvmImageDecoder {

    /** The whole image at full size, or `null` when nothing here can read it. */
    fun decode(bytes: ByteArray): BufferedImage?

    object Default : JvmImageDecoder {

        /**
         * Decode [bytes] to a `BufferedImage`, falling back to macOS's ImageIO for
         * anything `javax.imageio` can't read.
         *
         * `ImageIO.read` has no HEIF plugin, so a HEIC — the iOS camera default —
         * returned `null` here and the file got **no thumbnail at all** on any device
         * (uploads from an iPhone were unaffected: `IosGetPreviewImplementation`
         * transcodes its preview to JPEG/PNG before it syncs, so only a HEIC uploaded
         * *from a Mac* hit this). [MacImageDecoder] shells out to the platform's own
         * codecs and hands back PNG/JPEG, which `ImageIO` then reads happily — the
         * same bridge the Compose viewer uses, hence the shared Compose-free module
         * (`drive-content-preview.md` §3.1).
         *
         * Keyed on `ImageIO` *failing* rather than on a format list, so exotic TIFFs,
         * camera raw and (on recent macOS) AVIF come along for free; JPEG and PNG
         * succeed on the first line and never spawn a process. Off macOS the fallback
         * is a no-op and the answer stays `null`, exactly as before.
         */
        override fun decode(bytes: ByteArray): BufferedImage? {
            // ⚠⚠ Upright (`ExifUpright`): `ImageIO` ignores EXIF, so a portrait JPEG decoded here
            // lay on its side in every rung minted from it. The ImageIO-bridge answer is NOT turned:
            // macOS applies the orientation itself, and turning it again is the double rotation.
            readImageIo(bytes)?.let { return it.upright(readMediaMetadata(bytes).orientation) }
            val transcoded = MacImageDecoder.decode(bytes) ?: return null
            return readImageIo(transcoded)
        }

        /**
         * Turned by one `drawImage` through an [AffineTransform] — native loops, one allocation,
         * and the same colour handling as [JvmThumbnailRaster.scale] (a per-pixel `getRGB` copy
         * would brighten a linear-grey JPEG). Exact quarter turns, so nearest neighbour is lossless.
         */
        private fun BufferedImage.upright(orientation: Int?): BufferedImage {
            val turn = ExifUpright.turnFor(orientation) ?: return this
            val w = width.toDouble()
            val h = height.toDouble()
            // `concatenate` applies its argument FIRST: the mirror, then the turn.
            val transform = when (turn.clockwiseDegrees) {
                90 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0) // (x, y) -> (h - y, x)
                180 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h) // (x, y) -> (w - x, h - y)
                270 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w) // (x, y) -> (y, w - x)
                else -> AffineTransform()
            }
            if (turn.mirror) transform.concatenate(AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0))
            val swaps = turn.clockwiseDegrees % 180 != 0
            val outWidth = if (swaps) height else width
            val outHeight = if (swaps) width else height
            // ⚠ RGB unless the source has alpha: a rung under its edge cap is encoded straight from
            // this image, and ImageIO's JPEG writer refuses an ARGB one.
            val type = if (colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            val turned = BufferedImage(outWidth, outHeight, type)
            val g = turned.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(this, transform, null)
            } finally {
                g.dispose()
            }
            return turned
        }

        private fun readImageIo(bytes: ByteArray): BufferedImage? = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            // A malformed payload can throw out of the reader rather than returning
            // null; treat both as "ImageIO can't read this" so the ImageIO-bridge
            // fallback still gets its turn.
            null
        }
    }
}
