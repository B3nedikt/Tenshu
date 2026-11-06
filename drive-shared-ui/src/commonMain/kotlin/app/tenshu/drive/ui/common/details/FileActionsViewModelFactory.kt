package app.tenshu.drive.ui.common.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

val LocalFileActionsViewModelFactory =
    staticCompositionLocalOf<@Composable (String, Boolean) -> FileActionsViewModel> {
        { path, isFolder -> koinViewModel(key = path, parameters = { parametersOf(path, isFolder) }) }
    }
