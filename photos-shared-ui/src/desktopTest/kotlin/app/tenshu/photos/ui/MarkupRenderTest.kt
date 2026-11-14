package app.tenshu.photos.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.tenshu.photos.edit.AdjustEdit
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.FilterEdit
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.InkColor
import app.tenshu.photos.edit.InkStroke
import app.tenshu.photos.edit.MarkupEdit
import app.tenshu.photos.edit.MarkupParityFixtures
import app.tenshu.photos.edit.NormPoint
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.TransformEdit
import app.tenshu.photos.ui.screens.editor.ComposePhotoEditRenderer
import app.tenshu.photos.ui.screens.editor.drawMarkup
import app.tenshu.photos.ui.screens.editor.writeArgbRows
import app.tenshu.ui.image.ImageEncodeFormat
import app.tenshu.ui.image.decodeToImageBitmap
import app.tenshu.ui.image.encodeToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Markup's render (`photos-editing.md` ED-4, §11): parity with the shared reference, the redaction
 * proof, the stage order, and ink that stays on its pixels through a later transform — all on the
 * real Compose renderer that Android and desktop save with.
 */
class MarkupRenderTest {

    // ── parity (the Compose half; the Core Graphics half is PhotosIosTests/MarkupParityTests) ──

    @Test
    fun composeMatchesTheReference_withinTheStatedTolerance() {
        val w = MarkupParityFixtures.WIDTH
        val h = MarkupParityFixtures.HEIGHT
        val out = ImageBitmap(w, h).apply { writeArgbRows(MarkupParityFixtures.background(), 0, h) }
        Canvas(out).drawMarkup(MarkupParityFixtures.scene())
        val drawn = IntArray(w * h).also { out.readPixels(it) }
        val (mean, far) = MarkupParityFixtures.difference(drawn, MarkupParityFixtures.reference())
        println("MarkupRenderTest parity: mean $mean, far ${far * 100}%")
        assertTrue(
            mean <= MarkupParityFixtures.MEAN_DIFF && far <= MarkupParityFixtures.FAR_FRACTION,
            "Compose vs the reference: mean $mean (≤ ${MarkupParityFixtures.MEAN_DIFF}), far $far (≤ ${MarkupParityFixtures.FAR_FRACTION})",
        )
        // ⚠ And the tolerance is not so loose that nothing could fail it: the same scene with the
        // highlighters drawn opaque is far outside it.
        val opaque = ImageBitmap(w, h).apply { writeArgbRows(MarkupParityFixtures.background(), 0, h) }
        Canvas(opaque).drawMarkup(
            MarkupParityFixtures.scene().map {
                if (it is app.tenshu.photos.edit.StrokeOp) app.tenshu.photos.edit.StrokeOp(it.points, it.width, it.argb, 1f) else it
            },
        )
        val (badMean, badFar) = MarkupParityFixtures.difference(IntArray(w * h).also { opaque.readPixels(it) }, MarkupParityFixtures.reference())
        assertTrue(badMean > MarkupParityFixtures.MEAN_DIFF || badFar > MarkupParityFixtures.FAR_FRACTION, "opaque highlighters pass: $badMean / $badFar")
    }

    // ── redaction: the saved copy holds no trace of what was under the box ────────────────────

    private val redactRecipe = MarkupParityFixtures.redactRecipe

    private fun source(seed: Int, secret: Int? = null): ByteArray =
        ImageBitmap(SRC_W, SRC_H).apply { writeArgbRows(MarkupParityFixtures.redactSource(seed, secret), 0, SRC_H) }
            .encodeToBytes(ImageEncodeFormat.PNG)

    @Test
    fun aRedactedCopy_isTheSameBytes_whateverWasUnderTheBox() {
        for (useShader in listOf(true, false)) {
            val renderer = ComposePhotoEditRenderer(useShader = useShader)
            val a = renderer.render(source(seed = 1), redactRecipe)
            val b = renderer.render(source(seed = 1, secret = 2), redactRecipe)
            val c = renderer.render(source(seed = 1, secret = 3), redactRecipe)
            assertContentEquals(a, b, "shader=$useShader: the copy depends on what was under the box")
            assertContentEquals(a, c, "shader=$useShader: the copy depends on what was under the box")

            // ⚠ The test can fail: without the box, the same two sources save differently.
            val bare = redactRecipe.copy(markup = MarkupEdit())
            assertFalse(renderer.render(source(1), bare).contentEquals(renderer.render(source(1, secret = 2), bare)), "the sources differ")
        }
    }

    /**
     * The same proof where the decode DOWNSCALES the original (a phone photo over `MAX_DECODE_EDGE`):
     * the fill then lands on the decoded raster, and must still cover everything the box touched.
     */
    @Test
    fun aRedactedCopy_isTheSameBytes_whenTheOriginalIsDownscaled() {
        val renderer = ComposePhotoEditRenderer(maxEdge = SRC_W * 2 / 3, useShader = false)
        val a = renderer.render(source(seed = 1), redactRecipe)
        val b = renderer.render(source(seed = 1, secret = 2), redactRecipe)
        // ⚠ It did downscale: the copy is smaller than a full-size save of the same recipe.
        val full = requireNotNull(decodeToImageBitmap(ComposePhotoEditRenderer(useShader = false).render(source(1), redactRecipe)))
        assertTrue(requireNotNull(decodeToImageBitmap(a)).width < full.width, "the decode downscaled")
        assertContentEquals(a, b, "the downscaled copy depends on what was under the box")
    }

    @Test
    fun aRedactedCopy_isBlackUnderTheBox_underEverySliderAndLook() {
        val out = requireNotNull(decodeToImageBitmap(ComposePhotoEditRenderer().render(source(1), redactRecipe)))
        val plan = redactRecipe.transform.plan(SRC_W, SRC_H)
        assertEquals(plan.outputWidth, out.width)
        val px = out.toPixelMap()
        var checked = 0
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val (sx, sy) = plan.unmap(x + 0.5f, y + 0.5f)
            // Well inside the box (4 source px from its edges), where JPEG's ringing cannot reach.
            if (!MarkupParityFixtures.deepInsideBox(sx, sy, 4f)) continue
            val p = px[x, y]
            assertTrue(p.red < 0.05f && p.green < 0.05f && p.blue < 0.05f, "($x,$y) is not black: $p")
            checked++
        }
        assertTrue(checked > 1000, "checked $checked pixels")
    }

    // ── the order: markup after the colour stages ──────────────────────────────────────────────

    @Test
    fun inkIsDrawnAfterTheColourStages_noLookRecoloursIt_andTheVignetteDoesNotDarkenIt() {
        val stroke = InkStroke(1, highlighter = false, color = InkColor.RED, width = 0.08f, points = listOf(NormPoint(0.02f, 0.05f), NormPoint(0.98f, 0.05f)))
        val recipe = EditRecipe(
            adjust = AdjustEdit(vignette = -100),
            filter = FilterEdit(FilterLook.SLATE),
            markup = MarkupEdit(listOf(stroke)),
        )
        val out = requireNotNull(decodeToImageBitmap(ComposePhotoEditRenderer().render(source(4), recipe))).toPixelMap()
        // The corner of the stroke, where the vignette is darkest and the look is black-and-white.
        for (x in listOf(8, out.width / 2, out.width - 8)) {
            val p = out[x, (0.05f * out.height).toInt()]
            assertTrue(p.red > 0.8f && p.green < 0.3f && p.blue < 0.3f, "the ink at x=$x is the pen's red: $p")
        }
        // And the picture under it did get the look: grey.
        val under = out[out.width / 2, out.height / 2]
        assertTrue(kotlin.math.abs(under.red - under.green) < 0.06f && kotlin.math.abs(under.green - under.blue) < 0.06f, "the photo is grey: $under")
    }

    // ── ink stays on its pixels ───────────────────────────────────────────────────────────────

    @Test
    fun aStroke_landsOnTheSameSourcePixels_afterALaterRotateAndCrop() {
        // A white square on black at a known source spot, and a green dot drawn on it.
        val w = SRC_W
        val h = SRC_H
        val px = IntArray(w * h) { i -> if ((i % w) in 140..150 && (i / w) in 40..50) -1 else 0xFF000000.toInt() }
        val bytes = ImageBitmap(w, h).apply { writeArgbRows(px, 0, h) }.encodeToBytes(ImageEncodeFormat.PNG)
        val spot = NormPoint(145.5f / w, 45.5f / h)
        val dot = InkStroke(1, highlighter = false, color = InkColor.GREEN, width = 0.02f, points = listOf(spot))
        val recipe = EditRecipe(
            transform = TransformEdit().rotatedAnticlockwise().withViewCrop(NormRect(0f, 0f, 0.7f, 0.6f)),
            markup = MarkupEdit(listOf(dot)),
        )
        val out = requireNotNull(decodeToImageBitmap(ComposePhotoEditRenderer().render(bytes, recipe))).toPixelMap()
        val (ox, oy) = recipe.transform.plan(w, h).map(spot.x * w, spot.y * h)
        val p = out[ox.toInt(), oy.toInt()]
        assertTrue(p.green > 0.5f && p.green - p.red > 0.2f, "the dot is where the square went: ($ox, $oy) = $p")
        // …and that is where the square went: just beside the dot, it is white.
        val beside = out[(ox + 4).toInt().coerceAtMost(out.width - 1), oy.toInt()]
        assertTrue(beside.red > 0.8f && beside.green > 0.8f, "beside the dot is the white square: $beside")
    }

    private companion object {
        const val SRC_W = MarkupParityFixtures.REDACT_W
        const val SRC_H = MarkupParityFixtures.REDACT_H
    }
}
