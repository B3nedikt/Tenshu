package app.tenshu.control.ui.screens.locked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.UnwrapResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.sync.RecoveryPhraseUnreadableError

/**
 * Typed user-facing errors surfaced by [LockedViewModel] — per ADR 0016
 * anchor #4, error state crosses the VM ↔ Composable boundary as typed
 * codes; the screen maps each to a localised string at render.
 *
 * `BiometricUnlockFailed.detail` carries OS-supplied diagnostic copy
 * (already locale-resolved by the platform) when available; the screen
 * falls back to a generic message when it's null.
 */
sealed interface LockedError {
    data object PassphraseInvalid : LockedError
    data class BiometricUnlockFailed(val detail: String? = null) : LockedError
    data object BiometricEnrollmentChanged : LockedError

    /**
     * The credential was right; this device's own stored recovery phrase no longer decodes.
     *
     * ⚠⚠ Kept apart from [PassphraseInvalid] because the advice is the opposite. "Try again" is
     * advice the user can win at — a wrong passphrase. This one cannot be retried into working,
     * so rendering it as a bad passphrase sends them to retype a correct one for ever. The proxy
     * distinguishes the two (`RecoveryPhraseUnreadableError`); this is the half that carries the
     * distinction to the screen, and without it the typed error is raised and then discarded.
     */
    data object RecoveryPhraseUnreadable : LockedError
}

/**
 * Drives the §3 returning-user unlock screen per
 * `docs/proposals/control-onboarding-ux.md`. On Android with biometric
 * available + enrolled, biometric is the primary path (auto-triggered
 * on screen entry); passphrase is the fallback. On desktop the
 * biometric delegate reports `isAvailable=false` and the screen stays
 * passphrase-only.
 */
class LockedViewModel(
    private val syncRepository: SyncRepository,
    private val biometricDelegate: BiometricUnlockDelegate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockedUiState())
    val uiState: StateFlow<LockedUiState> = _uiState.asStateFlow()

    init {
        // Probe biometric availability off the constructor: the desktop delegate
        // shells out to the keychain helper, and doing that synchronously here
        // stalled the UI thread while the locked screen composed. `initializing`
        // gates the screen on the probe so it doesn't flash the passphrase
        // fallback before biometric resolves.
        viewModelScope.launch {
            val available = biometricDelegate.isAvailable()
            val enrolled = biometricDelegate.hasEnrolled()
            _uiState.update {
                it.copy(
                    biometricAvailable = available,
                    biometricEnrolled = enrolled,
                    initializing = false,
                )
            }
        }
    }

    fun onPassphraseChanged(value: String) {
        _uiState.update { it.copy(passphrase = value, error = null) }
    }

    fun onUnlock() {
        val passphrase = _uiState.value.passphrase
        if (passphrase.isEmpty() || _uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = syncRepository.unlockLocal(passphrase)
            val ok = result.getOrNull()?.get() == true
            val unreadable = result.isUnreadablePhrase()
            _uiState.update {
                when {
                    ok -> it.copy(
                        isLoading = false,
                        unlocked = true,
                        passphrase = "",
                        failureCount = 0,
                    )
                    // ⚠ `failureCount` deliberately does not move: it drives the
                    // attempt limit, and this was not a failed attempt at the
                    // passphrase — it was a correct one over a phrase that cannot be
                    // read. Counting it would lock the user out over a condition no
                    // number of correct attempts can clear.
                    unreadable -> it.copy(
                        isLoading = false,
                        error = LockedError.RecoveryPhraseUnreadable,
                    )
                    else -> it.copy(
                        isLoading = false,
                        error = LockedError.PassphraseInvalid,
                        failureCount = it.failureCount + 1,
                    )
                }
            }
        }
    }

    /**
     * Reveals the passphrase field. Called either by the user tapping
     * the "Use passphrase instead" link, or implicitly when the
     * biometric path returns a non-success outcome.
     */
    fun onUsePassphrase() {
        _uiState.update { it.copy(passphraseExpanded = true, error = null) }
    }

    /**
     * Returns to the biometric path after the passphrase field was revealed — the inverse of
     * [onUsePassphrase].
     *
     * Needed because [passphraseExpanded] is a one-way latch in practice: a **cancelled** prompt
     * (`UnwrapResult.CancelledByUser`) sets it with no error copy, and cancelling is easy to do by
     * accident — backgrounding the app while the sheet is up is enough. Without a way back, the
     * session was stuck on "enter your unlock passphrase" with the fingerprint reader silently
     * unavailable, and killing Control was the only recovery.
     *
     * Clears [error] too: whatever the previous outcome was, the user is explicitly retrying, so
     * stale failure copy must not sit above a fresh prompt. The screen re-arms its auto-prompt off
     * `passphraseExpanded == false`, so this alone re-triggers biometric — but only when the device
     * can actually satisfy it, which is why the affordance is gated on
     * [LockedUiState.biometricAvailable] + [LockedUiState.biometricEnrolled] at the call site.
     */
    fun onUseBiometric() {
        _uiState.update { it.copy(passphraseExpanded = false, error = null, passphrase = "") }
    }

    /**
     * Drives the biometric path: prompt → wrap-key → proxy unlock. The
     * caller (the screen) supplies [host] from
     * `rememberBiometricPromptHost()` and only invokes this when both
     * [LockedUiState.biometricAvailable] and
     * [LockedUiState.biometricEnrolled] are true.
     *
     * - Success → [LockedUiState.unlocked] flips true.
     * - CancelledByUser → reveal passphrase (no error copy — the user
     *   chose).
     * - KeyInvalidated → wipe both on-device + proxy biometric blobs;
     *   reveal passphrase with re-enrollment nudge.
     * - NotAvailable / Failure → reveal passphrase with the diagnostic
     *   message.
     */
    fun onUnlockBiometric(host: BiometricPromptHost, promptCopy: BiometricPromptCopy) {
        // Claim the in-flight slot SYNCHRONOUSLY (not inside the launched
        // coroutine) so two calls in the same frame can't both pass the guard
        // and each spawn a biometric prompt. getAndUpdate returns the prior
        // value; bail if it was already loading/in-flight.
        val prior = _uiState.getAndUpdate {
            if (it.isLoading || it.biometricInFlight) it
            else it.copy(isLoading = true, error = null, biometricInFlight = true)
        }
        if (prior.isLoading || prior.biometricInFlight) return
        viewModelScope.launch {
            when (val outcome = biometricDelegate.unwrapWrapKey(host, promptCopy)) {
                is UnwrapResult.Success -> {
                    val result = syncRepository.unlockBiometric(outcome.wrapKey)
                    val ok = result.getOrNull()?.get() == true
                    // ⚠⚠ Checked BEFORE the else-branch below, which clears enrollment.
                    // An unreadable stored phrase is not a wrap-key problem, so dropping
                    // the biometric blob over it destroys a working credential and the
                    // re-enrollment it invites cannot succeed either — the phrase is what
                    // is unreadable, not the key.
                    if (!ok && result.isUnreadablePhrase()) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                biometricInFlight = false,
                                passphraseExpanded = true,
                                error = LockedError.RecoveryPhraseUnreadable,
                            )
                        }
                        return@launch
                    }
                    _uiState.update {
                        if (ok) {
                            it.copy(
                                isLoading = false,
                                biometricInFlight = false,
                                unlocked = true,
                                passphrase = "",
                                failureCount = 0,
                            )
                        } else {
                            // The proxy rejected the wrap-key (wrapper
                            // missing or mismatched). Drop the
                            // on-device blob + proxy biometric wrapper
                            // so the next attempt can re-enroll from
                            // passphrase.
                            biometricDelegate.clear()
                            syncRepository.clearBiometricSetup()
                            it.copy(
                                isLoading = false,
                                biometricInFlight = false,
                                biometricEnrolled = false,
                                passphraseExpanded = true,
                                error = LockedError.BiometricUnlockFailed(),
                            )
                        }
                    }
                }

                is UnwrapResult.CancelledByUser -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            biometricInFlight = false,
                            passphraseExpanded = true,
                            error = null,
                        )
                    }
                }

                is UnwrapResult.KeyInvalidated -> {
                    biometricDelegate.clear()
                    syncRepository.clearBiometricSetup()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            biometricInFlight = false,
                            biometricEnrolled = false,
                            passphraseExpanded = true,
                            error = LockedError.BiometricEnrollmentChanged,
                        )
                    }
                }

                is UnwrapResult.NotAvailable -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            biometricInFlight = false,
                            biometricEnrolled = false,
                            passphraseExpanded = true,
                            error = null,
                        )
                    }
                }

                is UnwrapResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            biometricInFlight = false,
                            passphraseExpanded = true,
                            error = LockedError.BiometricUnlockFailed(outcome.message),
                        )
                    }
                }
            }
        }
    }

    /**
     * Whether an unlock failed because this device's stored phrase cannot be decoded, as opposed
     * to a wrong credential.
     *
     * ⚠ Matched by schema id rather than by type, following `RealmDataClient`'s `NotFoundError`
     * check: the id is wire-stable (House Rule 9 makes changing one a migration), and the value
     * arriving here has crossed the IPC boundary as a DTO.
     */
    private fun TResult<TBoolean>.isUnreadablePhrase(): Boolean =
        this is TResult.Failure && error.id == RecoveryPhraseUnreadableError.ID
}

data class LockedUiState(
    /**
     * True until the async biometric-availability probe resolves. The screen
     * shows a neutral loading state while it holds so the passphrase fallback
     * doesn't flash before biometric is known to be available.
     */
    val initializing: Boolean = true,
    val passphrase: String = "",
    val isLoading: Boolean = false,
    val unlocked: Boolean = false,
    val error: LockedError? = null,
    val failureCount: Int = 0,
    val biometricAvailable: Boolean = false,
    val biometricEnrolled: Boolean = false,
    val biometricInFlight: Boolean = false,
    /**
     * True once the user has tapped "Use passphrase instead" — or
     * implicitly once the biometric path returns a non-success outcome.
     * When false the screen hides the passphrase field behind the
     * biometric CTA.
     */
    val passphraseExpanded: Boolean = false,
)
