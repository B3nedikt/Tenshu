package app.tenshu.photos.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.ui.components.faceRequestPx
import app.tenshu.photos.ui.components.faceSourceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IN-6 F6a: where a person header's face is cut from, and how big a preview it asks for. */
class FaceAvatarGeometryTest {

    @Test
    fun theCropIsTheFaceGrownByAMarginAndSquare() {
        // A 1000×800 photo, face at (0.4,0.4)–(0.6,0.6): 200×160 px, so a 200 px square face.
        val (offset, size) = faceSourceRect(1000, 800, FaceRegion(0.4f, 0.4f, 0.6f, 0.6f))

        assertEquals(IntSize(300, 300), size, "200 px face + 25% on each side")
        // Centred on the face centre (500, 400).
        assertEquals(IntOffset(350, 250), offset)
    }

    /** ⚠ A face at the frame's edge must not read outside the bitmap. */
    @Test
    fun aFaceAtTheEdgeIsKeptInsideTheImage() {
        val (offset, size) = faceSourceRect(1000, 800, FaceRegion(0.9f, 0.0f, 1.0f, 0.1f))

        assertTrue(offset.x >= 0 && offset.y >= 0)
        assertTrue(offset.x + size.width <= 1000 && offset.y + size.height <= 800)
    }

    @Test
    fun noRegionIsTheCentredSquareOfTheWholePhoto() {
        assertEquals(IntOffset(100, 0) to IntSize(800, 800), faceSourceRect(1000, 800, null))
    }

    /** A small face needs a bigger preview, or the avatar is drawn from a handful of pixels. */
    @Test
    fun aSmallerFaceAsksForALargerPreviewWithinTheCap() {
        val big = faceRequestPx(200, FaceRegion(0f, 0f, 0.8f, 0.8f))
        val small = faceRequestPx(200, FaceRegion(0f, 0f, 0.1f, 0.1f))

        assertTrue(small > big)
        assertTrue(small <= 2048)
    }
}
