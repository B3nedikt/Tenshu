package app.tenshu.photos

import app.tenshu.photos.edit.AdjustEdit
import app.tenshu.photos.edit.AspectPreset
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.EditRecipeCodec
import app.tenshu.photos.edit.FilterEdit
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.InkColor
import app.tenshu.photos.edit.InkStroke
import app.tenshu.photos.edit.MarkupEdit
import app.tenshu.photos.edit.NormPoint
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.Redaction
import app.tenshu.photos.edit.TextLabel
import app.tenshu.photos.edit.TransformEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored recipe (`photos-editing.md` §12.2, ED-D9): every tool round-trips to an EQUAL recipe,
 * enums are stored by stable id, and a recipe this build cannot read is [EditRecipeCodec.Decoded.Unreadable]
 * — never a partial edit.
 */
class EditRecipeCodecTest {

    private val everything = EditRecipe(
        transform = TransformEdit(
            quarterTurns = 3, mirrored = true, straightenDegrees = -7.25f, perspectiveX = 0.3f,
            perspectiveY = -0.1f, crop = NormRect(0.1f, 0.2f, 0.85f, 0.9f), aspect = AspectPreset.R4_3,
        ),
        adjust = AdjustEdit(brightness = 43, contrast = -12, tone = 5, whitePoint = -3, highlights = 70, shadows = -70,
            blackPoint = 1, saturation = -100, warmth = 100, tint = -1, vignette = 33),
        filter = FilterEdit(FilterLook.EMBER, intensity = 40),
        markup = MarkupEdit(
            listOf(
                InkStroke(1, highlighter = false, color = InkColor.RED, width = 0.012f,
                    points = listOf(NormPoint(0.1f, 0.2f), NormPoint(0.33333334f, 0.6f))),
                InkStroke(2, highlighter = true, color = InkColor.YELLOW, width = 0.03f, points = listOf(NormPoint(0.5f, 0.5f))),
                Redaction(3, listOf(NormPoint(0f, 0f), NormPoint(1f, 0f), NormPoint(1f, 0.5f), NormPoint(0f, 0.5f))),
                TextLabel(4, text = "Tenshu \"quoted\" ✓", color = InkColor.WHITE, size = 0.055f, anchor = NormPoint(0.7f, 0.1f)),
            ),
        ),
    )

    private fun decoded(text: String) = EditRecipeCodec.decode(text)

    @Test
    fun everyToolRoundTripsToAnEqualRecipe() {
        val text = EditRecipeCodec.encode(everything)
        assertEquals(EditRecipeCodec.Decoded.Recipe(everything), decoded(text))
    }

    @Test
    fun theIdentityRoundTrips_andIsSmall() {
        val text = EditRecipeCodec.encode(EditRecipe())
        assertEquals(EditRecipeCodec.Decoded.Recipe(EditRecipe()), decoded(text))
        assertEquals("""{"v":1}""", text)
    }

    /** ⚠⚠ Stable ids, never labels or ordinals (§10.3): a renamed label must not break a stored edit. */
    @Test
    fun enumsAreStoredByTheirStableIds() {
        val text = EditRecipeCodec.encode(everything)
        assertTrue("\"ember\"" in text, text)
        assertTrue("\"red\"" in text && "\"yellow\"" in text, text)
        assertTrue("\"R4_3\"" in text, text)
        assertFalse("Ember" in text, "never the label: $text")
    }

    @Test
    fun aNewerVersion_isUnreadable_notAPartialEdit() {
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded("""{"v":2,"adjust":{"brightness":10}}"""))
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded("""{"adjust":{"brightness":10}}"""), "no version")
    }

    @Test
    fun anythingThisBuildCannotName_isUnreadable() {
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded("""{"v":1,"filter":{"look":"sepia-2031","intensity":100}}"""))
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded("""{"v":1,"markup":[{"kind":"arrow","id":1,"points":[0,0]}]}"""))
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded("""{"v":1,"newTool":{"x":1}}"""), "an unknown key is a newer tool")
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded("not json"))
        assertEquals(EditRecipeCodec.Decoded.Unreadable, decoded(""))
    }
}
