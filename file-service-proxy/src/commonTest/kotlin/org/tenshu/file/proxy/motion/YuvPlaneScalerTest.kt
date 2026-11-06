package org.tenshu.file.proxy.motion

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `MOTION` mint's only pixel work, pinned on the JVM: area averaging, the three clockwise
 * rotations, and every plane layout `YUV_420_888` can hand us (padded rows, interleaved chroma,
 * a short final row).
 */
class YuvPlaneScalerTest {

    private fun scale(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        rotation: Int = 0,
        rowStride: Int = srcWidth,
        pixelStride: Int = 1,
        srcOffset: Int = 0,
        srcLength: Int = src.size - srcOffset,
    ): ByteArray {
        val dst = ByteArray(dstWidth * dstHeight)
        YuvPlaneScaler.scale(
            src = src, srcOffset = srcOffset, srcLength = srcLength,
            srcWidth = srcWidth, srcHeight = srcHeight,
            rowStride = rowStride, pixelStride = pixelStride,
            dstWidth = dstWidth, dstHeight = dstHeight,
            rotationDegrees = rotation, dst = dst, dstOffset = 0,
        )
        return dst
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun sameSizeIsIdentity() {
        val src = bytes(1, 2, 3, 4)
        assertContentEquals(src, scale(src, 2, 2, 2, 2))
    }

    @Test
    fun halvingAveragesEachTwoByTwoBlock() {
        val src = ByteArray(16) { it.toByte() } // 4x4, value == index
        // (0,1,4,5) → 2 · (2,3,6,7) → 4 · (8,9,12,13) → 10 · (10,11,14,15) → 12
        assertContentEquals(bytes(2, 4, 10, 12), scale(src, 4, 4, 2, 2))
    }

    @Test
    fun unevenRatiosCoverEverySourceSampleExactlyOnce() {
        // 3 wide → 2 wide: blocks [0,1) and [1,3) — nothing skipped, nothing double-counted.
        val src = bytes(0, 30, 90)
        assertContentEquals(bytes(0, 60), scale(src, 3, 1, 2, 1))
    }

    @Test
    fun highValuesDoNotOverflowThroughTheSignedByte() {
        val src = bytes(255, 255, 255, 255)
        assertContentEquals(bytes(255), scale(src, 2, 2, 1, 1))
    }

    @Test
    fun clockwiseNinetyPutsTheLeftEndOnTop() {
        // A horizontal strip [A B] rotated 90° CW reads A over B.
        assertContentEquals(bytes(7, 9), scale(bytes(7, 9), 2, 1, 1, 2, rotation = 90))
        // 2x2 [[a b],[c d]] → [[c a],[d b]]
        assertContentEquals(bytes(3, 1, 4, 2), scale(bytes(1, 2, 3, 4), 2, 2, 2, 2, rotation = 90))
    }

    @Test
    fun oneEightyReversesBothAxes() {
        assertContentEquals(bytes(9, 7), scale(bytes(7, 9), 2, 1, 2, 1, rotation = 180))
        assertContentEquals(bytes(4, 3, 2, 1), scale(bytes(1, 2, 3, 4), 2, 2, 2, 2, rotation = 180))
    }

    @Test
    fun twoSeventyPutsTheRightEndOnTop() {
        assertContentEquals(bytes(9, 7), scale(bytes(7, 9), 2, 1, 1, 2, rotation = 270))
        // [[a b],[c d]] → [[b d],[a c]]
        assertContentEquals(bytes(2, 4, 1, 3), scale(bytes(1, 2, 3, 4), 2, 2, 2, 2, rotation = 270))
    }

    @Test
    fun negativeAndWrappedRotationsNormalise() {
        assertContentEquals(bytes(9, 7), scale(bytes(7, 9), 2, 1, 1, 2, rotation = -90))
        assertContentEquals(bytes(7, 9), scale(bytes(7, 9), 2, 1, 1, 2, rotation = 450))
    }

    @Test
    fun scalingAndRotationCompose() {
        // 4x2 → 2x1 in source space, then 90° CW → 1x2.
        val src = bytes(
            0, 0, 100, 100,
            0, 0, 100, 100,
        )
        assertContentEquals(bytes(0, 100), scale(src, 4, 2, 1, 2, rotation = 90))
    }

    @Test
    fun paddedRowsAreReadThroughTheRowStride() {
        val src = bytes(
            1, 2, 99, 99,
            3, 4, 99, 99,
        )
        assertContentEquals(bytes(1, 2, 3, 4), scale(src, 2, 2, 2, 2, rowStride = 4))
    }

    /** NV12's chroma: U and V interleaved, each plane a `pixelStride` 2 view from its own offset. */
    @Test
    fun interleavedChromaIsReadThroughThePixelStride() {
        val src = bytes(10, 20, 11, 21) // u0 v0 u1 v1
        assertContentEquals(bytes(10, 11), scale(src, 2, 1, 2, 1, rowStride = 4, pixelStride = 2))
        assertContentEquals(
            bytes(20, 21),
            scale(src, 2, 1, 2, 1, rowStride = 4, pixelStride = 2, srcOffset = 1, srcLength = 3),
        )
    }

    /** A codec's last row can be shorter than its stride: reads past the window are simply absent. */
    @Test
    fun readsBeyondTheSourceWindowContributeNothing() {
        val src = bytes(10, 20, 30, 40)
        // Only the first row is inside the window: the 2x2 block averages 10 and 20.
        assertContentEquals(bytes(15), scale(src, 2, 2, 1, 1, srcLength = 2))
        // No sample at all inside the window: 0, not an exception.
        assertContentEquals(bytes(0), scale(src, 2, 2, 1, 1, srcLength = 0))
    }

    @Test
    fun aQuarterTurnThatIsNotOneIsRejected() {
        assertFailsWith<IllegalArgumentException> { scale(bytes(1, 2, 3, 4), 2, 2, 2, 2, rotation = 45) }
    }

    @Test
    fun aFrameLaysOutPackedI420() {
        val frame = YuvFrame(width = 4, height = 2, presentationUs = 0L)
        assertEquals(0, frame.yOffset)
        assertEquals(8, frame.uOffset)
        assertEquals(10, frame.vOffset)
        assertEquals(12, frame.byteCount)
        assertEquals(12, frame.data.size)
        assertFailsWith<IllegalArgumentException> { YuvFrame(width = 3, height = 2, presentationUs = 0L) }
    }
}
