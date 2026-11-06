package app.tenshu.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `:ui-core` half of §13 P2 — backend selection and the preview render — kept camera-free.
 *
 * The engine's own coverage lives in `:macos-helpers`; what can go wrong *here* is picking the
 * wrong backend or rastering a frame incorrectly, and both are testable without a camera.
 * Live capture stays the §13.4 human gate.
 */
class FfmScannerAdapterTest {

    private val mac = isMacOs()

    @Test
    fun defaultsToTheSwiftHelperEvenOnMac() {
        if (!mac) return
        val previous = System.getProperty(QrCameraScanner.IMPL_PROPERTY)
        System.clearProperty(QrCameraScanner.IMPL_PROPERTY)
        try {
            // Load-bearing default, not an accident. Since §13 P3 the FFM path can decode, so
            // the reason has moved: what's still unverified is first-grant survival on a
            // signed build against a live camera — the one claim no test can make. The flip
            // belongs with that pass, and this guards against it drifting in early.
            assertTrue(
                QrCameraScanner.forCurrentOs() is MacQrCameraScanner,
                "the Swift helper stays the default until the on-device pass (§13.4)",
            )
        } finally {
            previous?.let { System.setProperty(QrCameraScanner.IMPL_PROPERTY, it) }
        }
    }

    @Test
    fun selectsTheInProcessScannerWhenTheFlagIsSet() {
        if (!mac) return
        val previous = System.getProperty(QrCameraScanner.IMPL_PROPERTY)
        System.setProperty(QrCameraScanner.IMPL_PROPERTY, QrCameraScanner.IMPL_FFM)
        try {
            assertTrue(
                QrCameraScanner.forCurrentOs() is FfmMacQrCameraScanner,
                "-D${QrCameraScanner.IMPL_PROPERTY}=${QrCameraScanner.IMPL_FFM} must select the FFM path",
            )
        } finally {
            if (previous == null) System.clearProperty(QrCameraScanner.IMPL_PROPERTY)
            else System.setProperty(QrCameraScanner.IMPL_PROPERTY, previous)
        }
    }

    @Test
    fun rastersABgraFrameWhoseStrideExceedsItsWidth() {
        // The case that matters. CoreVideo pads rows, so a 3-px-wide frame can arrive with a
        // 16-byte stride; a renderer that assumed `width * 4` would shear the image on every
        // device whose width isn't already aligned. Here the padding is real (12 → 16).
        val width = 3
        val height = 2
        val stride = 16
        val bytes = ByteArray(stride * height) { 0x7F }

        val bitmap = assertNotNull(
            decodeBgraFrame(bytes, width, height, stride),
            "a padded BGRA frame must raster",
        )
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
    }

    @Test
    fun rastersAnUnpaddedBgraFrame() {
        val bitmap = assertNotNull(decodeBgraFrame(ByteArray(4 * 4 * 4), 4, 4, 16))
        assertEquals(4, bitmap.width)
        assertEquals(4, bitmap.height)
    }

    @Test
    fun rejectsFramesThatCannotBeRasteredInsteadOfReadingPastTheBuffer() {
        // Each of these would otherwise be an out-of-bounds read against Skia rather than a
        // clean null, which on a live camera is the difference between a blank preview and a
        // crash in the render loop.
        assertNull(decodeBgraFrame(ByteArray(16), width = 0, height = 1, bytesPerRow = 4))
        assertNull(decodeBgraFrame(ByteArray(16), width = 1, height = 0, bytesPerRow = 4))
        assertNull(
            decodeBgraFrame(ByteArray(16), width = 4, height = 1, bytesPerRow = 8),
            "a stride narrower than width * 4 is malformed",
        )
        assertNull(
            decodeBgraFrame(ByteArray(8), width = 2, height = 2, bytesPerRow = 8),
            "a buffer shorter than stride * height must not be read past its end",
        )
    }

    @Test
    fun rejectsGarbageJpegWithoutThrowing() {
        assertNull(decodeJpegFrame(ByteArray(0)))
        assertNull(decodeJpegFrame(ByteArray(64) { it.toByte() }))
    }
}
