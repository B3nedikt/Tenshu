package app.tenshu.drive.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.selection_top_app_bar_clear
import app.tenshu.drive.shared.resources.selection_top_app_bar_title
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopAppBar(
    selectedCount: Int,
    onClearSelection: () -> Unit
) {
    AppTopAppBar(
        title = stringResource(Res.string.selection_top_app_bar_title, selectedCount),
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.selection_top_app_bar_clear)
                )
            }
        }
    )
}
