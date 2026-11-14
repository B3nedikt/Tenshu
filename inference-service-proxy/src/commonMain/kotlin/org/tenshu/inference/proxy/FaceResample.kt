package org.tenshu.inference.proxy

/**
 * The aligned square a face embedder consumes, read out of a decoded image through [FaceCrop.unmap]
 * — nearest neighbour, at pixel centres, clamped at the border (`ios-face-grouping.md` §4, F3).
 *
 * ⚠⚠ **Shared so iOS and Android crop with the same arithmetic, and frozen because Android's
 * calibration was measured on it.** `FaceClustering.Measured` and the 0.44 threshold rest on this
 * exact chain: the destination pixel's CENTRE (`x + 0.5`), `unmap` into source pixels, `toInt()`
 * (truncation toward zero, not `floor`), then `coerceIn` to the image. A bilinear read or a
 * `roundToInt` would be an "improvement" that moves every cosine without failing anything that
 * tests the maths, which is why `MediaPipeFaceResampleGoldenTest` (Android host) and
 * `FaceResampleTest` pin the output pixel for pixel, with numbers recorded before this moved here.
 *
 * The pixels are copied, never looked inside: packed ARGB in, packed ARGB out, which is what
 * `ImagePreprocessing.preprocessPixels` reads.
 *
 * @param width the source image's width in pixels — the image the landmarks were found in, after
 *   any EXIF rotation or downscale, exactly as given to [alignFace].
 * @param side the output square's edge; the template's side.
 * @param pixelAt the source pixel at `(x, y)`, both already inside the image. ⚠ A reader rather
 *   than an array so Android reads its `Bitmap` in place: copying a 2048-px decode out per face
 *   would be 16 MB on Control's heap that the measured pipeline never allocated.
 */
inline fun resampleFace(
    width: Int,
    height: Int,
    side: Int,
    crop: FaceCrop,
    pixelAt: (x: Int, y: Int) -> Int,
): IntArray {
    require(width > 0 && height > 0) { "an image has a positive size; was ${width}x$height" }
    val out = IntArray(side * side)
    for (y in 0 until side) {
        for (x in 0 until side) {
            // ⚠ Pixel centres, and `unmap` answers in the pixel space the landmarks were fitted in.
            val at = crop.unmap(FacePoint(x + 0.5f, y + 0.5f))
            out[y * side + x] = pixelAt(at.x.toInt().coerceIn(0, width - 1), at.y.toInt().coerceIn(0, height - 1))
        }
    }
    return out
}

/**
 * [resampleFace] over a decoded image held as row-major packed ARGB — the shape `IosImageDecoder`
 * produces.
 */
fun resampleFace(pixels: IntArray, width: Int, height: Int, side: Int, crop: FaceCrop): IntArray {
    require(pixels.size == width * height) { "${pixels.size} pixels is not a ${width}x$height image" }
    return resampleFace(width, height, side, crop) { x, y -> pixels[y * width + x] }
}
