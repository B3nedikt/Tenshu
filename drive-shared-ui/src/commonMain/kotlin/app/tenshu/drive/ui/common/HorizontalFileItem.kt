package app.tenshu.drive.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.ui.image.decodePreviewBitmap
import app.tenshu.drive.shared.resources.file_item_icon_description
import app.tenshu.drive.shared.resources.file_item_more_actions
import app.tenshu.drive.shared.resources.file_item_offline_pending_description
import app.tenshu.drive.shared.resources.file_item_online_only_description
import app.tenshu.drive.shared.resources.file_item_preview_description
import app.tenshu.drive.shared.resources.file_item_toggle_selection
import org.tenshu.core.file.sync.SyncPolicy
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview


@Composable
fun HorizontalFileItem(
    item: FileUiModel,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isLoading: Boolean = false,
    /**
     * This file's effective sync policy on this device (the *intent* axis —
     * `docs/proposals/drive-offline-online.md` §4): the resolved
     * folder/path override or device default. [SyncPolicy.ONLINE_ONLY] rows whose
     * bytes are absent show an "online only" cloud; [SyncPolicy.KEEP_OFFLINE] rows
     * whose bytes are absent show a "queued" cloud. Default [SyncPolicy.ONLINE_ONLY]
     * keeps previews / un-threaded call sites buildable — with no [localDataLoader]
     * presence stays unknown, so no glyph renders regardless.
     */
    effectivePolicy: SyncPolicy = SyncPolicy.ONLINE_ONLY,
    /**
     * Per-row preview loader. Suspending so it can do the proxy round-trip
     * + decode off the UI thread; `null` return means "no preview" and the
     * row falls back to the category icon. The `previewLoader == null`
     * default keeps existing call sites (Compose `@Preview` previews,
     * test fixtures) buildable without a loader — they'll just render the
     * category icon, matching the pre-Phase-1 behaviour.
     *
     * The actual fetch is launched lazily via `produceState` below — the
     * coroutine is bound to the row's composition lifetime, so a row
     * scrolled off-screen cancels its in-flight fetch immediately.
     */
    previewLoader: (suspend (path: String) -> ImageBitmap?)? = null,
    /**
     * Per-row byte-**presence** probe — the v2 presence signal
     * (`docs/proposals/drive-offline-online.md` §3). Resolves whether the
     * file's bytes are on local disk right now, launched lazily + bounded
     * exactly like [previewLoader] (cancelled when the row scrolls away).
     *
     * `null` (the default, and always for folders) means "don't probe": the
     * row shows no glyph (the unknown state is neutral, §4.5). When supplied,
     * presence refines the glyph — a file with local bytes (kept-offline **or**
     * merely opened-and-cached) shows no cloud, while a kept-offline file whose
     * bytes haven't arrived shows a "queued" glyph.
     */
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    /**
     * Whether to render the trailing "…" overflow action. Picker surfaces
     * (e.g. the Move destination picker) browse folders without per-row
     * actions, so they pass `false`. Defaults to `true` to keep every
     * existing browse/list call site unchanged.
     */
    showMoreButton: Boolean = true,
    onItemClicked: (item: FileUiModel) -> Unit,
    onItemLongClicked: (item: FileUiModel) -> Unit,
    onToggleSelection: (item: FileUiModel) -> Unit,
    onMoreIconClicked: (item: FileUiModel) -> Unit
) {
    // Preview resolution, in precedence order:
    //  1. The projected thumbnail bytes (`item.previewBytes`), for a caller whose
    //     projection carries `File.Preview` inline. No LIST surface does today —
    //     inline per-row preview resolution blocked the page paint (see
    //     `TenshuFilesRepository.getFilesPaged`) — but the precedence stays for
    //     surfaces that already hold bytes. Keying `produceState` on the bytes
    //     (`key2`) is what makes the row recompose the instant the preview lands
    //     instead of caching the first (null) result — the "preview only shows
    //     after a screen switch" bug.
    //  2. The per-row `previewLoader` — the async-image-loader path every list
    //     surface (paged Files/Folder, search, home) now uses. Bound to the row's
    //     composition lifetime: scrolling off-screen cancels it and releases its
    //     semaphore permit (`FilesScreenViewModel.loadPreviewBytes`'s 4-permit bound).
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
    // Presence (the v2 axis): probe lazily per row, same cancel-on-scroll
    // discipline as the preview above. `null` = not-yet-known / not-probed
    // (folders, or no loader) → the glyph falls back to intent. Folders have
    // no bytes, so are never probed.
    val hasLocalData: Boolean? by produceState<Boolean?>(
        initialValue = null,
        key1 = item.path,
        key2 = localDataLoader,
    ) {
        if (localDataLoader == null || item.isFolder) return@produceState
        value = localDataLoader(item.path)
    }
    // The offline glyph, resolved once and rendered in the row's trailing slot
    // (right edge) — the convention OneDrive / iCloud Files / Dropbox follow,
    // and quieter than sitting next to the name. Presence (v2,
    // drive-offline-online.md §3) refines intent (v1, §2): a file whose bytes
    // are on disk (pinned *or* merely opened-and-cached) shows nothing; only
    // the exceptional states are badged:
    //   • absent + KEEP_OFFLINE → "queued" glyph (kept offline, not here yet)
    //   • absent + ONLINE_ONLY  → "online only"
    //   • unknown (probing / no loader) → no glyph (neutral, §4.5)
    // Folders have no bytes, so never carry a glyph.
    val offlineGlyph: Pair<ImageVector, String>? = if (item.isFolder) {
        null
    } else when (offlineRowGlyph(hasLocalData, effectivePolicy)) {
        OfflineRowGlyph.OnlineOnly -> Icons.Default.CloudQueue to
            stringResource(Res.string.file_item_online_only_description)
        OfflineRowGlyph.OfflinePending -> Icons.Default.CloudSync to
            stringResource(Res.string.file_item_offline_pending_description)
        null -> null
    }
    AppListItem(
        headline = item.name,
        onClick = {
            if (isSelectionMode) onToggleSelection(item) else onItemClicked(item)
        },
        onLongClick = { onItemLongClicked(item) },
        supportingText = item.subLine,
        leadingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)
            ) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = stringResource(Res.string.file_item_toggle_selection),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }

                // Local copy so the smart-cast works — `previewImage` is a
                // `by` delegate from `produceState`, which Kotlin treats as
                // potentially-mutable and won't smart-cast across the `!= null`
                // check otherwise.
                val resolvedPreview = previewImage
                if (resolvedPreview != null) {
                    Image(
                        modifier = Modifier
                            .width(40.dp)
                            .height(40.dp),
                        bitmap = resolvedPreview,
                        contentDescription = stringResource(Res.string.file_item_preview_description),
                    )
                } else {
                    Icon(
                        modifier = Modifier
                            .width(40.dp)
                            .height(40.dp),
                        imageVector = item.previewIcon.asImageVector(),
                        contentDescription = stringResource(Res.string.file_item_icon_description),
                    )
                }
            }
        },
        // Trailing slot holds the offline status glyph (if any) followed by the
        // "…" overflow action — status then affordance, both right-aligned. Null
        // only when there's nothing at all to show (e.g. a picker folder row).
        trailingContent = if (offlineGlyph == null && !isLoading && !showMoreButton) {
            null
        } else {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                ) {
                    if (offlineGlyph != null) {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            imageVector = offlineGlyph.first,
                            contentDescription = offlineGlyph.second,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isLoading) {
                        AppLoadingIndicator(size = AppLoadingSize.Inline)
                    } else if (showMoreButton) {
                        Icon(
                            modifier = Modifier.clickable { onMoreIconClicked(item) },
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
    )
}

@Preview
@Composable
fun HorizontalFileItemPreviewWithoutImage() {
    HorizontalFileItem(
        item = FileUiModel(
            path = "/example.pdf",
            name = "A PDF",
            subLine = "Subline",
            previewIcon = FileIcon.Pdf,
            previewBytes = null,
        ),
        onItemClicked = { },
        onItemLongClicked = { },
        onToggleSelection = { },
        onMoreIconClicked = { },
    )
}
