package app.tenshu.photos.edit

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Adjust + Filter on the CPU — [EditShader]'s math in Kotlin, line for line.
 *
 * Two jobs, which is why it is exact rather than approximate:
 * - ⭐ **The reference.** The golden tests pin every engine (SkSL on desktop, the Core Image kernel on
 *   iOS) to this, on the same fixtures, within a stated tolerance ([EditParityFixtures]).
 * - **Every Android save** (a bitmap canvas there cannot run AGSL), and the Android preview below
 *   API 33, which has no runtime shaders at all (`photos-editing.md` §10.4). Slower than a shader,
 *   never absent, and pinned by construction.
 */
object EditColor {

    /** The LUT lattice: 33 points a side, the common size for a look. */
    const val LUT_SIZE = 33

    // ── uniforms ────────────────────────────────────────────────────────────────────────────

    /** `u0, u1, u2` as twelve floats, each slider / 100. */
    fun uniforms(a: AdjustEdit): FloatArray = floatArrayOf(
        a.brightness / 100f, a.contrast / 100f, a.tone / 100f, a.whitePoint / 100f,
        a.highlights / 100f, a.shadows / 100f, a.blackPoint / 100f, a.saturation / 100f,
        a.warmth / 100f, a.tint / 100f, a.vignette / 100f, 0f,
    )

    /**
     * `v`: the vignette's centre in output pixels and 1 / its half-size. [rect] is normalised to the
     * [width]×[height] output, y down.
     */
    fun vignetteUniforms(rect: NormRect, width: Int, height: Int): FloatArray {
        val hw = max(rect.width * width / 2f, 1e-3f)
        val hh = max(rect.height * height / 2f, 1e-3f)
        return floatArrayOf(rect.centerX * width, rect.centerY * height, 1f / hw, 1f / hh)
    }

    // ── the pixel ───────────────────────────────────────────────────────────────────────────

    /**
     * One pixel, unpremultiplied and sRGB-encoded, in place in [c] (`r, g, b` at 0..2). [px], [py]
     * is its centre in output pixels — `x + 0.5`, as a shader's `coord` is.
     */
    fun apply(c: FloatArray, px: Float, py: Float, u: FloatArray, v: FloatArray, lut: Lut?, amount: Float) {
        adjust(c, (px - v[0]) * v[2], (py - v[1]) * v[3], u)
        if (lut != null && amount > 0f) {
            val r = c[0]; val g = c[1]; val b = c[2]
            lut.lookup(c)
            c[0] = mix(r, c[0], amount); c[1] = mix(g, c[1], amount); c[2] = mix(b, c[2], amount)
        }
    }

    /** `tenshuAdjust`. ⚠ Keep in step with [EditShader.ADJUST_BODY], statement by statement. */
    internal fun adjust(c: FloatArray, p0: Float, p1: Float, u: FloatArray) {
        val lo = 0.2f * u[6]
        val hi = 1f - 0.2f * u[3]
        val gamma = 2f.pow(-u[0])
        val slope = 2f.pow(u[1])
        for (i in 0..2) {
            var x = clamp01((c[i] - lo) / (hi - lo))
            x = x.pow(gamma)
            x = clamp01((x - 0.5f) * slope + 0.5f)
            x = x + (x * x * (3f - 2f * x) - x) * u[2]
            c[i] = x
        }
        var l = luma(c)
        val hs = 0.25f * (u[4] * smoothstep(0.5f, 1f, l) + u[5] * (1f - smoothstep(0f, 0.5f, l)))
        for (i in 0..2) c[i] = clamp01(c[i] + hs)
        l = luma(c)
        for (i in 0..2) c[i] = clamp01(l + (c[i] - l) * (1f + u[7]))
        c[0] = clamp01(c[0] + 0.08f * u[8])
        c[1] = clamp01(c[1] - 0.08f * u[9])
        c[2] = clamp01(c[2] - 0.08f * u[8])
        val m = smoothstep(0.35f, 1f, sqrt(p0 * p0 + p1 * p1) * 0.70710678f) * abs(u[10])
        val light = if (u[10] >= 0f) 1f else 0f
        for (i in 0..2) {
            val dark = c[i] * (1f - 0.85f * m)
            val bright = c[i] + (1f - c[i]) * (0.6f * m)
            c[i] = clamp01(mix(dark, bright, light))
        }
    }

    /**
     * A block of ARGB pixels (`0xAARRGGBB`, unpremultiplied — what `getPixels` and Compose's
     * `readPixels` hand out), in place. ([x0], [y0]) is the block's origin within the
     * [outWidth]×[outHeight] output — the vignette needs to know where each pixel is. The block
     * starts at [offset] in [pixels].
     */
    fun processArgb(
        pixels: IntArray,
        width: Int,
        rows: Int,
        x0: Int,
        y0: Int,
        outWidth: Int,
        outHeight: Int,
        stage: ColorStage,
        offset: Int = 0,
    ) {
        val u = uniforms(stage.adjust)
        val v = vignetteUniforms(stage.vignette, outWidth, outHeight)
        val lut = stage.filter?.let { lut(it.look) }
        val amount = (stage.filter?.intensity ?: 0) / 100f
        val c = FloatArray(3)
        for (y in 0 until rows) {
            for (x in 0 until width) {
                val i = offset + y * width + x
                val argb = pixels[i]
                val a = argb ushr 24
                if (a == 0) continue
                c[0] = ((argb shr 16) and 0xFF) / 255f
                c[1] = ((argb shr 8) and 0xFF) / 255f
                c[2] = (argb and 0xFF) / 255f
                apply(c, x0 + x + 0.5f, y0 + y + 0.5f, u, v, lut, amount)
                pixels[i] = (a shl 24) or (to8(c[0]) shl 16) or (to8(c[1]) shl 8) or to8(c[2])
            }
        }
    }

    // ── the LUT ─────────────────────────────────────────────────────────────────────────────

    /**
     * A look baked on an [LUT_SIZE]³ lattice, each entry **quantised to 8 bits** — because that is
     * what the Skia engines sample (an 8-bit image), so every engine, this one included, interpolates
     * the very same lattice.
     */
    class Lut internal constructor(val size: Int, private val rgb: IntArray) {
        /** Entry (r, g, b) as `0xRRGGBB`. */
        fun entry(r: Int, g: Int, b: Int): Int = rgb[(b * size + g) * size + r]

        /**
         * The lattice as a `(size·size) × size` ARGB image — texel `(b·size + r, g)` — for the Skia
         * engines' `lut` child.
         */
        fun toArgbImage(): IntArray {
            val n = size
            val out = IntArray(n * n * n)
            for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
                out[g * n * n + b * n + r] = 0xFF000000.toInt() or entry(r, g, b)
            }
            return out
        }

        /** The lattice as `CIColorCube` wants it: RGBA float32, red fastest, then green, then blue. */
        fun toCubeFloats(): FloatArray {
            val out = FloatArray(rgb.size * 4)
            for (i in rgb.indices) {
                out[i * 4] = ((rgb[i] shr 16) and 0xFF) / 255f
                out[i * 4 + 1] = ((rgb[i] shr 8) and 0xFF) / 255f
                out[i * 4 + 2] = (rgb[i] and 0xFF) / 255f
                out[i * 4 + 3] = 1f
            }
            return out
        }

        /** `tenshuLookup`: trilinear, in place. ⚠ Keep in step with [EditShader.SKSL]. */
        internal fun lookup(c: FloatArray) {
            val m = (size - 1).toFloat()
            val xr = clamp01(c[0]) * m; val xg = clamp01(c[1]) * m; val xb = clamp01(c[2]) * m
            val ir = min(floor(xr), m - 1f); val ig = min(floor(xg), m - 1f); val ib = min(floor(xb), m - 1f)
            val tr = xr - ir; val tg = xg - ig; val tb = xb - ib
            val r0 = ir.toInt(); val g0 = ig.toInt(); val b0 = ib.toInt()
            for (ch in 0..2) {
                val shift = 16 - 8 * ch
                fun t(r: Int, g: Int, b: Int) = ((entry(r, g, b) shr shift) and 0xFF) / 255f
                val c00 = mix(t(r0, g0, b0), t(r0 + 1, g0, b0), tr)
                val c10 = mix(t(r0, g0 + 1, b0), t(r0 + 1, g0 + 1, b0), tr)
                val c01 = mix(t(r0, g0, b0 + 1), t(r0 + 1, g0, b0 + 1), tr)
                val c11 = mix(t(r0, g0 + 1, b0 + 1), t(r0 + 1, g0 + 1, b0 + 1), tr)
                c[ch] = mix(mix(c00, c10, tg), mix(c01, c11, tg), tb)
            }
        }
    }

    private val luts: Map<FilterLook, Lazy<Lut>> = FilterLook.entries.associateWith { look -> lazy { bake(look) } }

    /** [look]'s LUT, baked once per process from [FilterLooks]. */
    fun lut(look: FilterLook): Lut = luts.getValue(look).value

    private fun bake(look: FilterLook): Lut {
        val n = LUT_SIZE
        val out = IntArray(n * n * n)
        val c = FloatArray(3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            c[0] = r / (n - 1f); c[1] = g / (n - 1f); c[2] = b / (n - 1f)
            FilterLooks.apply(look, c)
            out[(b * n + g) * n + r] = (to8(c[0]) shl 16) or (to8(c[1]) shl 8) or to8(c[2])
        }
        return Lut(n, out)
    }

    // ── helpers, as the shading languages define them ───────────────────────────────────────

    internal fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    internal fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }

    internal fun luma(c: FloatArray): Float = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    internal fun to8(x: Float): Int = (clamp01(x) * 255f).roundToInt()
}

/**
 * What each [FilterLook] does to a colour (ED-D5). Plain arithmetic on sRGB-encoded values, run once
 * per lattice point by [EditColor.lut] — never per pixel — so a look is cheap however elaborate it is.
 *
 * ⚠ Changing a look changes every copy saved with it from then on, and ED-3 would re-open old edits
 * with the new look. Add a new look rather than retune one people have used.
 */
object FilterLooks {

    fun apply(look: FilterLook, c: FloatArray) {
        when (look) {
            // Warm, airy morning light: brighter mids, lifted blacks, a little warmth.
            FilterLook.DAYBREAK -> {
                gamma(c, 0.9f); warm(c, 0.04f); fade(c, 0.05f); saturate(c, 1.05f)
            }
            // Teal shadows, amber highlights.
            FilterLook.HARBOUR -> {
                contrast(c, 0.25f); split(c, shadow = floatArrayOf(-0.03f, 0.05f, 0.08f), highlight = floatArrayOf(0.08f, 0.03f, -0.05f))
            }
            // Warm and punchy.
            FilterLook.EMBER -> {
                contrast(c, 0.4f); warm(c, 0.06f); saturate(c, 1.2f)
            }
            // Cool and crisp.
            FilterLook.FJORD -> {
                warm(c, -0.05f); saturate(c, 0.85f); contrast(c, 0.15f); gamma(c, 0.95f)
            }
            // Soft and pale, warm whites.
            FilterLook.LINEN -> {
                contrast(c, -0.3f); fade(c, 0.08f); saturate(c, 0.8f)
                split(c, shadow = floatArrayOf(0f, 0f, 0f), highlight = floatArrayOf(0.03f, 0.015f, -0.01f))
            }
            // Muted, with green in the shadows.
            FilterLook.MOSS -> {
                saturate(c, 0.75f); fade(c, 0.06f); split(c, shadow = floatArrayOf(-0.01f, 0.04f, 0f), highlight = floatArrayOf(0.02f, 0.02f, -0.02f))
            }
            // Neutral black and white.
            FilterLook.SLATE -> {
                saturate(c, 0f); contrast(c, 0.1f); fade(c, 0.03f)
            }
            // Hard black and white with cool shadows.
            FilterLook.GRAPHITE -> {
                saturate(c, 0f); contrast(c, 0.6f); split(c, shadow = floatArrayOf(0f, 0.005f, 0.03f), highlight = floatArrayOf(0f, 0f, 0f))
            }
        }
        for (i in 0..2) c[i] = EditColor.clamp01(c[i])
    }

    private fun gamma(c: FloatArray, g: Float) {
        for (i in 0..2) c[i] = EditColor.clamp01(c[i]).pow(g)
    }

    /** An S-curve that keeps black and white; negative flattens. */
    private fun contrast(c: FloatArray, k: Float) {
        for (i in 0..2) {
            val x = EditColor.clamp01(c[i])
            c[i] = x + (x * x * (3f - 2f * x) - x) * k
        }
    }

    private fun warm(c: FloatArray, k: Float) {
        c[0] += k; c[2] -= k
    }

    /** Lifts black to [k] and pulls white down by half as much: the "faded print". */
    private fun fade(c: FloatArray, k: Float) {
        for (i in 0..2) c[i] = k + EditColor.clamp01(c[i]) * (1f - 1.5f * k)
    }

    private fun saturate(c: FloatArray, k: Float) {
        val l = EditColor.luma(c)
        for (i in 0..2) c[i] = l + (c[i] - l) * k
    }

    /** Tints shadows toward [shadow] and highlights toward [highlight], weighted by luminance. */
    private fun split(c: FloatArray, shadow: FloatArray, highlight: FloatArray) {
        val l = EditColor.clamp01(EditColor.luma(c))
        for (i in 0..2) c[i] += shadow[i] * (1f - l) + highlight[i] * l
    }
}
