package org.tenshu.sdk.ios

import androidx.lifecycle.viewModelScope
import app.tenshu.control.ui.screens.models.ModelsUiState
import app.tenshu.control.ui.screens.models.ModelsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * One Models screen's view model, for SwiftUI (`ios-on-device-inference.md` §14.18.15): what it
 * shows, and the way to end it.
 *
 * @param install [PhotosSurface.modelsInstalls] as it was when this was built. A screen whose
 *   binding is of an earlier install holds a replaced repository, and builds another.
 */
class PhotosModelsBinding internal constructor(val viewModel: ModelsViewModel, val install: Int) {

    val state: StateFlow<ModelsUiState> get() = viewModel.uiState

    /**
     * Ends the view model's collectors. ⚠⚠ Owed by the screen when it goes: nothing on iOS
     * clears a view model, and this one's collectors keep the repository's one collection of
     * the model source alive for as long as they run. ⚠ It stops nothing the repository does:
     * a download, a removal and a check go on (§14.18.5, *Leave the screen*).
     */
    fun close() = viewModel.viewModelScope.cancel()
}
