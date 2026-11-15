package app.tenshu.photos.edit

import kotlin.math.max

/**
 * A point in the **source** frame, normalised: `0..1` on both axes of the upright original, y down.
 *
 * ⚠⚠ Source frame, as [TransformEdit]'s crop is (`photos-editing.md` §11.1): a stroke is ink on
 * the photograph's pixels, so a rotate, mirror, crop, straighten or keystone made afterwards moves
 * it with the picture by construction — nothing re-maps it. Normalised rather than in pixels for
 * the crop's reason: the preview and the save decode the same photograph at two sizes.
 */
data class NormPoint(val x: Float, val y: Float)

/** The Markup tab's tools, in Google's order (`photos-editing.md` §1.2). */
enum class MarkupTool(val label: String) {
    PEN("Pen"),
    HIGHLIGHTER("Highlighter"),
    REDACT("Redact"),
    TEXT("Text"),
}

/**
 * The ink palette. ⚠ [id] is what ED-3 would store, never the label or the ordinal — the same rule
 * as [FilterLook.id].
 */
enum class InkColor(val id: String, val label: String, val argb: Int) {
    RED("red", "Red", 0xFFE53935.toInt()),
    YELLOW("yellow", "Yellow", 0xFFFDD835.toInt()),
    GREEN("green", "Green", 0xFF43A047.toInt()),
    BLUE("blue", "Blue", 0xFF1E88E5.toInt()),
    WHITE("white", "White", 0xFFFFFFFF.toInt()),
    BLACK("black", "Black", 0xFF000000.toInt()),
}

/**
 * Three widths per tool. A width is a fraction of the long edge of the picture **as shown when the
 * stroke is drawn** — what the user sees — and is converted to the source frame then
 * ([PhotoEditorViewModel]), so a later crop thickens the ink with the picture it is on.
 */
enum class InkWidth(val label: String, internal val pen: Float, internal val highlighter: Float, internal val text: Float) {
    THIN("Thin", 0.004f, 0.016f, 0.035f),
    MEDIUM("Medium", 0.008f, 0.03f, 0.055f),
    THICK("Thick", 0.016f, 0.05f, 0.085f),
    ;

    /** The view-frame fraction for [tool]. ⚠ Redact has no width: it is a box. */
    fun fractionFor(tool: MarkupTool): Float = when (tool) {
        MarkupTool.PEN -> pen
        MarkupTool.HIGHLIGHTER -> highlighter
        MarkupTool.TEXT -> text
        MarkupTool.REDACT -> 0f
    }
}

/** One markup item. [id] is stable for the item's life, so a label can be picked, moved and edited. */
sealed interface MarkupItem {
    val id: Int
}

/**
 * A pen or highlighter stroke. [width] is a fraction of the source's **long edge**; [points] are in
 * the source frame. A highlighter is the same stroke drawn at [HIGHLIGHTER_ALPHA], as one coverage —
 * a stroke crossing itself is not darker where it crosses.
 */
data class InkStroke(
    override val id: Int,
    val highlighter: Boolean,
    val color: InkColor,
    val width: Float,
    val points: List<NormPoint>,
) : MarkupItem

/**
 * A redaction: the four corners of a box, in the source frame (a box drawn on a straightened or
 * keystoned view is a quad on the source).
 *
 * ⚠⚠ **A privacy tool.** The saved copy's pixels under it are destroyed in the source raster
 * before anything else draws ([MarkupRender.redactionsInSource]) — not covered by a layer that could
 * be stripped. The photograph being edited is never written (ED-D1), so the original keeps them.
 */
data class Redaction(override val id: Int, val corners: List<NormPoint>) : MarkupItem

/**
 * A text label: one line, centred on [anchor] (source frame), [size] a fraction of the source's long
 * edge. ⚠ The anchor follows the picture; the text itself is drawn **upright** in the output — never
 * turned, mirrored or keystoned — so a label stays readable after a rotate or a mirror.
 */
data class TextLabel(
    override val id: Int,
    val text: String,
    val color: InkColor,
    val size: Float,
    val anchor: NormPoint,
) : MarkupItem

/** The Markup stage of an [EditRecipe]: its items, drawn in order (a later one over an earlier one). */
data class MarkupEdit(val items: List<MarkupItem> = emptyList()) {
    val isEmpty: Boolean get() = items.isEmpty()

    val hasRedactions: Boolean get() = items.any { it is Redaction }

    internal val nextId: Int get() = (items.maxOfOrNull { it.id } ?: 0) + 1

    internal fun added(item: MarkupItem) = MarkupEdit(items + item)

    internal fun replaced(item: MarkupItem) = MarkupEdit(items.map { if (it.id == item.id) item else it })

    internal fun removed(id: Int) = MarkupEdit(items.filterNot { it.id == id })

    fun item(id: Int): MarkupItem? = items.firstOrNull { it.id == id }

    companion object {
        /** A highlighter's opacity. */
        const val HIGHLIGHTER_ALPHA = 0.4f

        /** What a redaction is filled with. Opaque, so nothing under it shows through. */
        const val REDACT_ARGB = 0xFF000000.toInt()
    }
}

// ── the scene: what each shell draws ─────────────────────────────────────────────────────────────

/**
 * One drawing operation, in **output** pixels (y down) of the plan it was built for.
 *
 * ⚠⚠ The shells draw these and only these — Compose on Android/desktop, Core Graphics on iOS — so
 * where ink lands, how wide it is and what is filled are decided once, here.
 */
sealed interface MarkupOp

/** A polyline as x,y pairs, stroked with round caps and joins at [alpha], as one coverage. */
class StrokeOp(val points: FloatArray, val width: Float, val argb: Int, val alpha: Float) : MarkupOp

/** A filled polygon as x,y pairs. Opaque. */
class FillOp(val polygon: FloatArray, val argb: Int) : MarkupOp

/** One line of bold text, centred on ([x], [y]), [size] pixels tall (the font size). */
class LabelOp(val text: String, val x: Float, val y: Float, val size: Float, val argb: Int) : MarkupOp

object MarkupRender {

    /**
     * [markup] as draw operations over the output of [plan], for an upright source of
     * [sourceWidth]×[sourceHeight] pixels (any scale — the preview's and the save's give the same
     * picture).
     *
     * - ink: every point through the plan (exact, keystone included); the width scaled by the plan
     *   at the stroke's first point (exact but for a keystone, where the centreline is still exact);
     * - redactions: the four corners through the plan — a projective map keeps edges straight, so
     *   the quad is exact;
     * - labels: the anchor through the plan, the size scaled there, the text upright.
     */
    fun scene(markup: MarkupEdit, plan: RenderPlan, sourceWidth: Int, sourceHeight: Int): List<MarkupOp> {
        if (markup.isEmpty) return emptyList()
        val w = sourceWidth.toFloat()
        val h = sourceHeight.toFloat()
        val long = max(w, h)
        return markup.items.mapNotNull { item ->
            when (item) {
                is InkStroke -> {
                    if (item.points.isEmpty()) return@mapNotNull null
                    val first = item.points.first()
                    val scale = plan.localScale(first.x * w, first.y * h)
                    // ⚠ A tap is a dot: a zero-length path strokes to nothing on some engines.
                    val pts = if (item.points.size == 1) listOf(first, NormPoint(first.x + 1e-4f, first.y)) else item.points
                    val out = FloatArray(pts.size * 2)
                    pts.forEachIndexed { i, p ->
                        val (x, y) = plan.map(p.x * w, p.y * h)
                        out[2 * i] = x
                        out[2 * i + 1] = y
                    }
                    StrokeOp(
                        out,
                        width = max(1f, item.width * long * scale),
                        argb = item.color.argb,
                        alpha = if (item.highlighter) MarkupEdit.HIGHLIGHTER_ALPHA else 1f,
                    )
                }
                is Redaction -> {
                    val out = FloatArray(8)
                    item.corners.take(4).forEachIndexed { i, p ->
                        val (x, y) = plan.map(p.x * w, p.y * h)
                        out[2 * i] = x
                        out[2 * i + 1] = y
                    }
                    FillOp(out, MarkupEdit.REDACT_ARGB)
                }
                is TextLabel -> {
                    if (item.text.isBlank()) return@mapNotNull null
                    val ax = item.anchor.x * w
                    val ay = item.anchor.y * h
                    val (x, y) = plan.map(ax, ay)
                    LabelOp(item.text, x, y, max(1f, item.size * long * plan.localScale(ax, ay)), item.color.argb)
                }
            }
        }
    }

    /**
     * Every redaction as a polygon in **source** pixels of a [sourceWidth]×[sourceHeight] decode —
     * what a save fills **before** the transform reads the source.
     *
     * ⚠⚠ Why the source and not only the output: the transform samples the source with a filter, so
     * an output pixel just outside a box drawn on the output still blends source pixels inside it.
     * Filled in the source (no anti-aliasing, plus a [SOURCE_FILL_PAD] px outline so a pixel the edge
     * merely touches goes too), no later stage reads a single original pixel from under it — the
     * saved copy is the same bytes whatever was there (`MarkupRenderTest`, desktop; `MarkupParityTests`, iOS). The output's
     * own box ([scene]) is what makes it look right: drawn after the colour stages, it stays black
     * under any slider or look.
     */
    fun redactionsInSource(markup: MarkupEdit, sourceWidth: Int, sourceHeight: Int): List<FloatArray> =
        markup.items.filterIsInstance<Redaction>().map { r ->
            FloatArray(8).also { out ->
                r.corners.take(4).forEachIndexed { i, p ->
                    out[2 * i] = p.x * sourceWidth
                    out[2 * i + 1] = p.y * sourceHeight
                }
            }
        }

    /** The width, in source pixels, of the outline drawn round each source fill. */
    const val SOURCE_FILL_PAD = 2f
}

/**
 * The Markup tab's picture — the cropped output of [plan] — and the one place a point a shell
 * reports (normalised to that picture) becomes a source-frame point, and back.
 */
internal class MarkupView(val plan: RenderPlan, val sourceWidth: Int, val sourceHeight: Int) {

    fun toSource(u: Float, v: Float): NormPoint {
        val (x, y) = plan.unmap(u * plan.outputWidth, v * plan.outputHeight)
        return NormPoint((x / sourceWidth).clamp01(), (y / sourceHeight).clamp01())
    }

    fun toView(p: NormPoint): Pair<Float, Float> {
        val (x, y) = plan.map(p.x * sourceWidth, p.y * sourceHeight)
        return x / plan.outputWidth to y / plan.outputHeight
    }

    /**
     * A width given as [viewFraction] of the shown picture's long edge, as a fraction of the
     * source's long edge at [at] — so it is drawn at exactly the width the user picked, today.
     */
    fun sourceWidthOf(viewFraction: Float, at: NormPoint): Float {
        val scale = plan.localScale(at.x * sourceWidth, at.y * sourceHeight)
        return viewFraction * max(plan.outputWidth, plan.outputHeight) / (scale * max(sourceWidth, sourceHeight))
    }
}
