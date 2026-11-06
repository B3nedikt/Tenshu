package org.tenshu.inference.proxy.onnx

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The desktop half of preprocessing (IN-3b) — the arithmetic that follows is [ImagePreprocessing].
 *
 * ⚠⚠ **The crop-or-squash decision belongs to the model's profile, not to this file.** A centre
 * crop is the more *honest*-looking option — squashing distorts every photo in the library — but
 * the criterion is what the model was trained on, and SigLIP's own processor squashes.
 * `models.md` §3.7.1 is two sessions of quality numbers measured before that was understood.
 *
 * ⚠ This resampler is not Android's, so the same photograph yields slightly different vectors on
 * the two platforms. Tolerable only because IN-D5 keeps embeddings device-local — see
 * [ImagePreprocessing.preprocessPixels].
 *
 * ⚠ `ImageIO` does not decode HEIC, so an iPhone-shot library on desktop reads as unreadable
 * files rather than as an error. `drive-content-preview.md` §3.1 has the bridge this repo already
 * bundles; wiring it here belongs with the desktop indexer.
 */
object AwtImageDecoder : ImageDecoder {

    override fun decode(bytes: ByteArray, size: Int, centreCrop: Boolean): IntArray? {
        val image = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
        return pixelsOf(image, size, centreCrop)
    }

    /** ⚠ Also the harness's entry point, so the measurement and production resize identically. */
    fun pixelsOf(image: BufferedImage, size: Int, centreCrop: Boolean): IntArray {
        val source = if (centreCrop) {
            val side = minOf(image.width, image.height)
            image.getSubimage((image.width - side) / 2, (image.height - side) / 2, side, side)
        } else {
            image
        }

        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            drawImage(source.getScaledInstance(size, size, BufferedImage.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }

        val argb = IntArray(size * size)
        scaled.getRGB(0, 0, size, size, argb, 0, size)
        return argb
    }
}
