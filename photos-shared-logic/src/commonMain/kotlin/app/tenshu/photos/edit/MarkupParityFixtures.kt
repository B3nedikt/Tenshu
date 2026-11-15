package app.tenshu.photos.edit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared fixture for markup's render parity (`photos-editing.md` §11.5): one background, one
 * scene and one tolerance, drawn by **each shell's** markup drawer — Compose in `:photos-shared-ui`'s
 * desktop tests, Core Graphics in `PhotosIosTests` — and compared to [reference], a plain-Kotlin
 * coverage rasteriser. The ED-2 pattern ([EditParityFixtures]): two engines each within the
 * tolerance of the reference are within twice it of each other.
 *
 * ⚠ The scene is built through [MarkupRender.scene] over a straightened plan, so the parity covers
 * the shared geometry the shells are handed, not a hand-made op list.
 *
 * ⚠ **Text is not in it.** Each platform draws labels in its own system font (Roboto, Skia's
 * default, San Francisco), so their pixels differ by design; a saved copy is baked (ED-D2), so no
 * device ever redraws another's label. What a label shares — where it is and how big — is the
 * scene's, and `MarkupTest` pins that.
 *
 * In main, not a test source set, for [EditParityFixtures]' reason: two modules' tests use it.
 */
object MarkupParityFixtures {

    const val WIDTH = 160
    const val HEIGHT = 120

    /**
     * ⚠⚠ The stated tolerance. Anti-aliasing differs between engines along every edge — a pixel the
     * edge crosses is a coverage *estimate* — so the budget is in two parts: the **mean** over the
     * picture stays under [MEAN_DIFF] levels, and at most [FAR_FRACTION] of pixels may differ by
     * more than [FAR_LEVELS] (which only edge pixels can). A misplaced, mis-sized, mis-coloured or
     * opaque-where-translucent stroke fails both by far.
     */
    const val MEAN_DIFF = 1.5
    const val FAR_LEVELS = 48
    const val FAR_FRACTION = 0.02

    /** A grey ramp left to right, so a translucent stroke is checked over dark and light. */
    fun background(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val g = 40 + (i % WIDTH) * 180 / (WIDTH - 1)
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    val recipe: EditRecipe = EditRecipe(
        transform = TransformEdit(straightenDegrees = 6f),
        markup = MarkupEdit(
            listOf(
                InkStroke(
                    1, highlighter = false, color = InkColor.RED, width = 0.05f,
                    points = List(24) { k -> NormPoint(0.1f + 0.8f * k / 23f, 0.35f + 0.15f * sin(k / 23f * 2 * PI).toFloat()) },
                ),
                InkStroke(2, highlighter = true, color = InkColor.YELLOW, width = 0.09f, points = listOf(NormPoint(0.08f, 0.62f), NormPoint(0.92f, 0.58f))),
                InkStroke(3, highlighter = false, color = InkColor.BLUE, width = 0.015f, points = listOf(NormPoint(0.2f, 0.9f), NormPoint(0.5f, 0.2f), NormPoint(0.8f, 0.85f))),
                Redaction(4, listOf(NormPoint(0.6f, 0.7f), NormPoint(0.85f, 0.72f), NormPoint(0.83f, 0.9f), NormPoint(0.58f, 0.88f))),
                InkStroke(5, highlighter = true, color = InkColor.GREEN, width = 0.06f, points = listOf(NormPoint(0.55f, 0.1f), NormPoint(0.95f, 0.95f))),
            ),
        ),
    )

    /** The plan the scene is built over: a [WIDTH]×[HEIGHT] source, straightened. */
    val plan: RenderPlan get() = recipe.transform.plan(WIDTH, HEIGHT)

    /** What each shell draws over [background], in output pixels — [WIDTH]×[HEIGHT], as straighten keeps the size. */
    fun scene(): List<MarkupOp> = MarkupRender.scene(recipe.markup, plan, WIDTH, HEIGHT)

    /** [scene] over [background], by coverage: the reference. Labels are skipped (see the KDoc). */
    fun reference(): IntArray {
        val out = background()
        for (op in scene()) when (op) {
            is StrokeOp -> rasterise(out, op.argb, op.alpha, op.points) { px, py -> strokeCoverage(op, px, py) }
            is FillOp -> rasterise(out, op.argb, 1f, op.polygon) { px, py -> fillCoverage(op.polygon, px, py) }
            is LabelOp -> Unit
        }
        return out
    }

    // ── the redaction proof's inputs (`MarkupRenderTest` on desktop, `MarkupParityTests` on iOS) ──

    const val REDACT_W = 240
    const val REDACT_H = 180

    val redactBox = Redaction(
        1,
        listOf(NormPoint(0.3f, 0.25f), NormPoint(0.62f, 0.3f), NormPoint(0.58f, 0.66f), NormPoint(0.27f, 0.6f)),
    )

    /**
     * A recipe that resamples every pixel — a turn, straighten, keystone and crop — with sliders and
     * a look that lift black, so the proof covers the transform's filtered sampling and every colour
     * stage.
     */
    val redactRecipe = EditRecipe(
        transform = TransformEdit(quarterTurns = 1, straightenDegrees = 7f, perspectiveX = 0.2f, crop = NormRect(0.05f, 0.05f, 0.95f, 0.9f)),
        adjust = AdjustEdit(brightness = 40, blackPoint = -60, shadows = 50),
        filter = FilterEdit(FilterLook.LINEN),
        markup = MarkupEdit(listOf(redactBox)),
    )

    /**
     * Opaque noise ([seed]) over [REDACT_W]×[REDACT_H]; with [secret], every pixel the box so much as
     * touches is replaced by other noise. ⚠ Two sources that differ ONLY under the box: a save of
     * each must be the same bytes.
     */
    fun redactSource(seed: Int, secret: Int? = null): IntArray {
        val rnd = kotlin.random.Random(seed)
        val px = IntArray(REDACT_W * REDACT_H) { (0xFF shl 24) or rnd.nextInt(0x1000000) }
        if (secret != null) {
            val other = kotlin.random.Random(secret)
            for (y in 0 until REDACT_H) for (x in 0 until REDACT_W) {
                if (touchesBox(x, y)) px[y * REDACT_W + x] = (0xFF shl 24) or other.nextInt(0x1000000)
            }
        }
        return px
    }

    /** Pixel ([x], [y])'s square shares a point with the box: its centre is inside, or an edge crosses it. */
    fun touchesBox(x: Int, y: Int): Boolean {
        val c = boxInSource()
        val cx = x + 0.5f
        val cy = y + 0.5f
        return insidePolygon(c, cx, cy) || distanceToEdges(c, cx, cy) <= 0.71f
    }

    /** Source point ([sx], [sy]) is inside the box and at least [margin] px from its edges. */
    fun deepInsideBox(sx: Float, sy: Float, margin: Float): Boolean {
        val c = boxInSource()
        return insidePolygon(c, sx, sy) && distanceToEdges(c, sx, sy) >= margin
    }

    private fun boxInSource(): FloatArray = MarkupRender.redactionsInSource(redactRecipe.markup, REDACT_W, REDACT_H).single()

    private fun insidePolygon(poly: FloatArray, px: Float, py: Float): Boolean {
        val n = poly.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]; val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun distanceToEdges(poly: FloatArray, px: Float, py: Float): Float {
        val n = poly.size / 2
        var d = Float.MAX_VALUE
        var j = n - 1
        for (i in 0 until n) {
            d = min(d, segmentDistance(px, py, poly[2 * i], poly[2 * i + 1], poly[2 * j], poly[2 * j + 1]))
            j = i
        }
        return d
    }

    /** The mean per-channel difference, and the fraction of pixels with any channel more than [FAR_LEVELS] off. */
    fun difference(a: IntArray, b: IntArray): Pair<Double, Double> {
        require(a.size == b.size) { "${a.size} vs ${b.size} pixels" }
        var sum = 0L
        var far = 0
        for (i in a.indices) {
            var worst = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF))
                sum += d
                worst = max(worst, d)
            }
            if (worst > FAR_LEVELS) far++
        }
        return sum.toDouble() / (a.size * 3) to far.toDouble() / a.size
    }

    private fun rasterise(out: IntArray, argb: Int, alpha: Float, pts: FloatArray, coverage: (Float, Float) -> Float) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in pts.indices step 2) {
            minX = min(minX, pts[i]); maxX = max(maxX, pts[i]); minY = min(minY, pts[i + 1]); maxY = max(maxY, pts[i + 1])
        }
        val pad = 64f
        val x0 = max(0, floor(minX - pad).toInt()); val x1 = min(WIDTH - 1, ceil(maxX + pad).toInt())
        val y0 = max(0, floor(minY - pad).toInt()); val y1 = min(HEIGHT - 1, ceil(maxY + pad).toInt())
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        for (y in y0..y1) for (x in x0..x1) {
            val c = coverage(x + 0.5f, y + 0.5f) * alpha
            if (c <= 0f) continue
            val i = y * WIDTH + x
            val p = out[i]
            fun mix(src: Int, shift: Int): Int = ((src * c + ((p shr shift) and 0xFF) * (1f - c)) + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (mix(r, 16) shl 16) or (mix(g, 8) shl 8) or mix(b, 0)
        }
    }

    /** Round caps and joins = the union of capsules, so one coverage however the line crosses itself. */
    private fun strokeCoverage(op: StrokeOp, px: Float, py: Float): Float {
        var d = Float.MAX_VALUE
        val p = op.points
        for (i in 0 until p.size / 2 - 1) d = min(d, segmentDistance(px, py, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]))
        return (op.width / 2f + 0.5f - d).coerceIn(0f, 1f)
    }

    private fun fillCoverage(poly: FloatArray, px: Float, py: Float): Float {
        val n = poly.size / 2
        var inside = false
        var d = Float.MAX_VALUE
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]; val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            d = min(d, segmentDistance(px, py, xi, yi, xj, yj))
            j = i
        }
        return ((if (inside) d else -d) + 0.5f).coerceIn(0f, 1f)
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val qx = ax + t * dx - px; val qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }
}
