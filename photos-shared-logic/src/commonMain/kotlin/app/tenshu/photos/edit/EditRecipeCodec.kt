package app.tenshu.photos.edit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An [EditRecipe] as the string a `PhotoEdit` row stores (`photos-editing.md` §12.2, ED-D9).
 *
 * ⚠⚠ **Versioned, and a version this build does not know is not an error.** [decode] answers
 * [Decoded.Unreadable] for a newer `v` (or a body it cannot parse): the photo still *shows* its baked
 * rendition (ED-D2), and only re-editing is blocked — never a partial decode that would silently
 * drop a newer tool's part of the edit on the next save.
 *
 * ⚠⚠ **Every enum is stored by a stable id** — [FilterLook.id], [InkColor.id], [AspectPreset]'s
 * name — never a label (translated, renamed) or an ordinal (moves when an entry is added). A look
 * must not be retuned once used (§10.3): this is how an old edit re-opens.
 *
 * ⚠ Floats are written as Kotlin prints them — the shortest text that reads back to the same
 * `Float` — so a decoded recipe is **equal** to the one encoded, which is what lets the editor tell
 * "re-opened and changed" from "re-opened" (`PhotoEditorUiState.dirty`).
 */
object EditRecipeCodec {

    /** The version [encode] writes. Bump it — not `:photo-service` — when the recipe gains a tool. */
    const val VERSION = 1

    sealed interface Decoded {
        data class Recipe(val recipe: EditRecipe) : Decoded
        /** A newer editor's recipe, or a body this build cannot read: show, do not re-edit. */
        data object Unreadable : Decoded
    }

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(recipe: EditRecipe): String = json.encodeToString(RecipeV1.serializer(), recipe.toV1())

    fun decode(text: String): Decoded {
        val version = runCatching { json.parseToJsonElement(text).jsonObject["v"]?.jsonPrimitive?.intOrNull }
            .getOrNull()
        if (version != VERSION) return Decoded.Unreadable
        return runCatching { json.decodeFromString(RecipeV1.serializer(), text).toRecipe() }
            .fold(onSuccess = { Decoded.Recipe(it) }, onFailure = { Decoded.Unreadable })
    }

    // ── v1 ──────────────────────────────────────────────────────────────────────────────────────
    //
    // ⚠ Frozen once shipped: a field renamed or retyped here makes every stored v1 recipe
    // unreadable. A change is a RecipeV2 beside this one, with v1 still decoded.

    @Serializable
    private data class RecipeV1(
        /** ⚠ No default: `encodeDefaults = false` would otherwise drop the version itself. */
        val v: Int,
        val transform: TransformV1? = null,
        val adjust: AdjustV1? = null,
        val filter: FilterV1? = null,
        val markup: List<ItemV1> = emptyList(),
    )

    @Serializable
    private data class TransformV1(
        val quarterTurns: Int = 0,
        val mirrored: Boolean = false,
        val straighten: Float = 0f,
        val perspectiveX: Float = 0f,
        val perspectiveY: Float = 0f,
        /** `[left, top, right, bottom]`, source frame. */
        val crop: List<Float>? = null,
        /** [AspectPreset.name]. */
        val aspect: String? = null,
    )

    @Serializable
    private data class AdjustV1(
        val brightness: Int = 0,
        val contrast: Int = 0,
        val tone: Int = 0,
        val whitePoint: Int = 0,
        val highlights: Int = 0,
        val shadows: Int = 0,
        val blackPoint: Int = 0,
        val saturation: Int = 0,
        val warmth: Int = 0,
        val tint: Int = 0,
        val vignette: Int = 0,
    )

    /** [look] is [FilterLook.id]. */
    @Serializable
    private data class FilterV1(val look: String, val intensity: Int)

    /**
     * One markup item. [points] is flat `x0, y0, x1, y1, …` in the source frame — a stroke's points,
     * a redaction's four corners, a label's anchor.
     */
    @Serializable
    private data class ItemV1(
        val kind: String,
        val id: Int,
        val points: List<Float>,
        /** [InkColor.id]; absent for a redaction. */
        val color: String? = null,
        /** A stroke's width or a label's size, as a fraction of the source's long edge. */
        val size: Float = 0f,
        val text: String? = null,
    )

    private const val STROKE = "stroke"
    private const val HIGHLIGHTER = "highlighter"
    private const val REDACTION = "redaction"
    private const val LABEL = "label"

    private fun EditRecipe.toV1(): RecipeV1 = RecipeV1(
        v = VERSION,
        transform = transform.takeUnless { it == TransformEdit() }?.let { t ->
            TransformV1(
                quarterTurns = t.quarterTurns,
                mirrored = t.mirrored,
                straighten = t.straightenDegrees,
                perspectiveX = t.perspectiveX,
                perspectiveY = t.perspectiveY,
                crop = t.crop.takeUnless { it == NormRect.FULL }?.let { listOf(it.left, it.top, it.right, it.bottom) },
                aspect = t.aspect.takeUnless { it == AspectPreset.FREEFORM }?.name,
            )
        },
        adjust = adjust.takeUnless { it.isIdentity }?.let { a ->
            AdjustV1(
                a.brightness, a.contrast, a.tone, a.whitePoint, a.highlights, a.shadows,
                a.blackPoint, a.saturation, a.warmth, a.tint, a.vignette,
            )
        },
        filter = filter?.let { FilterV1(it.look.id, it.intensity) },
        markup = markup.items.map { item ->
            when (item) {
                is InkStroke -> ItemV1(
                    kind = if (item.highlighter) HIGHLIGHTER else STROKE,
                    id = item.id,
                    points = item.points.flat(),
                    color = item.color.id,
                    size = item.width,
                )
                is Redaction -> ItemV1(kind = REDACTION, id = item.id, points = item.corners.flat())
                is TextLabel -> ItemV1(
                    kind = LABEL,
                    id = item.id,
                    points = listOf(item.anchor.x, item.anchor.y),
                    color = item.color.id,
                    size = item.size,
                    text = item.text,
                )
            }
        },
    )

    /** ⚠ Throws on anything it cannot name — an unknown look, colour, aspect or item kind — so [decode] answers [Decoded.Unreadable]. */
    private fun RecipeV1.toRecipe(): EditRecipe = EditRecipe(
        transform = transform?.let { t ->
            TransformEdit(
                quarterTurns = t.quarterTurns.also { require(it in 0..3) },
                mirrored = t.mirrored,
                straightenDegrees = t.straighten,
                perspectiveX = t.perspectiveX,
                perspectiveY = t.perspectiveY,
                crop = t.crop?.let { c ->
                    require(c.size == 4)
                    NormRect(c[0], c[1], c[2], c[3])
                } ?: NormRect.FULL,
                aspect = t.aspect?.let { AspectPreset.valueOf(it) } ?: AspectPreset.FREEFORM,
            )
        } ?: TransformEdit(),
        adjust = adjust?.let { a ->
            AdjustEdit(
                a.brightness, a.contrast, a.tone, a.whitePoint, a.highlights, a.shadows,
                a.blackPoint, a.saturation, a.warmth, a.tint, a.vignette,
            )
        } ?: AdjustEdit(),
        filter = filter?.let { FilterEdit(requireNotNull(FilterLook.byId(it.look)), it.intensity) },
        markup = MarkupEdit(
            markup.map { item ->
                when (item.kind) {
                    STROKE, HIGHLIGHTER -> InkStroke(
                        id = item.id,
                        highlighter = item.kind == HIGHLIGHTER,
                        color = inkColor(item.color),
                        width = item.size,
                        points = item.points.pairs(),
                    )
                    REDACTION -> Redaction(id = item.id, corners = item.points.pairs().also { require(it.size == 4) })
                    LABEL -> TextLabel(
                        id = item.id,
                        text = requireNotNull(item.text),
                        color = inkColor(item.color),
                        size = item.size,
                        anchor = item.points.pairs().single(),
                    )
                    else -> error("unknown markup kind ${item.kind}")
                }
            },
        ),
    )

    private fun inkColor(id: String?): InkColor = requireNotNull(InkColor.entries.firstOrNull { it.id == id })

    private fun List<NormPoint>.flat(): List<Float> = flatMap { listOf(it.x, it.y) }

    private fun List<Float>.pairs(): List<NormPoint> {
        require(size % 2 == 0)
        return chunked(2) { (x, y) -> NormPoint(x, y) }
    }
}
