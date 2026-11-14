package app.tenshu.photos

import app.tenshu.photos.edit.AdjustEdit
import app.tenshu.photos.edit.AdjustTool
import app.tenshu.photos.edit.ColorStage
import app.tenshu.photos.edit.EditColor
import app.tenshu.photos.edit.EditParityFixtures
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.EditShader
import app.tenshu.photos.edit.FilterEdit
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.TransformEdit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Adjust + Filter recipe and its CPU reference (`photos-editing.md` ED-2): what a recipe counts
 * as an edit, that each slider moves the picture the way its name says, the looks, and the order.
 */
class EditColorTest {

    /** One opaque pixel through [stage], at the centre of a 100×100 output unless placed. */
    private fun px(r: Int, g: Int, b: Int, stage: ColorStage, x: Int = 50, y: Int = 50): Triple<Int, Int, Int> {
        val out = intArrayOf((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        EditColor.processArgb(out, 1, 1, x, y, 100, 100, stage)
        val v = out[0]
        return Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    private fun adjust(tool: AdjustTool, v: Int) = ColorStage(AdjustEdit().with(tool, v), null)

    private fun grey(level: Int, stage: ColorStage) = px(level, level, level, stage).first

    @Test
    fun anUntouchedRecipeIsNoEdit_andChangesNoPixel() {
        assertTrue(EditRecipe().isIdentity)
        assertTrue(EditRecipe(filter = FilterEdit(FilterLook.EMBER, 0)).isIdentity, "a look at 0 is no change")
        assertFalse(EditRecipe(adjust = AdjustEdit(tint = 1)).isIdentity)
        assertFalse(EditRecipe(transform = TransformEdit().rotatedAnticlockwise()).isIdentity)
        val before = EditParityFixtures.image()
        val after = EditParityFixtures.reference(ColorStage(AdjustEdit(), null))
        assertContentEquals(before, after, "the identity is exact, not merely close")
    }

    @Test
    fun aSliderClampsAndRoundTrips() {
        for (tool in AdjustTool.entries) {
            assertEquals(37, AdjustEdit().with(tool, 37)[tool], tool.name)
            assertEquals(100, AdjustEdit().with(tool, 900)[tool])
            assertEquals(-100, AdjustEdit().with(tool, -900)[tool])
        }
        // Every slider reaches its own uniform, and only its own.
        for ((i, tool) in AdjustTool.entries.withIndex()) {
            val u = EditColor.uniforms(AdjustEdit().with(tool, 50))
            assertEquals(1, u.count { it != 0f }, tool.name)
            assertTrue(u.indexOfFirst { it != 0f } in 0..10, "$i ${tool.name}")
        }
    }

    @Test
    fun eachSliderMovesThePictureTheWayItsNameSays() {
        // Brightness lifts the mids and keeps black and white.
        assertTrue(grey(128, adjust(AdjustTool.BRIGHTNESS, 60)) > 150)
        assertEquals(0, grey(0, adjust(AdjustTool.BRIGHTNESS, 60)))
        assertEquals(255, grey(255, adjust(AdjustTool.BRIGHTNESS, 60)))
        assertTrue(grey(128, adjust(AdjustTool.BRIGHTNESS, -60)) < 100)
        // Contrast pushes away from the middle.
        assertTrue(grey(64, adjust(AdjustTool.CONTRAST, 60)) < 50)
        assertTrue(grey(192, adjust(AdjustTool.CONTRAST, 60)) > 205)
        assertTrue(grey(64, adjust(AdjustTool.CONTRAST, -60)) > 80)
        // Tone: an S-curve that keeps the ends.
        assertTrue(grey(64, adjust(AdjustTool.TONE, 80)) < 60)
        assertTrue(grey(192, adjust(AdjustTool.TONE, 80)) > 196)
        assertEquals(255, grey(255, adjust(AdjustTool.TONE, 80)))
        // White point brightens whites; black point darkens blacks.
        assertTrue(grey(220, adjust(AdjustTool.WHITE_POINT, 60)) > 240)
        assertTrue(grey(30, adjust(AdjustTool.BLACK_POINT, 60)) < 10)
        assertTrue(grey(0, adjust(AdjustTool.BLACK_POINT, -60)) > 20, "a negative black point lifts black")
        // Highlights touch the brights, shadows the darks.
        assertTrue(grey(230, adjust(AdjustTool.HIGHLIGHTS, -60)) < 215)
        assertEquals(20, grey(20, adjust(AdjustTool.HIGHLIGHTS, -60)), "highlights leave the shadows")
        assertTrue(grey(30, adjust(AdjustTool.SHADOWS, 60)) > 50)
        assertEquals(240, grey(240, adjust(AdjustTool.SHADOWS, 60)), "shadows leave the highlights")
        // Saturation -100 is grey; warmth is red against blue; tint is green against magenta.
        val (r, g, b) = px(200, 80, 40, adjust(AdjustTool.SATURATION, -100))
        assertTrue(abs(r - g) <= 1 && abs(g - b) <= 1, "($r,$g,$b) is grey")
        val (wr, _, wb) = px(128, 128, 128, adjust(AdjustTool.WARMTH, 60))
        assertTrue(wr > 128 && wb < 128)
        val (_, tg, _) = px(128, 128, 128, adjust(AdjustTool.TINT, 60))
        assertTrue(tg < 128, "positive tint is toward magenta")
    }

    @Test
    fun theVignetteFollowsItsFrame() {
        val dark = adjust(AdjustTool.VIGNETTE, -80)
        assertEquals(128, px(128, 128, 128, dark, 50, 50).first, "the centre is untouched")
        assertTrue(px(128, 128, 128, dark, 0, 0).first < 60, "the corner is darkened")
        assertTrue(px(128, 128, 128, adjust(AdjustTool.VIGNETTE, 80), 0, 0).first > 180, "positive lightens")
        // Centred on a crop in the top-left quarter, the output's centre is that crop's corner.
        val cropped = dark.copy(vignette = NormRect(0f, 0f, 0.5f, 0.5f))
        assertEquals(128, px(128, 128, 128, cropped, 25, 25).first)
        assertTrue(px(128, 128, 128, cropped, 50, 50).first < 60)
    }

    @Test
    fun aLookIsItsLatticeAtTheLatticePoints_andMonoLooksAreGrey() {
        for (look in FilterLook.entries) {
            val lut = EditColor.lut(look)
            assertEquals(EditColor.LUT_SIZE, lut.size)
            val stage = ColorStage(AdjustEdit(), FilterEdit(look))
            // 128 is not a lattice point; 0 and 255 are.
            val e = lut.entry(EditColor.LUT_SIZE - 1, 0, 0)
            assertEquals(Triple((e shr 16) and 0xFF, (e shr 8) and 0xFF, e and 0xFF), px(255, 0, 0, stage), look.id)
        }
        for (look in listOf(FilterLook.SLATE, FilterLook.GRAPHITE)) {
            val (r, g, b) = px(200, 80, 40, ColorStage(AdjustEdit(), FilterEdit(look)))
            assertTrue(abs(r - g) <= 8 && abs(g - b) <= 12, "${look.id}: ($r,$g,$b)")
        }
        // Every look is a different picture.
        val pictures = FilterLook.entries.map { EditParityFixtures.reference(ColorStage(AdjustEdit(), FilterEdit(it))).toList() }
        assertEquals(pictures.size, pictures.toSet().size)
        assertEquals(FilterLook.HARBOUR, FilterLook.byId("harbour"))
    }

    @Test
    fun intensityFadesBetweenThePictureAndTheLook() {
        val none = px(200, 80, 40, ColorStage(AdjustEdit(), null))
        val full = px(200, 80, 40, ColorStage(AdjustEdit(), FilterEdit(FilterLook.EMBER)))
        val half = px(200, 80, 40, ColorStage(AdjustEdit(), FilterEdit(FilterLook.EMBER, 50)))
        assertTrue(abs(half.first - (none.first + full.first) / 2) <= 1)
        assertTrue(abs(half.third - (none.third + full.third) / 2) <= 1)
    }

    /**
     * ⚠⚠ The order is adjust, then filter ([EditRecipe]) — and it matters: the two do not commute,
     * so an engine that swapped them would fail the parity tests' combined recipe.
     */
    @Test
    fun adjustRunsBeforeTheFilter_andTheOrderShows() {
        val a = AdjustEdit(brightness = 50, saturation = -60)
        val both = px(200, 80, 40, ColorStage(a, FilterEdit(FilterLook.HARBOUR)))
        val adjusted = px(200, 80, 40, ColorStage(a, null))
        val adjustThenLook = px(adjusted.first, adjusted.second, adjusted.third, ColorStage(AdjustEdit(), FilterEdit(FilterLook.HARBOUR)))
        val look = px(200, 80, 40, ColorStage(AdjustEdit(), FilterEdit(FilterLook.HARBOUR)))
        val lookThenAdjust = px(look.first, look.second, look.third, ColorStage(a, null))
        // Within two levels of re-quantising the in-between picture.
        assertTrue(abs(both.first - adjustThenLook.first) <= 2 && abs(both.third - adjustThenLook.third) <= 2, "$both vs $adjustThenLook")
        assertTrue(abs(both.first - lookThenAdjust.first) + abs(both.third - lookThenAdjust.third) > 6, "the other order is visibly different")
    }

    @Test
    fun oneShaderText_twoDialects() {
        assertEquals(EditShader.SKSL, EditShader.AGSL, "AGSL and SkSL are the one text")
        assertTrue(EditShader.SKSL.contains(EditShader.ADJUST_BODY))
        val ci = EditShader.CORE_IMAGE
        assertFalse(Regex("\\bfloat[234]\\b").containsMatchIn(ci), "no SkSL vector type survives into CIKL")
        assertTrue(ci.contains("kernel vec4 tenshuAdjustKernel"))
        assertTrue(ci.contains("vec3 tenshuAdjust(vec3 c, vec2 p, vec4 u0, vec4 u1, vec4 u2)"))
    }

    @Test
    fun theFixturesCoverWhatTheyClaim() {
        val names = EditParityFixtures.recipes.map { it.first }
        for (tool in AdjustTool.entries) assertTrue(names.any { it.startsWith(tool.name.lowercase()) }, tool.name)
        for (look in FilterLook.entries) assertTrue("look ${look.id}" in names)
        val everything = assertNotNull(EditParityFixtures.recipes.toMap()["everything"])
        assertTrue(AdjustTool.entries.all { everything.adjust[it] != 0 } && everything.filter != null)
    }
}
