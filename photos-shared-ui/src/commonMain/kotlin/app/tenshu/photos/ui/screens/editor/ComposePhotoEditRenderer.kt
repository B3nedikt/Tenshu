package app.tenshu.photos.ui.screens.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.MarkupRender
import app.tenshu.photos.edit.PhotoEditRenderer
import app.tenshu.photos.edit.RenderPlan
import app.tenshu.photos.edit.TransformEdit
import app.tenshu.ui.image.ImageEncodeException
import app.tenshu.ui.image.ImageEncodeFormat
import app.tenshu.ui.image.MAX_DECODE_EDGE
import app.tenshu.ui.image.decodeToUprightImageBitmap
import app.tenshu.ui.image.encodeToBytes

/**
 * The Android and desktop renderer: decode the original upright, draw the recipe — [TransformEdit.plan]
 * through the Adjust + Filter shader ([drawEdited]), then the markup ([drawMarkup]) — on a Compose
 * canvas, encode JPEG
 * (`photos-editing.md` §4).
 *
 * ⚠⚠ Upright end to end: [decodeToUprightImageBitmap] applies EXIF orientation (and refuses rather
 * than fall back to stored order), the plan is drawn against that upright bitmap, and
 * `encodeToBytes` writes no orientation tag (`photos-creation-features.md` §9.1).
 *
 * ⚠ [maxEdge] bounds the decode, so an original larger than it saves smaller — 4096 holds every
 * 12 MP phone photograph at full size and keeps the two full-size bitmaps (source and output)
 * near 2 × 64 MB on Android's heap.
 */
class ComposePhotoEditRenderer(
    private val maxEdge: Int = MAX_DECODE_EDGE,
    private val quality: Int = EDIT_JPEG_QUALITY,
    /** Colour with the shader, or on the CPU. A parameter so a desktop test can draw Android's way. */
    private val useShader: Boolean = shaderOnBitmapCanvas,
) : PhotoEditRenderer {

    override fun render(original: ByteArray, recipe: EditRecipe): ByteArray {
        var source = decodeToUprightImageBitmap(original, maxEdge)
            ?: throw ImageEncodeException("The original could not be decoded upright")
        // ⚠⚠ Redaction first, in the source raster, before the transform reads a pixel of it
        // (`MarkupRender.redactionsInSource`): the saved copy then cannot depend on what was there.
        if (recipe.markup.hasRedactions) {
            val filled = source.redacted(MarkupRender.redactionsInSource(recipe.markup, source.width, source.height))
            source.release()
            source = filled
        }
        val plan = recipe.transform.plan(source.width, source.height)
        val out = ImageBitmap(plan.outputWidth, plan.outputHeight)
        // ⚠ The recipe's order (EditRecipe): the transform samples the source, then adjust, then the
        // look — one shader pass where a bitmap canvas runs shaders (desktop), the transform then the
        // CPU reference where it does not (Android, every API level: `shaderOnBitmapCanvas`).
        val stage = recipe.colorStage()
        val viaShader = useShader && !stage.isIdentity
        val coloured = Canvas(out).run {
            // ⚠ JPEG has no alpha, so the picture goes over black — but on the CPU path only AFTER
            // the colour (`overBlack`), or the sliders would tint a straightened frame's empty
            // wedges, which the shader leaves black.
            if (viaShader || stage.isIdentity) {
                drawRect(Rect(0f, 0f, plan.outputWidth.toFloat(), plan.outputHeight.toFloat()), Paint().apply { color = Color.Black })
            }
            drawEdited(source, plan, stage, FilterQuality.High, useShader = useShader)
        }
        if (!coloured) out.applyColorOnCpu(stage, overBlack = true)
        // ⚠ Markup last, over the coloured picture (EditRecipe's order): no slider or look recolours
        // the ink, and a redaction's box stays black whatever the colour stages did to the source fill.
        val ops = MarkupRender.scene(recipe.markup, plan, source.width, source.height)
        if (ops.isNotEmpty()) Canvas(out).drawMarkup(ops)
        source.release()
        return out.encodeToBytes(ImageEncodeFormat.JPEG, quality)
    }
}

/** JPEG quality of an edited copy. ⚠ Above the encoder default: a copy is kept, not previewed. */
const val EDIT_JPEG_QUALITY = 92

/**
 * The plan's row-major 3×3 as Compose's column-major 4×4.
 *
 * ⚠ Perspective survives this on both backends: Android's `Matrix.setFrom` and Skia's `Matrix33`
 * both read Perspective0/1/2 (indices 3, 7, 15).
 */
fun RenderPlan.composeMatrix(): Matrix {
    val m = matrix
    return Matrix().apply {
        values[Matrix.ScaleX] = m[0]
        values[Matrix.SkewX] = m[1]
        values[Matrix.TranslateX] = m[2]
        values[Matrix.SkewY] = m[3]
        values[Matrix.ScaleY] = m[4]
        values[Matrix.TranslateY] = m[5]
        values[Matrix.Perspective0] = m[6]
        values[Matrix.Perspective1] = m[7]
        values[Matrix.Perspective2] = m[8]
    }
}
