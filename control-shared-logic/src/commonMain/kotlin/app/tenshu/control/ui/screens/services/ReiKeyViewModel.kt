package app.tenshu.control.ui.screens.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.PublisherClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * §2.3 "Have a code?" advanced redemption per
 * `docs/proposals/commerce-accounts-and-sessions.md`. Phase 3b wires this to the real
 * identity-light `POST /commerce/entitlement/redeem`: a valid code mints + persists the
 * corresponding entitlement and skips checkout entirely (founder / debug grants, out-of-band
 * privacy tokens). No email, no session — the granted record is persisted directly.
 */
class ReiKeyViewModel(
    private val publisherClient: PublisherClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReiKeyUiState())
    val uiState: StateFlow<ReiKeyUiState> = _uiState.asStateFlow()

    fun onKeyChange(value: String) {
        _uiState.update { it.copy(key = value, error = null) }
    }

    fun onContinue() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            _uiState.update {
                when (val result = publisherClient.redeem(_uiState.value.key.trim())) {
                    is PublisherClient.RedeemResult.Redeemed ->
                        it.copy(busy = false, linked = true)
                    is PublisherClient.RedeemResult.Invalid ->
                        it.copy(busy = false, error = "This code isn't valid. Check it and try again.")
                    is PublisherClient.RedeemResult.Error ->
                        it.copy(busy = false, error = result.message)
                }
            }
        }
    }
}

data class ReiKeyUiState(
    val key: String = "",
    val busy: Boolean = false,
    val linked: Boolean = false,
    val error: String? = null,
)
