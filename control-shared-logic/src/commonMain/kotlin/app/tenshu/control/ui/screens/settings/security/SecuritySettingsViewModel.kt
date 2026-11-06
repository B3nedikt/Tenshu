package app.tenshu.control.ui.screens.settings.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.AppLockAvailability
import app.tenshu.control.data.AppLockSettings
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.confirm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.getOrNull

/**
 * Backs **Settings → Advanced → Security**
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2 / §4.4).
 *
 * Two rows:
 *  - **"Require unlock"** — the soft app-lock (Ph1). A device-local
 *    [AppLockSettings] toggle, live, default on, only enforced when
 *    [AppLockAvailability.isAvailable] (a secure lock screen exists). It gates
 *    *viewing* Control's UI, never keys — background sync is unaffected.
 *  - **"Encrypt on unlock only"** — the hard key gate (Ph-hard). A **child** of
 *    "Require unlock": enabling it wraps the recovery phrase behind a passphrase
 *    (via `setUpUnlock`, driven by the reused SetupUnlock screen — the screen owns
 *    that navigation), so a locked cold start seals the realm and background sync
 *    pauses until the next unlock. Disabling it calls [onDisableEncryptOnUnlock]
 *    → `relaxToHeadless()` + clears the biometric key, returning A1 to the headless
 *    default. Reflected from `getUnlockStatus().isSetUp` (a wrapper exists).
 */
class SecuritySettingsViewModel(
    private val appLockSettings: AppLockSettings,
    private val availability: AppLockAvailability,
    private val syncRepository: SyncRepository,
    private val biometricDelegate: BiometricUnlockDelegate,
    private val reauthenticator: Reauthenticator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SecuritySettingsUiState(requireUnlock = appLockSettings.requireUnlock.value),
    )
    val uiState: StateFlow<SecuritySettingsUiState> = _uiState.asStateFlow()

    init {
        // Probe availability off the constructor — BiometricManager lookups are
        // cheap, but keep the pattern uniform with the other unlock VMs.
        viewModelScope.launch {
            val available = availability.isAvailable()
            _uiState.update { it.copy(available = available) }
        }
        refreshEncryptOnUnlock()
    }

    /**
     * Re-reads whether a persisted unlock wrapper exists (hard mode on). Called on
     * init and whenever the screen resumes — e.g. after returning from the
     * SetupUnlock flow that enables hard mode.
     */
    fun refreshEncryptOnUnlock() {
        viewModelScope.launch {
            val isSetUp = syncRepository.getUnlockStatus().getOrNull()?.isSetUp?.get() == true
            _uiState.update { it.copy(encryptOnUnlock = isSetUp) }
        }
    }

    fun onRequireUnlockChanged(enabled: Boolean) {
        if (!_uiState.value.available) return
        appLockSettings.setRequireUnlock(enabled)
        _uiState.update { it.copy(requireUnlock = enabled) }
    }

    /**
     * Disable hard mode: re-persist the plaintext + drop the wrappers
     * (`relaxToHeadless`) and clear the biometric Keystore key. Enabling is the
     * screen's job (it navigates to the SetupUnlock passphrase flow), so there's
     * only a disable path here.
     *
     * Ph-reauth (`control-keep-device-unlocked.md` §6.1, RA-4): gated on a presence check, because
     * this is the one control on the screen that **lowers** the at-rest posture — it removes the
     * passphrase wrapper from A1 and hands the key back to the OS keystore alone. ⚠ Enabling is
     * deliberately **not** gated: it is a step up, and the passphrase the user must set *is* the
     * proof.
     *
     * ⚠ Sequenced VM-side (the host is a parameter, matching `UnlockSettingsViewModel.onToggle`)
     * rather than in the composable, so the gate is unit-testable — this is a key-material change,
     * and a guard only a UI test can see is a guard that can be dropped by a refactor.
     */
    fun onDisableEncryptOnUnlock(host: BiometricPromptHost?, promptCopy: ReauthPromptCopy) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            // busy BEFORE the prompt, so a second tap cannot stack a second system sheet.
            _uiState.update { it.copy(busy = true) }
            if (!reauthenticator.confirm(host, promptCopy)) {
                _uiState.update { it.copy(busy = false) }
                return@launch
            }
            val ok = syncRepository.relaxToHeadless().getOrNull()?.get() == true
            if (ok) biometricDelegate.clear()
            val isSetUp = syncRepository.getUnlockStatus().getOrNull()?.isSetUp?.get() == true
            _uiState.update { it.copy(busy = false, encryptOnUnlock = isSetUp) }
        }
    }
}

data class SecuritySettingsUiState(
    /** The device-local "Require unlock" choice. */
    val requireUnlock: Boolean = true,
    /**
     * Whether the soft app-lock can be enforced on this device (a biometric or
     * device-credential prompt is possible). When false the toggle is disabled.
     */
    val available: Boolean = false,
    /** Whether "Encrypt on unlock only" (hard mode) is currently on — a wrapper exists. */
    val encryptOnUnlock: Boolean = false,
    val busy: Boolean = false,
)
