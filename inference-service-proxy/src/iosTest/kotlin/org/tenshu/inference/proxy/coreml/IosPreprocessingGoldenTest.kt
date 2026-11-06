package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.tensorDigest
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * What iOS feeds the model for two fixed images, pinned per [IOS_PREPROCESSING_REVISION]
 * (`ios-on-device-inference.md` §3.2, *The runtime fingerprint*).
 *
 * ⚠⚠ **The revision is bumped by hand, and a hand-bumped constant decays.** The fingerprint clears
 * the stored vectors when the revision changes; if the transform changes and the revision does
 * not, old vectors stay beside new ones made differently and nothing can tell them apart. This
 * test is the reminder: the tensor moved, so say whether the revision should.
 *
 * Two images, because they fail for different reasons:
 * - **exact**: 256 × 256, so nothing is resampled. Its tensor is decided by this repo alone (the
 *   decoder's channel order, colour space and alpha handling, and `ImagePreprocessing`).
 * - **resampled**: 1024 × 768, squashed to the square. Its tensor is also decided by ImageIO's and
 *   Core Graphics' resamplers, which belong to the OS.
 *
 * ⚠ The images are uncompressed BMPs built here byte by byte, not JPEGs: an encoder is one more
 * thing that could move. So EXIF orientation is not covered; `IosImageDecoderTest` pins that.
 */
class IosPreprocessingGoldenTest {

    private class Golden(val exact: String, val resampled: String)

    private val side = 256

    /** A 24-bit bottom-up BMP: 54 header bytes, then rows of BGR padded to four bytes. */
    private fun bmp(width: Int, height: Int, colour: (x: Int, y: Int) -> Int): ByteArray {
        val rowSize: Int = (width * 3 + 3) / 4 * 4
        val out = ByteArray(54 + rowSize * height)
        fun intLe(at: Int, value: Int) {
            for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
        }
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        intLe(2, out.size)
        intLe(10, 54)
        intLe(14, 40)
        intLe(18, width)
        intLe(22, height)
        out[26] = 1
        out[28] = 24
        intLe(34, rowSize * height)
        intLe(38, 2835)
        intLe(42, 2835)
        for (y in 0 until height) {
            val row: Int = 54 + (height - 1 - y) * rowSize
            for (x in 0 until width) {
                val rgb: Int = colour(x, y)
                out[row + 3 * x] = rgb.toByte()
                out[row + 3 * x + 1] = (rgb shr 8).toByte()
                out[row + 3 * x + 2] = (rgb shr 16).toByte()
            }
        }
        return out
    }

    /** Every channel value appears, and no two rows or columns are alike. */
    private fun exactColour(x: Int, y: Int): Int = (x shl 16) or (y shl 8) or ((x * 3 + y * 5) and 0xFF)

    /** Smooth ramps under a hard-edged grid: a resampler shows in both. */
    private fun resampledColour(x: Int, y: Int): Int {
        val grid: Boolean = (x / 37 + y / 29) % 2 == 0
        val red: Int = x * 255 / 1023
        val green: Int = y * 255 / 767
        val blue: Int = if (grid) 230 else 25
        return (red shl 16) or (green shl 8) or blue
    }

    private fun tensorOf(image: ByteArray): FloatArray {
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(image, side, centreCrop = false), "ImageIO refused a BMP")
        return ImagePreprocessing.preprocessPixels(argb, side, ImagePreprocessing.Profile.SIGLIP)
    }

    private fun golden(): Golden = GOLDEN[IOS_PREPROCESSING_REVISION] ?: fail(
        """
        IOS_PREPROCESSING_REVISION is $IOS_PREPROCESSING_REVISION and no tensor is recorded for it.
        Add a row for it to GOLDEN in this file: run this test and copy the two digests it reports.
        Keep the earlier rows.
          exact:     ${tensorDigest(tensorOf(bmp(side, side, ::exactColour)))}
          resampled: ${tensorDigest(tensorOf(bmp(1024, 768, ::resampledColour)))}
        """.trimIndent(),
    )

    /** With nothing to resample, the decoder hands over the stored pixels themselves. */
    @Test
    fun anImageOfTheModelsSizeReachesTheModelPixelForPixel() {
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(bmp(side, side, ::exactColour), side, centreCrop = false))

        var wrong = 0
        var firstWrong = ""
        for (y in 0 until side) {
            for (x in 0 until side) {
                val expected: Int = (0xFF shl 24) or exactColour(x, y)
                if (argb[y * side + x] != expected) {
                    if (wrong == 0) firstWrong = "($x, $y): #${argb[y * side + x].toUInt().toString(16)}, stored #${expected.toUInt().toString(16)}"
                    wrong++
                }
            }
        }
        assertEquals(0, wrong, "$wrong pixels differ from the stored image, first at $firstWrong")
    }

    @Test
    fun theTensorOfTheExactImageHasNotMovedUnderThisRevision() {
        assertEquals(
            golden().exact,
            tensorDigest(tensorOf(bmp(side, side, ::exactColour))),
            """
            iOS now feeds the model a different tensor for the same image, under the same
            IOS_PREPROCESSING_REVISION ($IOS_PREPROCESSING_REVISION). Nothing was resampled for this
            image, so the change is in this repo: IosImageDecoder (channel order, colour space,
            alpha) or ImagePreprocessing.
              1. Bump IOS_PREPROCESSING_REVISION (iosMain/…/coreml/IosImageDecoder.kt). The fingerprint
                 then clears the vectors made the old way (ios-on-device-inference.md §3.2).
              2. Add a row to GOLDEN for the new revision with the digests this test reports.
                 Do not edit the row of a revision that has shipped.
            If the change is not intended, it is the bug.
            """.trimIndent(),
        )
    }

    @Test
    fun theTensorOfTheResampledImageHasNotMovedUnderThisRevision() {
        val runtime: String = NSProcessInfo.processInfo.operatingSystemVersionString
        println("IOS-GOLDEN resampled digest taken on $runtime")
        assertEquals(
            golden().resampled,
            tensorDigest(tensorOf(bmp(1024, 768, ::resampledColour))),
            """
            iOS now feeds the model a different tensor for a photo that had to be scaled, under
            the same IOS_PREPROCESSING_REVISION ($IOS_PREPROCESSING_REVISION).
            First ask what changed:
              - IosImageDecoder (decodeLongEdge, the thumbnail options, the interpolation quality,
                the colour space) or ImagePreprocessing changed: bump IOS_PREPROCESSING_REVISION
                and add a GOLDEN row for the new revision. Do not edit a shipped revision's row.
              - Nothing in this repo changed, and theTensorOfTheExactImage… still passes: a new
                Xcode or simulator runtime resamples differently. The fingerprint already clears
                the vectors on an OS build change (§3.2), so the revision stays; replace this
                revision's `resampled` digest and say which runtime it was taken on.
            This run: $runtime.
            """.trimIndent(),
        )
    }

    private companion object {
        /**
         * Revision → the two digests. Append a row with a bump; a shipped row is history.
         *
         * `resampled` was taken on the iOS simulator runtime named beside it.
         */
        val GOLDEN: Map<Int, Golden> = mapOf(
            // `resampled`: the iOS 27.0 (24A434) simulator runtime, arm64, Xcode 27.0, 2026-10-06.
            1 to Golden(exact = "36a1513db934c31d", resampled = "84210b5db1be675a"),
        )
    }
}
