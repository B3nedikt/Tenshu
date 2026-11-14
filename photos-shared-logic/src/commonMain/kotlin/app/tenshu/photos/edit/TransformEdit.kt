package app.tenshu.photos.edit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A rectangle in **normalised** frame coordinates: `0..1` on both axes, origin top-left, y down.
 *
 * ⚠ Normalised rather than in pixels because the same edit is drawn against two decodes of one
 * photograph — a screen-sized preview and the full-size export — and a pixel rect would be right
 * for exactly one of them.
 */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * The crop's aspect presets, in the order Google's editor lists them (`photos-editing.md` §1.2).
 *
 * ⚠ The ratio is width:height **as the user sees it** — 4:3 is landscape, 3:4 portrait — which is
 * why both are listed rather than one with an orientation switch.
 */
enum class AspectPreset(val label: String, val width: Int, val height: Int) {
    FREEFORM("Freeform", 0, 0),
    ORIGINAL("Original", 0, 0),
    SQUARE("Square", 1, 1),
    R5_4("5:4", 5, 4),
    R4_3("4:3", 4, 3),
    R3_2("3:2", 3, 2),
    R16_9("16:9", 16, 9),
    R4_5("4:5", 4, 5),
    R3_4("3:4", 3, 4),
    R2_3("2:3", 2, 3),
    R9_16("9:16", 9, 16),
    ;

    /** The same preset turned a quarter: 4:3 ↔ 3:4. The non-ratio ones are their own transpose. */
    val transposed: AspectPreset
        get() = if (width == 0 || width == height) this
        else entries.firstOrNull { it.width == height && it.height == width } ?: this
}

/**
 * The Transform family's recipe for one photograph (`photos-editing.md` §2) — what ED-3 will store
 * as a `PhotoEdit`, and what both renderers draw.
 *
 * ⚠⚠ **Crop, straighten and perspective live in the SOURCE frame; rotate and mirror apply last.**
 * That is what makes rotate and mirror free: a 90° turn changes nothing but [quarterTurns], the crop
 * turns with the picture by construction, and "crop, then rotate" is the same image as "rotate, then
 * crop the same pixels". The shells work in the **view** frame (what is on screen), so every
 * view-space read and write goes through the accessors here ([viewCrop], [viewStraightenDegrees],
 * [viewPerspective]) and never touches the stored fields directly.
 *
 * The source frame is the photograph **upright** — EXIF orientation already applied by the decode
 * (`photos-creation-features.md` §9.1). An edit is never drawn against raw sensor order.
 */
data class TransformEdit(
    /** Anticlockwise quarter turns, `0..3` — Google's rotate button turns anticlockwise. */
    val quarterTurns: Int = 0,
    /** A horizontal flip, applied after [quarterTurns] — i.e. left↔right **on screen**. */
    val mirrored: Boolean = false,
    /** Fine rotation in the source frame, degrees, positive = clockwise. */
    val straightenDegrees: Float = 0f,
    /** Horizontal keystone in the source frame, `-1..1`; positive widens the right edge. */
    val perspectiveX: Float = 0f,
    /** Vertical keystone in the source frame, `-1..1`; positive widens the top edge. */
    val perspectiveY: Float = 0f,
    /** The crop, in the source frame. */
    val crop: NormRect = NormRect.FULL,
    /** The aspect lock, **view-space** (turns with [quarterTurns]). */
    val aspect: AspectPreset = AspectPreset.FREEFORM,
) {
    /**
     * Whether this edit leaves the photograph's pixels exactly as they were.
     *
     * ⚠ The aspect lock is not a change: choosing *Square* and putting the crop back to the whole
     * frame saves nothing, so it must not arm the ✕ confirmation (ED-D6) or the save button.
     */
    val isIdentity: Boolean
        get() = quarterTurns == 0 && !mirrored && straightenDegrees == 0f &&
            perspectiveX == 0f && perspectiveY == 0f && crop == NormRect.FULL

    // ── view-space accessors ─────────────────────────────────────────────────────────────────

    /** The crop as the user sees it. */
    val viewCrop: NormRect get() = orientRect(crop, quarterTurns, mirrored)

    fun withViewCrop(rect: NormRect): TransformEdit = copy(crop = unorientRect(rect, quarterTurns, mirrored).snappedToFull())

    /** ⚠ A mirror reverses the sense of a rotation; a quarter turn does not. */
    val viewStraightenDegrees: Float get() = if (mirrored) -straightenDegrees else straightenDegrees

    fun withViewStraighten(degrees: Float): TransformEdit {
        val clamped = degrees.coerceIn(-MAX_STRAIGHTEN_DEGREES, MAX_STRAIGHTEN_DEGREES)
        return copy(straightenDegrees = if (mirrored) -clamped else clamped)
    }

    /**
     * The keystone as the user sees it: `x` widens the right edge, `y` the top edge.
     *
     * ⚠ A keystone is treated as the direction of the edge it widens — right is `(1, 0)`, top is
     * `(0, -1)` in y-down screen space — and a direction turns with the picture like any vector.
     */
    val viewPerspective: Pair<Float, Float>
        get() {
            val (x, y) = orientVector(perspectiveX, -perspectiveY, quarterTurns, mirrored)
            return x to -y
        }

    fun withViewPerspective(x: Float, y: Float): TransformEdit {
        val (sx, sy) = unorientVector(x.coerceIn(-1f, 1f), -y.coerceIn(-1f, 1f), quarterTurns, mirrored)
        return copy(perspectiveX = sx, perspectiveY = -sy)
    }

    // ── the strip's actions ─────────────────────────────────────────────────────────────────

    /**
     * Google's rotate: a quarter turn anticlockwise **on screen**. The aspect lock turns with the picture.
     *
     * ⚠ The mirror is applied after [quarterTurns], and a mirror reverses the sense of a rotation, so
     * on a mirrored picture the stored turn steps the other way — else Rotate turns it clockwise.
     */
    fun rotatedAnticlockwise(): TransformEdit =
        copy(quarterTurns = (quarterTurns + if (mirrored) 3 else 1) % 4, aspect = aspect.transposed)

    /**
     * Mirror left↔right on screen.
     *
     * ⚠ The stored straighten and keystone are left alone, and they look the same on screen
     * afterwards: the view reads them through [mirrored], so flipping it flips what a straighten
     * *means* and the picture's tilt is mirrored with the picture — which is what a mirror is.
     */
    fun mirroredOnScreen(): TransformEdit = copy(mirrored = !mirrored)

    /**
     * The frame's size as the user sees it, for a source of [sourceWidth]×[sourceHeight] — swapped
     * by an odd number of quarter turns.
     */
    fun viewSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        if (quarterTurns % 2 == 0) sourceWidth to sourceHeight else sourceHeight to sourceWidth

    /**
     * Lock the crop to [preset] for a source of [sourceWidth]×[sourceHeight].
     *
     * The crop becomes the largest rectangle of that ratio that fits the frame, centred where the
     * current crop is centred (pulled back inside the frame where it would overhang). *Freeform*
     * only drops the lock and keeps the crop.
     */
    fun withAspect(preset: AspectPreset, sourceWidth: Int, sourceHeight: Int): TransformEdit {
        val ratio = aspectRatioOf(preset, sourceWidth, sourceHeight) ?: return copy(aspect = preset)
        val (vw, vh) = viewSize(sourceWidth, sourceHeight)
        val view = viewCrop
        // Normalised width/height of a crop of pixel ratio `ratio` inside a vw×vh frame.
        val normRatio = ratio * vh / vw
        val w: Float
        val h: Float
        if (normRatio >= 1f) { w = 1f; h = 1f / normRatio } else { h = 1f; w = normRatio }
        val cx = view.centerX.coerceIn(w / 2f, 1f - w / 2f)
        val cy = view.centerY.coerceIn(h / 2f, 1f - h / 2f)
        return copy(aspect = preset)
            .withViewCrop(NormRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f))
    }

    /** The locked pixel ratio (width / height, view-space), or `null` for Freeform. */
    fun aspectRatioOf(preset: AspectPreset, sourceWidth: Int, sourceHeight: Int): Float? = when (preset) {
        AspectPreset.FREEFORM -> null
        AspectPreset.ORIGINAL -> viewSize(sourceWidth, sourceHeight).let { (w, h) -> w.toFloat() / h }
        else -> preset.width.toFloat() / preset.height
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────────

    /**
     * How to draw this edit from an upright source of [sourceWidth]×[sourceHeight] pixels.
     *
     * With [includeCrop] `false` the plan draws the **whole** frame (straightened, keystoned,
     * turned) — the editor's preview, which shows the crop as an overlay rather than applying it.
     */
    fun plan(sourceWidth: Int, sourceHeight: Int, includeCrop: Boolean = true): RenderPlan {
        require(sourceWidth > 0 && sourceHeight > 0) { "Empty source ${sourceWidth}x$sourceHeight" }
        val w = sourceWidth.toDouble()
        val h = sourceHeight.toDouble()

        // 1. Keystone: each widened edge grows outward, so the quad always covers the frame.
        val topExp = KEYSTONE * max(perspectiveY, 0f)
        val bottomExp = KEYSTONE * max(-perspectiveY, 0f)
        val rightExp = KEYSTONE * max(perspectiveX, 0f)
        val leftExp = KEYSTONE * max(-perspectiveX, 0f)
        val keystone = Homography.fromQuad(
            src = doubleArrayOf(0.0, 0.0, w, 0.0, w, h, 0.0, h),
            dst = doubleArrayOf(
                -topExp * w / 2, -leftExp * h / 2,
                w + topExp * w / 2, -rightExp * h / 2,
                w + bottomExp * w / 2, h + rightExp * h / 2,
                -bottomExp * w / 2, h + leftExp * h / 2,
            ),
        )

        // 2. Straighten about the centre, scaled up just enough that no corner shows empty.
        val radians = straightenDegrees * PI / 180.0
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val cover = max(c + (h / w) * s, (w / h) * s + c)
        val straighten = Homography.translate(w / 2, h / 2) *
            Homography.rotate(radians) * Homography.scale(cover, cover) * Homography.translate(-w / 2, -h / 2)

        // 3. Crop.
        val rect = if (includeCrop) crop else NormRect.FULL
        val cropW = max(1, (rect.width * w).roundToInt())
        val cropH = max(1, (rect.height * h).roundToInt())
        val cropM = Homography.translate(-rect.left * w, -rect.top * h)

        // 4. Turn and mirror the cropped result.
        var outW = cropW
        var outH = cropH
        var orient = Homography.IDENTITY
        repeat(quarterTurns) {
            // Anticlockwise: (x, y) → (y, width − x), and the edges swap.
            orient = Homography(doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, outW.toDouble(), 0.0, 0.0, 1.0)) * orient
            val t = outW; outW = outH; outH = t
        }
        if (mirrored) {
            orient = Homography(doubleArrayOf(-1.0, 0.0, outW.toDouble(), 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) * orient
        }

        val total = orient * cropM * straighten * keystone
        return RenderPlan(outputWidth = outW, outputHeight = outH, matrix = total.values.map { it.toFloat() }.toFloatArray())
    }

    companion object {
        /** Google's straighten range. */
        const val MAX_STRAIGHTEN_DEGREES = 45f

        /** How far a full keystone widens an edge, as a fraction of the frame (half each side). */
        const val KEYSTONE = 0.5

        /** The smallest crop edge, normalised — a handle dragged past it stops rather than inverts. */
        const val MIN_CROP = 0.05f
    }
}

/**
 * One way to draw an edit: an output of [outputWidth]×[outputHeight] pixels, and the 3×3 projective
 * [matrix] (row-major) that takes a **source** pixel to an **output** pixel.
 *
 * ⚠ One matrix, not a list of steps, so the two renderers — a Compose canvas on Android and desktop,
 * Core Image on iOS — cannot disagree about the order. [corners] is the same thing said as four
 * points, which is the form Core Image's `CIPerspectiveTransform` takes.
 */
class RenderPlan(val outputWidth: Int, val outputHeight: Int, val matrix: FloatArray) {
    init { require(matrix.size == 9) }

    /** Where the source's top-left, top-right, bottom-right and bottom-left land, as x,y pairs. */
    fun corners(sourceWidth: Int, sourceHeight: Int): FloatArray {
        val m = Homography(matrix.map { it.toDouble() }.toDoubleArray())
        val w = sourceWidth.toDouble()
        val h = sourceHeight.toDouble()
        return listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h)
            .flatMap { (x, y) -> m.map(x, y).let { listOf(it.first.toFloat(), it.second.toFloat()) } }
            .toFloatArray()
    }

    /** Map one source point — for tests, and for shells that hit-test against the source. */
    fun map(x: Float, y: Float): Pair<Float, Float> =
        Homography(matrix.map { it.toDouble() }.toDoubleArray()).map(x.toDouble(), y.toDouble())
            .let { it.first.toFloat() to it.second.toFloat() }

    /** The inverse of [map]: which source pixel an output pixel shows. */
    fun unmap(x: Float, y: Float): Pair<Float, Float> =
        Homography(matrix.map { it.toDouble() }.toDoubleArray()).inverse().map(x.toDouble(), y.toDouble())
            .let { it.first.toFloat() to it.second.toFloat() }

    /**
     * How many output pixels one source pixel spans at source point ([x], [y]) — the square root of
     * the map's area scale there. Exact for everything but a keystone, which varies across the frame.
     */
    fun localScale(x: Float, y: Float): Float {
        val (x0, y0) = map(x, y)
        val (x1, y1) = map(x + 1f, y)
        val (x2, y2) = map(x, y + 1f)
        val det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        return kotlin.math.sqrt(abs(det))
    }
}

// ── orientation of rects and vectors ─────────────────────────────────────────────────────────────

/** Source frame → view frame: [turns] anticlockwise quarter turns, then a mirror. */
internal fun orientRect(r: NormRect, turns: Int, mirrored: Boolean): NormRect {
    var out = r
    repeat(turns) {
        // A point (x, y) goes to (y, 1 − x).
        out = NormRect(left = out.top, top = 1f - out.right, right = out.bottom, bottom = 1f - out.left)
    }
    if (mirrored) out = NormRect(1f - out.right, out.top, 1f - out.left, out.bottom)
    return out
}

internal fun unorientRect(r: NormRect, turns: Int, mirrored: Boolean): NormRect {
    var out = if (mirrored) NormRect(1f - r.right, r.top, 1f - r.left, r.bottom) else r
    repeat(turns) {
        // The inverse: (x, y) goes to (1 − y, x).
        out = NormRect(left = 1f - out.bottom, top = out.left, right = 1f - out.top, bottom = out.right)
    }
    return out
}

internal fun orientVector(x: Float, y: Float, turns: Int, mirrored: Boolean): Pair<Float, Float> {
    var vx = x
    var vy = y
    repeat(turns) { val t = vx; vx = vy; vy = -t }
    if (mirrored) vx = -vx
    return vx to vy
}

internal fun unorientVector(x: Float, y: Float, turns: Int, mirrored: Boolean): Pair<Float, Float> {
    var vx = if (mirrored) -x else x
    var vy = y
    repeat(turns) { val t = vx; vx = -vy; vy = t }
    return vx to vy
}

// ── a small 3×3 projective matrix ─────────────────────────────────────────────────────────────────

/** Row-major 3×3. Kept to what [TransformEdit.plan] needs. */
internal class Homography(val values: DoubleArray) {
    init { require(values.size == 9) }

    operator fun times(o: Homography): Homography {
        val a = values
        val b = o.values
        return Homography(DoubleArray(9) { i ->
            val r = i / 3
            val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        })
    }

    fun map(x: Double, y: Double): Pair<Double, Double> {
        val v = values
        val wq = v[6] * x + v[7] * y + v[8]
        return (v[0] * x + v[1] * y + v[2]) / wq to (v[3] * x + v[4] * y + v[5]) / wq
    }

    /** The adjugate over the determinant. A plan is never singular: every step is invertible. */
    fun inverse(): Homography {
        val (a, b, c, d, e) = values
        val f = values[5]
        val g = values[6]
        val h = values[7]
        val i = values[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-18) { "Singular plan" }
        return Homography(
            doubleArrayOf(
                (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
            ),
        )
    }

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translate(x: Double, y: Double) = Homography(doubleArrayOf(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0))

        fun scale(x: Double, y: Double) = Homography(doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0))

        /** Positive = clockwise on a y-down screen. */
        fun rotate(radians: Double): Homography {
            val c = cos(radians)
            val s = sin(radians)
            return Homography(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }

        /**
         * The projective map taking the four [src] points to the four [dst] points (x,y pairs).
         * The classic eight-unknown linear solve, with partial pivoting.
         */
        fun fromQuad(src: DoubleArray, dst: DoubleArray): Homography {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[2 * i]
                val y = src[2 * i + 1]
                val u = dst[2 * i]
                val v = dst[2 * i + 1]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (col in 0 until 8) {
                var pivot = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                val p = a[col][col]
                require(abs(p) > 1e-12) { "Degenerate quad" }
                for (k in col until 9) a[col][k] /= p
                for (r in 0 until 8) if (r != col) {
                    val f = a[r][col]
                    if (f != 0.0) for (k in col until 9) a[r][k] -= f * a[col][k]
                }
            }
            return Homography(DoubleArray(9) { if (it < 8) a[it][8] else 1.0 })
        }
    }
}

/**
 * ⚠ A crop within float noise of the whole frame IS the whole frame. A ratio that matches the frame
 * (*Original*, or 4:3 on a 4:3 photo) computes to 0.99999994, and without this the hair's-breadth
 * crop would count as an edit — arming Save and the ✕ question for a photograph nobody changed.
 */
internal fun NormRect.snappedToFull(): NormRect {
    val e = 1e-4f
    return if (left < e && top < e && right > 1f - e && bottom > 1f - e) NormRect.FULL else this
}

/** Clamp helper shared by the crop handles. */
internal fun Float.clamp01(): Float = min(1f, max(0f, this))
