package app.tenshu.control.ui.screens.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.getOrNull

/**
 * §6.4 disconnect — destructive action that drops the local Publisher binding.
 *
 * The confirmation factor is **whatever this device actually has**, resolved once on open from
 * [SyncRepository.getUnlockStatus]:
 *
 * - a local-unlock passphrase is configured → re-enter it (verified via [SyncRepository.unlockLocal]);
 * - nothing is configured → an explicit confirm, with no field to fill.
 *
 * ⚠⚠ The second branch is the bug this replaces, not a relaxation. `unlockLocal` opens with
 * `getSecret(Keys.UNLOCK_WRAPPER) ?: return false`, and on desktop Control auto-unlocks from the
 * login Keychain, so a local passphrase is **optional** and most desktop users have no wrapper.
 * The screen nevertheless rendered the field unconditionally, so for those users every input
 * returned "Passphrase doesn't match" and disconnect was **unreachable** — an action the product
 * offers but no one without a passphrase can perform. Asking for a factor that cannot exist is
 * not a security gate; it is a dead end that also hides the fact that it is one.
 *
 * ⚠ Where there is no passphrase the device's own session is the factor: Control is already
 * unlocked and holding realm keys, which is precisely why no further proof is obtainable here.
 * The confirm step stays (this is destructive and the warning has to be read), it just stops
 * pretending to verify something.
 *
 * ⚠ Biometric is deliberately NOT wired here yet. The proposal wanted *confirm-by-biometric* and
 * `BiometricUnlockDelegate` now exists on both desktop and Android — but the desktop Touch ID
 * helper only embeds under `-Ptenshu.withKeychainHelper`, so `isAvailable()` is false on an
 * ordinary build and it would not have fixed the reachability defect for the reporting user.
 * Adding it is a strict improvement on top of this, not a substitute for it (`tech-debt.md`
 * §"Disconnect from Tenshu Storage is unreachable").
 */
class DisconnectConfirmViewModel(
    private val publisherClient: PublisherClient,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DisconnectConfirmUiState())
    val uiState: StateFlow<DisconnectConfirmUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // ⚠ Fails CLOSED: if the status can't be read we keep asking for a passphrase rather
            // than silently dropping the gate. An unreadable status is not evidence that no
            // passphrase exists, and the "no factor" branch must be a known fact, never a default.
            val hasPassphrase = syncRepository.getUnlockStatus().getOrNull()
                ?.hasPassphrase?.get() ?: true
            _uiState.update { it.copy(requiresPassphrase = hasPassphrase, resolving = false) }
        }
    }

    fun onPassphraseChange(value: String) {
        _uiState.update { it.copy(passphrase = value, error = null) }
    }

    fun onConfirm() {
        val state = _uiState.value
        if (state.busy || state.resolving) return
        if (state.requiresPassphrase && state.passphrase.isBlank()) {
            _uiState.update { it.copy(error = "Enter your passphrase to confirm.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            if (state.requiresPassphrase) {
                val unlocked = syncRepository.unlockLocal(state.passphrase).getOrNull()?.get() == true
                if (!unlocked) {
                    _uiState.update { it.copy(busy = false, error = "Passphrase doesn't match.") }
                    return@launch
                }
            }
            publisherClient.disconnect()
            _uiState.update { it.copy(busy = false, disconnected = true, passphrase = "") }
        }
    }
}

data class DisconnectConfirmUiState(
    val passphrase: String = "",
    /**
     * Whether this device has a local-unlock passphrase to re-enter. Starts `true` so the field is
     * never *briefly* hidden on a slow status read — the stricter state is the safe one to show
     * while [resolving].
     */
    val requiresPassphrase: Boolean = true,
    /** True until [SyncRepository.getUnlockStatus] answers; confirm is inert meanwhile. */
    val resolving: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val disconnected: Boolean = false,
)
