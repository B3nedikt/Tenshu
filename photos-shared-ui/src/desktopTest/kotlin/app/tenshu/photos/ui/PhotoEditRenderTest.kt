package app.tenshu.photos.ui

import androidx.compose.ui.graphics.toPixelMap
import app.tenshu.photos.edit.AdjustEdit
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.TransformEdit
import app.tenshu.photos.ui.screens.editor.ComposePhotoEditRenderer
import app.tenshu.ui.image.decodeToImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's export (`photos-editing.md` §4): EXIF orientation first, then the transform, written
 * upright.
 *
 * The fixture is a 32×16 JPEG stored left-red/right-blue and tagged orientation 6, so it is shown
 * 16×32, **red on top**. A renderer that drew stored order would save it sideways; one that kept the
 * tag would turn it twice on every reader.
 */
class PhotoEditRenderTest {

    private fun isRed(argb: androidx.compose.ui.graphics.Color) = argb.red > 0.6f && argb.blue < 0.4f
    private fun isBlue(argb: androidx.compose.ui.graphics.Color) = argb.blue > 0.6f && argb.red < 0.4f

    private fun render(edit: TransformEdit, adjust: AdjustEdit = AdjustEdit()) = requireNotNull(
        decodeToImageBitmap(ComposePhotoEditRenderer().render(ROTATED_EXIF_6_JPEG, EditRecipe(edit, adjust))),
    )

    @Test
    fun anExifRotatedOriginalSavesUpright() {
        // ⚠ A real edit, not the identity: the editor never saves an unchanged photograph, and a
        // crop of the whole frame minus a sliver is the smallest change that still shows the frame.
        val out = render(TransformEdit().withViewCrop(NormRect(0f, 0f, 1f, 0.999f)))
        assertEquals(16, out.width, "upright width")
        assertEquals(32, out.height, "upright height")
        val px = out.toPixelMap()
        assertTrue(isRed(px[8, 4]), "red on top, as the photograph is shown: ${px[8, 4]}")
        assertTrue(isBlue(px[8, 27]), "blue below: ${px[8, 27]}")
    }

    @Test
    fun theTransformIsAppliedAfterTheOrientation() {
        // Shown upright the picture is red over blue; a quarter turn anticlockwise puts the top on
        // the left — so red left, blue right, 32×16.
        val out = render(TransformEdit().rotatedAnticlockwise())
        assertEquals(32, out.width)
        assertEquals(16, out.height)
        val px = out.toPixelMap()
        assertTrue(isRed(px[4, 8]), "red on the left: ${px[4, 8]}")
        assertTrue(isBlue(px[27, 8]), "blue on the right: ${px[27, 8]}")
    }

    @Test
    fun aCropKeepsOnlyTheChosenPixels() {
        // The bottom half of the upright picture is all blue.
        val out = render(TransformEdit().withViewCrop(NormRect(0f, 0.5f, 1f, 1f)))
        assertEquals(16, out.width)
        assertEquals(16, out.height)
        val px = out.toPixelMap()
        for ((x, y) in listOf(3 to 3, 12 to 3, 3 to 12, 12 to 12)) assertTrue(isBlue(px[x, y]), "($x,$y) ${px[x, y]}")
    }

    @Test
    fun aMirrorSwapsLeftAndRight() {
        // ⚠ After a turn, so the picture differs left to right (the fixture alone is uniform across):
        // turned it is red-left/blue-right, so a mirror that did nothing would fail here.
        val out = render(TransformEdit().rotatedAnticlockwise().mirroredOnScreen())
        val px = out.toPixelMap()
        assertTrue(isBlue(px[4, 8]), "blue now on the left: ${px[4, 8]}")
        assertTrue(isRed(px[27, 8]), "red now on the right: ${px[27, 8]}")
    }

    /**
     * ⚠⚠ The plan's projective entries survive `composeMatrix()`: with a straighten AND a keystone,
     * a source pixel lands where the shared plan says it does. A wrong Perspective index passes every
     * plan-only test; this one draws through the real canvas.
     */
    @Test
    fun straightenAndKeystoneLandWhereThePlanSays() {
        val edit = TransformEdit().withViewStraighten(8f).withViewPerspective(0.4f, -0.6f)
        val out = render(edit)
        val plan = edit.plan(16, 32)
        assertEquals(plan.outputWidth, out.width)
        assertEquals(plan.outputHeight, out.height)
        val px = out.toPixelMap()
        // Deep inside the red and the blue halves of the upright 16×32 picture.
        for ((sx, sy, red) in listOf(Triple(8f, 6f, true), Triple(8f, 26f, false))) {
            val (ox, oy) = plan.map(sx, sy)
            val c = px[ox.toInt().coerceIn(0, out.width - 1), oy.toInt().coerceIn(0, out.height - 1)]
            assertTrue(if (red) isRed(c) else isBlue(c), "source ($sx,$sy) → ($ox,$oy) is $c")
        }
    }

    /**
     * ⚠⚠ ED-2's order: the transform first, then adjust — so the vignette is centred on the CROP, not
     * on the uncropped photograph. The bottom half of the upright picture is all blue; cropped to it
     * and vignetted, its own centre stays blue and its corners go dark. Drawn the other way round the
     * crop's top corners would sit at the photograph's centre and stay bright.
     */
    @Test
    fun theVignetteIsCentredOnTheCrop() {
        val out = render(TransformEdit().withViewCrop(NormRect(0f, 0.5f, 1f, 1f)), AdjustEdit(vignette = -100))
        val px = out.toPixelMap()
        val centre = px[8, 8].blue
        for ((x, y) in listOf(0 to 0, 15 to 0, 0 to 15, 15 to 15)) {
            assertTrue(px[x, y].blue < centre * 0.5f, "corner ($x,$y) ${px[x, y]} vs centre $centre")
        }
        assertTrue(isBlue(px[8, 8]), "the centre is untouched: ${px[8, 8]}")
    }

    /**
     * Android's save colours on the CPU (a bitmap canvas there cannot run AGSL); desktop's through
     * the shader. The same recipe saved both ways is the same picture, up to JPEG.
     */
    @Test
    fun androidsCpuSave_isDesktopsShaderSave() {
        val recipe = EditRecipe(
            TransformEdit().rotatedAnticlockwise().withViewCrop(NormRect(0.1f, 0f, 1f, 0.9f)),
            AdjustEdit(brightness = 30, contrast = 20, saturation = -40, warmth = 25, vignette = -50),
            app.tenshu.photos.edit.FilterEdit(app.tenshu.photos.edit.FilterLook.HARBOUR, 80),
        )
        val shader = requireNotNull(decodeToImageBitmap(ComposePhotoEditRenderer(useShader = true).render(ROTATED_EXIF_6_JPEG, recipe))).toPixelMap()
        val cpu = requireNotNull(decodeToImageBitmap(ComposePhotoEditRenderer(useShader = false).render(ROTATED_EXIF_6_JPEG, recipe))).toPixelMap()
        assertEquals(shader.width, cpu.width)
        assertEquals(shader.height, cpu.height)
        var worst = 0f
        var sum = 0f
        for (y in 0 until cpu.height) for (x in 0 until cpu.width) {
            val a = shader[x, y]
            val b = cpu[x, y]
            val d = listOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue))
            worst = maxOf(worst, d.max())
            sum += d.sum()
        }
        val mean = sum * 255 / (cpu.width * cpu.height * 3)
        // ⚠ Looser than the parity tolerance on purpose: these are two JPEGs of a 16×32 picture, and
        // the straightened frame's edge pixels differ in kind — the shader blends a half-covered
        // edge over black after colouring, the CPU path colours it after — which JPEG then smears.
        assertTrue(mean <= 1.5f && worst * 255 <= 12f, "mean $mean, worst ${worst * 255} levels")
    }
}
