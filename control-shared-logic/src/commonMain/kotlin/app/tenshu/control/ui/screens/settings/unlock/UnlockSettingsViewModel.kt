package app.tenshu.control.ui.screens.settings.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.getOrNull

/**
 * Typed user-facing errors surfaced by [UnlockSettingsViewModel] — per
 * ADR 0016 anchor #4. Screen maps each to a localised string at render.
 *
 * `BiometricEnrollFailed.detail` carries OS-supplied diagnostic copy
 * (already locale-resolved) when present; the screen falls back to a
 * generic message when null.
 */
sealed interface UnlockSettingsError {
    data object BiometricUnavailable : UnlockSettingsError
    data object EnableBiometricFailed : UnlockSettingsError
    data class BiometricEnrollFailed(val detail: String? = null) : UnlockSettingsError

    /**
     * The passphrase floor: biometric can only be enabled on top of an existing
     * recovery passphrase. Surfaced when the user tries to enable biometric with
     * no passphrase wrapper set (e.g. they skipped unlock setup at onboarding).
     */
    data object PassphraseRequired : UnlockSettingsError
}

/**
 * Backs the Settings → Unlock screen — the post-onboarding entry point
 * for enabling / disabling biometric unlock. This is the idiomatic
 * Android pattern: the app owns its own "use biometric to unlock" toggle
 * (banking apps, password managers, Signal all do this in-app); the
 * system Settings → Biometrics only controls OS-level enrollment.
 *
 * Enable path mirrors `SetupUnlockViewModel.onSetUpBiometric` — random
 * wrap-key → [BiometricUnlockDelegate.enrollWrapKey] → proxy
 * `setUpBiometricUnlock`. Trust model: this VM is only resolvable from
 * an unlocked session (Settings is gated behind the LockedScreen), so
 * the recovery phrase is in memory and `setUpBiometricUnlock` finds it.
 * No re-authentication is required to flip the toggle — same UX
 * pattern as Touch ID toggles on iOS.
 *
 * Disable path: [BiometricUnlockDelegate.clear] +
 * `SyncRepository.clearBiometricSetup` (both idempotent). Both sides
 * cleared so future LockedScreen visits don't reach
 * [app.tenshu.control.data.UnwrapResult.KeyInvalidated].
 */
class UnlockSettingsViewModel(
    private val syncRepository: SyncRepository,
    private val biometricDelegate: BiometricUnlockDelegate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockSettingsUiState())
    val uiState: StateFlow<UnlockSettingsUiState> = _uiState.asStateFlow()

    init {
        // Probe biometric state off the constructor — the desktop delegate
        // shells out to the keychain helper, which must not block the UI thread.
        viewModelScope.launch {
            val available = biometricDelegate.isAvailable()
            val enabled = biometricDelegate.hasEnrolled()
            _uiState.update { it.copy(biometricAvailable = available, biometricEnabled = enabled) }
        }
    }

    /**
     * Toggle the biometric switch.
     *
     * Enable: caller must supply a non-null [host]. If [host] is null
     * (e.g. desktop / non-FragmentActivity Android tree) we surface a
     * "not available" error and leave the toggle off.
     *
     * Disable: [host] is ignored — no biometric prompt for tearing
     * down a key the user already proved access to.
     */
    fun onToggle(
        enabled: Boolean,
        host: BiometricPromptHost?,
        promptCopy: BiometricPromptCopy,
    ) {
        if (_uiState.value.busy) return
        if (enabled) enable(host, promptCopy) else disable()
    }

    private fun enable(host: BiometricPromptHost?, promptCopy: BiometricPromptCopy) {
        if (!_uiState.value.biometricAvailable) return
        if (host == null) {
            _uiState.update {
                it.copy(error = UnlockSettingsError.BiometricUnavailable)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }

            // Passphrase floor: refuse biometric unless a recovery passphrase
            // already exists. The proxy's setUpBiometricUnlock backstops this too,
            // but checking up front lets us skip the Touch ID prompt + give a
            // clear "set a passphrase first" message rather than a generic failure.
            val hasPassphrase = syncRepository.getUnlockStatus()
                .getOrNull()?.hasPassphrase?.get() == true
            if (!hasPassphrase) {
                _uiState.update {
                    it.copy(busy = false, error = UnlockSettingsError.PassphraseRequired)
                }
                return@launch
            }

            val wrapKey = nextSecureRandomBytes(WRAP_KEY_BYTES)
            when (val outcome =
                biometricDelegate.enrollWrapKey(wrapKey, host, promptCopy)
            ) {
                is EnrollResult.Success -> {
                    val ok = syncRepository.setUpBiometricUnlock(wrapKey)
                        .getOrNull()?.get() == true
                    if (ok) {
                        _uiState.update {
                            it.copy(busy = false, biometricEnabled = true)
                        }
                    } else {
                        // Proxy refused — usually means no recovery phrase
                        // is in memory (the proxy locked while the user
                        // was in Settings, edge case). Roll back the
                        // delegate so on-device + proxy stay in sync.
                        biometricDelegate.clear()
                        _uiState.update {
                            it.copy(
                                busy = false,
                                biometricEnabled = false,
                                error = UnlockSettingsError.EnableBiometricFailed,
                            )
                        }
                    }
                }

                is EnrollResult.CancelledByUser -> {
                    _uiState.update { it.copy(busy = false) }
                }

                is EnrollResult.NotAvailable -> {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            biometricAvailable = false,
                            error = UnlockSettingsError.BiometricUnavailable,
                        )
                    }
                }

                is EnrollResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            error = UnlockSettingsError.BiometricEnrollFailed(outcome.message),
                        )
                    }
                }
            }
        }
    }

    private fun disable() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            biometricDelegate.clear()
            syncRepository.clearBiometricSetup()
            _uiState.update { it.copy(busy = false, biometricEnabled = false) }
        }
    }

    /**
     * UI dismisses the error banner. Idempotent.
     */
    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private companion object {
        const val WRAP_KEY_BYTES = 32
    }
}

data class UnlockSettingsUiState(
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val busy: Boolean = false,
    val error: UnlockSettingsError? = null,
)
