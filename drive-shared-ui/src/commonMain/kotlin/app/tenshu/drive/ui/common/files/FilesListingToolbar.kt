package app.tenshu.drive.ui.common.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.file_view_switch_to_grid
import app.tenshu.drive.shared.resources.file_view_switch_to_list
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The folder-listing toolbar row: the [FileSortBar] sort chip on the left and the
 * list/grid display toggle on the right — the placement settled in
 * `docs/proposals/drive-listing-sort-display.md` §2 (a dedicated row, uniform on
 * the root and subfolder screens, since the subfolder screen has no search bar).
 *
 * The toggle button shows the mode it switches *to* (a grid icon while in list,
 * a list icon while in grid) — the common convention.
 */
@Composable
fun FilesListingToolbar(
    sort: FileSort,
    onSortChange: (FileSort) -> Unit,
    viewMode: FileViewMode,
    onViewModeChange: (FileViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileSortBar(sort = sort, onSortChange = onSortChange)

        IconButton(onClick = { onViewModeChange(viewMode.toggled) }) {
            when (viewMode) {
                FileViewMode.LIST -> Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = stringResource(Res.string.file_view_switch_to_grid),
                )
                FileViewMode.GRID -> Icon(
                    imageVector = Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = stringResource(Res.string.file_view_switch_to_list),
                )
            }
        }
    }
}
