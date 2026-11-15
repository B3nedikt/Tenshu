package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.edit.AdjustTool
import app.tenshu.photos.edit.AspectPreset
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.EditorTab
import app.tenshu.photos.edit.FilterEdit
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.CropHandle
import app.tenshu.photos.edit.EditorSource
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.PhotoEditRenderer
import app.tenshu.photos.edit.PhotoEditorViewModel
import app.tenshu.photos.edit.SavedAs
import app.tenshu.photos.data.StoredEdit
import app.tenshu.photos.edit.TransformEdit
import app.tenshu.photos.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The editor's view model (`photos-editing.md` ED-1): when ✕ asks (ED-D6), what a save writes
 * (ED-D1), and that a failure is said.
 */
class PhotoEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val photo = Photo(
        "/Camera/IMG_1.jpg", capturedAtMs = 42_000L, isVideo = false, contentId = "c1",
        latE7 = 10L, lngE7 = 20L,
    )

    private fun repo(holding: Boolean = true) = MockPhotosRepository(photos = listOf(photo)).apply {
        if (holding) originals[photo.path] = byteArrayOf(1, 2, 3)
    }

    /** Records the edit it was asked to draw; answers fixed bytes. */
    private class Recording(val fail: Boolean = false) : PhotoEditRenderer {
        val drawn = mutableListOf<EditRecipe>()
        override fun render(original: ByteArray, recipe: EditRecipe): ByteArray {
            drawn += recipe
            if (fail) error("encoder refused")
            return byteArrayOf(7, 7)
        }
    }

    @Test
    fun closingAnUntouchedEdit_closesAtOnce() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        assertEquals(EditorSource.READY, vm.state.value.source)
        assertTrue(vm.requestClose())
        assertFalse(vm.state.value.confirmingDiscard)
    }

    @Test
    fun closingWithUnsavedChanges_asksFirst() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.rotate()
        assertFalse(vm.requestClose(), "an edit is not dropped on a stray tap (ED-D6)")
        assertTrue(vm.state.value.confirmingDiscard)
        vm.keepEditing()
        assertFalse(vm.state.value.confirmingDiscard)
        assertEquals(1, vm.state.value.edit.quarterTurns, "keep editing keeps the edit")
    }

    @Test
    fun anEditUndoneByHand_isNoLongerDirty() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.mirror(); vm.mirror()
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.requestClose())
    }

    @Test
    fun saveRendersTheEditAndWritesACopy_neverTheOriginal() = vmTest(dispatcher) { vms ->
        val repo = repo()
        val renderer = Recording()
        val vm = vms.own(PhotoEditorViewModel(repo, photo, renderer, dispatcher))
        testScheduler.advanceUntilIdle()
        vm.onSourceDecoded(400, 300)
        vm.setAspect(AspectPreset.SQUARE)
        vm.rotate()
        val asked = vm.state.value.recipe
        vm.saveAsCopy()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(asked), renderer.drawn)
        val saved = assertNotNull(vm.state.value.saved)
        assertEquals("/Camera/IMG_1-edited.jpg", saved.path)
        assertEquals(42_000L, saved.capturedAtMs)
        assertEquals(10L, saved.latE7)
        assertEquals(20L, saved.lngE7)
        assertEquals(listOf<Byte>(1, 2, 3), repo.originals[photo.path]?.toList(), "the original's bytes are untouched")
        assertEquals(listOf<Byte>(7, 7), repo.savedCopies.single().second.toList())
        assertTrue(vm.requestClose(), "a saved edit closes without asking")
    }

    @Test
    fun aFailedRenderIsSaid_andNothingIsWritten() = vmTest(dispatcher) { vms ->
        val repo = repo()
        val vm = vms.own(PhotoEditorViewModel(repo, photo, Recording(fail = true), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.rotate()
        vm.saveAsCopy()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.saved)
        assertTrue(repo.savedCopies.isEmpty())
        assertTrue(vm.state.value.canSave, "the user may try again")
    }

    @Test
    fun anEditIsFrozenWhileItSaves() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.rotate()
        vm.saveAsCopy()
        assertTrue(vm.state.value.saving)
        vm.rotate()
        assertFalse(vm.requestClose(), "not while a copy is being written")
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.edit.quarterTurns)
    }

    @Test
    fun withoutTheOriginal_theEditorSaysSo_andOffersNothing() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(holding = false), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        assertEquals(EditorSource.UNAVAILABLE, vm.state.value.source)
        vm.rotate()
        assertFalse(vm.state.value.dirty)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun anOriginalTheShellCannotDecode_isSaid_andNothingCanBeEdited() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.onSourceUndecodable()
        assertEquals(EditorSource.UNREADABLE, vm.state.value.source, "not a spinner for ever")
        vm.rotate()
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun aLockedHandleDragKeepsTheRatioOnScreen() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.onSourceDecoded(400, 300)
        vm.setAspect(AspectPreset.SQUARE)
        val start = vm.state.value.edit.viewCrop
        vm.dragCrop(start, CropHandle.TOP_LEFT, 0.2f, 0.05f)
        val c = vm.state.value.edit.viewCrop
        assertEquals(start.right, c.right, 1e-4f)
        assertEquals(start.bottom, c.bottom, 1e-4f)
        assertEquals(1f, c.width * 400 / (c.height * 300), 1e-3f, "still square in pixels")
    }

    @Test
    fun presetsWaitForTheSourceSize() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.setAspect(AspectPreset.SQUARE)
        assertEquals(NormRect.FULL, vm.state.value.edit.crop, "no ratio can be honoured without the frame's shape")
    }

    // ── ED-2: Adjust and Filter ──────────────────────────────────────────────────────────────

    @Test
    fun aSliderIsAnEdit_andItsResetIsNot() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.selectTab(EditorTab.ADJUST)
        vm.selectAdjustTool(AdjustTool.WARMTH)
        vm.setAdjust(AdjustTool.WARMTH, 31.6f)
        assertEquals(32, vm.state.value.recipe.adjust.warmth, "whole steps, as Google's dial")
        assertTrue(vm.state.value.dirty)
        assertTrue(vm.state.value.canSave, "an adjustment alone is worth saving")
        vm.setAdjust(AdjustTool.CONTRAST, 500f)
        assertEquals(100, vm.state.value.recipe.adjust.contrast, "clamped")
        vm.resetAdjust(AdjustTool.WARMTH)
        assertEquals(0, vm.state.value.recipe.adjust.warmth)
        assertEquals(100, vm.state.value.recipe.adjust.contrast, "a slider's reset leaves the others")
        vm.resetAdjust(AdjustTool.CONTRAST)
        assertFalse(vm.state.value.dirty, "every slider back at 0 is no edit")
        assertTrue(vm.requestClose(), "and ✕ closes without asking")
    }

    @Test
    fun doneGoesBackToTheList_andTabsForgetTheOpenSlider() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.selectTab(EditorTab.ADJUST)
        vm.selectAdjustTool(AdjustTool.VIGNETTE)
        assertEquals(AdjustTool.VIGNETTE, vm.state.value.adjustTool)
        vm.selectAdjustTool(null)
        assertNull(vm.state.value.adjustTool)
        vm.selectAdjustTool(AdjustTool.TINT)
        vm.selectTab(EditorTab.FILTER)
        vm.selectTab(EditorTab.ADJUST)
        assertNull(vm.state.value.adjustTool)
    }

    @Test
    fun aLookStartsFull_keepsItsIntensity_andOriginalRemovesIt() = vmTest(dispatcher) { vms ->
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.setFilterIntensity(40f)
        assertNull(vm.state.value.recipe.filter, "no intensity without a look")
        vm.selectLook(FilterLook.HARBOUR)
        assertEquals(FilterEdit(FilterLook.HARBOUR, 100), vm.state.value.recipe.filter)
        vm.setFilterIntensity(40f)
        vm.selectLook(FilterLook.HARBOUR)
        assertEquals(40, vm.state.value.recipe.filter?.intensity, "the same look again keeps its strength")
        vm.selectLook(FilterLook.SLATE)
        assertEquals(FilterEdit(FilterLook.SLATE, 100), vm.state.value.recipe.filter)
        vm.setFilterIntensity(0f)
        assertFalse(vm.state.value.dirty, "a look at 0 changes no pixel")
        vm.selectLook(null)
        assertNull(vm.state.value.recipe.filter)
    }

    @Test
    fun saveDrawsTheWholeRecipe_andResetClearsEveryTab() = vmTest(dispatcher) { vms ->
        val renderer = Recording()
        val vm = vms.own(PhotoEditorViewModel(repo(), photo, renderer, dispatcher))
        testScheduler.advanceUntilIdle()
        vm.rotate()
        vm.setAdjust(AdjustTool.SHADOWS, 25f)
        vm.selectLook(FilterLook.EMBER)
        val asked = vm.state.value.recipe
        assertEquals(1, asked.transform.quarterTurns)
        vm.saveAsCopy()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(asked), renderer.drawn, "transform, adjust and filter reach the renderer together")

        val again = vms.own(PhotoEditorViewModel(repo(), photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        again.rotate(); again.setAdjust(AdjustTool.TONE, -10f); again.selectLook(FilterLook.MOSS)
        again.reset()
        assertEquals(EditRecipe(), again.state.value.recipe)
    }

    // ── ED-3: Save (non-destructive), re-open, revert ─────────────────────────────────────────

    @Test
    fun save_recordsTheEditOnThePhotoItself_notACopy() = vmTest(dispatcher) { vms ->
        val repository = repo()
        val vm = vms.own(PhotoEditorViewModel(repository, photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        vm.rotate()
        vm.save()
        testScheduler.advanceUntilIdle()
        val saved = assertNotNull(vm.state.value.saved)
        assertEquals(SavedAs.EDIT, vm.state.value.savedAs)
        assertEquals(photo.path, saved.path, "the photo itself")
        assertEquals(photo.contentId, saved.contentId)
        assertNotNull(saved.renditionPath)
        assertTrue(repository.savedCopies.isEmpty(), "no copy")
        assertEquals(1, repository.edits[photo.contentId]?.recipe?.transform?.quarterTurns)
        assertEquals(listOf<Byte>(7, 7), repository.savedRenditions.single().second.toList(), "the rendition is the render")
    }

    /** Re-opening an edit is not a change: ✕ closes at once, and *Save* waits for a real one. */
    @Test
    fun anEditedPhoto_opensOnItsRecipe_andIsNotDirty() = vmTest(dispatcher) { vms ->
        val stored = EditRecipe(transform = TransformEdit(quarterTurns = 2), filter = FilterEdit(FilterLook.SLATE, 60))
        val repository = repo().apply { edits[photo.contentId] = StoredEdit(stored, "/Trash/.edits/c1-1.jpg", redacted = false) }
        val vm = vms.own(PhotoEditorViewModel(repository, photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        assertEquals(stored, vm.state.value.recipe, "the original plus the stored recipe")
        assertTrue(vm.state.value.hasStoredEdit)
        assertFalse(vm.state.value.dirty)
        assertFalse(vm.state.value.canSave)
        assertTrue(vm.state.value.canSaveAsCopy, "an edit can be copied as it stands")
        assertTrue(vm.requestClose())
        vm.rotate()
        assertTrue(vm.state.value.canSave)
    }

    /** Every tool back to nothing over a stored edit, saved, is a revert — and renders nothing. */
    @Test
    fun resettingAnEditAndSaving_revertsIt() = vmTest(dispatcher) { vms ->
        val repository = repo().apply {
            edits[photo.contentId] = StoredEdit(EditRecipe(transform = TransformEdit(quarterTurns = 1)), "/Trash/.edits/c1-1.jpg", false)
        }
        val renderer = Recording()
        val vm = vms.own(PhotoEditorViewModel(repository, photo, renderer, dispatcher))
        testScheduler.advanceUntilIdle()
        vm.reset()
        assertTrue(vm.state.value.canSave)
        vm.save()
        testScheduler.advanceUntilIdle()
        assertEquals(SavedAs.EDIT, vm.state.value.savedAs)
        assertNull(vm.state.value.saved?.renditionPath)
        assertNull(repository.edits[photo.contentId], "the edit is gone")
        assertTrue(renderer.drawn.isEmpty(), "nothing rendered for the original")
    }

    /** ED-D9: a newer editor's recipe is said, and the editor starts from the original. */
    @Test
    fun aRecipeFromANewerEditor_isSaid_andTheOriginalIsWhatOpens() = vmTest(dispatcher) { vms ->
        val repository = repo().apply { edits[photo.contentId] = StoredEdit(null, "/Trash/.edits/c1-1.jpg", redacted = true) }
        val vm = vms.own(PhotoEditorViewModel(repository, photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.storedEditUnreadable)
        assertEquals(EditRecipe(), vm.state.value.recipe)
        assertEquals(EditorSource.READY, vm.state.value.source)
    }

    /** ⚠⚠ A stored edit that could not be READ is never opened as "no edit" (a save would replace it). */
    @Test
    fun anEditThatCannotBeRead_blocksTheEditor() = vmTest(dispatcher) { vms ->
        val repository = repo().apply { editReadFails = true }
        val vm = vms.own(PhotoEditorViewModel(repository, photo, Recording(), dispatcher))
        testScheduler.advanceUntilIdle()
        assertEquals(EditorSource.EDIT_UNKNOWN, vm.state.value.source)
        vm.rotate()
        assertFalse(vm.state.value.canSave, "nothing can be edited or saved")
        assertFalse(vm.state.value.canSaveAsCopy)
    }
}
