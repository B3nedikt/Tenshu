package app.tenshu.photos.edit

/**
 * The whole edit of one photograph (`photos-editing.md` §2): what both renderers draw, and what ED-3
 * would store as a `PhotoEdit`.
 *
 * ⚠⚠ **The order is fixed, and this is where it is defined: [transform], then [adjust], then
 * [filter], then [markup].** The transform runs first because every later stage reads the *transformed* picture —
 * the vignette is centred on the crop the user chose, not on the uncropped frame. Adjust runs before
 * the filter so a look is applied to the picture the user tuned, as it is in Google's editor, and so
 * a filter's intensity fades between two pictures that both carry the adjustments. Every engine draws
 * this order: the shared shader ([EditShader]) does adjust-then-filter in one pass over the
 * transformed picture, and the CPU port ([EditColor.apply]) is the reference the golden tests pin
 * each engine to — with a recipe that has both stages, because the two do not commute.
 *
 * [markup] draws last (ED-4), over the coloured picture, so no slider or look recolours the ink and
 * the vignette does not darken it. Its points live in the source frame, so it is *placed* by the
 * transform like the pixels it sits on (`photos-editing.md` §11.1). ⚠ A redaction is the one part
 * that also acts first: its pixels are destroyed in the source before the transform reads it
 * ([MarkupRender.redactionsInSource]).
 */
data class EditRecipe(
    val transform: TransformEdit = TransformEdit(),
    val adjust: AdjustEdit = AdjustEdit(),
    /** `null` = no look (Google's *Original*). */
    val filter: FilterEdit? = null,
    val markup: MarkupEdit = MarkupEdit(),
) {
    /** Nothing to save, and nothing to lose (ED-D6). */
    val isIdentity: Boolean get() = transform.isIdentity && colorIsIdentity && markup.isEmpty

    /**
     * Whether this and [other] draw the same picture — ⚠ not `==`: a look at intensity 0 draws
     * nothing, and the aspect lock is a handle rule, not pixels ([TransformEdit.isIdentity]'s rule).
     * What the editor's "is there anything to save" asks once it can open on a stored edit (ED-3).
     */
    fun drawsLike(other: EditRecipe): Boolean = drawn() == other.drawn()

    private fun drawn(): EditRecipe = copy(
        transform = transform.copy(aspect = AspectPreset.FREEFORM),
        filter = filter?.takeIf { it.intensity > 0 },
    )

    /** The colour stages leave every pixel as it was — the renderers skip the shader entirely. */
    val colorIsIdentity: Boolean get() = adjust.isIdentity && (filter == null || filter.intensity == 0)

    /**
     * The colour stages as one value. [vignette] is where the vignette is centred, normalised to the
     * output being drawn: the whole output for an export, the crop box for the uncropped preview.
     */
    fun colorStage(vignette: NormRect = NormRect.FULL): ColorStage = ColorStage(adjust, filter, vignette)
}

/** Adjust and Filter, as one engine call draws them. */
data class ColorStage(
    val adjust: AdjustEdit,
    val filter: FilterEdit?,
    /** The vignette's frame, normalised to the output (`0..1`, y down). */
    val vignette: NormRect = NormRect.FULL,
) {
    val isIdentity: Boolean get() = adjust.isIdentity && (filter == null || filter.intensity == 0)
}

/**
 * Google's eleven pixel-math sliders (`photos-editing.md` §1.2), each `-100..100`, `0` = untouched.
 *
 * ⚠ Integers, as Google's dial is: an edit is a handful of small numbers to store (ED-3), and two
 * edits that look the same compare equal.
 */
data class AdjustEdit(
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
) {
    val isIdentity: Boolean get() = this == NONE

    operator fun get(tool: AdjustTool): Int = when (tool) {
        AdjustTool.BRIGHTNESS -> brightness
        AdjustTool.CONTRAST -> contrast
        AdjustTool.TONE -> tone
        AdjustTool.WHITE_POINT -> whitePoint
        AdjustTool.HIGHLIGHTS -> highlights
        AdjustTool.SHADOWS -> shadows
        AdjustTool.BLACK_POINT -> blackPoint
        AdjustTool.SATURATION -> saturation
        AdjustTool.WARMTH -> warmth
        AdjustTool.TINT -> tint
        AdjustTool.VIGNETTE -> vignette
    }

    /** [value] clamped to `-100..100`. */
    fun with(tool: AdjustTool, value: Int): AdjustEdit {
        val v = value.coerceIn(-MAX, MAX)
        return when (tool) {
            AdjustTool.BRIGHTNESS -> copy(brightness = v)
            AdjustTool.CONTRAST -> copy(contrast = v)
            AdjustTool.TONE -> copy(tone = v)
            AdjustTool.WHITE_POINT -> copy(whitePoint = v)
            AdjustTool.HIGHLIGHTS -> copy(highlights = v)
            AdjustTool.SHADOWS -> copy(shadows = v)
            AdjustTool.BLACK_POINT -> copy(blackPoint = v)
            AdjustTool.SATURATION -> copy(saturation = v)
            AdjustTool.WARMTH -> copy(warmth = v)
            AdjustTool.TINT -> copy(tint = v)
            AdjustTool.VIGNETTE -> copy(vignette = v)
        }
    }

    companion object {
        const val MAX = 100
        val NONE = AdjustEdit()
    }
}

/**
 * The Adjust tab's sliders, **in Google's order** (§1.2). The shader applies them in its own fixed
 * order ([EditShader]), which is not this one: this is the order they are listed in.
 */
enum class AdjustTool(val label: String) {
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    TONE("Tone"),
    WHITE_POINT("White point"),
    HIGHLIGHTS("Highlights"),
    SHADOWS("Shadows"),
    BLACK_POINT("Black point"),
    SATURATION("Saturation"),
    WARMTH("Warmth"),
    TINT("Tint"),
    VIGNETTE("Vignette"),
}

/** A look and how strongly it applies, `0..100`. */
data class FilterEdit(val look: FilterLook, val intensity: Int = FULL) {
    companion object {
        const val FULL = 100
    }
}

/**
 * The Filter tab's looks (ED-D5): **our own**, baked into one 3D LUT each by [EditColor.lut] from the
 * shared definitions in [FilterLooks], so a look's name means the same pixels on every platform.
 *
 * ⚠⚠ [id] is what ED-3 would store — never the label (which may be translated or renamed) and never
 * the ordinal (which moves when a look is added). Never reuse an id for a different look.
 *
 * ⚠ None of these names is one of Google's (§1.2) or Apple's `CIPhotoEffect*` looks: the names are
 * ours, as the looks are.
 */
enum class FilterLook(val id: String, val label: String) {
    DAYBREAK("daybreak", "Daybreak"),
    HARBOUR("harbour", "Harbour"),
    EMBER("ember", "Ember"),
    FJORD("fjord", "Fjord"),
    LINEN("linen", "Linen"),
    MOSS("moss", "Moss"),
    SLATE("slate", "Slate"),
    GRAPHITE("graphite", "Graphite"),
    ;

    companion object {
        fun byId(id: String): FilterLook? = entries.firstOrNull { it.id == id }
    }
}
