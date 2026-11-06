package app.tenshu.control.ui.screens.unlock

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
 * Typed user-facing errors surfaced by [SetupUnlockViewModel] — per ADR 0016
 * anchor #4. Screen maps each to a localised string at render.
 */
sealed interface SetupUnlockError {
    data class PassphraseTooShort(val minLength: Int) : SetupUnlockError
    data object PassphrasesDoNotMatch : SetupUnlockError
    data object LocalUnlockSetupFailed : SetupUnlockError
    data object BiometricSetupFailed : SetupUnlockError
    data class BiometricEnrollFailed(val detail: String? = null) : SetupUnlockError
}

/**
 * Drives the post-pairing / post-recovery interstitial.
 *
 * **Headless by default** (`docs/proposals/control-keep-device-unlocked.md` §4.1):
 * the screen opens on the device-protection step and [onUseDeviceProtection] just
 * completes — the joiner's `JOINER_PAYLOAD` (or the recovered `RECOVERY_PHRASE`)
 * stays keystore-sealed and unwrapped, so `SyncManagerImpl.init` autoloads it on
 * every cold start and background sync survives a reboot with no app-level unlock.
 * §4.1 asked for this on *both* onboarding paths; genesis got it in Ph1.1 and this
 * one followed — until then the joiner still led with the lock.
 *
 * The passphrase/biometric wrapper is an expert opt-in behind
 * [onShowAdvancedUnlock], and it enforces the **passphrase floor**
 * (`docs/proposals/desktop-masterkeystore.md`): biometric is the everyday unlock,
 * but a recovery passphrase is always required underneath it. When biometric is
 * available that branch opens on a credential **choice** → biometric enrolls first
 * (enroll-first), then routes to passphrase entry; submit wraps *both* via the
 * atomic `setUpUnlock(passphrase, wrapKey)` so the floor can never be skipped.
 * Passphrase-only and no-biometric paths go straight to passphrase entry.
 */
class SetupUnlockViewModel(
    private val syncRepository: SyncRepository,
    private val biometricDelegate: BiometricUnlockDelegate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUnlockUiState())
    val uiState: StateFlow<SetupUnlockUiState> = _uiState.asStateFlow()

    init {
        // Probe biometric availability off the constructor — the desktop
        // delegate shells out to the keychain helper and must not block the UI.
        viewModelScope.launch {
            val available = biometricDelegate.isAvailable()
            _uiState.update { it.copy(biometricAvailable = available) }
        }
    }

    // The enrolled biometric wrap-key, held between enroll and the passphrase
    // submit that wraps it. Never put a secret in UiState.
    private var pendingWrapKey: ByteArray? = null

    fun onPassphraseChanged(value: String) {
        _uiState.update { it.copy(passphrase = value, error = null) }
    }

    fun onConfirmChanged(value: String) {
        _uiState.update { it.copy(confirm = value, error = null) }
    }

    /** Credential choice → biometric. Enrolls first, then routes to the recovery passphrase. */
    fun onChooseBiometric(host: BiometricPromptHost, promptCopy: BiometricPromptCopy) {
        if (_uiState.value.busy || _uiState.value.biometricBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(biometricBusy = true, error = null) }
            when (val enroll = biometricDelegate.enrollWrapKey(
                nextSecureRandomBytes(WRAP_KEY_BYTES).also { pendingWrapKey = it },
                host,
                promptCopy,
            )) {
                is EnrollResult.Success -> _uiState.update {
                    it.copy(biometricBusy = false, showPassphrase = true, biometricChosen = true)
                }

                is EnrollResult.CancelledByUser -> {
                    pendingWrapKey = null
                    _uiState.update { it.copy(biometricBusy = false) } // stay on choice
                }

                is EnrollResult.NotAvailable -> {
                    pendingWrapKey = null
                    _uiState.update {
                        it.copy(biometricBusy = false, biometricAvailable = false, showPassphrase = true)
                    }
                }

                is EnrollResult.Failure -> {
                    pendingWrapKey = null
                    _uiState.update {
                        it.copy(
                            biometricBusy = false,
                            error = SetupUnlockError.BiometricEnrollFailed(enroll.message),
                        )
                    }
                }
            }
        }
    }

    /** Credential choice → passphrase only (the floor is the credential here). */
    fun onChoosePassphrase() {
        _uiState.update { it.copy(showPassphrase = true, biometricChosen = false, error = null) }
    }

    /** Back from passphrase entry to the choice — discards a pending biometric enrollment. */
    fun onBack() {
        if (_uiState.value.biometricChosen) {
            biometricDelegate.clear()
            pendingWrapKey = null
        }
        _uiState.update {
            it.copy(
                showPassphrase = false,
                biometricChosen = false,
                passphrase = "",
                confirm = "",
                error = null,
            )
        }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.busy || state.biometricBusy) return
        if (state.passphrase.length < MIN_PASSPHRASE_LENGTH) {
            _uiState.update {
                it.copy(error = SetupUnlockError.PassphraseTooShort(MIN_PASSPHRASE_LENGTH))
            }
            return
        }
        if (state.passphrase != state.confirm) {
            _uiState.update { it.copy(error = SetupUnlockError.PassphrasesDoNotMatch) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            val wrapKey = pendingWrapKey
            val ok = syncRepository.setUpUnlock(state.passphrase, wrapKey)
                .getOrNull()?.get() == true
            if (ok) {
                pendingWrapKey = null
                _uiState.update { it.copy(busy = false, done = true, passphrase = "", confirm = "") }
            } else {
                // Proxy refused (no plaintext available). Discard a pending biometric
                // enrollment so we never strand a biometric wrapper without its floor.
                if (wrapKey != null) {
                    biometricDelegate.clear()
                    pendingWrapKey = null
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        biometricChosen = false,
                        error = if (wrapKey != null) SetupUnlockError.BiometricSetupFailed
                        else SetupUnlockError.LocalUnlockSetupFailed,
                    )
                }
            }
        }
    }

    /**
     * Device-protection default — complete without wrapping anything. Nothing to
     * do but leave: the payload was persisted unwrapped when the device joined (or
     * recovered), and only `setUpUnlock` would clear that plaintext slot.
     *
     * This is also the advanced branch's "skip", which is why it kept working
     * headlessly while the screen still led with the lock.
     */
    fun onUseDeviceProtection() {
        if (_uiState.value.busy || _uiState.value.biometricBusy) return
        _uiState.update { it.copy(done = true, error = null) }
    }

    /** Reveal the expert passphrase/biometric lock setup (advanced opt-in). */
    fun onShowAdvancedUnlock() {
        _uiState.update { it.copy(advancedUnlock = true, error = null) }
    }

    /**
     * Leave the expert lock setup and return to the device-protection default,
     * discarding any pending biometric enrollment and typed passphrase.
     */
    fun onExitAdvancedUnlock() {
        if (_uiState.value.biometricChosen) {
            biometricDelegate.clear()
            pendingWrapKey = null
        }
        _uiState.update {
            it.copy(
                advancedUnlock = false,
                showPassphrase = false,
                biometricChosen = false,
                passphrase = "",
                confirm = "",
                error = null,
            )
        }
    }

    private companion object {
        const val MIN_PASSPHRASE_LENGTH = 8
        const val WRAP_KEY_BYTES = 32
    }
}

data class SetupUnlockUiState(
    val passphrase: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val done: Boolean = false,
    val error: SetupUnlockError? = null,
    val biometricAvailable: Boolean = false,
    val biometricBusy: Boolean = false,
    /** True once a credential is chosen and the passphrase entry is shown. */
    val showPassphrase: Boolean = false,
    /** True when biometric was chosen → passphrase entry shows the recovery floor framing. */
    val biometricChosen: Boolean = false,
    /** True once the user opted into the expert passphrase/biometric lock. */
    val advancedUnlock: Boolean = false,
)
