package org.tenshu.inference.proxy.onnx

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Android's half of preprocessing (IN-3b) — the arithmetic that follows is [ImagePreprocessing].
 *
 * ⚠⚠ **`inSampleSize` first, then the exact scale.** A 12-megapixel phone photo decoded at full
 * resolution is ~48 MB of `Bitmap`, and an index pass does thousands of them in a loop: on
 * Control's 256 MB heap that is an OOM rather than an index. Sub-sampling to roughly twice the
 * target keeps the resample quality while bounding residency. ⚠ This is the same decode IN-3m
 * measured (§15.12), so the wall-clock figures describe this code.
 */
object AndroidImageDecoder : ImageDecoder {

    override fun decode(bytes: ByteArray, size: Int, centreCrop: Boolean): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size * 2) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val source = if (centreCrop) {
            val side = minOf(decoded.width, decoded.height)
            Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        } else {
            decoded
        }

        val scaled = Bitmap.createScaledBitmap(source, size, size, /* filter = */ true)
        val argb = IntArray(size * size)
        scaled.getPixels(argb, 0, size, 0, 0, size, size)

        // ⚠ Released eagerly rather than left to the GC: a pass allocates these thousands of times
        // and the collector has no idea how expensive the native half is.
        if (scaled !== source) scaled.recycle()
        if (source !== decoded) source.recycle()
        decoded.recycle()
        return argb
    }
}
