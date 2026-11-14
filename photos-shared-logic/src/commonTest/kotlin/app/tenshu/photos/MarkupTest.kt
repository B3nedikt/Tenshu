package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.edit.AspectPreset
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.EditorTab
import app.tenshu.photos.edit.FillOp
import app.tenshu.photos.edit.InkColor
import app.tenshu.photos.edit.InkStroke
import app.tenshu.photos.edit.InkWidth
import app.tenshu.photos.edit.LabelOp
import app.tenshu.photos.edit.MarkupEdit
import app.tenshu.photos.edit.MarkupRender
import app.tenshu.photos.edit.MarkupTool
import app.tenshu.photos.edit.NormPoint
import app.tenshu.photos.edit.PhotoEditRenderer
import app.tenshu.photos.edit.PhotoEditorViewModel
import app.tenshu.photos.edit.Redaction
import app.tenshu.photos.edit.StrokeOp
import app.tenshu.photos.edit.TextLabel
import app.tenshu.photos.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Markup (`photos-editing.md` ED-4, §11): the recipe stage, its undo, and the source-frame rule —
 * ink stays on the pixels it was drawn on through any later transform.
 */
class MarkupTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val photo = Photo("/Camera/IMG_1.jpg", capturedAtMs = 42_000L, isVideo = false, contentId = "c1")

    private class Recording : PhotoEditRenderer {
        val drawn = mutableListOf<EditRecipe>()
        override fun render(original: ByteArray, recipe: EditRecipe): ByteArray {
            drawn += recipe
            return byteArrayOf(7)
        }
    }

    private fun TestScope.editor(vms: org.tenshu.viewmodel.test.OwnedViewModels, renderer: PhotoEditRenderer = Recording()): PhotoEditorViewModel {
        val repo = MockPhotosRepository(photos = listOf(photo)).apply { originals[photo.path] = byteArrayOf(1) }
        val vm = vms.own(PhotoEditorViewModel(repo, photo, renderer, dispatcher))
        testScheduler.advanceUntilIdle()
        vm.onSourceDecoded(SW, SH)
        vm.selectTab(EditorTab.MARKUP)
        return vm
    }

    private fun PhotoEditorViewModel.stroke(vararg pts: Pair<Float, Float>) {
        markupDown(pts[0].first, pts[0].second)
        for (p in pts.drop(1)) markupMove(p.first, p.second)
        markupUp()
    }

    private val markup get() = { vm: PhotoEditorViewModel -> vm.state.value.recipe.markup }

    // ── the source frame ────────────────────────────────────────────────────────────────────

    @Test
    fun aStroke_staysOnTheSamePixelsThroughALaterRotateCropStraightenAndMirror() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.stroke(0.25f to 0.4f, 0.5f to 0.5f, 0.75f to 0.6f)
        val stored = markup(vm).items.single() as InkStroke
        // Untransformed, the view is the source.
        assertNear(NormPoint(0.25f, 0.4f), stored.points.first())

        vm.rotate()
        vm.selectTab(EditorTab.TRANSFORM)
        vm.setAspect(AspectPreset.SQUARE)
        vm.setStraighten(9f)
        vm.mirror()
        assertEquals(stored, markup(vm).items.single(), "a transform never rewrites the ink")

        // Wherever the plan puts the ink, it is on the source pixels it was drawn on.
        val recipe = vm.state.value.recipe
        for ((w, h) in listOf(SW to SH, 4000 to 3000)) {
            val plan = recipe.transform.plan(w, h)
            val op = MarkupRender.scene(recipe.markup, plan, w, h).single() as StrokeOp
            stored.points.forEachIndexed { i, p ->
                val (sx, sy) = plan.unmap(op.points[2 * i], op.points[2 * i + 1])
                assertTrue(abs(sx - p.x * w) < 0.05f * w / SW && abs(sy - p.y * h) < 0.05f * h / SH, "point $i at ${w}x$h: ($sx, $sy) vs $p")
            }
        }
    }

    @Test
    fun aStrokeDrawnAfterATransform_landsWhereTheFingerWas() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.selectTab(EditorTab.TRANSFORM)
        vm.rotate()
        vm.setAspect(AspectPreset.SQUARE)
        vm.setStraighten(-12f)
        vm.selectTab(EditorTab.MARKUP)
        vm.stroke(0.3f to 0.3f, 0.6f to 0.7f)

        val recipe = vm.state.value.recipe
        val plan = recipe.transform.plan(SW, SH)
        val op = MarkupRender.scene(recipe.markup, plan, SW, SH).single() as StrokeOp
        assertNear(NormPoint(0.3f, 0.3f), NormPoint(op.points[0] / plan.outputWidth, op.points[1] / plan.outputHeight))
        assertNear(NormPoint(0.6f, 0.7f), NormPoint(op.points[2] / plan.outputWidth, op.points[3] / plan.outputHeight))
    }

    @Test
    fun aWidth_isTheFractionOfThePictureAsShown_andACropThickensItWithThePicture() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.setInkWidth(InkWidth.THICK)
        vm.stroke(0.2f to 0.5f, 0.8f to 0.5f)
        val recipe = vm.state.value.recipe
        val plan = recipe.transform.plan(SW, SH)
        val op = MarkupRender.scene(recipe.markup, plan, SW, SH).single() as StrokeOp
        assertEquals(InkWidth.THICK.fractionFor(MarkupTool.PEN) * max(SW, SH), op.width, 0.01f)

        // Crop to the left half: the picture is shown twice as large, and so is the ink on it.
        val cropped = recipe.copy(transform = recipe.transform.withViewCrop(app.tenshu.photos.edit.NormRect(0f, 0f, 0.5f, 1f)))
        val p2 = cropped.transform.plan(SW, SH)
        val op2 = MarkupRender.scene(cropped.markup, p2, SW, SH).single() as StrokeOp
        assertEquals(op.width, op2.width, 0.01f, "the same source pixels wide")
    }

    @Test
    fun aLabel_followsItsSpot_butIsDrawnUpright() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.selectMarkupTool(MarkupTool.TEXT)
        vm.markupDown(0.25f, 0.25f); vm.markupUp()
        vm.commitLabel("Hello")
        val label = markup(vm).items.single() as TextLabel
        vm.rotate()
        val recipe = vm.state.value.recipe
        val plan = recipe.transform.plan(SW, SH)
        val op = MarkupRender.scene(recipe.markup, plan, SW, SH).single() as LabelOp
        val (sx, sy) = plan.unmap(op.x, op.y)
        assertNear(label.anchor, NormPoint(sx / SW, sy / SH))
        // LabelOp has no angle: every shell draws it level, whatever the transform.
        assertEquals("Hello", op.text)
    }

    // ── the tools ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theHighlighter_isTranslucent_thePenIsNot() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.stroke(0.1f to 0.1f, 0.2f to 0.2f)
        vm.selectMarkupTool(MarkupTool.HIGHLIGHTER)
        vm.setInkColor(InkColor.GREEN)
        vm.stroke(0.1f to 0.5f, 0.9f to 0.5f)
        val recipe = vm.state.value.recipe
        val ops = MarkupRender.scene(recipe.markup, recipe.transform.plan(SW, SH), SW, SH).map { it as StrokeOp }
        assertEquals(1f, ops[0].alpha)
        assertEquals(InkColor.RED.argb, ops[0].argb, "the pen keeps its own colour")
        assertEquals(MarkupEdit.HIGHLIGHTER_ALPHA, ops[1].alpha)
        assertEquals(InkColor.GREEN.argb, ops[1].argb)
        assertTrue(ops[1].width > ops[0].width, "a highlighter is broader than a pen at the same setting")
    }

    @Test
    fun aRedaction_isTheBoxDragged_andATapDrawsNone() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.selectMarkupTool(MarkupTool.REDACT)
        vm.stroke(0.5f to 0.5f, 0.505f to 0.505f)
        assertTrue(markup(vm).isEmpty)
        assertFalse(vm.state.value.canUndo, "nothing drawn, nothing to undo")

        vm.stroke(0.2f to 0.3f, 0.4f to 0.4f, 0.6f to 0.7f)
        val box = markup(vm).items.single() as Redaction
        assertEquals(listOf(NormPoint(0.2f, 0.3f), NormPoint(0.6f, 0.3f), NormPoint(0.6f, 0.7f), NormPoint(0.2f, 0.7f)).size, box.corners.size)
        assertNear(NormPoint(0.2f, 0.3f), box.corners[0])
        assertNear(NormPoint(0.6f, 0.7f), box.corners[2])
        val recipe = vm.state.value.recipe
        val fill = MarkupRender.scene(recipe.markup, recipe.transform.plan(SW, SH), SW, SH).single() as FillOp
        assertEquals(MarkupEdit.REDACT_ARGB, fill.argb)
        assertEquals(0xFF, fill.argb ushr 24, "opaque")
        assertEquals(1, vm.state.value.let { if (it.canUndo) 1 else 0 })
    }

    @Test
    fun text_placeEditMoveAndDelete() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.selectMarkupTool(MarkupTool.TEXT)

        // A tap opens a new label; nothing joins the recipe until it has text.
        vm.markupDown(0.5f, 0.5f); vm.markupUp()
        val draft = assertNotNull(vm.state.value.labelDraft)
        assertNull(draft.id)
        assertTrue(markup(vm).isEmpty)
        vm.commitLabel("  Tenshu\n ")
        val label = markup(vm).items.single() as TextLabel
        assertEquals("Tenshu", label.text)
        assertEquals(InkColor.WHITE, label.color)

        // A tap on it opens it for editing.
        vm.markupDown(0.51f, 0.5f); vm.markupUp()
        assertEquals(label.id, vm.state.value.labelDraft?.id)
        vm.commitLabel("Tenshu!")
        assertEquals("Tenshu!", (markup(vm).items.single() as TextLabel).text)

        // A drag on it moves it, keeping where it was grabbed.
        vm.markupDown(0.52f, 0.5f)
        vm.markupMove(0.6f, 0.55f)
        vm.markupMove(0.72f, 0.6f)
        vm.markupUp()
        assertNull(vm.state.value.labelDraft, "a drag is not a tap")
        val moved = markup(vm).items.single() as TextLabel
        assertNear(NormPoint(0.7f, 0.6f), moved.anchor)

        // One undo puts the whole move back.
        vm.undo()
        assertNear(NormPoint(0.5f, 0.5f), (markup(vm).items.single() as TextLabel).anchor)
        vm.redo()

        // Deleted from its field; blank text deletes too.
        vm.markupDown(0.7f, 0.6f); vm.markupUp()
        vm.deleteLabel()
        assertTrue(markup(vm).isEmpty)

        // A new label cancelled leaves nothing, and no history.
        vm.markupDown(0.2f, 0.2f); vm.markupUp()
        val undoBefore = vm.state.value.canUndo
        vm.cancelLabel()
        assertTrue(markup(vm).isEmpty)
        assertEquals(undoBefore, vm.state.value.canUndo)
    }

    @Test
    fun aDraftOpen_blocksDrawingUntilItIsClosed() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.selectMarkupTool(MarkupTool.TEXT)
        vm.markupDown(0.5f, 0.5f); vm.markupUp()
        vm.selectMarkupTool(MarkupTool.PEN)
        vm.stroke(0.1f to 0.1f, 0.3f to 0.3f)
        assertTrue(markup(vm).isEmpty)
    }

    // ── undo ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun undoAndRedo_stepThroughTheMarkup_andANewStrokeClearsRedo() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        assertFalse(vm.state.value.canUndo)
        vm.stroke(0.1f to 0.1f, 0.2f to 0.2f)
        vm.stroke(0.5f to 0.5f, 0.6f to 0.6f)
        assertEquals(2, markup(vm).items.size)
        assertTrue(vm.state.value.dirty)

        vm.undo()
        assertEquals(1, markup(vm).items.size)
        assertTrue(vm.state.value.canRedo)
        vm.undo()
        assertTrue(markup(vm).isEmpty)
        assertFalse(vm.state.value.canUndo)
        assertFalse(vm.state.value.dirty, "all of it undone is nothing to save, and nothing to lose")
        assertTrue(vm.requestClose())

        vm.redo()
        assertEquals(1, markup(vm).items.size)
        vm.stroke(0.7f to 0.1f, 0.8f to 0.2f)
        assertFalse(vm.state.value.canRedo, "a new stroke ends the redo branch")
        assertEquals(2, markup(vm).items.size)
    }

    @Test
    fun undoTouchesMarkupOnly() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.stroke(0.1f to 0.1f, 0.2f to 0.2f)
        vm.rotate()
        vm.undo()
        assertTrue(markup(vm).isEmpty)
        assertEquals(1, vm.state.value.edit.quarterTurns, "the turn is the strip's, not markup's")
    }

    @Test
    fun reset_clearsMarkupAndItsHistory() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.stroke(0.1f to 0.1f, 0.2f to 0.2f)
        vm.reset()
        assertTrue(markup(vm).isEmpty)
        assertFalse(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)
    }

    // ── where markup can be drawn, and what a save gets ─────────────────────────────────────

    @Test
    fun markupGestures_onlyOnTheMarkupTab() = vmTest(dispatcher) { vms ->
        val vm = editor(vms)
        vm.selectTab(EditorTab.ADJUST)
        vm.stroke(0.1f to 0.1f, 0.2f to 0.2f)
        assertTrue(markup(vm).isEmpty)
    }

    @Test
    fun theSave_drawsTheMarkup_andMarkupFreezesWhileSaving() = vmTest(dispatcher) { vms ->
        val renderer = Recording()
        val vm = editor(vms, renderer)
        vm.stroke(0.1f to 0.1f, 0.2f to 0.2f)
        val asked = vm.state.value.recipe
        assertTrue(vm.state.value.canSave)
        vm.saveAsCopy()
        vm.stroke(0.5f to 0.5f, 0.6f to 0.6f)
        vm.undo()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(asked), renderer.drawn)
        assertEquals(1, asked.markup.items.size)
        assertNotNull(vm.state.value.saved)
    }

    @Test
    fun markupAlone_isAnEdit() {
        val recipe = EditRecipe(markup = MarkupEdit(listOf(Redaction(1, List(4) { NormPoint(0f, 0f) }))))
        assertFalse(recipe.isIdentity)
        assertTrue(recipe.colorIsIdentity, "and not a colour change")
    }

    private fun assertNear(expected: NormPoint, actual: NormPoint, eps: Float = 0.005f) {
        assertTrue(abs(expected.x - actual.x) < eps && abs(expected.y - actual.y) < eps, "expected $expected, was $actual")
    }

    private companion object {
        const val SW = 400
        const val SH = 300
    }
}
