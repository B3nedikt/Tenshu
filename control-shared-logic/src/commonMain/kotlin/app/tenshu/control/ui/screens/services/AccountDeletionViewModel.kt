package app.tenshu.control.ui.screens.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.PublisherClient.AccountDeletionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The in-app **Delete account** flow (`commerce-accounts-and-sessions.md` §9.7; Play requires one in
 * the app as well as on the web).
 *
 * Three steps: [AccountDeletionStep.Explain] (what is and is not deleted, then "Send code") →
 * [AccountDeletionStep.EnterCode] (the mailed code, an explicit "I understand" tick, the destructive
 * button) → [AccountDeletionStep.Deleted].
 *
 * ⚠⚠ The code is not ceremony. The device's session would be enough to *identify* the account, but
 * deletion is irreversible and a session lives on a device anyone holding it can tap: the service
 * demands a fresh code mailed to the account's address, so the person deleting has to hold the
 * inbox. The UI therefore cannot shortcut to a one-tap delete, by design.
 *
 * ⚠ The tick gates the button in the VM as well as on screen ([onConfirm] refuses without it), so a
 * screen that forgot to disable the button still cannot delete unacknowledged.
 */
class AccountDeletionViewModel(
    private val publisherClient: PublisherClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AccountDeletionUiState(
            email = (publisherClient.account.value?.identityMethod as? PublisherAccount.IdentityMethod.Email)?.address,
        ),
    )
    val uiState: StateFlow<AccountDeletionUiState> = _uiState.asStateFlow()

    fun onSendCode() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            val result = publisherClient.requestAccountDeletion()
            _uiState.update { state ->
                when (result) {
                    AccountDeletionResult.CodeSent ->
                        state.copy(busy = false, step = AccountDeletionStep.EnterCode, code = "", resent = state.step == AccountDeletionStep.EnterCode)
                    else -> state.copy(busy = false, error = result.toError())
                }
            }
        }
    }

    fun onCodeChange(value: String) {
        _uiState.update { it.copy(code = value.filter(Char::isDigit).take(CODE_LENGTH), error = null) }
    }

    fun onAcknowledgedChange(value: Boolean) {
        _uiState.update { it.copy(acknowledged = value) }
    }

    fun onConfirm() {
        val state = _uiState.value
        if (!state.canConfirm) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            val result = publisherClient.confirmAccountDeletion(state.code)
            _uiState.update {
                when (result) {
                    AccountDeletionResult.Deleted -> it.copy(busy = false, step = AccountDeletionStep.Deleted, code = "")
                    else -> it.copy(busy = false, error = result.toError())
                }
            }
        }
    }

    private fun AccountDeletionResult.toError(): AccountDeletionError = when (this) {
        AccountDeletionResult.InvalidCode -> AccountDeletionError.InvalidCode
        AccountDeletionResult.SignInRequired -> AccountDeletionError.SignInRequired
        AccountDeletionResult.SignedOut -> AccountDeletionError.SignedOut
        AccountDeletionResult.Unavailable -> AccountDeletionError.Unavailable
        is AccountDeletionResult.Error -> AccountDeletionError.Message(message)
        // Not errors; unreachable from the call sites above, mapped rather than thrown.
        AccountDeletionResult.CodeSent, AccountDeletionResult.Deleted -> AccountDeletionError.Unavailable
    }

    companion object {
        const val CODE_LENGTH = 6
    }
}

enum class AccountDeletionStep { Explain, EnterCode, Deleted }

/** What went wrong, as a kind — the screen owns the (translated) copy for every kind but [Message]. */
sealed interface AccountDeletionError {
    data object InvalidCode : AccountDeletionError
    data object SignInRequired : AccountDeletionError

    /** The session died on commerce — most likely because the deletion already went through. */
    data object SignedOut : AccountDeletionError
    data object Unavailable : AccountDeletionError

    /** The client's own, already user-facing text (unreachable, rate limited, mail failed). */
    data class Message(val text: String) : AccountDeletionError
}

data class AccountDeletionUiState(
    /** The signed-in address the code goes to, for the "sent to …" line; null if unknown. */
    val email: String? = null,
    val step: AccountDeletionStep = AccountDeletionStep.Explain,
    val code: String = "",
    val acknowledged: Boolean = false,
    val busy: Boolean = false,
    /** True after "Send a new code" succeeded, so the screen can say a new one is on its way. */
    val resent: Boolean = false,
    val error: AccountDeletionError? = null,
) {
    val canConfirm: Boolean
        get() = step == AccountDeletionStep.EnterCode && !busy && acknowledged &&
            code.length == AccountDeletionViewModel.CODE_LENGTH
}
