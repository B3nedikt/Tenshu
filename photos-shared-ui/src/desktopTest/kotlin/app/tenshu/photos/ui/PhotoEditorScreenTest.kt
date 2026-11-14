package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.MockPhotosRepository
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import app.tenshu.photos.edit.AdjustTool
import app.tenshu.photos.edit.EditorTab
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.PhotoEditorViewModel
import app.tenshu.photos.ui.screens.editor.ADJUST_DONE_TAG
import app.tenshu.photos.ui.screens.editor.adjustToolTag
import app.tenshu.photos.ui.screens.editor.editorTabTag
import app.tenshu.photos.ui.screens.editor.filterLookTag
import app.tenshu.photos.ui.screens.editor.EDITOR_CANVAS_TAG
import app.tenshu.photos.ui.screens.editor.LABEL_DELETE_TAG
import app.tenshu.photos.ui.screens.editor.LABEL_DONE_TAG
import app.tenshu.photos.ui.screens.editor.LABEL_FIELD_TAG
import app.tenshu.photos.ui.screens.editor.MARKUP_REDO_TAG
import app.tenshu.photos.ui.screens.editor.MARKUP_UNDO_TAG
import app.tenshu.photos.ui.screens.editor.inkColorTag
import app.tenshu.photos.ui.screens.editor.inkWidthTag
import app.tenshu.photos.ui.screens.editor.markupToolTag
import app.tenshu.photos.ui.screens.editor.writeArgbRows
import app.tenshu.photos.edit.InkColor
import app.tenshu.photos.edit.InkWidth
import app.tenshu.photos.edit.MarkupTool
import app.tenshu.photos.edit.TextLabel
import app.tenshu.ui.image.ImageEncodeFormat
import app.tenshu.ui.image.encodeToBytes
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import kotlin.math.abs
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.editor.ComposePhotoEditRenderer
import app.tenshu.photos.ui.screens.editor.EDITOR_CLOSE_TAG
import app.tenshu.photos.ui.screens.editor.EDITOR_SAVE_TAG
import app.tenshu.photos.ui.screens.viewer.VIEWER_REVERT_TAG
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithTag
import app.tenshu.photos.ui.screens.editor.EDITOR_MORE_TAG
import app.tenshu.photos.ui.screens.editor.EDITOR_SAVE_COPY_TAG
import app.tenshu.photos.ui.screens.editor.PhotoEditorRoute
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.photos.ui.screens.viewer.VIEWER_EDIT_TAG
import app.tenshu.ui.image.decodeToImageBitmap
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor as a user drives it (`photos-editing.md` ED-1), and the viewer bar that opens it
 * (ED-D7).
 */
@OptIn(ExperimentalTestApi::class)
class PhotoEditorScreenTest {

    // ⚠ The view model's scope is `Dispatchers.Main`, which a desktop test has none of.
    private val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @kotlin.test.BeforeTest fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @kotlin.test.AfterTest fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private val photo = Photo("/Camera/IMG_1.jpg", capturedAtMs = 42_000L, isVideo = false, contentId = "c1", latE7 = 5L, lngE7 = 6L)

    @Test
    fun theViewerBarOffersEditAddToAndBin_andNoShare() = runComposeUiTest {
        val edited = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = listOf(photo),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photo, canFavourite = true),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onEdit = { edited += it.path },
                    onAddToAlbum = {},
                    onTrash = {},
                )
            }
        }
        onNodeWithText("Add to").assertIsEnabled()
        onNodeWithText("Bin").assertIsEnabled()
        // ⚠⚠ ED-D7: no Share slot until Share exists.
        assertEquals(0, onAllNodesWithTextCount("Share"))
        onNodeWithTag(VIEWER_EDIT_TAG).performClick()
        assertEquals(listOf(photo.path), edited)
    }

    /**
     * ED-3: an edited photo is drawn by its rendition — the hero is asked for the rendition's path,
     * never the original's — and ⋯ offers *Revert to original* on it, and only on it.
     */
    @Test
    fun anEditedPhoto_isDrawnByItsRendition_andOffersRevert() = runComposeUiTest {
        val edited = photo.copy(renditionPath = "/Trash/.edits/c1-1.jpg")
        val asked = mutableListOf<String>()
        val reverted = mutableListOf<String>()
        var shown by mutableStateOf(edited)
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = listOf(shown),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = shown, canFavourite = true),
                    loadHero = { path, _, _ -> asked += path; emptyFlow() },
                    onBack = {},
                    onRevertEdit = { reverted += it.path },
                )
            }
        }
        waitForIdle()
        assertTrue(asked.isNotEmpty() && asked.all { it == "/Trash/.edits/c1-1.jpg" }, "asked: $asked")
        onNodeWithContentDescription("More").performClick()
        onNodeWithTag(VIEWER_REVERT_TAG).performClick()
        assertEquals(listOf(photo.path), reverted)

        shown = photo
        waitForIdle()
        onNodeWithContentDescription("More").performClick()
        assertEquals(0, onAllNodesWithTag(VIEWER_REVERT_TAG).fetchSemanticsNodes().size, "no revert on an unedited photo")
    }

    @Test
    fun aVideoCannotBeEdited_andAPhotoWithNoContentIdCannotBeFiled() = runComposeUiTest {
        val video = Photo("/v.mp4", 1L, isVideo = true)
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = listOf(video),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = video, canFavourite = false),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onEdit = {},
                    onAddToAlbum = {},
                    onTrash = {},
                )
            }
        }
        onNodeWithTag(VIEWER_EDIT_TAG).assertIsNotEnabled()
        onNodeWithText("Add to").assertIsNotEnabled()
        onNodeWithText("Bin").assertIsNotEnabled()
    }

    @Test
    fun closingAnEditAsksFirst_andSaveWritesAnUprightCopy() = runComposeUiTest {
        val repo = MockPhotosRepository(photos = listOf(photo)).apply { originals[photo.path] = ROTATED_EXIF_6_JPEG }
        val vm = PhotoEditorViewModel(repo, photo, ComposePhotoEditRenderer())
        var closed = 0
        var saved: Photo? = null
        setContent {
            TenshuTheme { PhotoEditorRoute(vm, onClose = { closed++ }, onSaved = { photo, _ -> saved = photo }) }
        }
        waitUntil(timeoutMillis = 5_000) { vm.state.value.sourceKnown }
        onNodeWithTag(EDITOR_SAVE_TAG).assertIsNotEnabled()

        onNodeWithContentDescription("Rotate").performClick()
        onNodeWithTag(EDITOR_SAVE_TAG).assertIsEnabled()

        // ✕ over an edit asks (ED-D6); keeping it keeps the edit.
        onNodeWithTag(EDITOR_CLOSE_TAG).performClick()
        onNodeWithText("Discard your edits?").assertExists()
        onNodeWithText("Keep editing").performClick()
        assertEquals(0, closed)
        assertEquals(1, vm.state.value.edit.quarterTurns)

        // *Save as copy* is in ⋯ now (ED-D11); *Save* is the non-destructive edit (below).
        onNodeWithTag(EDITOR_MORE_TAG).performClick()
        onNodeWithTag(EDITOR_SAVE_COPY_TAG).performClick()
        waitUntil(timeoutMillis = 10_000) { saved != null }
        val copy = assertNotNull(saved)
        assertEquals("/Camera/IMG_1-edited.jpg", copy.path)
        assertEquals(42_000L, copy.capturedAtMs)
        assertEquals(5L, copy.latE7)
        // The stored bytes: the upright 16×32 picture turned a quarter = 32×16.
        val stored = assertNotNull(decodeToImageBitmap(repo.savedCopies.single().second))
        assertEquals(32, stored.width)
        assertEquals(16, stored.height)
        assertTrue(repo.originals[photo.path].contentEquals(ROTATED_EXIF_6_JPEG), "the original is untouched")
        assertEquals(0, closed, "saving opens the copy; it is not a discard")
    }

    @Test
    fun closingAnUntouchedEditClosesWithoutAsking() = runComposeUiTest {
        val repo = MockPhotosRepository(photos = listOf(photo)).apply { originals[photo.path] = ROTATED_EXIF_6_JPEG }
        val vm = PhotoEditorViewModel(repo, photo, ComposePhotoEditRenderer())
        var closed = 0
        setContent { TenshuTheme { PhotoEditorRoute(vm, onClose = { closed++ }, onSaved = { _, _ -> }) } }
        waitUntil(timeoutMillis = 5_000) { vm.state.value.sourceKnown }
        onNodeWithTag(EDITOR_CLOSE_TAG).performClick()
        assertEquals(1, closed)
        assertNull(vm.state.value.saved)
    }

    /**
     * ED-2: the Adjust and Filter tabs reach the saved copy. The fixture is pure red over pure blue;
     * a black-and-white look over a desaturated picture can only come back grey.
     */
    @Test
    fun adjustAndFilter_reachTheSavedCopy() = runComposeUiTest {
        val repo = MockPhotosRepository(photos = listOf(photo)).apply { originals[photo.path] = ROTATED_EXIF_6_JPEG }
        val vm = PhotoEditorViewModel(repo, photo, ComposePhotoEditRenderer())
        var saved: Photo? = null
        setContent { TenshuTheme { PhotoEditorRoute(vm, onClose = {}, onSaved = { photo, _ -> saved = photo }) } }
        waitUntil(timeoutMillis = 5_000) { vm.state.value.sourceKnown }

        onNodeWithTag(editorTabTag(EditorTab.ADJUST)).performClick()
        onNodeWithTag(adjustToolTag(AdjustTool.SATURATION)).performClick()
        // The dial: dragging right turns it down.
        onNodeWithContentDescription("Saturation").performTouchInput { swipeRight() }
        assertTrue(vm.state.value.recipe.adjust.saturation < 0, "the dial drives the slider: ${vm.state.value.recipe.adjust}")
        onNodeWithTag(EDITOR_SAVE_TAG).assertIsEnabled()
        onNodeWithTag(ADJUST_DONE_TAG).performClick()
        assertNull(vm.state.value.adjustTool, "Done goes back to the list")

        onNodeWithTag(editorTabTag(EditorTab.FILTER)).performClick()
        onNodeWithTag(filterLookTag(FilterLook.SLATE)).performClick()
        assertEquals(FilterLook.SLATE, vm.state.value.recipe.filter?.look)

        onNodeWithTag(EDITOR_SAVE_TAG).performClick()
        waitUntil(timeoutMillis = 10_000) { saved != null }
        val stored = assertNotNull(decodeToImageBitmap(repo.savedRenditions.single().second)).toPixelMap()
        assertEquals(16, stored.width, "no transform: the upright 16×32 picture")
        for ((x, y) in listOf(8 to 4, 8 to 27)) {
            val c = stored[x, y]
            assertTrue(abs(c.red - c.green) < 0.06f && abs(c.green - c.blue) < 0.08f, "($x,$y) is grey: $c")
        }
    }

    /** A plain mid-grey photograph, large enough for ink to survive JPEG. */
    private fun grey(): ByteArray = androidx.compose.ui.graphics.ImageBitmap(320, 240)
        .apply { writeArgbRows(IntArray(320 * 240) { 0xFF808080.toInt() }, 0, 240) }
        .encodeToBytes(ImageEncodeFormat.PNG)

    /**
     * ED-4: the Markup tab draws through the UI and the saved copy carries the ink. A green stroke
     * across a grey photograph can only come back as green pixels.
     */
    @Test
    fun markup_aPenStrokeReachesTheSavedCopy_andUndoIsOneTap() = runComposeUiTest {
        val repo = MockPhotosRepository(photos = listOf(photo)).apply { originals[photo.path] = grey() }
        val vm = PhotoEditorViewModel(repo, photo, ComposePhotoEditRenderer())
        var saved: Photo? = null
        setContent { TenshuTheme { PhotoEditorRoute(vm, onClose = {}, onSaved = { photo, _ -> saved = photo }) } }
        waitUntil(timeoutMillis = 5_000) { vm.state.value.sourceKnown }

        onNodeWithTag(editorTabTag(EditorTab.MARKUP)).performClick()
        onNodeWithTag(markupToolTag(MarkupTool.PEN)).performClick()
        onNodeWithTag(inkColorTag(InkColor.GREEN)).performClick()
        onNodeWithTag(inkWidthTag(InkWidth.THICK)).performClick()
        onNodeWithTag(MARKUP_UNDO_TAG).assertIsNotEnabled()
        onNodeWithTag(EDITOR_CANVAS_TAG).performTouchInput { swipe(center.copy(x = width * 0.25f), center.copy(x = width * 0.75f), 300) }
        assertEquals(1, vm.state.value.recipe.markup.items.size, "one stroke")
        onNodeWithTag(EDITOR_SAVE_TAG).assertIsEnabled()

        // Undo takes it away; redo brings it back.
        onNodeWithTag(MARKUP_UNDO_TAG).performClick()
        assertTrue(vm.state.value.recipe.markup.isEmpty)
        onNodeWithTag(EDITOR_SAVE_TAG).assertIsNotEnabled()
        onNodeWithTag(MARKUP_REDO_TAG).performClick()
        assertEquals(1, vm.state.value.recipe.markup.items.size)

        onNodeWithTag(EDITOR_SAVE_TAG).performClick()
        waitUntil(timeoutMillis = 10_000) { saved != null }
        val stored = assertNotNull(decodeToImageBitmap(repo.savedRenditions.single().second)).toPixelMap()
        var green = 0
        for (y in 0 until stored.height) for (x in 0 until stored.width) {
            val c = stored[x, y]
            if (c.green > 0.5f && c.green - c.red > 0.25f && c.green - c.blue > 0.2f) green++
        }
        assertTrue(green > 400, "the copy carries the stroke: $green green pixels")
        // The middle row of the picture, where the swipe ran, holds it.
        val mid = stored[stored.width / 2, stored.height / 2]
        assertTrue(mid.green - mid.red > 0.25f, "the stroke is across the middle: $mid")
    }

    @Test
    fun markup_textIsPlacedEditedAndDeleted_throughItsField() = runComposeUiTest {
        val repo = MockPhotosRepository(photos = listOf(photo)).apply { originals[photo.path] = grey() }
        val vm = PhotoEditorViewModel(repo, photo, ComposePhotoEditRenderer())
        setContent { TenshuTheme { PhotoEditorRoute(vm, onClose = {}, onSaved = { _, _ -> }) } }
        waitUntil(timeoutMillis = 5_000) { vm.state.value.sourceKnown }

        onNodeWithTag(editorTabTag(EditorTab.MARKUP)).performClick()
        onNodeWithTag(markupToolTag(MarkupTool.TEXT)).performClick()
        onNodeWithTag(EDITOR_CANVAS_TAG).performTouchInput { click(center) }
        onNodeWithText("Add text").assertExists()
        onNodeWithTag(LABEL_FIELD_TAG).performTextInput("Hello")
        onNodeWithTag(LABEL_DONE_TAG).performClick()
        val label = vm.state.value.recipe.markup.items.single() as TextLabel
        assertEquals("Hello", label.text)

        onNodeWithTag(EDITOR_CANVAS_TAG).performTouchInput { click(center) }
        onNodeWithText("Edit text").assertExists()
        onNodeWithTag(LABEL_DELETE_TAG).performClick()
        assertTrue(vm.state.value.recipe.markup.isEmpty)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
