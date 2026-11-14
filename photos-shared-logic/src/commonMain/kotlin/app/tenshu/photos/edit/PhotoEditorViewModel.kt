package app.tenshu.photos.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The editor's tabs (`photos-editing.md` §2), in Google's order.
 *
 * ⚠⚠ **Only tabs that have a working tool.** A tab with nothing behind it is the line-with-no-route
 * rule (`control-cross-app-open.md` §5g.2) — which is why there is no *Enhance* (§10).
 */
enum class EditorTab(val label: String) {
    TRANSFORM("Crop"),
    ADJUST("Adjust"),
    /** ED-4. Before Filters, where Google's row has it (§1.2). */
    MARKUP("Markup"),
    FILTER("Filters"),
}

/** The current tool's colour and width. */
data class MarkupBrush(val color: InkColor, val width: InkWidth = InkWidth.MEDIUM)

/** One brush per inking tool, so switching tools keeps each one's own choice. Redact has none. */
data class MarkupBrushes(
    val pen: MarkupBrush = MarkupBrush(InkColor.RED),
    val highlighter: MarkupBrush = MarkupBrush(InkColor.YELLOW),
    val text: MarkupBrush = MarkupBrush(InkColor.WHITE),
) {
    operator fun get(tool: MarkupTool): MarkupBrush? = when (tool) {
        MarkupTool.PEN -> pen
        MarkupTool.HIGHLIGHTER -> highlighter
        MarkupTool.TEXT -> text
        MarkupTool.REDACT -> null
    }

    fun with(tool: MarkupTool, brush: MarkupBrush): MarkupBrushes = when (tool) {
        MarkupTool.PEN -> copy(pen = brush)
        MarkupTool.HIGHLIGHTER -> copy(highlighter = brush)
        MarkupTool.TEXT -> copy(text = brush)
        MarkupTool.REDACT -> this
    }
}

/**
 * A text label open for typing: a new one ([id] `null`, not in the recipe until committed) or an
 * existing one being edited. The shells show a text field while this is set.
 */
data class LabelDraft(val id: Int?, val text: String, val anchor: NormPoint)

/** The Transform tab's tools. Rotate and mirror are not tools: they are the strip, always shown. */
enum class TransformTool { CROP, STRAIGHTEN, PERSPECTIVE }

/**
 * Whether the original's bytes are here to edit. [UNREADABLE]: they are here, but this device could
 * not decode them (a format it has no decoder for, or no memory for the upright copy) — said, never
 * a spinner that never stops.
 */
enum class EditorSource {
    LOADING, READY, UNAVAILABLE, UNREADABLE,

    /**
     * ED-3: whether the photo has a stored edit could not be read. ⚠⚠ Never opened as "no edit": a
     * save from there would replace an edit the user never saw — its crop and its redactions.
     */
    EDIT_UNKNOWN,
}

/**
 * Draws an edit at full size and encodes it. One per platform: a Compose canvas + `encodeToBytes`
 * on Android and desktop, Core Image on iOS.
 *
 * ⚠⚠ **Blocking and throwing.** It is called off the main thread ([PhotoEditorViewModel]'s render
 * dispatcher), and a failure must reach the user as *couldn't save* — never as empty bytes uploaded
 * as a photograph.
 *
 * ⚠⚠ The bytes handed in are the **original's** and the output must be **upright**: decode with EXIF
 * orientation applied, draw [TransformEdit.plan], encode with no orientation tag
 * (`photos-creation-features.md` §9.1). Never a thumbnail rung — those are raw sensor order on
 * Android/JVM.
 */
fun interface PhotoEditRenderer {
    /** [recipe] drawn in [EditRecipe]'s order: transform, adjust, filter. */
    fun render(original: ByteArray, recipe: EditRecipe): ByteArray
}

/** What the last save wrote — the shells close onto it differently. */
enum class SavedAs {
    /** *Save* (ED-3): the photo itself, now showing its edit — or its original, after a revert. */
    EDIT,
    /** *Save as copy* (ED-1): a new photograph beside the original. */
    COPY,
}

data class PhotoEditorUiState(
    /**
     * The photograph being edited. ⚠ Its bytes are never written: *Save* records a `PhotoEdit`
     * beside them (ED-3) and *Save as copy* writes a new file (ED-1) — never an overwrite (ED-D1).
     */
    val photo: Photo,
    val source: EditorSource = EditorSource.LOADING,
    /**
     * The upright source's size, reported by the shell once it has decoded the preview. ⚠ Only the
     * **ratio** is used (the aspect presets), so a downsampled preview's size is as good as the
     * original's. `0` until known, and the presets wait for it.
     */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** The whole edit — what a save draws. */
    val recipe: EditRecipe = EditRecipe(),
    /**
     * The edit the photo had when the editor opened (ED-3) — the stored recipe, re-opened over the
     * original — or nothing. ⚠ What [dirty] compares with: re-opening an edit is not a change.
     */
    val initialRecipe: EditRecipe = EditRecipe(),
    /** The photo had a stored edit when the editor opened, so *Save* replaces it and *Revert* exists. */
    val hasStoredEdit: Boolean = false,
    /**
     * The stored edit came from a newer editor and cannot be re-opened here (ED-D9). The editor
     * starts from the original and says so; a save then **replaces** that edit — the user's choice,
     * made with the notice on screen, never a silent drop of a part this build cannot read.
     */
    val storedEditUnreadable: Boolean = false,
    val tab: EditorTab = EditorTab.TRANSFORM,
    val tool: TransformTool = TransformTool.CROP,
    /**
     * The Adjust slider open on its dial, or `null` for the list of sliders. Google's pattern
     * (§1.2): pick a slider, turn it, *Done* goes back to the list.
     */
    val adjustTool: AdjustTool? = null,
    val markupTool: MarkupTool = MarkupTool.PEN,
    val brushes: MarkupBrushes = MarkupBrushes(),
    /** A label being typed — the shells show a text field over the editor while it is set. */
    val labelDraft: LabelDraft? = null,
    /** Markup's undo and redo (ED-4). ⚠ Markup only: the other tabs' controls are their own undo. */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** The finger is down on the picture: show the original (Google's "hold to compare"). */
    val comparing: Boolean = false,
    /** ✕ was pressed over unsaved changes — the shells ask before discarding (ED-D6). */
    val confirmingDiscard: Boolean = false,
    val saving: Boolean = false,
    /** The last save did not happen. Said, never swallowed. */
    val saveFailed: Boolean = false,
    /**
     * What was saved: after *Save*, the photo itself with its new [Photo.renditionPath] (or none,
     * after a revert); after *Save as copy*, the copy. ⚠ The shells close the editor onto it, as
     * [savedAs] says; nothing else reads it.
     */
    val saved: Photo? = null,
    val savedAs: SavedAs? = null,
) {
    /** The Transform part of [recipe] — what the crop box, the strip and the dials read. */
    val edit: TransformEdit get() = recipe.transform

    /** There is something to save — and to lose: the edit differs from the one the editor opened with. */
    val dirty: Boolean get() = !recipe.drawsLike(initialRecipe)

    /**
     * *Save* (ED-D11). ⚠ An identity recipe over a stored edit is still a save — it is a revert —
     * which is why this is [dirty] and not "has an edit".
     */
    val canSave: Boolean get() = dirty && source == EditorSource.READY && !saving && saved == null

    /** *Save as copy*: anything other than the original can be copied, changed in this session or not. */
    val canSaveAsCopy: Boolean get() = !recipe.isIdentity && source == EditorSource.READY && !saving && saved == null

    val tabs: List<EditorTab> get() = EditorTab.entries

    val sourceKnown: Boolean get() = sourceWidth > 0 && sourceHeight > 0

    /** The current markup tool's brush; `null` for Redact. */
    val brush: MarkupBrush? get() = brushes[markupTool]
}

/**
 * The photo editor (`photos-editing.md` ED-1, ED-2, ED-4): Transform, Adjust, Markup, Filter, and
 * *Save as copy*.
 *
 * ⚠⚠ **Compose-free**, so the SwiftUI shell drives this same model. Everything an editor decides —
 * the recipe, the aspect lock, the crop handles, when ✕ asks, what a save writes — is here; the
 * shells only draw [PhotoEditorUiState] and report gestures.
 *
 * ⚠ It holds the original's bytes ([originalBytes]) for its whole life. Unlike the viewer (whose
 * pager would make that a function of how far the user swiped) an editor is one photograph, and the
 * save needs the full-size bytes anyway.
 */
class PhotoEditorViewModel(
    private val repository: PhotosRepository,
    photo: Photo,
    private val renderer: PhotoEditRenderer,
    /** Where the full-size render runs. ⚠ Never the main thread: it is a decode, a draw and an encode. */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoEditorUiState(photo = photo))
    val state: StateFlow<PhotoEditorUiState> = _state.asStateFlow()

    /** The original's bytes, once [PhotoEditorUiState.source] is [EditorSource.READY]. */
    var originalBytes: ByteArray? = null
        private set

    init {
        viewModelScope.launch {
            // ⚠ The stored edit is read BEFORE the editor turns ready (ED-3): a recipe that landed
            // after the first gesture would snap the picture out from under the user's finger.
            // ⚠ Read by contentId whatever [Photo.isEdited] says — the photo may come from a list
            // mapped before a peer's edit arrived.
            val storedRead = runCatchingCancellable { repository.photoEdit(photo.contentId) }
                .onFailure { println("PhotoEditor -> the stored edit could not be read: $it") }
            val stored = storedRead.getOrNull()
            val bytes = runCatchingCancellable { repository.originalBytes(photo.path) }.getOrNull()
            originalBytes = bytes
            val initial = stored?.recipe ?: EditRecipe()
            _state.update {
                it.copy(
                    source = when {
                        storedRead.isFailure -> EditorSource.EDIT_UNKNOWN
                        bytes == null -> EditorSource.UNAVAILABLE
                        else -> EditorSource.READY
                    },
                    recipe = initial,
                    initialRecipe = initial,
                    hasStoredEdit = stored != null,
                    storedEditUnreadable = stored != null && stored.recipe == null,
                )
            }
        }
    }

    /** The shell decoded the original, upright, at [width]×[height] (any scale). */
    fun onSourceDecoded(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _state.update { it.copy(sourceWidth = width, sourceHeight = height) }
    }

    /** The shell could not decode the original for its preview. ⚠ Nothing can be edited or saved. */
    fun onSourceUndecodable() {
        if (_state.value.source == EditorSource.READY) _state.update { it.copy(source = EditorSource.UNREADABLE) }
    }

    fun selectTab(tab: EditorTab) {
        gesture = null
        _state.update { it.copy(tab = tab, adjustTool = null, comparing = false) }
    }

    fun selectTool(tool: TransformTool) = _state.update { it.copy(tool = tool) }

    // ── the strip ───────────────────────────────────────────────────────────────────────────

    fun rotate() = transform { it.rotatedAnticlockwise() }

    fun mirror() = transform { it.mirroredOnScreen() }

    // ── tools ───────────────────────────────────────────────────────────────────────────────

    fun setAspect(preset: AspectPreset) {
        val s = _state.value
        if (!s.sourceKnown && preset != AspectPreset.FREEFORM) return
        transform { it.withAspect(preset, s.sourceWidth, s.sourceHeight) }
    }

    /** View-space degrees, clamped to ±45. */
    fun setStraighten(degrees: Float) = transform { it.withViewStraighten(degrees) }

    /** View-space keystone, each `-1..1`: [x] widens the right edge, [y] the top edge. */
    fun setPerspective(x: Float, y: Float) = transform { it.withViewPerspective(x, y) }

    /**
     * A drag on the crop box, view-space and normalised, relative to [start] — the box as it was
     * when the finger went down. ⚠ Relative to the start rather than accumulated per event, so a
     * handle clamped at an edge does not drift once the finger comes back.
     */
    fun dragCrop(start: NormRect, handle: CropHandle, dx: Float, dy: Float) {
        val s = _state.value
        val ratio = s.edit.aspectRatioOf(s.edit.aspect, s.sourceWidth, s.sourceHeight)
            ?.takeIf { s.sourceKnown }
            ?.let { s.edit.normalisedRatio(it, s.sourceWidth, s.sourceHeight) }
        transform { it.withViewCrop(dragCrop(start, handle, dx, dy, ratio)) }
    }

    // ── Adjust ──────────────────────────────────────────────────────────────────────────────

    /** Open [tool]'s dial, or `null` = back to the list (*Done*). */
    fun selectAdjustTool(tool: AdjustTool?) = _state.update { it.copy(adjustTool = tool) }

    /** [value] in `-100..100`, rounded: Google's dial moves in whole steps. */
    fun setAdjust(tool: AdjustTool, value: Float) = recipe { it.copy(adjust = it.adjust.with(tool, value.roundToInt())) }

    /** The open slider's reset: this one slider back to 0, the rest untouched. */
    fun resetAdjust(tool: AdjustTool) = recipe { it.copy(adjust = it.adjust.with(tool, 0)) }

    // ── Filter ──────────────────────────────────────────────────────────────────────────────

    /**
     * [look], or `null` = *Original*. ⚠ Choosing a look again keeps its intensity; choosing another
     * starts it at full, as Google's does.
     */
    fun selectLook(look: FilterLook?) = recipe {
        it.copy(filter = look?.let { l -> if (it.filter?.look == l) it.filter else FilterEdit(l) })
    }

    /** The current look's intensity, `0..100`. Nothing without a look. */
    fun setFilterIntensity(value: Float) = recipe { r ->
        r.copy(filter = r.filter?.copy(intensity = value.roundToInt().coerceIn(0, FilterEdit.FULL)))
    }

    /** Back to the photograph as it was — every tab's edits, and markup's undo history with them. */
    fun reset() {
        if (!editable) return
        undoStack.clear()
        redoStack.clear()
        gesture = null
        recipe { EditRecipe() }
        _state.update { it.copy(labelDraft = null) }
        syncHistory()
    }

    // ── Markup (ED-4) ───────────────────────────────────────────────────────────────────────

    fun selectMarkupTool(tool: MarkupTool) {
        gesture = null
        _state.update { it.copy(markupTool = tool) }
    }

    fun setInkColor(color: InkColor) = _state.update { s ->
        val brush = s.brush ?: return@update s
        s.copy(brushes = s.brushes.with(s.markupTool, brush.copy(color = color)))
    }

    fun setInkWidth(width: InkWidth) = _state.update { s ->
        val brush = s.brush ?: return@update s
        s.copy(brushes = s.brushes.with(s.markupTool, brush.copy(width = width)))
    }

    /**
     * A finger down on the Markup tab's picture at ([u], [v]), normalised to the picture as shown —
     * the **cropped** result, as a save writes it. Every point is taken to the source frame here, so
     * the shells never see it. Pen/highlighter start a stroke, Redact a box, Text a label pick (or,
     * if it stays a tap, a new label).
     */
    fun markupDown(u: Float, v: Float) {
        val s = _state.value
        if (!editable || s.tab != EditorTab.MARKUP || s.labelDraft != null) return
        val view = markupView() ?: return
        gesture = when (s.markupTool) {
            MarkupTool.PEN, MarkupTool.HIGHLIGHTER -> {
                val brush = s.brush ?: return
                val p = view.toSource(u, v)
                val stroke = InkStroke(
                    id = s.recipe.markup.nextId,
                    highlighter = s.markupTool == MarkupTool.HIGHLIGHTER,
                    color = brush.color,
                    width = view.sourceWidthOf(brush.width.fractionFor(s.markupTool), p),
                    points = listOf(p),
                )
                pushUndo()
                markup { it.added(stroke) }
                Gesture.Drawing(stroke.id, u, v)
            }
            MarkupTool.REDACT -> Gesture.Boxing(null, u, v)
            MarkupTool.TEXT -> {
                val hit = labelAt(u, v, view)
                if (hit != null) {
                    val (ax, ay) = view.toView(hit.anchor)
                    Gesture.MovingLabel(hit.id, u, v, ax - u, ay - v, moved = false)
                } else Gesture.Tapping(u, v)
            }
        }
    }

    fun markupMove(u: Float, v: Float) {
        val g = gesture ?: return
        if (!editable) return
        val view = markupView() ?: return
        when (g) {
            is Gesture.Drawing -> {
                // ⚠ Thinned in view space, so a slow finger does not store a point per pixel.
                if (hypot(u - g.lastU, v - g.lastV) < MIN_POINT_STEP) return
                val stroke = _state.value.recipe.markup.item(g.id) as? InkStroke ?: return
                markup { it.replaced(stroke.copy(points = stroke.points + view.toSource(u, v))) }
                gesture = g.copy(lastU = u, lastV = v)
            }
            is Gesture.Boxing -> {
                val corners = listOf(
                    view.toSource(g.startU, g.startV), view.toSource(u, g.startV),
                    view.toSource(u, v), view.toSource(g.startU, v),
                )
                if (g.id == null) {
                    if (abs(u - g.startU) < MIN_BOX && abs(v - g.startV) < MIN_BOX) return
                    val box = Redaction(_state.value.recipe.markup.nextId, corners)
                    pushUndo()
                    markup { it.added(box) }
                    gesture = g.copy(id = box.id)
                } else {
                    markup { it.replaced(Redaction(g.id, corners)) }
                }
            }
            is Gesture.MovingLabel -> {
                if (!g.moved && hypot(u - g.startU, v - g.startV) < TAP_SLOP) return
                val label = _state.value.recipe.markup.item(g.id) as? TextLabel ?: return
                if (!g.moved) {
                    pushUndo()
                    gesture = g.copy(moved = true)
                }
                markup { it.replaced(label.copy(anchor = view.toSource(u + g.offsetU, v + g.offsetV))) }
            }
            is Gesture.Tapping -> if (hypot(u - g.u, v - g.v) >= TAP_SLOP) gesture = Gesture.Ignored
            Gesture.Ignored -> Unit
        }
    }

    fun markupUp() {
        val g = gesture ?: return
        gesture = null
        if (!editable) return
        when (g) {
            is Gesture.MovingLabel -> if (!g.moved) {
                val label = _state.value.recipe.markup.item(g.id) as? TextLabel ?: return
                _state.update { it.copy(labelDraft = LabelDraft(label.id, label.text, label.anchor)) }
            }
            is Gesture.Tapping -> {
                val view = markupView() ?: return
                _state.update { it.copy(labelDraft = LabelDraft(null, "", view.toSource(g.u, g.v))) }
            }
            else -> Unit
        }
    }

    /**
     * The open label's text, committed. A new label joins the recipe; an existing one changes. ⚠
     * Blank text is no label: a new one is dropped, an existing one deleted.
     */
    fun commitLabel(text: String) {
        val draft = _state.value.labelDraft ?: return
        _state.update { it.copy(labelDraft = null) }
        if (!editable) return
        val clean = text.replace('\n', ' ').trim()
        val existing = draft.id?.let { _state.value.recipe.markup.item(it) as? TextLabel }
        when {
            existing == null && clean.isEmpty() -> Unit
            existing == null -> {
                val s = _state.value
                val view = markupView() ?: return
                val brush = s.brushes.text
                val label = TextLabel(
                    id = s.recipe.markup.nextId,
                    text = clean,
                    color = brush.color,
                    size = view.sourceWidthOf(brush.width.fractionFor(MarkupTool.TEXT), draft.anchor),
                    anchor = draft.anchor,
                )
                pushUndo()
                markup { it.added(label) }
            }
            clean.isEmpty() -> { pushUndo(); markup { it.removed(existing.id) } }
            clean != existing.text -> { pushUndo(); markup { it.replaced(existing.copy(text = clean)) } }
        }
    }

    /** The open label, deleted (an unsaved new one is just dropped). */
    fun deleteLabel() {
        val draft = _state.value.labelDraft ?: return
        _state.update { it.copy(labelDraft = null) }
        val id = draft.id ?: return
        if (!editable || _state.value.recipe.markup.item(id) == null) return
        pushUndo()
        markup { it.removed(id) }
    }

    /** The label field closed without a change. */
    fun cancelLabel() = _state.update { it.copy(labelDraft = null) }

    fun undo() {
        if (!editable || undoStack.isEmpty()) return
        gesture = null
        redoStack.addLast(_state.value.recipe.markup)
        val previous = undoStack.removeLast()
        markup { previous }
        syncHistory()
    }

    fun redo() {
        if (!editable || redoStack.isEmpty()) return
        gesture = null
        undoStack.addLast(_state.value.recipe.markup)
        val next = redoStack.removeLast()
        markup { next }
        syncHistory()
    }

    private fun pushUndo() {
        undoStack.addLast(_state.value.recipe.markup)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        syncHistory()
    }

    private fun syncHistory() = _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }

    private fun markup(change: (MarkupEdit) -> MarkupEdit) = recipe { it.copy(markup = change(it.markup)) }

    private val editable: Boolean
        get() = _state.value.let { !it.saving && it.saved == null && it.source == EditorSource.READY }

    private val undoStack = ArrayDeque<MarkupEdit>()
    private val redoStack = ArrayDeque<MarkupEdit>()
    private var gesture: Gesture? = null

    /** The Markup tab's picture: the plan with the crop applied, for the source's reported size. */
    private fun markupView(): MarkupView? {
        val s = _state.value
        if (!s.sourceKnown) return null
        return MarkupView(s.recipe.transform.plan(s.sourceWidth, s.sourceHeight), s.sourceWidth, s.sourceHeight)
    }

    /** The top-most label under ([u], [v]), by an estimate of its box (the shells measure text; this does not). */
    private fun labelAt(u: Float, v: Float, view: MarkupView): TextLabel? {
        val labels = _state.value.recipe.markup.items.filterIsInstance<TextLabel>()
        val x = u * view.plan.outputWidth
        val y = v * view.plan.outputHeight
        return labels.lastOrNull { label ->
            val op = MarkupRender.scene(MarkupEdit(listOf(label)), view.plan, view.sourceWidth, view.sourceHeight)
                .firstOrNull() as? LabelOp ?: return@lastOrNull false
            val halfW = max(op.size * 0.3f * label.text.length, op.size * 0.6f) + op.size * 0.3f
            val halfH = op.size * 0.8f
            abs(x - op.x) <= halfW && abs(y - op.y) <= halfH
        }
    }

    private sealed interface Gesture {
        data class Drawing(val id: Int, val lastU: Float, val lastV: Float) : Gesture
        data class Boxing(val id: Int?, val startU: Float, val startV: Float) : Gesture
        data class MovingLabel(
            val id: Int, val startU: Float, val startV: Float,
            /** Where the anchor sits relative to the finger, so the label does not jump to it. */
            val offsetU: Float, val offsetV: Float, val moved: Boolean,
        ) : Gesture
        data class Tapping(val u: Float, val v: Float) : Gesture
        /** A Text press that became a drag on nothing: it does nothing. */
        data object Ignored : Gesture
    }

    fun setComparing(comparing: Boolean) = _state.update { it.copy(comparing = comparing) }

    private fun transform(change: (TransformEdit) -> TransformEdit) = recipe { it.copy(transform = change(it.transform)) }

    private fun recipe(change: (EditRecipe) -> EditRecipe) {
        val s = _state.value
        // ⚠ Frozen while a save runs: the save renders the edit it was asked to, and a change made
        // meanwhile would be lost silently when the editor closes onto the copy.
        if (s.saving || s.saved != null || s.source != EditorSource.READY) return
        _state.update { it.copy(recipe = change(it.recipe), saveFailed = false) }
    }

    private companion object {
        /** View-space distance between two stored stroke points. */
        const val MIN_POINT_STEP = 0.002f
        /** View-space size below which a redaction drag is still a tap, and draws nothing. */
        const val MIN_BOX = 0.01f
        /** View-space distance a Text press may travel and still be a tap. */
        const val TAP_SLOP = 0.015f
        const val MAX_UNDO = 100
    }

    // ── leaving ─────────────────────────────────────────────────────────────────────────────

    /**
     * ✕ or Back. `true` = close now. With unsaved changes it asks instead (ED-D6) and answers
     * `false`; the shells close on [confirmDiscard]. ⚠ Not while a save runs — closing then would
     * leave a copy appearing in the feed that the user thinks they abandoned.
     */
    fun requestClose(): Boolean {
        val s = _state.value
        if (s.saving) return false
        if (s.dirty && s.saved == null) {
            _state.update { it.copy(confirmingDiscard = true) }
            return false
        }
        return true
    }

    fun keepEditing() = _state.update { it.copy(confirmingDiscard = false) }

    /** The user chose to discard. The shell closes; nothing was written. */
    fun confirmDiscard() = _state.update { it.copy(confirmingDiscard = false) }

    // ── saving ──────────────────────────────────────────────────────────────────────────────

    /**
     * *Save* (ED-3, ED-D11): render the edit at full size and record it as the photo's own edit — the
     * original untouched, the rendition what every device shows. An identity recipe is a *Revert*
     * and renders nothing.
     */
    fun save() {
        val s = _state.value
        val bytes = originalBytes
        if (!s.canSave || bytes == null) return
        val recipe = s.recipe
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = runCatchingCancellable {
                if (recipe.isIdentity) {
                    repository.revertEdit(s.photo)
                } else {
                    val rendered = withContext(renderDispatcher) { renderer.render(bytes, recipe) }
                    repository.saveEdit(s.photo, recipe, rendered)
                }
            }
                .onFailure { println("PhotoEditor -> save failed: $it") }
                .getOrNull()
                .also { if (it == null) println("PhotoEditor -> save wrote nothing for ${s.photo.path}") }
            _state.update {
                if (saved == null) it.copy(saving = false, saveFailed = true)
                else it.copy(saving = false, saved = saved, savedAs = SavedAs.EDIT)
            }
        }
    }

    /**
     * Render the edit at full size and upload it **as a new file** beside the original (ED-D1).
     *
     * ⚠⚠ Never an overwrite: an overwrite re-mints `contentId`, which clears the favourite and leaves
     * album entries un-removable (`photos-editing.md` §3.1). [PhotosRepository.saveEditedCopy] only
     * ever creates a name that does not exist.
     */
    fun saveAsCopy() {
        val s = _state.value
        val bytes = originalBytes
        if (!s.canSaveAsCopy || bytes == null) return
        val recipe = s.recipe
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val copy = runCatchingCancellable {
                val rendered = withContext(renderDispatcher) { renderer.render(bytes, recipe) }
                repository.saveEditedCopy(s.photo, rendered)
            }
                // ⚠ The user sees "couldn't save"; the cause goes to System.out (logcat on Android),
                // the one place a device check can read it.
                .onFailure { println("PhotoEditor -> save failed: $it") }
                .getOrNull()
                .also { if (it == null) println("PhotoEditor -> save wrote nothing for ${s.photo.path}") }
            _state.update {
                if (copy == null) it.copy(saving = false, saveFailed = true)
                else it.copy(saving = false, saved = copy, savedAs = SavedAs.COPY)
            }
        }
    }

    fun dismissSaveFailure() = _state.update { it.copy(saveFailed = false) }
}
