package app.tenshu.photos.ui.screens.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import app.tenshu.photos.edit.ColorStage
import app.tenshu.photos.edit.EditColor
import app.tenshu.photos.edit.EditShader
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.RenderPlan
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/** Compiled once: an effect is immutable and safe to share; each draw gets its own builder. */
private val effect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(EditShader.SKSL) }

private val lutImages: Map<FilterLook?, Lazy<Image>> =
    (FilterLook.entries + listOf(null)).associateWith { look -> lazy { lutImage(look) } }

internal actual val hasEditShader: Boolean = true

internal actual val shaderOnBitmapCanvas: Boolean = true

internal actual fun editShader(source: ImageBitmap, plan: RenderPlan, stage: ColorStage): Shader? {
    // ⚠ Immutable first: Skia copies a MUTABLE bitmap's pixels into every image made from it, and the
    // preview makes one per frame. Nothing writes to a decoded source after this.
    val bitmap = source.asSkiaBitmap().also { if (!it.isImmutable) it.setImmutable() }
    val image = Image.makeFromBitmap(bitmap)
    // ⚠ DECAL: outside the source is transparent, as a canvas draw leaves it; the export paints
    // black under it (JPEG has no alpha).
    val picture = image.makeShader(FilterTileMode.DECAL, FilterTileMode.DECAL, SamplingMode.LINEAR, plan.matrix.let { m -> Matrix33(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]) })
    val u = EditColor.uniforms(stage.adjust)
    val v = EditColor.vignetteUniforms(stage.vignette, plan.outputWidth, plan.outputHeight)
    val look = stage.filter?.takeIf { it.intensity > 0 }
    return RuntimeShaderBuilder(effect).run {
        child("image", picture)
        // ⚠ Nearest, at texel centres: the trilinear blend is the shader's own.
        child("lut", lutImages.getValue(look?.look).value.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.DEFAULT))
        uniform("u0", u[0], u[1], u[2], u[3])
        uniform("u1", u[4], u[5], u[6], u[7])
        uniform("u2", u[8], u[9], u[10], u[11])
        uniform("v", v[0], v[1], v[2], v[3])
        uniform("f", EditColor.LUT_SIZE.toFloat(), (look?.intensity ?: 0) / 100f, 0f, 0f)
        makeShader()
    }.asComposeShader()
}

/** A look's lattice as an image; `null` = a 1×1 stand-in, bound because a child must be (never sampled). */
private fun lutImage(look: FilterLook?): Image {
    val argb = look?.let { EditColor.lut(it).toArgbImage() } ?: intArrayOf(0xFF000000.toInt())
    val n = if (look == null) 1 else EditColor.LUT_SIZE
    val w = n * n
    return Image.makeRaster(ImageInfo(w, n, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), argb.toBgraBytes(), w * 4)
}

internal actual fun ImageBitmap.writeArgbRows(pixels: IntArray, y: Int, rows: Int) {
    val band = Bitmap()
    band.allocPixels(ImageInfo(width, rows, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    band.installPixels(pixels.copyOf(width * rows).toBgraBytes())
    val canvas = org.jetbrains.skia.Canvas(asSkiaBitmap())
    try {
        check(canvas.writePixels(band, 0, y)) { "could not write rows $y..${y + rows}" }
    } finally {
        canvas.close()
        band.close()
    }
}

/** `0xAARRGGBB` ints → B, G, R, A bytes: an ARGB int in little-endian memory. */
private fun IntArray.toBgraBytes(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val p = this[i]
        out[i * 4] = p.toByte()
        out[i * 4 + 1] = (p shr 8).toByte()
        out[i * 4 + 2] = (p shr 16).toByte()
        out[i * 4 + 3] = (p ushr 24).toByte()
    }
    return out
}
