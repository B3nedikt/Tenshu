package app.tenshu.photos.edit

import kotlin.math.abs
import kotlin.math.max

/**
 * The shared fixtures for ED-D8's golden-image tests: one picture, one list of recipes and one
 * tolerance, used by **every** engine's parity test — SkSL in `:photos-shared-ui`'s desktop tests,
 * the Core Image kernel in `:tenshu-sdk-ios-photos`'s iOS tests — against the CPU reference
 * [EditColor]. Two engines each within the tolerance of the reference are within twice it of each
 * other, and that is the claim ED-D8 makes.
 *
 * ⚠ In main, not a test source set, because those two tests live in two modules and this is the one
 * both depend on. It is a few hundred bytes of code and draws nothing at runtime.
 */
object EditParityFixtures {

    const val WIDTH = 96
    const val HEIGHT = 64

    /**
     * ⚠⚠ The stated tolerance, in 8-bit levels: no channel of any pixel more than [MAX_DIFF] from
     * the reference, and the mean over the picture under [MEAN_DIFF]. The budget is for GPU `pow`/
     * `exp2` precision, half-float intermediates (Core Image's default working format) and the sRGB
     * curve round trip on iOS — not for a different formula, which moves pixels by far more.
     */
    const val MAX_DIFF = 6
    const val MEAN_DIFF = 1.0

    /**
     * Every hue at every lightness across the top two-thirds — hue along x, dark to light down y —
     * and a grey ramp below, so each slider has something to move. Opaque ARGB, row-major.
     */
    fun image(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        val split = HEIGHT * 2 / 3
        if (y >= split) {
            val g = (x * 255) / (WIDTH - 1)
            argb(g, g, g)
        } else {
            val hue = x * 6f / WIDTH
            val light = 0.08f + 0.84f * y / (split - 1)
            val (r, g, b) = hueRgb(hue)
            // Toward black below the middle, toward white above it.
            fun shade(c: Float) = if (light < 0.5f) c * light * 2f else c + (1f - c) * (light - 0.5f) * 2f
            argb((shade(r) * 255).toInt(), (shade(g) * 255).toInt(), (shade(b) * 255).toInt())
        }
    }

    /** The recipes compared, by name: each slider both ways, each look, a half look, and all at once. */
    val recipes: List<Pair<String, ColorStage>> = buildList {
        for (tool in AdjustTool.entries) for (v in listOf(-70, 70)) {
            add("${tool.name.lowercase()} $v" to ColorStage(AdjustEdit().with(tool, v), null))
        }
        for (look in FilterLook.entries) add("look ${look.id}" to ColorStage(AdjustEdit(), FilterEdit(look)))
        add("harbour at 40" to ColorStage(AdjustEdit(), FilterEdit(FilterLook.HARBOUR, 40)))
        add(
            "everything" to ColorStage(
                AdjustEdit(
                    brightness = 20, contrast = 30, tone = -25, whitePoint = 15, highlights = -40,
                    shadows = 35, blackPoint = 10, saturation = 25, warmth = -30, tint = 20, vignette = -60,
                ),
                FilterEdit(FilterLook.EMBER, 70),
                // An off-centre vignette, as the uncropped preview draws one.
                vignette = NormRect(0.1f, 0.2f, 0.8f, 0.9f),
            ),
        )
    }

    /** [stage] applied to [image] by the reference. */
    fun reference(stage: ColorStage): IntArray = image().also {
        EditColor.processArgb(it, WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT, stage)
    }

    /** The largest and the mean per-channel difference between two ARGB pictures, in 8-bit levels. */
    fun difference(a: IntArray, b: IntArray): Pair<Int, Double> {
        require(a.size == b.size) { "${a.size} vs ${b.size} pixels" }
        var worst = 0
        var sum = 0L
        for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) {
            val d = abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF))
            worst = max(worst, d)
            sum += d
        }
        return worst to sum.toDouble() / (a.size * 3)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun hueRgb(h: Float): Triple<Float, Float, Float> {
        val f = h - h.toInt()
        return when (h.toInt() % 6) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(1f - f, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, 1f - f, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, 1f - f)
        }
    }
}
