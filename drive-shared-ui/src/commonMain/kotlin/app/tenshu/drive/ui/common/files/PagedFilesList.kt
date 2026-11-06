package app.tenshu.drive.ui.common.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.files_list_empty_body
import app.tenshu.drive.shared.resources.files_list_error_action
import app.tenshu.drive.shared.resources.files_list_error_body
import app.tenshu.drive.shared.resources.files_list_error_title
import app.tenshu.drive.shared.resources.files_list_empty_title
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.common.HorizontalFileItem
import app.tenshu.drive.ui.common.VerticalFileItem
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * Paging-backed sibling of [FilesList]. Used by the folder browser
 * (FilesScreen / FolderScreen) where only the visible window is materialised;
 * [FilesList] stays for whole-list callers (the home recent-files surface).
 *
 * Callbacks deal in [FileUiModel] rather than a list index — a `LazyPagingItems`
 * has no stable full list to index into, and the click target is already the
 * row's own item.
 */
@Composable
fun PagedFilesList(
    modifier: Modifier,
    files: LazyPagingItems<FileUiModel>,
    isSelectionMode: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    /** This device's effective sync-rule set — each row resolves its offline
     *  glyph against it (`docs/proposals/drive-offline-online.md` §4). */
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    /** List vs. grid layout (the toolbar display toggle). Defaults to list so
     *  picker / test call sites are unaffected. */
    viewMode: FileViewMode = FileViewMode.LIST,
    /** Per-row preview loader; pass-through to [HorizontalFileItem]. */
    previewLoader: (suspend (path: String) -> ImageBitmap?)? = null,
    /** Per-row byte-presence probe (the `HasLocalData` signal, `drive-offline-online.md` §3); pass-through to
     *  [HorizontalFileItem]. `null` falls back to the offline-intent glyph. */
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    /** Pass-through to [HorizontalFileItem]; picker surfaces hide the "…" action. */
    showMoreButton: Boolean = true,
    onFileClicked: (item: FileUiModel) -> Unit,
    onItemLongClicked: (item: FileUiModel) -> Unit = {},
    onToggleSelection: (item: FileUiModel) -> Unit = {},
    onMoreButtonOfFileClicked: (item: FileUiModel) -> Unit = {},
) {
    // Do NOT compose the LazyColumn while the listing is still empty — not even
    // during the refresh load. On desktop, a LazyColumn that first exists while
    // non-scrollable (0 items) and only later grows past a screenful stops
    // responding to the MOUSE WHEEL (scrollbar-drag still works) — a
    // long-standing Compose-Desktop bug (JetBrains/compose-multiplatform issues
    // 3551 and 3366). Because the folder listing is paging-backed it ALWAYS
    // renders empty first, then fills, so it reliably trips the bug in large
    // folders (e.g. a synced Screenshots folder). Gating the LazyColumn on `itemCount > 0` means the list is born
    // already holding items — "starts scrollable" — which is the shape that
    // never regresses. It also removes the empty-list flash during load.
    if (files.itemCount == 0) {
        // ⚠ Three states, not two. A refresh that FAILED also lands here with zero items, and
        // rendering it as "No files here yet" would assert the folder is empty at the one moment
        // the app could not read it. The reachable cause is ordinary: a cold start issues its
        // first load before the schema session is admitted (~6 s on a real device), so the load
        // fails, `TenshuPagingSource` correctly returns `LoadResult.Error`, and Paging does not
        // retry an error on its own — the listing would stay "empty" until something rebuilt the
        // Pager.
        //
        // Same family as the iOS `FilesView` "empty == loading" conflation and the
        // "no signal == nothing to report" banner gap (`core-client-resubscription.md` §4.3):
        // absence of data is not evidence of absence of files.
        when (files.loadState.refresh) {
            is LoadState.Loading -> Box(
                modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AppLoadingIndicator()
            }

            is LoadState.Error -> AppEmptyState(
                modifier = modifier,
                icon = Icons.Default.CloudOff,
                iconContentDescription = null,
                title = stringResource(Res.string.files_list_error_title),
                body = stringResource(Res.string.files_list_error_body),
                actionLabel = stringResource(Res.string.files_list_error_action),
                // Paging's own retry — it re-runs the failed load on the SAME PagingSource, so a
                // transient failure recovers without rebuilding the pager or losing the folder.
                onAction = { files.retry() },
            )

            else -> AppEmptyState(
                modifier = modifier,
                icon = Icons.Default.FolderOpen,
                iconContentDescription = null,
                title = stringResource(Res.string.files_list_empty_title),
                body = stringResource(Res.string.files_list_empty_body)
            )
        }
        return
    }

    val loadedPaths = files.itemSnapshotList.items.map { it.path }

    when (viewMode) {
        FileViewMode.LIST -> {
            val listState = rememberLazyListState()
            FollowHeadInserts(
                loadedPaths = loadedPaths,
                firstVisibleIndex = { listState.firstVisibleItemIndex },
                firstVisibleOffset = { listState.firstVisibleItemScrollOffset },
                scrollToTop = { listState.scrollToItem(0) },
            )
            LazyColumn(
                modifier.fillMaxWidth(),
                state = listState,
            ) {
                items(
                    count = files.itemCount,
                    key = files.itemKey { it.path },
                ) { index ->
                    val item = files[index] ?: return@items

                    HorizontalFileItem(
                        item = item,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedPaths.contains(item.path),
                        effectivePolicy = syncRules.effectivePolicy(item.path),
                        previewLoader = previewLoader,
                        localDataLoader = localDataLoader,
                        showMoreButton = showMoreButton,
                        onItemClicked = onFileClicked,
                        onItemLongClicked = onItemLongClicked,
                        onToggleSelection = onToggleSelection,
                        onMoreIconClicked = onMoreButtonOfFileClicked
                    )
                }
        }
        }

        // The empty gate above (itemCount == 0) covers the grid too, so like the
        // LazyColumn it is only composed once it already holds items — the same
        // shape that dodges the desktop mouse-wheel regression.
        FileViewMode.GRID -> {
            val gridState = rememberLazyGridState()
            FollowHeadInserts(
                loadedPaths = loadedPaths,
                firstVisibleIndex = { gridState.firstVisibleItemIndex },
                firstVisibleOffset = { gridState.firstVisibleItemScrollOffset },
                scrollToTop = { gridState.scrollToItem(0) },
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = modifier.fillMaxWidth(),
                state = gridState,
                contentPadding = PaddingValues(MaterialTheme.spacing.s),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
            ) {
                items(
                    count = files.itemCount,
                    key = files.itemKey { it.path },
                ) { index ->
                    val item = files[index] ?: return@items

                    VerticalFileItem(
                        item = item,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedPaths.contains(item.path),
                        effectivePolicy = syncRules.effectivePolicy(item.path),
                        previewLoader = previewLoader,
                        localDataLoader = localDataLoader,
                        showMoreButton = showMoreButton,
                        onItemClicked = onFileClicked,
                        onItemLongClicked = onItemLongClicked,
                        onToggleSelection = onToggleSelection,
                        onMoreIconClicked = onMoreButtonOfFileClicked,
                    )
                }
        }
        }
    }
}

/**
 * Keep the viewport on the **head** of the listing when rows are inserted above it and the reader
 * is parked at the very top.
 *
 * ⚠⚠ **Why this is needed: the listing is sorted.** A `LazyColumn` keyed by a stable id (here the
 * row's `path`) anchors on the item it is currently showing: prepend a row and the list keeps the
 * *old* first row exactly where it was, so the new one materialises **above the viewport** and is
 * never seen. In natural (store) order that cannot happen — uploads and folder creations append at
 * the tail. Under "Date modified ↓" every new row is a head insert, so a folder created while the
 * listing is open would sit one row above the fold indefinitely (`drive-sorted-folder-paging.md`
 * §10.6).
 *
 * ⚠ It deliberately does **not** fire for a reader who has scrolled: the test is that the viewport
 * sits exactly on the row that used to be first, at offset 0, which is only true of someone parked
 * at the top. Scrolling someone deeper in the list back to 0 would be the more annoying bug, and
 * `core-paging-scroll-restoration.md` exists because losing a reader's place is expensive.
 */
@Composable
private fun FollowHeadInserts(
    loadedPaths: List<String>,
    firstVisibleIndex: () -> Int,
    firstVisibleOffset: () -> Int,
    scrollToTop: suspend () -> Unit,
) {
    var previousFirstPath by remember { mutableStateOf(loadedPaths.firstOrNull()) }
    val firstPath = loadedPaths.firstOrNull()

    LaunchedEffect(firstPath) {
        val previous = previousFirstPath
        previousFirstPath = firstPath
        if (shouldFollowHeadInsert(
                previousFirstPath = previous,
                loadedPaths = loadedPaths,
                firstVisibleIndex = firstVisibleIndex(),
                firstVisibleOffset = firstVisibleOffset(),
            )
        ) {
            scrollToTop()
        }
    }
}

/**
 * Whether [FollowHeadInserts] should scroll back to the head — extracted so the rule is testable
 * without a composition (`PagedFilesListHeadInsertTest`).
 *
 * True only when all of these hold:
 *  - the listing had a head before, and it changed;
 *  - the previous head is **still loaded**, [shift] rows further down — i.e. rows were *prepended*,
 *    not a fresh generation that replaced everything (a refresh leaves the old head absent, and
 *    scrolling for that would fight scroll restoration);
 *  - the viewport is anchored **exactly** on that old head at offset 0, which is what
 *    `LazyColumn`'s key anchoring produces for a reader who was parked at the very top, and is
 *    false for a reader who had scrolled anywhere else.
 */
internal fun shouldFollowHeadInsert(
    previousFirstPath: String?,
    loadedPaths: List<String>,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
): Boolean {
    val firstPath = loadedPaths.firstOrNull()
    if (previousFirstPath == null || firstPath == null || previousFirstPath == firstPath) return false
    val shift = loadedPaths.indexOf(previousFirstPath)
    if (shift <= 0) return false
    return firstVisibleIndex == shift && firstVisibleOffset == 0
}
