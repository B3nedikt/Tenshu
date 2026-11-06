package app.tenshu.drive.ui.common.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import app.tenshu.drive.shared.resources.Res
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.drive.shared.resources.files_list_empty_body
import app.tenshu.drive.shared.resources.files_list_empty_title
import app.tenshu.drive.shared.resources.files_list_error_retrying_body
import app.tenshu.drive.shared.resources.files_list_error_title
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.common.HorizontalFileItem
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.feedback.AppLoadingIndicator
import org.jetbrains.compose.resources.stringResource

@Composable
fun FilesList(
    modifier: Modifier,
    files: List<FileUiModel>,
    /**
     * Which of loading / loaded / failed [files] is in. Defaults to `LOADED` so the many call
     * sites that pass an already-resolved list (previews, sub-lists of a screen that has its own
     * state) keep today's behaviour; the reactive callers pass their real phase.
     */
    load: ListLoadPhase = ListLoadPhase.LOADED,
    isSelectionMode: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    /** This device's effective sync-rule set — each row resolves its offline
     *  glyph against it (`docs/proposals/drive-offline-online.md` §4). */
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    /** Per-row preview loader; pass-through to [HorizontalFileItem]. `null` keeps
     *  preview-less rendering (file-category icon only). */
    previewLoader: (suspend (path: String) -> ImageBitmap?)? = null,
    /** Per-row byte-presence probe (the v2 presence signal, §3); pass-through to
     *  [HorizontalFileItem]. `null` falls back to the offline-intent glyph. */
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    onFileClicked: (index: Int) -> Unit,
    onItemLongClicked: (index: Int) -> Unit = {},
    onToggleSelection: (index: Int) -> Unit = {},
    onMoreButtonOfFileClicked: (index: Int) -> Unit,
) {
    if (files.isEmpty()) {
        // ⚠ Three states, not two — the same family as [PagedFilesList]'s gate, iOS's `FilesView`
        // "empty == loading" and R4's "no signal == nothing to report": absence of data is not
        // evidence of absence of files. This list is fed by a reactive `flow()` read whose failure
        // used to be folded to `emptyList()` in the repository, so a broken read rendered as a
        // statement about the user's drive.
        when (load) {
            ListLoadPhase.LOADING -> Box(
                modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppLoadingIndicator()
            }

            // No *Try again*, unlike the paged sibling: a `flow()` subscribe retries itself
            // (`core-client-resubscription.md` R1b, waking on the reconnect edge since R5), so a
            // button would duplicate what is already happening — and would read as though nothing
            // were, if the user did not press it.
            ListLoadPhase.FAILED -> AppEmptyState(
                modifier = modifier,
                icon = Icons.Default.CloudOff,
                iconContentDescription = null,
                title = stringResource(Res.string.files_list_error_title),
                body = stringResource(Res.string.files_list_error_retrying_body),
            )

            ListLoadPhase.LOADED -> AppEmptyState(
                modifier = modifier,
                icon = Icons.Default.FolderOpen,
                iconContentDescription = null,
                title = stringResource(Res.string.files_list_empty_title),
                body = stringResource(Res.string.files_list_empty_body)
            )
        }
        return
    }

    LazyColumn(
        modifier.fillMaxWidth()
    ) {

        itemsIndexed(files) { index, item ->

            HorizontalFileItem(
                item = item,
                isSelectionMode = isSelectionMode,
                isSelected = selectedPaths.contains(item.path),
                effectivePolicy = syncRules.effectivePolicy(item.path),
                previewLoader = previewLoader,
                localDataLoader = localDataLoader,
                onItemClicked = { onFileClicked(index) },
                onItemLongClicked = { onItemLongClicked(index) },
                onToggleSelection = { onToggleSelection(index) },
                onMoreIconClicked = { onMoreButtonOfFileClicked(index) }
            )
        }
    }
}
