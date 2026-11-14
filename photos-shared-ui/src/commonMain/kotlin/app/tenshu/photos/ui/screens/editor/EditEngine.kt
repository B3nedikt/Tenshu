package app.tenshu.photos.ui.screens.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import app.tenshu.photos.edit.ColorStage
import app.tenshu.photos.edit.EditColor
import app.tenshu.photos.edit.RenderPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * [EditShader][app.tenshu.photos.edit.EditShader]'s pass as one shader over [source]: the picture
 * sampled through [plan] (source → output), then adjust, then the look — evaluated in **output**
 * coordinates, so drawing a rect `0..outputWidth × 0..outputHeight` with it draws the edit.
 *
 * `null` where the platform has no runtime shaders: Android below API 33. The caller then draws the
 * transform alone and runs [applyColorOnCpu] over it — the same math, on the CPU (§10.4).
 */
internal expect fun editShader(source: ImageBitmap, plan: RenderPlan, stage: ColorStage): Shader?

/** Writes [rows] rows of ARGB [pixels] (unpremultiplied, the bitmap's width) into this bitmap at row [y]. */
internal expect fun ImageBitmap.writeArgbRows(pixels: IntArray, y: Int, rows: Int)

/**
 * Draws [source] through [plan] with [stage] applied, in output coordinates.
 *
 * @return `false` when the colour stages could **not** be drawn here (no runtime shader): only the
 * transform was, and the caller owes [applyColorOnCpu] over the result. ⚠ Never silently dropped —
 * the one way a slider could do nothing.
 */
internal fun Canvas.drawEdited(
    source: ImageBitmap,
    plan: RenderPlan,
    stage: ColorStage,
    quality: FilterQuality,
    /** `false` on a canvas that cannot run a runtime shader — see [shaderOnBitmapCanvas]. */
    useShader: Boolean = true,
    /**
     * The shader for exactly this (source, plan, stage), when the caller keeps one — the preview
     * does, so a redraw does not compile AGSL again. `null` = make one here.
     */
    prepared: Shader? = null,
): Boolean {
    if (!stage.isIdentity && useShader) {
        val shader = prepared ?: editShader(source, plan, stage)
        if (shader != null) {
            drawRect(Rect(0f, 0f, plan.outputWidth.toFloat(), plan.outputHeight.toFloat()), Paint().apply { this.shader = shader })
            return true
        }
    }
    save()
    concat(plan.composeMatrix())
    drawImage(source, Offset.Zero, Paint().apply { filterQuality = quality })
    restore()
    return stage.isIdentity
}

/**
 * The colour stages on the CPU, in place, over a picture that is the whole [outputWidth]×
 * [outputHeight] output — or that output scaled to this bitmap's size, which is how the preview
 * uses it (the vignette is normalised, so the scale does not move it).
 *
 * ⚠ In bands of rows, so the extra memory is one band, not a second copy of a 4096² picture. The
 * pixel IO stays on the calling thread, one band at a time; only the math within a band is split
 * across [CPU_WORKERS] — it is the cost (a 12 MP save is ~38 M `pow`s).
 */
internal fun ImageBitmap.applyColorOnCpu(
    stage: ColorStage,
    /**
     * Flatten onto black afterwards, as a JPEG save needs. ⚠ After the colour, never before: a
     * straightened frame's empty wedges are transparent until here, so no slider tints them — the
     * shader path leaves them alone the same way.
     */
    overBlack: Boolean = false,
    /** Checked between bands: `false` abandons the pass (a superseded preview frame). */
    keepGoing: () -> Boolean = { true },
) {
    if (stage.isIdentity && !overBlack) return
    val band = max(1, min(height, BAND_PIXELS / max(1, width)))
    val buffer = IntArray(width * band)
    var y = 0
    while (y < height) {
        if (!keepGoing()) return
        val rows = min(band, height - y)
        readPixels(buffer, startX = 0, startY = y, width = width, height = rows)
        val y0 = y
        if (!stage.isIdentity) {
            // ⚠ On a pool of its own, not `Dispatchers.Default`: this blocks its caller, which is
            // itself on Default (the save, the preview), and Default's workers all parked here
            // waiting for children queued on Default would never run them.
            runBlocking {
                val per = (rows + CPU_WORKERS - 1) / CPU_WORKERS
                (0 until rows step per).map { r ->
                    launch(cpuPool) {
                        val n = min(per, rows - r)
                        EditColor.processArgb(buffer, width, n, 0, y0 + r, width, height, stage, offset = r * width)
                    }
                }.joinAll()
            }
        }
        if (overBlack) for (i in 0 until width * rows) buffer[i] = flattenOnBlack(buffer[i])
        writeArgbRows(buffer, y, rows)
        y += rows
    }
}

/** An unpremultiplied ARGB pixel drawn over opaque black. */
private fun flattenOnBlack(p: Int): Int {
    val a = p ushr 24
    if (a == 0xFF) return p
    fun ch(shift: Int) = (((p shr shift) and 0xFF) * a + 127) / 255
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val cpuPool = Dispatchers.IO.limitedParallelism(CPU_WORKERS)

private const val BAND_PIXELS = 1 shl 20
private const val CPU_WORKERS = 4

/**
 * The preview where there is no runtime shader: the transformed picture drawn at [scale] of the
 * plan's output, coloured on the CPU. ⚠ Off the main thread — it is per-pixel Kotlin.
 */
internal fun renderOnCpu(
    source: ImageBitmap,
    plan: RenderPlan,
    stage: ColorStage,
    scale: Float,
    keepGoing: () -> Boolean = { true },
): ImageBitmap {
    val w = max(1, ceil(plan.outputWidth * scale).toInt())
    val h = max(1, ceil(plan.outputHeight * scale).toInt())
    val out = ImageBitmap(w, h)
    Canvas(out).apply {
        scale(w / plan.outputWidth.toFloat(), h / plan.outputHeight.toFloat())
        concat(plan.composeMatrix())
        drawImage(source, Offset.Zero, Paint().apply { filterQuality = FilterQuality.Medium })
    }
    out.applyColorOnCpu(stage, keepGoing = keepGoing)
    return out
}

/** Whether this platform draws the colour stages with a shader (so the preview can follow a drag live). */
internal expect val hasEditShader: Boolean

/**
 * Whether a shader can draw into a `Canvas(ImageBitmap)` — the export's canvas. ⚠⚠ **Not on
 * Android:** a bitmap canvas there is software-rendered, and software rendering throws on a
 * `RuntimeShader` ("Software rendering doesn't support RuntimeShader" — found on the emulator, every
 * save failed). So Android's save colours on the CPU at every API level — the reference itself
 * (`EditColor`) — and AGSL draws only the on-screen preview. Skia on desktop draws runtime effects
 * on a raster canvas, so desktop saves through the shader.
 */
internal expect val shaderOnBitmapCanvas: Boolean
