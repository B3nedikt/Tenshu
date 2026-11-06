package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * ⚠⚠ The stored polyline format, pinned as bytes — because the web reads it with a **second**
 * decoder (`decodePolyline` in `:tenshu-sdk-js` `TimelineBook.kt`, web-app-suite Wave 2, Timeline
 * run A), and `TimelineBookMappingTest` there decodes exactly [GOLDEN]. Change the codec and this
 * fails first; then the web twin must follow in the same PR.
 */
class MovementTracePolylineGoldenTest {

    @Test
    fun encodeProducesTheGoldenBytesTheWebDecoderIsPinnedTo() {
        assertContentEquals(GOLDEN, MovementTracePolyline.encode(POINTS))
    }

    @Test
    fun theGoldenBytesDecodeBackToThePoints() {
        assertEquals(POINTS, MovementTracePolyline.decode(GOLDEN))
    }

    private companion object {
        val POINTS = listOf(
            CapturedFix(latE7 = 482_000_000, lngE7 = 115_000_000, timestampMs = 1_700_000_000_000, accuracyM = 5),
            CapturedFix(latE7 = 482_000_150, lngE7 = 115_000_300, timestampMs = 1_700_000_005_000, accuracyM = 7),
            CapturedFix(latE7 = 481_999_900, lngE7 = 114_999_800, timestampMs = 1_700_000_012_000, accuracyM = 4),
        )

        // ⚠ Byte-identical to `TimelineBookMappingTest.GOLDEN` in `:tenshu-sdk-js`.
        val GOLDEN = byteArrayOf(
            3, -128, -14, -43, -53, 3, -128, -117, -42, 109, -128, -96, -85, -2, -7, 98, 10,
            -84, 2, -40, 4, -112, 78, 4, -13, 3, -25, 7, -80, 109, 5,
        )
    }
}
