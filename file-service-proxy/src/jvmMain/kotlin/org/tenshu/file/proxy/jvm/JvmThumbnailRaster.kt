package org.tenshu.file.proxy.jvm

import org.tenshu.core.file.GetPreview
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * The fit, scale and encode steps of a JVM thumbnail render, shared by [JvmGetPreviewImplementation]
 * (one rung per call) and [JvmThumbnailLadderRenderer] (the whole ladder from one decode).
 *
 * ⚠ Shared rather than copied so the two paths cannot drift: P1a's contract is that a rung is the same
 * size, format and cap whichever path minted it, and two spellings of the edge rule is how they would
 * stop being.
 */
internal object JvmThumbnailRaster {

    /**
     * The output size for a source of [sourceWidth] × [sourceHeight] fitted within [width] × [height]:
     * the source's own size when it already fits (never upscaled), else scaled to the box keeping its
     * aspect. `null` when the answer would be empty.
     */
    fun fitWithin(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Pair<Int, Int>? {
        val (outWidth, outHeight) = if (sourceWidth <= width && sourceHeight <= height) {
            sourceWidth to sourceHeight
        } else {
            val aspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            if (width / aspectRatio <= height) {
                width to (width / aspectRatio).toInt()
            } else {
                (height * aspectRatio).toInt() to height
            }
        }
        return if (outWidth <= 0 || outHeight <= 0) null else outWidth to outHeight
    }

    /** [source] drawn bilinear into a new `TYPE_INT_RGB` bitmap — no alpha, which JPEG requires. */
    fun scale(source: BufferedImage, outWidth: Int, outHeight: Int): BufferedImage {
        val resized = BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, outWidth, outHeight, null)
        g.dispose()
        return resized
    }

    /**
     * PNG, or JPEG at [quality] (1–100) — ImageIO's own default when `null`, which is what every
     * render before `GetPreview.Params.QUALITY` got, so an unparameterised call is byte-identical.
     */
    fun encode(image: BufferedImage, format: String, quality: Int?): ByteArray? {
        val outputStream = ByteArrayOutputStream()
        if (format != GetPreview.Params.FORMAT_JPEG) {
            ImageIO.write(image, "png", outputStream)
            return outputStream.toByteArray()
        }
        if (quality == null) {
            ImageIO.write(image, "jpeg", outputStream)
            return outputStream.toByteArray()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(1, 100) / 100f
            }
            MemoryCacheImageOutputStream(outputStream).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return outputStream.toByteArray()
    }
}
