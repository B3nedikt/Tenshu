package app.tenshu.control.ui.screens.newuser

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
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.getPlatformDeviceName
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.getOrNull

/**
 * Typed user-facing errors surfaced by [NewUserViewModel] — per ADR 0016
 * anchor #4. The screen maps each to a localised string at render. ICU
 * params (e.g. `minLength`) travel as data so the catalogue carries the
 * interpolation, not the VM.
 *
 * `BiometricEnrollFailed.detail` carries OS-supplied diagnostic copy
 * (already locale-resolved) when present; the screen falls back to a
 * generic message when null.
 */
sealed interface NewUserError {
    data class PassphraseTooShort(val minLength: Int) : NewUserError
    data object PassphrasesDoNotMatch : NewUserError
    data object LocalUnlockSetupFailed : NewUserError
    data object BiometricFinalizeFailed : NewUserError
    data class BiometricEnrollFailed(val detail: String? = null) : NewUserError
    data object RealmBootstrapFailed : NewUserError
}

/**
 * Drives the redesigned new-user flow per
 * `docs/proposals/onboarding-low-friction.md` §4. The user picks "Start
 * fresh on this device" on the Welcome screen and lands here; the screen
 * sequence is:
 *
 *  1. [NewUserStep.SetupUnlock] — device-protection default per
 *     `docs/proposals/control-keep-device-unlocked.md` §4.1. The primary
 *     CTA ([onUseDeviceProtection]) creates the realm **headless**: the
 *     recovery phrase stays keystore-sealed in SecureStorage, so the realm
 *     autoloads on every cold start and background sync survives a reboot
 *     with no app-level unlock. An expert opt-in ([onShowAdvancedUnlock])
 *     reveals the passphrase/biometric lock (the old forced floor, now
 *     optional). Phrase generation is silent and lazy: a fresh BIP-39
 *     mnemonic is minted on submit, never shown.
 *  2. [NewUserStep.Bootstrapping] — registers the realm and wraps the
 *     plaintext phrase under the chosen credential.
 *  3. [NewUserStep.YoureSetUp] — terminal "You're set up" page (§4.2) with
 *     the three onward CTAs (Open Drive / Add another device / Set up
 *     account recovery). The screen owns the routing dispatch through three
 *     onComplete callbacks.
 *
 * The previous Intro / ShowPhrase / ConfirmPhrase / Manifest steps are
 * gone from the first-run path. The phrase-display + confirmation screens
 * remain reachable from Settings → "Set up account recovery" (§7.4) for
 * users who want belt-and-braces backup.
 *
 * Threat-model note: per House Rule #2, this change is accompanied by an
 * amendment in `docs/sync/threat-model.md` covering the residual loss
 * window (T-OB-01) and the fact that the phrase is persisted in
 * SecureStorage rather than re-derived from the master key (which is
 * cryptographically one-way per `core-common`'s `Bip39` + Argon2id stack).
 */
class NewUserViewModel(
    private val syncRepository: SyncRepository,
    private val biometricDelegate: BiometricUnlockDelegate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewUserUiState())
    val uiState: StateFlow<NewUserUiState> = _uiState.asStateFlow()

    init {
        // Probe biometric availability off the constructor — the desktop
        // delegate shells out to the keychain helper and must not block the UI.
        viewModelScope.launch {
            val available = biometricDelegate.isAvailable()
            _uiState.update { it.copy(biometricAvailable = available) }
        }
    }

    // Enrolled biometric wrap-key, held between enroll and the submit that wraps
    // it (alongside the passphrase floor). Never put a secret in UiState.
    private var pendingWrapKey: ByteArray? = null

    fun onUnlockPassphraseChanged(value: String) {
        _uiState.update { it.copy(unlockPassphrase = value, error = null) }
    }

    fun onUnlockPassphraseConfirmChanged(value: String) {
        _uiState.update { it.copy(unlockPassphraseConfirm = value, error = null) }
    }

    /**
     * Credential choice → biometric. Enrolls the wrap-key first (no realm needed
     * yet), then routes to the recovery passphrase. The realm + both wrappers are
     * created together at [onUnlockSetupSubmit] so the passphrase floor is atomic.
     */
    fun onChooseBiometric(host: BiometricPromptHost, promptCopy: BiometricPromptCopy) {
        if (_uiState.value.unlockSetupBusy || _uiState.value.biometricBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(biometricBusy = true, error = null) }
            when (val enroll = biometricDelegate.enrollWrapKey(
                nextSecureRandomBytes(WRAP_KEY_BYTES).also { pendingWrapKey = it },
                host,
                promptCopy,
            )) {
                is EnrollResult.Success -> _uiState.update {
                    it.copy(biometricBusy = false, passphraseExpanded = true, biometricChosen = true)
                }

                is EnrollResult.CancelledByUser -> {
                    pendingWrapKey = null
                    _uiState.update { it.copy(biometricBusy = false) } // stay on choice
                }

                is EnrollResult.NotAvailable -> {
                    pendingWrapKey = null
                    _uiState.update {
                        it.copy(biometricBusy = false, biometricAvailable = false, passphraseExpanded = true)
                    }
                }

                is EnrollResult.Failure -> {
                    pendingWrapKey = null
                    _uiState.update {
                        it.copy(biometricBusy = false, error = NewUserError.BiometricEnrollFailed(enroll.message))
                    }
                }
            }
        }
    }

    /** Credential choice → passphrase only (the passphrase is the credential here). */
    fun onChoosePassphrase() {
        _uiState.update { it.copy(passphraseExpanded = true, biometricChosen = false, error = null) }
    }

    /** Back from passphrase entry to the credential choice — discards a pending biometric enrollment. */
    fun onBack() {
        if (_uiState.value.biometricChosen) {
            biometricDelegate.clear()
            pendingWrapKey = null
        }
        _uiState.update {
            it.copy(
                passphraseExpanded = false,
                biometricChosen = false,
                unlockPassphrase = "",
                unlockPassphraseConfirm = "",
                error = null,
            )
        }
    }

    /**
     * Headless default (`docs/proposals/control-keep-device-unlocked.md` §4.1):
     * create the realm and leave the recovery phrase **keystore-sealed** in
     * SecureStorage — *no* passphrase floor. Because nothing wraps + clears the
     * plaintext slot, `SyncManagerImpl.init` autoloads it on every cold start, so
     * the realm boots enabled and background sync survives a reboot without any
     * app-level unlock. The passphrase/biometric lock is opt-in via
     * [onShowAdvancedUnlock] → [onUnlockSetupSubmit].
     */
    fun onUseDeviceProtection() {
        val state = _uiState.value
        if (state.unlockSetupBusy || state.biometricBusy) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(step = NewUserStep.Bootstrapping, unlockSetupBusy = true, error = null)
            }
            val phrase = Bip39.generateMnemonic()
            if (!createRealm(phrase)) return@launch
            _uiState.update {
                it.copy(step = NewUserStep.YoureSetUp, unlockSetupBusy = false, error = null)
            }
        }
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
                passphraseExpanded = false,
                biometricChosen = false,
                unlockPassphrase = "",
                unlockPassphraseConfirm = "",
                error = null,
            )
        }
    }

    fun onUnlockSetupSubmit() {
        val state = _uiState.value
        if (state.unlockSetupBusy || state.biometricBusy) return
        val pass = state.unlockPassphrase
        if (pass.length < MIN_PASSPHRASE_LENGTH) {
            _uiState.update {
                it.copy(error = NewUserError.PassphraseTooShort(MIN_PASSPHRASE_LENGTH))
            }
            return
        }
        if (pass != state.unlockPassphraseConfirm) {
            _uiState.update { it.copy(error = NewUserError.PassphrasesDoNotMatch) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(step = NewUserStep.Bootstrapping, unlockSetupBusy = true, error = null)
            }

            // Lazily mint the realm now, then wrap its plaintext under the passphrase
            // (always) + the pending biometric key (if any) in one atomic op.
            val phrase = Bip39.generateMnemonic()
            if (!createRealm(phrase)) return@launch

            val wrapKey = pendingWrapKey
            val ok = syncRepository.setUpUnlock(pass, wrapKey).getOrNull()?.get() == true
            if (!ok) {
                if (wrapKey != null) {
                    biometricDelegate.clear()
                    pendingWrapKey = null
                }
                _uiState.update {
                    it.copy(
                        step = NewUserStep.SetupUnlock,
                        unlockSetupBusy = false,
                        biometricChosen = false,
                        error = if (wrapKey != null) NewUserError.BiometricFinalizeFailed
                        else NewUserError.LocalUnlockSetupFailed,
                    )
                }
                return@launch
            }

            pendingWrapKey = null
            _uiState.update {
                it.copy(
                    step = NewUserStep.YoureSetUp,
                    unlockSetupBusy = false,
                    unlockPassphrase = "",
                    unlockPassphraseConfirm = "",
                    error = null,
                )
            }
        }
    }

    /**
     * Bootstraps the realm; on failure, reverts the step to SetupUnlock
     * with the error and clears the busy field. Returns true on
     * success so the calling orchestrator can continue.
     */
    private suspend fun createRealm(phrase: String): Boolean {
        val createResult = syncRepository.createRealm(
            recoveryPhrase = phrase,
            deviceName = getPlatformDeviceName(),
        )
        val createError = (createResult as? org.tenshu.core.TResult.Failure)?.error
        if (createError != null) {
            _uiState.update {
                it.copy(
                    step = NewUserStep.SetupUnlock,
                    passphraseExpanded = true,
                    unlockSetupBusy = false,
                    error = NewUserError.RealmBootstrapFailed,
                )
            }
            return false
        }
        return true
    }

    fun onOpenDrive() {
        _uiState.update { it.copy(exitTo = NewUserExit.OpenDrive, step = NewUserStep.Done) }
    }

    private companion object {
        const val MIN_PASSPHRASE_LENGTH = 8
        const val WRAP_KEY_BYTES = 32
    }
}

sealed class NewUserStep {
    /** Set up the local-unlock credential per §4.1. Entry point of the redesigned flow. */
    data object SetupUnlock : NewUserStep()

    /** Realm bootstrap + unlock-wrapper setup, invisible spinner. */
    data object Bootstrapping : NewUserStep()

    /** §4.2 — "You're set up" with a single Continue CTA into Home. */
    data object YoureSetUp : NewUserStep()

    /** Terminal state — the screen signals completion via [NewUserUiState.exitTo]. */
    data object Done : NewUserStep()
}

enum class NewUserExit {
    OpenDrive,
}

data class NewUserUiState(
    val step: NewUserStep = NewUserStep.SetupUnlock,
    val unlockPassphrase: String = "",
    val unlockPassphraseConfirm: String = "",
    val unlockSetupBusy: Boolean = false,
    val exitTo: NewUserExit? = null,
    val error: NewUserError? = null,
    val biometricAvailable: Boolean = false,
    val biometricBusy: Boolean = false,
    val passphraseExpanded: Boolean = false,
    /** True when biometric was chosen → passphrase entry shows the recovery floor framing. */
    val biometricChosen: Boolean = false,
    /**
     * True once the user opts into the expert passphrase/biometric lock from the
     * device-protection default. Drives whether the SetupUnlock step shows the
     * headless intro or the credential-setup flow.
     */
    val advancedUnlock: Boolean = false,
)
