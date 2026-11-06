package org.tenshu.file.proxy.motion

/**
 * Area-average downscaling of one 8-bit image plane, with a clockwise rotation folded in — the
 * whole of the `MOTION` mint's pixel work (`photos-video-support.md` §14).
 *
 * ⚠ Why this exists at all, rather than a `Surface` and a GL shader. The textbook Android
 * transcode (decoder → `SurfaceTexture` → GL quad → encoder input `Surface`) is ~1 000 lines of EGL
 * plumbing whose frame timestamps come from the *render clock*, not the source — so a clip's
 * length would depend on how fast the phone drew it. Decoding to flexible YUV buffers, scaling the
 * three planes here and feeding the encoder's `Image` gives exact presentation times, no GL, and a
 * pure function that a JVM test can pin. At 256 px the per-frame cost is a few milliseconds.
 *
 * Works on any plane layout `YUV_420_888` can describe: [rowStride] between rows and [pixelStride]
 * between samples in a row (1 for planar I420, 2 for the interleaved chroma of NV12/NV21). Output
 * is always packed (`pixelStride` 1, `rowStride == width`), which is what the encoder-side copy
 * wants. Area averaging (every source sample of the block contributes once) is the right filter
 * for a 4–15× reduction: bilinear at that ratio samples a handful of pixels per output and shimmers
 * on motion, which is the artifact a *looping* tile shows most.
 */
internal object YuvPlaneScaler {

    /**
     * Scales the plane in [src] (a [srcWidth] × [srcHeight] grid read with [rowStride] /
     * [pixelStride], occupying [srcLength] bytes from [srcOffset]) to [dstWidth] × [dstHeight]
     * **in rotated space**, rotating clockwise by [rotationDegrees] (0/90/180/270) on the way, into
     * [dst] at [dstOffset].
     *
     * ⚠ [dstWidth] / [dstHeight] describe the *output*, so for a 90° rotation of a landscape source
     * they are portrait. A read that would fall outside the [srcLength] window contributes nothing
     * (a codec's last chroma row is often shorter than its stride), so a short buffer degrades to a
     * slightly darker edge sample rather than an exception.
     */
    fun scale(
        src: ByteArray,
        srcOffset: Int,
        srcLength: Int,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        dstWidth: Int,
        dstHeight: Int,
        rotationDegrees: Int,
        dst: ByteArray,
        dstOffset: Int,
    ) {
        require(srcWidth > 0 && srcHeight > 0) { "source plane must be non-empty" }
        require(dstWidth > 0 && dstHeight > 0) { "destination plane must be non-empty" }
        require(pixelStride >= 1 && rowStride >= 1) { "strides must be positive" }
        val rotation = MotionClipPlan.normaliseRotation(rotationDegrees)
        require(rotation % 90 == 0) { "rotation must be a multiple of 90, got $rotationDegrees" }
        require(dst.size - dstOffset >= dstWidth * dstHeight) { "destination too small" }
        val srcEnd = minOf(src.size, srcOffset + srcLength)

        // Scale in the source's own orientation, then rotate the (small) result.
        val transposed = rotation % 180 != 0
        val unrotatedWidth = if (transposed) dstHeight else dstWidth
        val unrotatedHeight = if (transposed) dstWidth else dstHeight
        val unrotated = ByteArray(unrotatedWidth * unrotatedHeight)

        for (uy in 0 until unrotatedHeight) {
            val sy0 = (uy.toLong() * srcHeight / unrotatedHeight).toInt()
            val sy1 = maxOf(sy0 + 1, ((uy + 1).toLong() * srcHeight / unrotatedHeight).toInt())
            for (ux in 0 until unrotatedWidth) {
                val sx0 = (ux.toLong() * srcWidth / unrotatedWidth).toInt()
                val sx1 = maxOf(sx0 + 1, ((ux + 1).toLong() * srcWidth / unrotatedWidth).toInt())
                var sum = 0
                var count = 0
                for (sy in sy0 until sy1) {
                    val rowStart = srcOffset + sy * rowStride
                    for (sx in sx0 until sx1) {
                        val index = rowStart + sx * pixelStride
                        if (index < srcEnd) {
                            sum += src[index].toInt() and 0xFF
                            count++
                        }
                    }
                }
                unrotated[uy * unrotatedWidth + ux] = if (count == 0) 0 else (sum / count).toByte()
            }
        }

        rotateInto(unrotated, unrotatedWidth, unrotatedHeight, rotation, dst, dstOffset)
    }

    /**
     * Writes [plane] ([width] × [height], packed) into [dst] rotated clockwise by [rotation]
     * (0/90/180/270). The rotated plane is `height × width` for 90/270.
     *
     * Clockwise 90°: the old bottom-left becomes the new top-left, so `new(x, y) = old(y, h−1−x)`.
     */
    private fun rotateInto(
        plane: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        dst: ByteArray,
        dstOffset: Int,
    ) {
        when (rotation) {
            0 -> plane.copyInto(dst, dstOffset, 0, width * height)
            90 -> {
                // new dims: height × width
                val newWidth = height
                for (y in 0 until width) {
                    for (x in 0 until newWidth) {
                        dst[dstOffset + y * newWidth + x] = plane[(height - 1 - x) * width + y]
                    }
                }
            }
            180 -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        dst[dstOffset + y * width + x] = plane[(height - 1 - y) * width + (width - 1 - x)]
                    }
                }
            }
            270 -> {
                val newWidth = height
                for (y in 0 until width) {
                    for (x in 0 until newWidth) {
                        dst[dstOffset + y * newWidth + x] = plane[x * width + (width - 1 - y)]
                    }
                }
            }
            else -> error("unreachable: rotation $rotation")
        }
    }
}

/**
 * One clip frame, packed I420 (`Y` then `U` then `V`, each `pixelStride` 1) at [width] × [height]
 * (both even), stamped with its presentation time inside the clip.
 *
 * Sized for the [MotionClipPlan.LONG_EDGE_PX] clip: ≤ 256 × 256 × 1.5 ≈ 98 KB, so the ~36 frames
 * of a window fit comfortably in memory — which is what makes the one bitrate retry an
 * encode-only pass rather than a second decode.
 */
internal class YuvFrame(
    val width: Int,
    val height: Int,
    val presentationUs: Long,
) {
    init {
        require(width > 0 && width % 2 == 0 && height > 0 && height % 2 == 0) {
            "I420 needs even dimensions, got ${width}x$height"
        }
    }

    val chromaWidth: Int = width / 2
    val chromaHeight: Int = height / 2
    val yOffset: Int = 0
    val uOffset: Int = width * height
    val vOffset: Int = uOffset + chromaWidth * chromaHeight

    /** `width × height × 3 / 2`. */
    val byteCount: Int = vOffset + chromaWidth * chromaHeight

    val data: ByteArray = ByteArray(byteCount)
}
