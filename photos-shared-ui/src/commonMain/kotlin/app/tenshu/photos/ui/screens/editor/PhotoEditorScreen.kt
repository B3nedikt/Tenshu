package app.tenshu.photos.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.photos.edit.AdjustEdit
import app.tenshu.photos.edit.AdjustTool
import app.tenshu.photos.edit.AspectPreset
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.EditorTab
import app.tenshu.photos.edit.FilterEdit
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.InkColor
import app.tenshu.photos.edit.InkWidth
import app.tenshu.photos.edit.MarkupRender
import app.tenshu.photos.edit.MarkupTool
import app.tenshu.photos.edit.CropHandle
import app.tenshu.photos.edit.EditorSource
import app.tenshu.photos.edit.NormRect
import app.tenshu.photos.edit.PhotoEditorUiState
import app.tenshu.photos.edit.RenderPlan
import app.tenshu.photos.edit.TransformEdit
import app.tenshu.photos.edit.TransformTool
import app.tenshu.photos.edit.cropHandleAt
import app.tenshu.photos.model.Photo
import app.tenshu.ui.image.decodeToUprightImageBitmap
import app.tenshu.ui.input.AppKeyboardShortcutHost
import app.tenshu.ui.input.AppShortcuts
import app.tenshu.ui.input.KeyboardShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

const val EDITOR_CANVAS_TAG = "photo-editor-canvas"
const val EDITOR_SAVE_TAG = "photo-editor-save"
const val EDITOR_CLOSE_TAG = "photo-editor-close"
const val EDITOR_MORE_TAG = "photo-editor-more"
const val EDITOR_SAVE_COPY_TAG = "photo-editor-save-copy"
const val EDITOR_NEWER_EDIT_TAG = "photo-editor-newer-edit"
const val ADJUST_DONE_TAG = "photo-editor-adjust-done"

fun editorTabTag(tab: EditorTab) = "photo-editor-tab-${tab.name}"

fun adjustToolTag(tool: AdjustTool) = "photo-editor-adjust-${tool.name}"

fun filterLookTag(look: FilterLook?) = "photo-editor-look-${look?.id ?: "original"}"

fun markupToolTag(tool: MarkupTool) = "photo-editor-markup-${tool.name}"

fun inkColorTag(color: InkColor) = "photo-editor-ink-${color.id}"

fun inkWidthTag(width: InkWidth) = "photo-editor-width-${width.name}"

const val MARKUP_UNDO_TAG = "photo-editor-markup-undo"
const val MARKUP_REDO_TAG = "photo-editor-markup-redo"
const val LABEL_FIELD_TAG = "photo-editor-label-field"
const val LABEL_DONE_TAG = "photo-editor-label-done"
const val LABEL_DELETE_TAG = "photo-editor-label-delete"

/**
 * The Markup tab's callbacks (ED-4), as one value so the screen's signature stays readable. Each is
 * a [app.tenshu.photos.edit.PhotoEditorViewModel] method of the same name; the picture's points
 * are normalised to the picture as shown.
 */
class MarkupActions(
    val onSelectTool: (MarkupTool) -> Unit = {},
    val onColor: (InkColor) -> Unit = {},
    val onWidth: (InkWidth) -> Unit = {},
    val onDown: (Float, Float) -> Unit = { _, _ -> },
    val onMove: (Float, Float) -> Unit = { _, _ -> },
    val onUp: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onCommitLabel: (String) -> Unit = {},
    val onDeleteLabel: () -> Unit = {},
    val onCancelLabel: () -> Unit = {},
)

/** The tool panel's height, the same on every tab so the picture does not jump when switching. */
private val PANEL_HEIGHT = 96.dp

/** The preview's decode edge. ⚠ The screen, not the save: the save decodes the original again. */
private const val PREVIEW_EDGE = 2048

/** How long a still finger must rest before the picture shows the original. */
private const val COMPARE_HOLD_MS = 300L

/**
 * The photo editor (`photos-editing.md` ED-1…ED-4): ✕, *Save* and ⋯ (*Save as copy*) above the picture,
 * the Transform strip, the tab's tools and the tab row below (§1.1's layout).
 *
 * ⚠ Stateless: every decision is [PhotoEditorUiState]'s, so the SwiftUI shell draws the same
 * editor from the same model. This draws, and reports gestures.
 */
@Composable
fun PhotoEditorScreen(
    state: PhotoEditorUiState,
    original: ByteArray?,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onSourceDecoded: (Int, Int) -> Unit,
    onSourceUndecodable: () -> Unit,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onReset: () -> Unit,
    onSelectTool: (TransformTool) -> Unit,
    onAspect: (AspectPreset) -> Unit,
    onStraighten: (Float) -> Unit,
    onPerspective: (Float, Float) -> Unit,
    onDragCrop: (NormRect, CropHandle, Float, Float) -> Unit,
    onCompare: (Boolean) -> Unit,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    onDismissSaveFailure: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectTab: (EditorTab) -> Unit = {},
    onSelectAdjustTool: (AdjustTool?) -> Unit = {},
    onAdjust: (AdjustTool, Float) -> Unit = { _, _ -> },
    onResetAdjust: (AdjustTool) -> Unit = {},
    onSelectLook: (FilterLook?) -> Unit = {},
    onFilterIntensity: (Float) -> Unit = {},
    markup: MarkupActions = MarkupActions(),
    onSaveAsCopy: () -> Unit = {},
) {
    // ⚠ Decoded upright off the main thread, once per original — the preview only; the save
    // decodes at full size in the renderer. ⚠ A decode that fails is REPORTED, or the spinner below
    // would spin for ever over bytes that are here and cannot be drawn.
    var preview by remember(original) { mutableStateOf<ImageBitmap?>(null) }
    val decoded by rememberUpdatedState(onSourceDecoded)
    val undecodable by rememberUpdatedState(onSourceUndecodable)
    LaunchedEffect(original) {
        val bytes = original ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { decodeToUprightImageBitmap(bytes, PREVIEW_EDGE) }.getOrNull()
        }
        if (bitmap == null) undecodable() else {
            preview = bitmap
            decoded(bitmap.width, bitmap.height)
        }
    }

    AppKeyboardShortcutHost(
        AppShortcuts.dismiss(onClose),
        KeyboardShortcut(Key.R, onTriggered = onRotate),
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            EditorTopBar(state, onClose, onSave, onSaveAsCopy)
            if (state.storedEditUnreadable) {
                // ED-D9: the edit came from a newer version — said, and the original is what is open.
                Text(
                    "This photo was edited in a newer version of Photos. You're editing the original; saving replaces that edit.",
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(EDITOR_NEWER_EDIT_TAG),
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bitmap = preview
                when {
                    state.source == EditorSource.UNREADABLE -> Text(
                        "This photo can't be edited on this device.",
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(32.dp),
                    )
                    state.source == EditorSource.EDIT_UNKNOWN -> Text(
                        "Your edits to this photo couldn't be loaded, so it can't be edited right now. Try again in a moment.",
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(32.dp),
                    )
                    state.source == EditorSource.UNAVAILABLE -> Text(
                        "This photo hasn't been downloaded to this device, so it can't be edited here yet.",
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(32.dp),
                    )
                    bitmap == null -> CircularProgressIndicator(color = Color.White)
                    else -> EditorCanvas(
                        bitmap = bitmap,
                        recipe = state.recipe,
                        cropping = state.tab == EditorTab.TRANSFORM,
                        marking = state.tab == EditorTab.MARKUP && state.labelDraft == null,
                        markup = markup,
                        comparing = state.comparing,
                        aspectRatio = state.edit.aspectRatioOf(state.edit.aspect, bitmap.width, bitmap.height),
                        onDragCrop = onDragCrop,
                        onCompare = onCompare,
                    )
                }
            }

            val ready = state.source == EditorSource.READY && preview != null && !state.saving
            TransformStrip(state, ready, onRotate, onMirror, onReset)
            when (state.tab) {
                EditorTab.TRANSFORM -> {
                    ToolPanel(state, ready, onAspect, onStraighten, onPerspective)
                    ToolRow(state.tool, ready, onSelectTool)
                }
                EditorTab.ADJUST -> AdjustPanel(state, ready, onSelectAdjustTool, onAdjust, onResetAdjust)
                EditorTab.FILTER -> FilterPanel(state, ready, onSelectLook, onFilterIntensity)
                EditorTab.MARKUP -> {
                    MarkupPanel(state, ready, markup)
                    MarkupToolRow(state.markupTool, ready, markup.onSelectTool)
                }
            }
            // ⚠ Drawn only for more than one tab — never a lone tab with nothing to switch to.
            if (state.tabs.size > 1) TabRow(state.tabs, state.tab, ready, onSelectTab)
        }
    }

    if (state.confirmingDiscard) {
        AlertDialog(
            onDismissRequest = onKeepEditing,
            title = { Text("Discard your edits?") },
            text = { Text("Your changes to this photo haven’t been saved.") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } },
        )
    }
    state.labelDraft?.let { draft ->
        // ⚠ Keyed on the draft, so opening another label starts from ITS text, not the last one typed.
        var text by remember(draft) { mutableStateOf(draft.text) }
        AlertDialog(
            onDismissRequest = markup.onCancelLabel,
            title = { Text(if (draft.id == null) "Add text" else "Edit text") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace('\n', ' ') },
                    singleLine = true,
                    modifier = Modifier.testTag(LABEL_FIELD_TAG),
                )
            },
            confirmButton = {
                TextButton(onClick = { markup.onCommitLabel(text) }, modifier = Modifier.testTag(LABEL_DONE_TAG)) { Text("Done") }
            },
            dismissButton = {
                Row {
                    if (draft.id != null) {
                        TextButton(onClick = markup.onDeleteLabel, modifier = Modifier.testTag(LABEL_DELETE_TAG)) { Text("Delete") }
                    }
                    TextButton(onClick = markup.onCancelLabel) { Text("Cancel") }
                }
            },
        )
    }
    if (state.saveFailed) {
        AlertDialog(
            onDismissRequest = onDismissSaveFailure,
            title = { Text("Couldn’t save") },
            text = { Text("Tenshu couldn’t save your edit. Your original is unchanged. Try again in a moment.") },
            confirmButton = { TextButton(onClick = onDismissSaveFailure) { Text("OK") } },
        )
    }
}

@Composable
private fun EditorTopBar(state: PhotoEditorUiState, onClose: () -> Unit, onSave: () -> Unit, onSaveAsCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = !state.saving, modifier = Modifier.testTag(EDITOR_CLOSE_TAG)) {
            Icon(Icons.Filled.Close, contentDescription = "Close editor", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        if (state.saving) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        } else {
            // ⚠⚠ *Save* is the non-destructive edit (ED-3, ED-D11): the original's bytes and id never
            // move. Offered once something has changed, as Google's is (§1.1). *Save as copy* — a new
            // file — is in ⋯.
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.testTag(EDITOR_SAVE_TAG),
            ) { Text("Save") }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(EDITOR_MORE_TAG)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Save as copy") },
                        enabled = state.canSaveAsCopy,
                        onClick = {
                            menuOpen = false
                            onSaveAsCopy()
                        },
                        modifier = Modifier.testTag(EDITOR_SAVE_COPY_TAG),
                    )
                }
            }
        }
    }
}

/** Rotate · Mirror — Google's strip, visible on every tab (§1.1). */
@Composable
private fun TransformStrip(
    state: PhotoEditorUiState,
    enabled: Boolean,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRotate, enabled = enabled) {
            Icon(Icons.Outlined.Rotate90DegreesCcw, contentDescription = "Rotate", tint = Color.White)
        }
        IconButton(onClick = onMirror, enabled = enabled) {
            Icon(Icons.Outlined.Flip, contentDescription = "Mirror", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        if (state.dirty) {
            TextButton(onClick = onReset, enabled = enabled) { Text("Reset", color = Color.White) }
        }
    }
}

@Composable
private fun ToolPanel(
    state: PhotoEditorUiState,
    enabled: Boolean,
    onAspect: (AspectPreset) -> Unit,
    onStraighten: (Float) -> Unit,
    onPerspective: (Float, Float) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT), contentAlignment = Alignment.Center) {
        when (state.tool) {
            TransformTool.CROP -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                items(AspectPreset.entries) { preset ->
                    FilterChip(
                        selected = state.edit.aspect == preset,
                        onClick = { onAspect(preset) },
                        // ⚠ A ratio needs the frame's shape; until the preview is decoded only
                        // Freeform can be honoured.
                        enabled = enabled && (state.sourceKnown || preset == AspectPreset.FREEFORM),
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
            TransformTool.STRAIGHTEN -> AngleDial(
                label = "Straighten",
                value = state.edit.viewStraightenDegrees,
                range = TransformEdit.MAX_STRAIGHTEN_DEGREES,
                format = { "${(it * 10).roundToInt() / 10f}°" },
                enabled = enabled,
                onChange = onStraighten,
            )
            TransformTool.PERSPECTIVE -> {
                val (x, y) = state.edit.viewPerspective
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AngleDial(
                        label = "Vertical",
                        value = y * 100f,
                        range = 100f,
                        format = { "${it.roundToInt()}" },
                        enabled = enabled,
                        compact = true,
                        onChange = { onPerspective(x, it / 100f) },
                    )
                    AngleDial(
                        label = "Horizontal",
                        value = x * 100f,
                        range = 100f,
                        format = { "${it.roundToInt()}" },
                        enabled = enabled,
                        compact = true,
                        onChange = { onPerspective(it / 100f, y) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolRow(selected: TransformTool, enabled: Boolean, onSelect: (TransformTool) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolButton("Crop", Icons.Outlined.Crop, selected == TransformTool.CROP, enabled) { onSelect(TransformTool.CROP) }
        ToolButton("Straighten", Icons.Outlined.Straighten, selected == TransformTool.STRAIGHTEN, enabled) {
            onSelect(TransformTool.STRAIGHTEN)
        }
        ToolButton("Perspective", Icons.Outlined.Transform, selected == TransformTool.PERSPECTIVE, enabled) {
            onSelect(TransformTool.PERSPECTIVE)
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else Color.White
    TextButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = tint)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

/** Crop · Adjust · Filters — Google's tab row, at the bottom (§1.1). */
@Composable
private fun TabRow(tabs: List<EditorTab>, current: EditorTab, enabled: Boolean, onSelect: (EditorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (tab in tabs) {
            val on = tab == current
            val tint = if (on) MaterialTheme.colorScheme.primary else Color.White
            TextButton(
                onClick = { onSelect(tab) },
                enabled = enabled,
                modifier = Modifier.testTag(editorTabTag(tab)).semantics { selected = on },
                colors = ButtonDefaults.textButtonColors(contentColor = tint),
            ) { Text(tab.label, color = tint, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/**
 * The Adjust tab (§1.2): the eleven sliders as a row; one picked opens Google's dial with its own
 * *Reset* and *Done* (back to the row).
 */
@Composable
private fun AdjustPanel(
    state: PhotoEditorUiState,
    enabled: Boolean,
    onSelectTool: (AdjustTool?) -> Unit,
    onAdjust: (AdjustTool, Float) -> Unit,
    onReset: (AdjustTool) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT), contentAlignment = Alignment.Center) {
        val open = state.adjustTool
        if (open == null) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                items(AdjustTool.entries) { tool ->
                    val value = state.recipe.adjust[tool]
                    FilterChip(
                        selected = value != 0,
                        onClick = { onSelectTool(tool) },
                        enabled = enabled,
                        label = { Text(if (value == 0) tool.label else "${tool.label}  ${signed(value)}") },
                        modifier = Modifier.testTag(adjustToolTag(tool)),
                        colors = editorChipColors(),
                    )
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AngleDial(
                    label = open.label,
                    value = state.recipe.adjust[open].toFloat(),
                    range = AdjustEdit.MAX.toFloat(),
                    format = { signed(it.roundToInt()) },
                    enabled = enabled,
                    compact = true,
                    onChange = { onAdjust(open, it) },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onReset(open) }, enabled = enabled && state.recipe.adjust[open] != 0) {
                        Text("Reset", color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSelectTool(null) }, modifier = Modifier.testTag(ADJUST_DONE_TAG)) {
                        Text("Done", color = Color.White)
                    }
                }
            }
        }
    }
}

/** The Filter tab: *Original* and our looks (ED-D5), and the chosen look's intensity. */
@Composable
private fun FilterPanel(
    state: PhotoEditorUiState,
    enabled: Boolean,
    onSelectLook: (FilterLook?) -> Unit,
    onIntensity: (Float) -> Unit,
) {
    val filter = state.recipe.filter
    Column(
        Modifier.fillMaxWidth().height(PANEL_HEIGHT),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (filter != null) {
            AngleDial(
                label = "Intensity",
                value = filter.intensity.toFloat(),
                range = FilterEdit.FULL.toFloat(),
                floor = 0f,
                format = { "${it.roundToInt()}" },
                enabled = enabled,
                compact = true,
                onChange = onIntensity,
            )
            Spacer(Modifier.height(6.dp))
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            item {
                FilterChip(
                    selected = filter == null,
                    onClick = { onSelectLook(null) },
                    enabled = enabled,
                    label = { Text("Original") },
                    modifier = Modifier.testTag(filterLookTag(null)),
                    colors = editorChipColors(),
                )
            }
            items(FilterLook.entries) { look ->
                FilterChip(
                    selected = filter?.look == look,
                    onClick = { onSelectLook(look) },
                    enabled = enabled,
                    label = { Text(look.label) },
                    modifier = Modifier.testTag(filterLookTag(look)),
                    colors = editorChipColors(),
                )
            }
        }
    }
}

/**
 * The Markup tab (ED-4): the current tool's colours and widths (Redact has neither: it is always
 * black), its hint, and markup's undo/redo.
 */
@Composable
private fun MarkupPanel(state: PhotoEditorUiState, enabled: Boolean, actions: MarkupActions) {
    Column(
        Modifier.fillMaxWidth().height(PANEL_HEIGHT).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val brush = state.brush
        if (brush != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (color in InkColor.entries) {
                    val on = brush.color == color
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(if (on) Color.White else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            // ⚠ A faint ring, or the black swatch vanishes into the black panel.
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .background(Color(color.argb), CircleShape)
                            .clickable(enabled = enabled) { actions.onColor(color) }
                            .testTag(inkColorTag(color))
                            .semantics { contentDescription = color.label; selected = on },
                    )
                }
                Spacer(Modifier.weight(1f))
                for (width in InkWidth.entries) {
                    val on = brush.width == width
                    Box(
                        Modifier
                            .size(30.dp)
                            .clickable(enabled = enabled) { actions.onWidth(width) }
                            .testTag(inkWidthTag(width))
                            .semantics { contentDescription = width.label; selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        val dot = when (width) { InkWidth.THIN -> 6.dp; InkWidth.MEDIUM -> 11.dp; InkWidth.THICK -> 17.dp }
                        Box(Modifier.size(dot).background(if (on) Color.White else Color.White.copy(alpha = 0.4f), CircleShape))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.markupTool) {
                    MarkupTool.PEN, MarkupTool.HIGHLIGHTER -> "Draw on the photo."
                    MarkupTool.REDACT -> "Drag a box over what to hide. It’s blacked out in the saved copy."
                    MarkupTool.TEXT -> "Tap to add text. Tap a label to edit it, drag to move it."
                },
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.onUndo, enabled = enabled && state.canUndo, modifier = Modifier.testTag(MARKUP_UNDO_TAG)) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo", tint = if (state.canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = actions.onRedo, enabled = enabled && state.canRedo, modifier = Modifier.testTag(MARKUP_REDO_TAG)) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo", tint = if (state.canRedo) Color.White else Color.Gray)
            }
        }
    }
}

/** Pen · Highlighter · Redact · Text — §1.2's order. */
@Composable
private fun MarkupToolRow(selected: MarkupTool, enabled: Boolean, onSelect: (MarkupTool) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (tool in MarkupTool.entries) {
            val icon = when (tool) {
                MarkupTool.PEN -> Icons.Outlined.Draw
                MarkupTool.HIGHLIGHTER -> Icons.Outlined.BorderColor
                MarkupTool.REDACT -> Icons.Outlined.VisibilityOff
                MarkupTool.TEXT -> Icons.Outlined.TextFields
            }
            Box(Modifier.testTag(markupToolTag(tool)).semantics { this.selected = selected == tool }) {
                ToolButton(tool.label, icon, selected == tool, enabled) { onSelect(tool) }
            }
        }
    }
}

@Composable
private fun editorChipColors() = FilterChipDefaults.filterChipColors(
    labelColor = Color.White,
    selectedContainerColor = Color.White,
    selectedLabelColor = Color.Black,
)

private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"

/**
 * Google's fine-angle dial (§1.2): a ruler of ticks that slides under a fixed centre mark. Drag to
 * turn; double-tap to put it back at zero.
 */
@Composable
internal fun AngleDial(
    label: String,
    value: Float,
    range: Float,
    format: (Float) -> String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    compact: Boolean = false,
    /** The low end: `-range` for a centred dial, `0` for an intensity. */
    floor: Float = -range,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    // Pixels per unit: the whole range spans a little over two screen-widths of ruler.
    val pxPerUnit = if (compact) 4f else 8f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            stateDescription = format(value)
        },
    ) {
        Text(
            if (compact) "$label  ${format(value)}" else format(value),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 22.dp else 36.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    // ⚠ Accumulated per gesture from the value at touch-down: two drag events before
                    // a recomposition would otherwise both read the same stale value and the dial
                    // would lag the finger.
                    var base = 0f
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { base = current; travelled = 0f },
                    ) { pointer, dx ->
                        pointer.consume()
                        travelled += dx
                        change((base - travelled / pxPerUnit).coerceIn(floor, range))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { change(if (floor < 0f) 0f else range) })
                },
        ) {
            val mid = size.width / 2f
            val step = if (range > 50f) 5 else 1
            var unit = floor.toInt()
            while (unit <= range.toInt()) {
                val x = mid + (unit - value) * pxPerUnit
                if (x in 0f..size.width && unit % step == 0) {
                    val major = unit % (step * 5) == 0
                    val h = if (major) size.height * 0.7f else size.height * 0.4f
                    drawLine(
                        Color.White.copy(alpha = if (major) 0.9f else 0.5f),
                        Offset(x, (size.height - h) / 2f),
                        Offset(x, (size.height + h) / 2f),
                        strokeWidth = if (major) 2f else 1f,
                    )
                }
                unit++
            }
            drawLine(
                if (enabled) Color(0xFF8AB4F8) else Color.Gray,
                Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 3f,
            )
        }
    }
}

/**
 * The picture, drawn through the recipe: on the Crop tab the whole frame with the crop box over it,
 * on Adjust and Filters the cropped result (what a save writes).
 *
 * ⚠⚠ The **plan** draws the preview, through the same [drawEdited] the save uses — not a separate set
 * of Compose transforms or colour filters — so what the user lines up is what
 * [ComposePhotoEditRenderer] saves. Only the scale-to-fit is added on top. On the Crop tab the
 * vignette is centred on the crop box, where it will be once the crop is applied.
 */
@Composable
private fun EditorCanvas(
    bitmap: ImageBitmap,
    recipe: EditRecipe,
    cropping: Boolean,
    /** The Markup tab with no label open: a press draws (or picks a label) rather than compares. */
    marking: Boolean,
    markup: MarkupActions,
    comparing: Boolean,
    aspectRatio: Float?,
    onDragCrop: (NormRect, CropHandle, Float, Float) -> Unit,
    onCompare: (Boolean) -> Unit,
) {
    val edit = recipe.transform
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp).testTag(EDITOR_CANVAS_TAG)) {
        val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.toPx() }
        val handlePx = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
        val box = with(androidx.compose.ui.platform.LocalDensity.current) {
            IntSize(maxWidth.toPx().roundToInt(), maxHeight.toPx().roundToInt())
        }
        val plan = remember(edit, bitmap, cropping) { edit.plan(bitmap.width, bitmap.height, includeCrop = !cropping) }
        val stage = remember(recipe, cropping) { recipe.colorStage(if (cropping) edit.viewCrop else NormRect.FULL) }
        val frame = remember(plan, box) { fitRect(plan.outputWidth, plan.outputHeight, box, padPx) }
        val original = remember(bitmap, box) { fitRect(bitmap.width, bitmap.height, box, padPx) }
        val crop = edit.viewCrop
        val cropRect = Rect(
            frame.left + crop.left * frame.width, frame.top + crop.top * frame.height,
            frame.left + crop.right * frame.width, frame.top + crop.bottom * frame.height,
        )

        // ⚠ One shader per (picture, plan, stage), kept across redraws: building one compiles AGSL.
        val shader = remember(bitmap, plan, stage) { if (stage.isIdentity) null else editShader(bitmap, plan, stage) }

        // ⚠ Where there is no runtime shader (Android < 33) the colour runs on the CPU, off the main
        // thread, at the size it is shown — latest wins, a superseded pass stops at its next band.
        // While it computes, the last frame drawn for THIS plan stays up; with none, nothing is drawn
        // rather than the picture with the sliders silently missing — or a frame from another plan
        // (the Crop tab's uncropped one) stretched into this one.
        var cpuFrame by remember(bitmap) { mutableStateOf<Pair<RenderPlan, ImageBitmap>?>(null) }
        val needsCpu = !stage.isIdentity && shader == null
        if (needsCpu) {
            LaunchedEffect(plan, stage, frame.width) {
                val scale = (frame.width / plan.outputWidth).coerceAtMost(1f)
                val job = coroutineContext
                val drawn = withContext(Dispatchers.Default) { renderOnCpu(bitmap, plan, stage, scale) { job.isActive } }
                cpuFrame = plan to drawn
            }
        }

        val latestFrame by rememberUpdatedState(frame)
        val latestEdit by rememberUpdatedState(edit)
        val latestCropping by rememberUpdatedState(cropping)
        val latestMarking by rememberUpdatedState(marking)
        val marks by rememberUpdatedState(markup)
        // The markup as the shared scene for THIS plan and THIS decode — the save draws the same ops at full size.
        val ops = remember(recipe.markup, plan, bitmap) { MarkupRender.scene(recipe.markup, plan, bitmap.width, bitmap.height) }
        val drag by rememberUpdatedState(onDragCrop)
        val compare by rememberUpdatedState(onCompare)
        val paint = remember { Paint().apply { filterQuality = FilterQuality.Medium } }

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // ⚠ Keyed on Unit and reading the latest values through `rememberUpdatedState`: a key
                // that changed with every drag event would restart this block and drop the gesture.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val f = latestFrame
                    if (latestMarking) {
                        // ⚠ Markup: every move is ink (or a label drag), from the first one — no
                        // hold-to-compare here, a finger at rest is the start of a dot.
                        fun u(p: Offset) = ((p.x - f.left) / f.width).coerceIn(0f, 1f)
                        fun v(p: Offset) = ((p.y - f.top) / f.height).coerceIn(0f, 1f)
                        if (!f.contains(down.position)) return@awaitEachGesture
                        down.consume()
                        marks.onDown(u(down.position), v(down.position))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            marks.onMove(u(change.position), v(change.position))
                        }
                        marks.onUp()
                        return@awaitEachGesture
                    }
                    val start = latestEdit.viewCrop
                    // ⚠ The crop box is the Crop tab's alone; elsewhere a press can only compare.
                    val handle = if (!latestCropping) null else cropHandleAt(
                        start, f.width, f.height, down.position.x - f.left, down.position.y - f.top, handlePx,
                    )
                    // Phase 1: still, moving, or released? `null` = still held when the wait ran out.
                    val first: Boolean? = withTimeoutOrNull(COMPARE_HOLD_MS) {
                        var outcome: Boolean? = null
                        while (outcome == null) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            outcome = when {
                                change == null || !change.pressed -> false // released
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> true
                                else -> null
                            }
                        }
                        outcome
                    }
                    if (first == false) return@awaitEachGesture
                    // ⚠ Hold-to-compare only OFF the box's edges and corners: pressing a corner,
                    // pausing and then dragging is how a corner is grabbed. The inside (MOVE) still
                    // compares — on an uncropped photo it is the whole picture.
                    if (first == null && (handle == null || handle == CropHandle.MOVE)) {
                        // Held still: show the original until the finger lifts (§1.2).
                        compare(true)
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        } while (change != null && change.pressed)
                        compare(false)
                        return@awaitEachGesture
                    }
                    if (handle == null) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val d = change.position - down.position
                        drag(start, handle, d.x / f.width, d.y / f.height)
                    }
                }
            },
        ) {
            if (comparing) {
                drawImageFitted(bitmap, original, paint)
                return@Canvas
            }
            clipRect(frame.left, frame.top, frame.right, frame.bottom) {
                val cpu = cpuFrame
                if (needsCpu) {
                    if (cpu != null && cpu.first === plan) drawImageFitted(cpu.second, frame, paint)
                } else {
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.translate(frame.left, frame.top)
                        canvas.scale(frame.width / plan.outputWidth, frame.height / plan.outputHeight)
                        canvas.drawEdited(bitmap, plan, stage, FilterQuality.Medium, prepared = shader)
                        canvas.restore()
                    }
                }
                // Markup over the coloured picture, as the save draws it (EditRecipe's order).
                if (ops.isNotEmpty()) drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.translate(frame.left, frame.top)
                    canvas.scale(frame.width / plan.outputWidth, frame.height / plan.outputHeight)
                    canvas.drawMarkup(ops)
                    canvas.restore()
                }
            }
            if (cropping) drawCropOverlay(frame, cropRect, locked = aspectRatio != null)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageFitted(bitmap: ImageBitmap, at: Rect, paint: Paint) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(at.left, at.top)
        canvas.scale(at.width / bitmap.width, at.height / bitmap.height)
        canvas.drawImage(bitmap, Offset.Zero, paint)
        canvas.restore()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropOverlay(frame: Rect, crop: Rect, locked: Boolean) {
    val dim = Color.Black.copy(alpha = 0.55f)
    // The four bands outside the crop, inside the frame.
    drawRect(dim, Offset(frame.left, frame.top), Size(frame.width, crop.top - frame.top))
    drawRect(dim, Offset(frame.left, crop.bottom), Size(frame.width, frame.bottom - crop.bottom))
    drawRect(dim, Offset(frame.left, crop.top), Size(crop.left - frame.left, crop.height))
    drawRect(dim, Offset(crop.right, crop.top), Size(frame.right - crop.right, crop.height))
    // Thirds.
    val thirds = Color.White.copy(alpha = 0.35f)
    for (i in 1..2) {
        val x = crop.left + crop.width * i / 3f
        val y = crop.top + crop.height * i / 3f
        drawLine(thirds, Offset(x, crop.top), Offset(x, crop.bottom), strokeWidth = 1f)
        drawLine(thirds, Offset(crop.left, y), Offset(crop.right, y), strokeWidth = 1f)
    }
    drawRect(Color.White, crop.topLeft, crop.size, style = Stroke(width = 2f))
    // Corner handles; edge handles too when the box is free to change shape.
    val len = min(48f, min(crop.width, crop.height) / 3f)
    val w = 8f
    val corners = listOf(
        crop.topLeft to Offset(1f, 1f), Offset(crop.right, crop.top) to Offset(-1f, 1f),
        crop.bottomRight to Offset(-1f, -1f), Offset(crop.left, crop.bottom) to Offset(1f, -1f),
    )
    for ((p, dir) in corners) {
        drawLine(Color.White, p, Offset(p.x + dir.x * len, p.y), strokeWidth = w)
        drawLine(Color.White, p, Offset(p.x, p.y + dir.y * len), strokeWidth = w)
    }
    if (!locked) {
        val c = crop.center
        val half = len / 2f
        drawLine(Color.White, Offset(c.x - half, crop.top), Offset(c.x + half, crop.top), strokeWidth = w)
        drawLine(Color.White, Offset(c.x - half, crop.bottom), Offset(c.x + half, crop.bottom), strokeWidth = w)
        drawLine(Color.White, Offset(crop.left, c.y - half), Offset(crop.left, c.y + half), strokeWidth = w)
        drawLine(Color.White, Offset(crop.right, c.y - half), Offset(crop.right, c.y + half), strokeWidth = w)
    }
}

/** A [w]×[h] picture fitted inside [box], centred, inset by [pad]. */
internal fun fitRect(w: Int, h: Int, box: IntSize, pad: Float): Rect {
    val availW = (box.width - 2 * pad).coerceAtLeast(1f)
    val availH = (box.height - 2 * pad).coerceAtLeast(1f)
    val scale = min(availW / w, availH / h)
    val dw = w * scale
    val dh = h * scale
    val left = (box.width - dw) / 2f
    val top = (box.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

/** The route: one [app.tenshu.photos.edit.PhotoEditorViewModel] drawn by [PhotoEditorScreen]. */
@Composable
fun PhotoEditorRoute(
    viewModel: app.tenshu.photos.edit.PhotoEditorViewModel,
    onClose: () -> Unit,
    onSaved: (Photo, app.tenshu.photos.edit.SavedAs) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        val saved = state.saved ?: return@LaunchedEffect
        onSaved(saved, state.savedAs ?: app.tenshu.photos.edit.SavedAs.COPY)
    }
    val requestClose = { if (viewModel.requestClose()) onClose() }
    app.tenshu.photos.ui.BackHandler(enabled = true) { requestClose() }
    PhotoEditorScreen(
        state = state,
        original = if (state.source == EditorSource.READY) viewModel.originalBytes else null,
        onClose = requestClose,
        onSave = viewModel::save,
        onSaveAsCopy = viewModel::saveAsCopy,
        onSourceDecoded = viewModel::onSourceDecoded,
        onSourceUndecodable = viewModel::onSourceUndecodable,
        onRotate = viewModel::rotate,
        onMirror = viewModel::mirror,
        onReset = viewModel::reset,
        onSelectTool = viewModel::selectTool,
        onAspect = viewModel::setAspect,
        onStraighten = viewModel::setStraighten,
        onPerspective = viewModel::setPerspective,
        onDragCrop = viewModel::dragCrop,
        onCompare = viewModel::setComparing,
        onKeepEditing = viewModel::keepEditing,
        onDiscard = { viewModel.confirmDiscard(); onClose() },
        onDismissSaveFailure = viewModel::dismissSaveFailure,
        onSelectTab = viewModel::selectTab,
        onSelectAdjustTool = viewModel::selectAdjustTool,
        onAdjust = viewModel::setAdjust,
        onResetAdjust = viewModel::resetAdjust,
        onSelectLook = viewModel::selectLook,
        onFilterIntensity = viewModel::setFilterIntensity,
        markup = remember(viewModel) {
            MarkupActions(
                onSelectTool = viewModel::selectMarkupTool,
                onColor = viewModel::setInkColor,
                onWidth = viewModel::setInkWidth,
                onDown = viewModel::markupDown,
                onMove = viewModel::markupMove,
                onUp = viewModel::markupUp,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onCommitLabel = viewModel::commitLabel,
                onDeleteLabel = viewModel::deleteLabel,
                onCancelLabel = viewModel::cancelLabel,
            )
        },
    )
}
