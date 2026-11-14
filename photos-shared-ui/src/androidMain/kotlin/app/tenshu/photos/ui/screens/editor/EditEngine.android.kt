package app.tenshu.photos.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import app.tenshu.photos.edit.ColorStage
import app.tenshu.photos.edit.EditColor
import app.tenshu.photos.edit.EditShader
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.RenderPlan

/**
 * ⚠⚠ AGSL needs API 33. Below it there is no runtime shader at all, and every slider runs on the
 * CPU instead ([applyColorOnCpu]) — slower, never hidden, never a slider that does nothing
 * (`photos-editing.md` §10).
 */
internal actual val hasEditShader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** ⚠⚠ Never: a `Canvas(Bitmap)` is software-rendered, and that throws on a `RuntimeShader`. */
internal actual val shaderOnBitmapCanvas: Boolean = false

internal actual fun editShader(source: ImageBitmap, plan: RenderPlan, stage: ColorStage): Shader? =
    if (hasEditShader) Agsl.shader(source, plan, stage) else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Agsl {
    private val luts = HashMap<FilterLook?, Bitmap>()

    fun shader(source: ImageBitmap, plan: RenderPlan, stage: ColorStage): Shader {
        val picture = BitmapShader(source.asAndroidBitmap(), android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL).apply {
            // The plan is row-major 3×3, which is what `setValues` reads — perspective included.
            setLocalMatrix(Matrix().apply { setValues(plan.matrix) })
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        val u = EditColor.uniforms(stage.adjust)
        val v = EditColor.vignetteUniforms(stage.vignette, plan.outputWidth, plan.outputHeight)
        val look = stage.filter?.takeIf { it.intensity > 0 }
        // ⚠ A new shader per draw: a RuntimeShader is mutable, and the preview (main thread) and a
        // save (render dispatcher) must never share one.
        return RuntimeShader(EditShader.AGSL).apply {
            setInputShader("image", picture)
            // ⚠ `setInputBuffer`: sampled as raw data — no filtering, no colour-space conversion — so
            // the shader's own trilinear blend reads the lattice exactly.
            setInputBuffer("lut", BitmapShader(lut(look?.look), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            setFloatUniform("u0", u[0], u[1], u[2], u[3])
            setFloatUniform("u1", u[4], u[5], u[6], u[7])
            setFloatUniform("u2", u[8], u[9], u[10], u[11])
            setFloatUniform("v", v[0], v[1], v[2], v[3])
            setFloatUniform("f", EditColor.LUT_SIZE.toFloat(), (look?.intensity ?: 0) / 100f, 0f, 0f)
        }
    }

    private fun lut(look: FilterLook?): Bitmap = synchronized(luts) {
        luts.getOrPut(look) {
            if (look == null) {
                Bitmap.createBitmap(intArrayOf(0xFF000000.toInt()), 1, 1, Bitmap.Config.ARGB_8888)
            } else {
                val n = EditColor.LUT_SIZE
                Bitmap.createBitmap(EditColor.lut(look).toArgbImage(), n * n, n, Bitmap.Config.ARGB_8888)
            }
        }
    }
}

internal actual fun ImageBitmap.writeArgbRows(pixels: IntArray, y: Int, rows: Int) {
    asAndroidBitmap().setPixels(pixels, 0, width, 0, y, width, rows)
}
