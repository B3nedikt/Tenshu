package app.tenshu.photos

import app.tenshu.photos.model.FaceCropSquare
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.faceCropRequestPx
import app.tenshu.photos.model.faceCropSquare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ios-face-grouping.md` F7: the face crop, shared so the SwiftUI person card cuts the square
 * Compose's `FaceAvatar` cuts (`FaceAvatarGeometryTest` pins Compose's delegation, unchanged).
 */
class FaceCropTest {

    @Test
    fun theCropIsTheFaceGrownByAMarginSquareAndCentredOnTheFace() {
        // A 1000×800 photo, face at (0.4,0.4)–(0.6,0.6): 200×160 px, so a 200 px face + 25% a side.
        assertEquals(FaceCropSquare(350, 250, 300), faceCropSquare(1000, 800, FaceRegion(0.4f, 0.4f, 0.6f, 0.6f)))
    }

    /** ⚠ An off-centre face is NOT the centred square — the whole point of the crop. */
    @Test
    fun aFaceInTheCornerIsCutFromTheCornerNotTheMiddle() {
        val crop = faceCropSquare(1000, 800, FaceRegion(0.05f, 0.05f, 0.15f, 0.15f))
        assertEquals(FaceCropSquare(25, 5, 150), crop, "centred on the face at (100, 80)")
        assertTrue(crop != faceCropSquare(1000, 800, null))
    }

    @Test
    fun aFaceAtTheEdgeIsKeptInsideTheImage() {
        val crop = faceCropSquare(1000, 800, FaceRegion(0.9f, 0.0f, 1.0f, 0.1f))
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.left + crop.side <= 1000 && crop.top + crop.side <= 800)
    }

    @Test
    fun noRegionOrAnEmptyOneIsTheCentredSquareOfTheWholePhoto() {
        assertEquals(FaceCropSquare(100, 0, 800), faceCropSquare(1000, 800, null))
        assertEquals(FaceCropSquare(100, 0, 800), faceCropSquare(1000, 800, FaceRegion(0.5f, 0.5f, 0.5f, 0.5f)))
    }

    @Test
    fun aSmallerFaceAsksForALargerPreviewWithinTheCap() {
        val big = faceCropRequestPx(200, FaceRegion(0f, 0f, 0.8f, 0.8f))
        val small = faceCropRequestPx(200, FaceRegion(0f, 0f, 0.1f, 0.1f))
        assertTrue(small > big)
        assertTrue(small <= 2048)
        assertEquals(400, faceCropRequestPx(200, null), "no face: twice the edge, as a tile asks")
    }
}
