package app.tenshu.control.ui.screens.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.AppDetail
import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.GrantState
import app.tenshu.control.data.UninstallScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the App-detail screen. */
data class AppDetailUiState(
    val detail: AppDetail? = null,
    val loading: Boolean = true,
)

/**
 * Drives the per-app detail screen: observes [AppRegistry.appDetail] for [appId]
 * and routes the permission / uninstall actions back. Grant changes are
 * optimistic — the matching row updates locally at once, then the write fires
 * and the observed flow reconciles. Parameterised by [appId], so it is
 * registered as `viewModel { (appId: String) -> AppDetailViewModel(appId, get()) }`.
 */
class AppDetailViewModel(
    private val appId: String,
    private val appRegistry: AppRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appRegistry.appDetail(appId).collect { detail ->
                _uiState.update { it.copy(detail = detail, loading = false) }
            }
        }
    }

    // ⚠ No setGrant / revokeAllPermissions here any more (control-app-catalogue.md C0).
    // Grants are never consulted on the dispatch path, so exposing a control would promise a
    // boundary that does not exist; per D6 a per-app permission control is a *third-party*
    // feature and first-party apps are realm co-owners. `AppRegistry.setGrant` /
    // `revokeAllPermissions` remain — that is the layer the third-party track will re-wire.

    fun uninstall(scope: UninstallScope) {
        viewModelScope.launch { appRegistry.uninstall(appId, scope) }
    }
}
