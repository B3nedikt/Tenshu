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
 * §3.2 Free path per `docs/proposals/commerce-accounts-and-sessions.md` — the real email-OTP flow
 * (Phase 3b), replacing the earlier "check your email" poll:
 *
 *  - [FreeAccountSignupStep.Form] — email (+ optional display name) entry → [PublisherClient.register].
 *  - [FreeAccountSignupStep.EnterCode] — the 6-digit OTP the service emailed → [PublisherClient.verifyOtp].
 *  - [FreeAccountSignupStep.Done] — terminal; the screen routes back to the caller, account bound +
 *    session open (which drives the entitlement mint writer to fetch the owner's Free record).
 */
class FreeAccountSignupViewModel(
    private val publisherClient: PublisherClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreeAccountSignupUiState())
    val uiState: StateFlow<FreeAccountSignupUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value, error = null) }
    }

    fun onCodeChange(value: String) {
        // The OTP is 6 numeric digits; keep the field constrained so the CTA gates cleanly.
        val digits = value.filter { it.isDigit() }.take(OTP_LENGTH)
        _uiState.update { it.copy(code = digits, error = null, resent = false) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            when (val result = publisherClient.register(
                email = state.email,
                displayName = state.displayName.takeIf { it.isNotBlank() },
            )) {
                is PublisherClient.RegisterResult.OtpSent ->
                    _uiState.update {
                        it.copy(step = FreeAccountSignupStep.EnterCode, busy = false, code = "", resent = false)
                    }
                is PublisherClient.RegisterResult.Error ->
                    _uiState.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun onVerify() {
        val state = _uiState.value
        if (state.busy || state.code.length != OTP_LENGTH) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            when (val result = publisherClient.verifyOtp(state.code)) {
                is PublisherClient.VerifyResult.Verified ->
                    _uiState.update { it.copy(step = FreeAccountSignupStep.Done, busy = false) }
                is PublisherClient.VerifyResult.InvalidCode ->
                    _uiState.update {
                        it.copy(busy = false, code = "", error = INVALID_CODE)
                    }
                is PublisherClient.VerifyResult.Error ->
                    _uiState.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /**
     * Re-send the code to the address already entered, without leaving the code step.
     *
     * ⚠ The capability existed before — [onEditEmail] then [onSubmit] re-registers and re-sends —
     * but it was unnamed, so a user waiting on a missing email had no visible way to ask for
     * another. ⚠ The service budgets this (5/address/hour, CE-2); a refusal surfaces as the
     * ordinary error, and deliberately spends no budget of its own.
     */
    fun onResend() {
        val state = _uiState.value
        if (state.busy || state.step != FreeAccountSignupStep.EnterCode) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, resent = false) }
            when (val result = publisherClient.register(
                email = state.email,
                displayName = state.displayName.takeIf { it.isNotBlank() },
            )) {
                is PublisherClient.RegisterResult.OtpSent ->
                    _uiState.update { it.copy(busy = false, code = "", resent = true) }
                is PublisherClient.RegisterResult.Error ->
                    _uiState.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /** Back from the code step to re-enter the email (e.g. typo'd address). */
    fun onEditEmail() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(step = FreeAccountSignupStep.Form, code = "", error = null, resent = false) }
    }

    companion object {
        /**
         * The OTP is 6 numeric digits. Public because every consumer gates its CTA on it and each
         * one used to spell the number itself — the Compose screen had a `private const` of its
         * own, and the iOS surface would have been the third copy. One place, next to the state
         * machine that enforces it in [onCodeChange] and [onVerify].
         */
        const val OTP_LENGTH = 6

        private const val INVALID_CODE = "That code didn't match. Check your email and try again."
    }
}

sealed class FreeAccountSignupStep {
    data object Form : FreeAccountSignupStep()
    data object EnterCode : FreeAccountSignupStep()
    data object Done : FreeAccountSignupStep()
}

data class FreeAccountSignupUiState(
    val step: FreeAccountSignupStep = FreeAccountSignupStep.Form,
    val email: String = "",
    val displayName: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** True after a successful [FreeAccountSignupViewModel.onResend] — drives a confirmation line. */
    val resent: Boolean = false,
)
