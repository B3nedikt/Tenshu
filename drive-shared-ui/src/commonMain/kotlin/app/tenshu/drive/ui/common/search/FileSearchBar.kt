package app.tenshu.drive.ui.common.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.file_search_close
import app.tenshu.drive.shared.resources.file_search_settings
import app.tenshu.drive.shared.resources.search_results_error_body
import app.tenshu.drive.shared.resources.search_results_error_title
import app.tenshu.liststate.ListLoadPhase
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.common.HorizontalFileItem
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.input.AppSearchBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FilesSearchBar(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSettingsClicked: () -> Unit = {},
    inputFocusRequester: FocusRequester? = null,
    /** Open / navigate to a tapped result. Hoisted to the host screen — the
     *  search VM has no navigator of its own; the host reuses the same
     *  folder-vs-file handler as its main listing. */
    onResultClicked: (FileUiModel) -> Unit = {},
    /** Overflow ("more") action on a result row — the host shows its
     *  file-actions sheet, exactly like a listing row's overflow. */
    onResultMoreClicked: (FileUiModel) -> Unit = {},
    /** This device's effective sync-rule set + per-row presence probe, both
     *  supplied by the *host screen's* VM. The search VM sources the result
     *  rows themselves (`FilesRepository.searchFiles`), but the offline-glyph
     *  inputs stay hoisted — they're device/screen state, not file data — and
     *  drive each row's glyph exactly like the main listings
     *  (`docs/proposals/drive-offline-online.md` §3–§4). */
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    viewModel: FilesSearchBarViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    FilesSearchBarContent(
        modifier = modifier,
        enabled = enabled,
        uiState = uiState,
        onQueryChanged = viewModel::onQueryChanged,
        onActiveChanged = viewModel::onActiveChanged,
        onSearchClicked = viewModel::onSearchClicked,
        // Collapse the search overlay as we hand off to the host (navigation or
        // the actions sheet) so the full-screen results don't stay on top.
        onResultClicked = { file ->
            viewModel.onActiveChanged(false)
            onResultClicked(file)
        },
        onResultMoreClicked = { file ->
            viewModel.onActiveChanged(false)
            onResultMoreClicked(file)
        },
        onSettingsClicked = onSettingsClicked,
        inputFocusRequester = inputFocusRequester,
        syncRules = syncRules,
        localDataLoader = localDataLoader,
    )
}

@Composable
fun FilesSearchBarContent(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    uiState: FileSearchBarUiState,
    onQueryChanged: (String) -> Unit,
    onActiveChanged: (Boolean) -> Unit,
    onSearchClicked: (String) -> Unit,
    onResultClicked: (FileUiModel) -> Unit = {},
    onResultMoreClicked: (FileUiModel) -> Unit = {},
    onSettingsClicked: () -> Unit = {},
    inputFocusRequester: FocusRequester? = null,
    /** Effective sync-rule set + per-row presence probe for result rows; see the
     *  [FilesSearchBar] overload. Default rule set / null loader keeps `@Preview`s buildable. */
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
) {
    AppSearchBar(
        modifier = modifier,
        query = uiState.query,
        onQueryChange = onQueryChanged,
        active = uiState.isActive,
        onActiveChange = onActiveChanged,
        onSearch = onSearchClicked,
        enabled = enabled,
        inputFocusRequester = inputFocusRequester,
        trailingIcon = {
            if (uiState.isActive) {
                Icon(
                    modifier = Modifier.clickable {
                        if (uiState.query.isNotEmpty()) {
                            onQueryChanged("")
                        } else {
                            onActiveChanged(false)
                        }
                    },
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.file_search_close)
                )
            } else {
                IconButton(onClick = onSettingsClicked) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(Res.string.file_search_settings)
                    )
                }
            }
        }
    ) {
        // ⚠ A failed search used to arrive as zero results, i.e. as "nothing matches" — which is
        // the one thing a search must be able to say truthfully. Only the failure gets a message:
        // a genuine no-match still renders as an empty list, which is the existing product
        // choice and not this change's business.
        if (uiState.filesLoad == ListLoadPhase.FAILED && uiState.files.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = stringResource(Res.string.search_results_error_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.search_results_error_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@AppSearchBar
        }
        LazyColumn(
            Modifier.fillMaxWidth()
        ) {
            itemsIndexed(uiState.files) { _, item ->
                HorizontalFileItem(
                    item = item,
                    effectivePolicy = syncRules.effectivePolicy(item.path),
                    localDataLoader = localDataLoader,
                    onItemClicked = { onResultClicked(item) },
                    onItemLongClicked = {},
                    onToggleSelection = {},
                    onMoreIconClicked = { onResultMoreClicked(item) }
                )
            }
        }
    }
}
