package app.tenshu.control.ui.screens.shares

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.ShareManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Data → Shares inventory. Collects the realm-wide outbound
 * shares from [ShareManager] into [uiState], and drives the revoke flow
 * (click → confirm → op). Revoke is optimistic only in the sense that the
 * reactive `shares()` projection re-emits the flipped `revoked` row, so no
 * manual list mutation is needed.
 */
class SharesViewModel(
    private val shareManager: ShareManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharesUiState())
    val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            shareManager.shares().collect { rows ->
                _uiState.update { it.copy(shares = rows) }
            }
        }
        viewModelScope.launch {
            shareManager.sharedFolders().collect { folders ->
                _uiState.update { it.copy(sharedFolders = folders) }
            }
        }
    }

    /** Arm the revoke-confirm dialog for [shareId]. */
    fun onRevokeClicked(shareId: String) {
        _uiState.update { it.copy(pendingRevokeShareId = shareId) }
    }

    /** Dismiss the confirm dialog without revoking. */
    fun onRevokeDismissed() {
        _uiState.update { it.copy(pendingRevokeShareId = null) }
    }

    /** Confirm: revoke the armed share (Eager flip + relay DELETE) and close the dialog. */
    fun onRevokeConfirmed() {
        val shareId = _uiState.value.pendingRevokeShareId ?: return
        _uiState.update { it.copy(pendingRevokeShareId = null) }
        viewModelScope.launch {
            shareManager.revoke(shareId)
        }
    }
}
