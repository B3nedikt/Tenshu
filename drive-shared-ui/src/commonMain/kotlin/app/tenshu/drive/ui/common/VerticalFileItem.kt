package app.tenshu.drive.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.shared.resources.Res
import app.tenshu.ui.image.decodePreviewBitmap
import app.tenshu.drive.shared.resources.file_item_icon_description
import app.tenshu.drive.shared.resources.file_item_more_actions
import app.tenshu.drive.shared.resources.file_item_offline_pending_description
import app.tenshu.drive.shared.resources.file_item_online_only_description
import app.tenshu.drive.shared.resources.file_item_preview_description
import app.tenshu.drive.shared.resources.file_item_toggle_selection
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import org.tenshu.core.file.sync.SyncPolicy

/**
 * Grid-tile sibling of [HorizontalFileItem] — the thumbnail-forward cell for the
 * listing's grid display mode ([app.tenshu.drive.data.FileViewMode.GRID]). Shares
 * the same per-row preview + presence discipline (lazy, cancel-on-scroll via
 * [produceState]) and the same selection / offline-glyph / more-action
 * affordances, laid out as a square preview with the name + subline beneath and
 * the overflow action right-aligned beside them.
 */
private val MoreButtonSize = 48.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VerticalFileItem(
    item: FileUiModel,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    /** See [HorizontalFileItem.effectivePolicy]. */
    effectivePolicy: SyncPolicy = SyncPolicy.ONLINE_ONLY,
    /** Per-tile preview loader; same contract + lifecycle as [HorizontalFileItem]. */
    previewLoader: (suspend (path: String) -> ImageBitmap?)? = null,
    /** Per-tile byte-presence probe; same contract as [HorizontalFileItem]. */
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    /** Hide the "…" overflow action (picker surfaces). */
    showMoreButton: Boolean = true,
    onItemClicked: (item: FileUiModel) -> Unit,
    onItemLongClicked: (item: FileUiModel) -> Unit,
    onToggleSelection: (item: FileUiModel) -> Unit,
    onMoreIconClicked: (item: FileUiModel) -> Unit,
) {
    // Identical resolution to HorizontalFileItem — see its KDoc for the precedence
    // and cancel-on-scroll rationale.
    val previewImage: ImageBitmap? by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = item.path,
        key2 = item.previewBytes,
        key3 = previewLoader,
    ) {
        item.previewBytes?.let { value = it.decodePreviewBitmap(); return@produceState }
        if (previewLoader == null) return@produceState
        value = previewLoader(item.path)
    }
    val hasLocalData: Boolean? by produceState<Boolean?>(
        initialValue = null,
        key1 = item.path,
        key2 = localDataLoader,
    ) {
        if (localDataLoader == null || item.isFolder) return@produceState
        value = localDataLoader(item.path)
    }
    val offlineGlyph: Pair<ImageVector, String>? = if (item.isFolder) {
        null
    } else when (offlineRowGlyph(hasLocalData, effectivePolicy)) {
        OfflineRowGlyph.OnlineOnly -> Icons.Default.CloudQueue to
            stringResource(Res.string.file_item_online_only_description)
        OfflineRowGlyph.OfflinePending -> Icons.Default.CloudSync to
            stringResource(Res.string.file_item_offline_pending_description)
        null -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onToggleSelection(item) else onItemClicked(item)
                },
                onLongClick = { onItemLongClicked(item) },
            )
            .padding(MaterialTheme.spacing.s),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(MaterialTheme.spacing.s))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val resolvedPreview = previewImage
            if (resolvedPreview != null) {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = resolvedPreview,
                    contentDescription = stringResource(Res.string.file_item_preview_description),
                )
            } else {
                Icon(
                    modifier = Modifier.size(48.dp),
                    imageVector = item.previewIcon.asImageVector(),
                    contentDescription = stringResource(Res.string.file_item_icon_description),
                )
            }

            if (isSelectionMode) {
                Icon(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(MaterialTheme.spacing.xs),
                    imageVector = if (isSelected) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.RadioButtonUnchecked
                    },
                    contentDescription = stringResource(Res.string.file_item_toggle_selection),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                )
            }

            if (offlineGlyph != null) {
                Icon(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(MaterialTheme.spacing.xs)
                        .size(16.dp),
                    imageVector = offlineGlyph.first,
                    contentDescription = offlineGlyph.second,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        }

        // Overflow action lives in the title row, right of the name (the Google Drive /
        // Material card convention), not over the thumbnail: there it fights the image
        // for contrast, and a 48dp target tucked inside the corner leaves the dots 24dp
        // off the edge, so it never looked right-aligned.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.spacing.xs)
                // Keep the row's height when the button hides in selection mode, so
                // tiles don't jump as the mode toggles.
                .then(if (showMoreButton) Modifier.heightIn(min = MoreButtonSize) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showMoreButton && !isSelectionMode) {
                // The dots are a thin centred column, so centre them `spacing.s` in from
                // the thumbnail's right edge. The 48dp touch target overhangs the row by
                // the rest — into the tile padding + grid gutter, never past the
                // neighbour's edge — and only the visible part claims row width, so the
                // name isn't squeezed by invisible touch padding.
                val overhang = MoreButtonSize / 2 - MaterialTheme.spacing.s
                IconButton(
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (placeable.width - overhang.roundToPx()).coerceAtLeast(0)
                        // placeRelative: in RTL the overhang must go out past the left
                        // edge, not into the name.
                        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
                    },
                    onClick = { onMoreIconClicked(item) },
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(
                            Res.string.file_item_more_actions,
                            item.name,
                        ),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun VerticalFileItemPreview() {
    VerticalFileItem(
        item = FileUiModel(
            path = "/example.pdf",
            name = "A PDF",
            subLine = "1.2 MB",
            previewIcon = FileIcon.Pdf,
            previewBytes = null,
        ),
        onItemClicked = { },
        onItemLongClicked = { },
        onToggleSelection = { },
        onMoreIconClicked = { },
    )
}
