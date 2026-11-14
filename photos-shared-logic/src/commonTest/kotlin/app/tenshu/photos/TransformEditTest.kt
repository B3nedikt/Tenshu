package app.tenshu.photos

import app.tenshu.photos.edit.AspectPreset
import app.tenshu.photos.edit.CropHandle
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.TransformEdit
import app.tenshu.photos.edit.dragCrop
import app.tenshu.photos.edit.normalisedRatio
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Transform recipe (`photos-editing.md` ED-1): what each control does to the stored edit, and
 * what the render plan draws from it.
 *
 * ⚠⚠ The plan is checked by **where pixels land**, not by matrix entries: a test of entries would
 * pass for a plan that drew the right shape at the wrong place.
 */
class TransformEditTest {

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.01f, what: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, was $actual")

    private fun nearRect(expected: NormRect, actual: NormRect, what: String = "") {
        near(expected.left, actual.left, what = "$what left")
        near(expected.top, actual.top, what = "$what top")
        near(expected.right, actual.right, what = "$what right")
        near(expected.bottom, actual.bottom, what = "$what bottom")
    }

    @Test
    fun theUntouchedEditIsIdentity_andAnAspectLockAloneIsNotAChange() {
        assertTrue(TransformEdit().isIdentity)
        val locked = TransformEdit().copy(aspect = AspectPreset.SQUARE)
        assertTrue(locked.isIdentity, "choosing a preset and keeping the whole frame changes no pixel")
        assertFalse(TransformEdit().rotatedAnticlockwise().isIdentity)
        assertFalse(TransformEdit().mirroredOnScreen().isIdentity)
    }

    @Test
    fun fourRotationsAreTheIdentity_andTwoMirrorsToo() {
        var e = TransformEdit()
        repeat(4) { e = e.rotatedAnticlockwise() }
        assertTrue(e.isIdentity)
        assertTrue(TransformEdit().mirroredOnScreen().mirroredOnScreen().isIdentity)
    }

    @Test
    fun rotationSwapsTheOutputEdges_andMapsTheTopRightCornerToTopLeft() {
        val plan = TransformEdit().rotatedAnticlockwise().plan(400, 300)
        assertEquals(300, plan.outputWidth)
        assertEquals(400, plan.outputHeight)
        // Anticlockwise: the source's top-right corner ends at the output's top-left.
        val (x, y) = plan.map(400f, 0f)
        near(0f, x, what = "x"); near(0f, y, what = "y")
        // …and the source's top-left at the output's bottom-left.
        val (x2, y2) = plan.map(0f, 0f)
        near(0f, x2, what = "x2"); near(400f, y2, what = "y2")
    }

    /**
     * Rotate turns what is on screen anticlockwise whether or not the picture is mirrored: a point
     * (x, y) of the view goes to (y, 1 − x), so the view crop does too.
     */
    @Test
    fun rotateIsAnticlockwiseOnScreen_mirroredOrNot() {
        val crop = NormRect(0.1f, 0.2f, 0.5f, 0.9f)
        val turnedOnScreen = NormRect(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left)
        for (turns in 0..3) for (mirror in listOf(false, true)) {
            var e = TransformEdit()
            repeat(turns) { e = e.rotatedAnticlockwise() }
            if (mirror) e = e.mirroredOnScreen()
            e = e.withViewCrop(crop)
            nearRect(turnedOnScreen, e.rotatedAnticlockwise().viewCrop, "turns=$turns mirror=$mirror")
        }
    }

    @Test
    fun mirrorFlipsLeftAndRightOnScreen() {
        val plan = TransformEdit().mirroredOnScreen().plan(400, 300)
        val (x, y) = plan.map(0f, 0f)
        near(400f, x); near(0f, y)
    }

    /**
     * ⚠⚠ The composition rule the stored-in-source-frame design exists for: cropping a region and
     * then rotating must produce the same pixels as rotating and then cropping that region on screen.
     */
    @Test
    fun cropThenRotate_isTheSameImageAsRotateThenCropTheSamePixels() {
        // A crop of the source's right half, top two-thirds.
        val cropFirst = TransformEdit().withViewCrop(NormRect(0.5f, 0f, 1f, 2f / 3f)).rotatedAnticlockwise()

        // The same pixels, chosen on screen after the turn: the right half becomes the top half,
        // the top two-thirds become the left two-thirds.
        val turned = TransformEdit().rotatedAnticlockwise()
        val rotateFirst = turned.withViewCrop(NormRect(0f, 0f, 2f / 3f, 0.5f))

        nearRect(cropFirst.crop, rotateFirst.crop, "stored crop")
        val a = cropFirst.plan(600, 300)
        val b = rotateFirst.plan(600, 300)
        assertEquals(a.outputWidth, b.outputWidth)
        assertEquals(a.outputHeight, b.outputHeight)
        for ((sx, sy) in listOf(300f to 0f, 600f to 0f, 600f to 200f, 450f to 100f)) {
            val (ax, ay) = a.map(sx, sy)
            val (bx, by) = b.map(sx, sy)
            near(ax, bx, what = "x of $sx,$sy"); near(ay, by, what = "y of $sx,$sy")
        }
        // And the output is the crop turned: 300×200 of source → 200×300.
        assertEquals(200, a.outputWidth)
        assertEquals(300, a.outputHeight)
    }

    @Test
    fun theCropTurnsWithThePicture() {
        val e = TransformEdit().withViewCrop(NormRect(0.1f, 0.2f, 0.5f, 0.6f))
        val turned = e.rotatedAnticlockwise()
        assertEquals(e.crop, turned.crop, "a rotation never rewrites the stored crop")
        // On screen, a point (x, y) is now at (y, 1 − x).
        nearRect(NormRect(0.2f, 0.5f, 0.6f, 0.9f), turned.viewCrop)
        // Mirroring on top flips it left↔right on screen.
        nearRect(NormRect(0.4f, 0.5f, 0.8f, 0.9f), turned.mirroredOnScreen().viewCrop)
    }

    @Test
    fun aViewCropRoundTripsThroughEveryOrientation() {
        val r = NormRect(0.1f, 0.25f, 0.7f, 0.8f)
        for (turns in 0..3) for (mirror in listOf(false, true)) {
            var e = TransformEdit()
            repeat(turns) { e = e.rotatedAnticlockwise() }
            if (mirror) e = e.mirroredOnScreen()
            nearRect(r, e.withViewCrop(r).viewCrop, "turns=$turns mirror=$mirror")
        }
    }

    @Test
    fun aspectLockGivesTheLargestCentredRectOfThatRatio() {
        val e = TransformEdit().withAspect(AspectPreset.SQUARE, 400, 300)
        val (w, h) = e.viewSize(400, 300)
        val crop = e.viewCrop
        near(1f, crop.width * w / (crop.height * h), what = "pixel ratio")
        near(1f, crop.height, what = "fills the short side")
        near(0.5f, crop.centerX)
        assertEquals(AspectPreset.SQUARE, e.aspect)
        val plan = e.plan(400, 300)
        assertEquals(300, plan.outputWidth); assertEquals(300, plan.outputHeight)
    }

    @Test
    fun aPresetThatMatchesTheFrameIsNoEdit() {
        // ⚠ For many frame shapes "Original" computes a ratio of 0.99999994 — a hair's-breadth crop
        // that would arm Save and the ✕ question for a photograph nobody changed. Swept, because
        // which sizes hit the noise is float luck.
        for (w in 97..4099 step 89) for (h in 101..4099 step 97) {
            assertTrue(TransformEdit().withAspect(AspectPreset.ORIGINAL, w, h).isIdentity, "$w×$h Original")
            assertTrue(
                TransformEdit().rotatedAnticlockwise().withAspect(AspectPreset.ORIGINAL, w, h).crop == NormRect.FULL,
                "$w×$h Original, turned",
            )
        }
        assertTrue(TransformEdit().withAspect(AspectPreset.R4_3, 4000, 3000).isIdentity, "4:3 on a 4:3 photo")
        assertTrue(TransformEdit().withAspect(AspectPreset.SQUARE, 1000, 1000).isIdentity)
    }

    @Test
    fun originalIsTheFrameAsSeen_andTurnsWithIt() {
        val turned = TransformEdit().rotatedAnticlockwise()
        assertEquals(3f / 4f, turned.aspectRatioOf(AspectPreset.ORIGINAL, 400, 300))
        assertEquals(AspectPreset.R3_4, TransformEdit(aspect = AspectPreset.R4_3).rotatedAnticlockwise().aspect)
        assertEquals(AspectPreset.SQUARE, TransformEdit(aspect = AspectPreset.SQUARE).rotatedAnticlockwise().aspect)
    }

    @Test
    fun aRotatedLockStillDescribesTheScreen() {
        // 16:9 chosen, then a turn: the lock becomes 9:16 and the crop on screen is portrait.
        val e = TransformEdit().withAspect(AspectPreset.R16_9, 1600, 900).rotatedAnticlockwise()
        assertEquals(AspectPreset.R9_16, e.aspect)
        val (w, h) = e.viewSize(1600, 900)
        val c = e.viewCrop
        near(9f / 16f, c.width * w / (c.height * h), what = "on-screen ratio after the turn")
    }

    @Test
    fun aLockedCornerKeepsTheRatio_andTheOppositeCornerStays() {
        val start = NormRect(0.25f, 0.25f, 0.75f, 0.75f)
        val dragged = dragCrop(start, CropHandle.BOTTOM_RIGHT, dx = -0.1f, dy = -0.3f, lockedRatio = 1f)
        near(start.left, dragged.left); near(start.top, dragged.top)
        near(dragged.width, dragged.height, what = "square")
    }

    /** A one-axis drag toward the centre shrinks a locked box; it is not held by the unmoved axis. */
    @Test
    fun aLockedCornerDraggedInwardAlongOneAxisShrinks() {
        val start = NormRect(0.25f, 0.25f, 0.75f, 0.75f)
        nearRect(NormRect(0.25f, 0.25f, 0.70f, 0.70f), dragCrop(start, CropHandle.BOTTOM_RIGHT, -0.1f, 0f, 1f), "x only")
        nearRect(NormRect(0.25f, 0.25f, 0.70f, 0.70f), dragCrop(start, CropHandle.BOTTOM_RIGHT, 0f, -0.1f, 1f), "y only")
        nearRect(NormRect(0.31f, 0.31f, 0.75f, 0.75f), dragCrop(start, CropHandle.TOP_LEFT, 0.1f, 0.02f, 1f), "mostly x")
    }

    /** A tiny change of drag is a tiny change of box — no jump where the two axes' asks cross. */
    @Test
    fun aLockedCornerResizesContinuously() {
        val start = NormRect(0.25f, 0.25f, 0.75f, 0.75f)
        val a = dragCrop(start, CropHandle.BOTTOM_RIGHT, 0.1f, -0.1f, 1f)
        val b = dragCrop(start, CropHandle.BOTTOM_RIGHT, 0.1f, -0.101f, 1f)
        assertTrue(abs(a.right - b.right) < 0.01f, "${a.right} vs ${b.right}")
    }

    @Test
    fun aLockedBoxThatWouldLeaveTheFrameShrinksInstead() {
        val start = NormRect(0.25f, 0.25f, 0.75f, 0.75f)
        val dragged = dragCrop(start, CropHandle.BOTTOM_RIGHT, dx = 0.5f, dy = 0.1f, lockedRatio = 1f)
        assertTrue(dragged.right <= 1f + 1e-4f && dragged.bottom <= 1f + 1e-4f, "inside the frame: $dragged")
        near(dragged.width, dragged.height, what = "still square")
    }

    @Test
    fun aFreeformHandleClampsAtTheFrameAndAtTheMinimum() {
        val start = NormRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(0f, dragCrop(start, CropHandle.LEFT, -1f, 0f, null).left)
        val squashed = dragCrop(start, CropHandle.RIGHT, -5f, 0f, null)
        near(TransformEdit.MIN_CROP, squashed.width, what = "stops at the minimum, never inverts")
        val moved = dragCrop(start, CropHandle.MOVE, 0.5f, 0f, null)
        near(1f, moved.right); near(0.6f, moved.width)
    }

    @Test
    fun theNormalisedRatioAccountsForTheFrameShape() {
        // A square crop of a 400×300 frame is 0.75 wide for 1.0 tall, normalised.
        near(0.75f, TransformEdit().normalisedRatio(1f, 400, 300))
    }

    @Test
    fun straightenCoversTheFrame_noCornerOfTheOutputIsOutsideTheSource() {
        val e = TransformEdit().withViewStraighten(12f)
        val plan = e.plan(400, 300)
        assertEquals(400, plan.outputWidth); assertEquals(300, plan.outputHeight)
        // Every output corner must come from inside the source: invert by checking that the source
        // quad (its corners mapped) contains each output corner.
        val c = plan.corners(400, 300)
        val quad = (0 until 4).map { c[2 * it] to c[2 * it + 1] }
        for ((x, y) in listOf(0f to 0f, 400f to 0f, 400f to 300f, 0f to 300f)) {
            assertTrue(insideConvex(quad, x, y), "output corner $x,$y shows no empty source: $quad")
        }
    }

    @Test
    fun aMirrorReversesTheSenseOfStraighten_soTheScreenStillShowsWhatTheDialSays() {
        val e = TransformEdit().withViewStraighten(10f).mirroredOnScreen()
        near(-10f, e.viewStraightenDegrees, what = "the stored tilt now reads mirrored")
        val back = e.withViewStraighten(10f)
        near(10f, back.viewStraightenDegrees)
        near(-10f, back.straightenDegrees, what = "stored in the source frame")
    }

    @Test
    fun perspectiveCoversTheFrame_andTurnsWithThePicture() {
        val e = TransformEdit().withViewPerspective(0f, 1f)
        val c = e.plan(400, 300).corners(400, 300)
        val quad = (0 until 4).map { c[2 * it] to c[2 * it + 1] }
        for ((x, y) in listOf(0f to 0f, 400f to 0f, 400f to 300f, 0f to 300f)) {
            assertTrue(insideConvex(quad, x, y), "keystone leaves no empty corner at $x,$y")
        }
        // The widened top edge, turned anticlockwise, is the left edge on screen.
        val turned = e.rotatedAnticlockwise()
        val (vx, vy) = turned.viewPerspective
        near(-1f, vx, what = "left edge widened"); near(0f, vy)
        // Round trip through the setter.
        val (rx, ry) = turned.withViewPerspective(vx, vy).viewPerspective
        near(vx, rx); near(vy, ry)
    }

    @Test
    fun straightenIsClamped() {
        near(45f, TransformEdit().withViewStraighten(80f).viewStraightenDegrees)
    }

    private fun insideConvex(quad: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var sign = 0
        for (i in quad.indices) {
            val (x1, y1) = quad[i]
            val (x2, y2) = quad[(i + 1) % quad.size]
            val cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
            if (abs(cross) < 1e-3f) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }
}
