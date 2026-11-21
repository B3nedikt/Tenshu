package app.tenshu.drive.ui.common

import androidx.compose.runtime.Composable
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.folder_item_file_count
import app.tenshu.drive.shared.resources.folder_item_file_count_one
import app.tenshu.drive.ui.model.FileUiModel
import org.jetbrains.compose.resources.stringResource

/**
 * The line under a row's name. A folder's child count is worded here, in the app's language;
 * [FileUiModel.subLine] carries the same count in English for the SwiftUI app, which has no
 * Compose resources.
 */
@Composable
internal fun FileUiModel.supportingText(): String = when (val count = childCount) {
    null -> subLine
    1L -> stringResource(Res.string.folder_item_file_count_one)
    else -> stringResource(Res.string.folder_item_file_count, count)
}
