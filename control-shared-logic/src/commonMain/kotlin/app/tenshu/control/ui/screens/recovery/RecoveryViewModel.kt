package app.tenshu.control.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.SyncRepository
import app.tenshu.error.ErrorCopyKey
import org.tenshu.core.InvalidParameter
import org.tenshu.core.TError
import app.tenshu.error.errorCopyKey
import org.tenshu.sync.RecoveryClaim
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.common.util.getPlatformDeviceName
import org.tenshu.core.onFailure
import org.tenshu.core.onSuccess

/**
 * Drives the §7 recovery flow per `docs/proposals/control-onboarding-ux.md`.
 * Bip39.decode runs client-side first so the UI surfaces typed
 * `Bip39InvalidMnemonicException` subtypes before any relay round-trip
 * (matching the existing `RecoveryClaimImplementation` invariant — a
 * mistyped phrase fails fast). On decode success, `recoveryClaim` posts
 * to the relay; non-cryptographic failures (network / no-backup-blob /
 * KDF-out-of-date) currently surface the underlying TError message —
 * typed network errors are a follow-up once `RecoveryClaim` returns
 * structured failure variants.
 */
class RecoveryViewModel(
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    fun onWordChange(index: Int, value: String) {
        if (index !in 0 until MNEMONIC_WORD_COUNT) return
        val updated = _uiState.value.words.toMutableList().also { it[index] = value }
        _uiState.update {
            it.copy(
                words = updated,
                wordCount = updated.count { w -> w.isNotBlank() },
                error = null,
            )
        }
    }

    fun onRecover() {
        val phrase = _uiState.value.words
            .joinToString(" ") { it.trim().lowercase() }
            .trim()
        if (phrase.isEmpty()) return

        // Client-side BIP-39 validation first so the UI surfaces typed
        // errors before the relay round-trip. Mirrors the same dance that
        // RecoveryClaimImplementation does at the proxy boundary.
        val entropy = try {
            Bip39.decode(phrase)
        } catch (e: Bip39InvalidMnemonicException.UnknownWord) {
            failEntry(RecoveryError.UnknownWord(e.word))
            return
        } catch (e: Bip39InvalidMnemonicException.InvalidLength) {
            failEntry(RecoveryError.InvalidLength(e.wordCount))
            return
        } catch (_: Bip39InvalidMnemonicException.ChecksumMismatch) {
            failEntry(RecoveryError.ChecksumMismatch)
            return
        }
        @Suppress("UNUSED_VARIABLE") val unused = entropy

        viewModelScope.launch {
            _uiState.update {
                it.copy(step = RecoveryStep.Processing, error = null)
            }
            syncRepository.recoveryClaim(
                recoveryPhrase = phrase,
                deviceName = getPlatformDeviceName(),
            )
                .onSuccess {
                    _uiState.update { it.copy(step = RecoveryStep.Success) }
                }
                .onFailure { err -> failEntry(err.toRecoveryError()) }
        }
    }

    fun onSuccessContinue() {
        _uiState.update { it.copy(step = RecoveryStep.Done) }
    }

    /**
     * Map a typed proxy failure onto the screen's own error vocabulary.
     *
     * ⚠⚠ Never reads `GenericError.message` — `core-error-copy-catalogue.md` §5.0. What used to
     * arrive here as a sentence was, in the common case, a **relay reason phrase**
     * (`"Recovery claim failed: 401 Unauthorized …"` and friends from `RestSyncRelay`), which is
     * diagnostics and must not reach a screen at all. It now becomes a catalogue key the UI layer
     * renders; the detail stays in the `ErrorEvent`, where a developer will look.
     *
     * ⚠ `DeviceAlreadySetUpError` (this device belongs to a different realm, T-RC-09) takes the
     * catalogue route too: its entry, `error_device_already_set_up`, tells the user to forget this
     * device first. It must never be folded into [RecoveryError.PhraseRejected] — the phrase may be
     * right, and retyping it cannot help.
     */
    private fun TError.toRecoveryError(): RecoveryError {
        val invalidPhrase = this is InvalidParameter &&
            name.get() == RecoveryClaim.Params.RECOVERY_PHRASE
        return if (invalidPhrase) RecoveryError.PhraseRejected else RecoveryError.Unexpected(errorCopyKey())
    }

    private fun failEntry(error: RecoveryError) {
        _uiState.update {
            it.copy(
                step = RecoveryStep.Entry,
                error = error,
                failureCount = it.failureCount + 1,
            )
        }
    }

    private companion object {
        const val MNEMONIC_WORD_COUNT = 24
    }
}

sealed class RecoveryStep {
    data object Entry : RecoveryStep()
    data object Processing : RecoveryStep()
    data object Success : RecoveryStep()
    data object Done : RecoveryStep()
}

/**
 * What went wrong recovering, in the screen's own vocabulary.
 *
 * ⚠⚠ No member carries a message. `Generic(message: String)` used to, and it was the hole through
 * which the proxy's English reached the screen — `core-error-copy-catalogue.md` §1 and EC-1. The
 * three precise members below are raised **client-side**, by decoding BIP-39 before any relay
 * round-trip, which is why they can carry the offending word and its copy can be parameterised.
 */
sealed class RecoveryError {
    data class UnknownWord(val word: String) : RecoveryError()
    data class InvalidLength(val wordCount: Int) : RecoveryError()
    data object ChecksumMismatch : RecoveryError()

    /**
     * The proxy refused the phrase, and we do not know which word or why.
     *
     * ⚠ Deliberately **not** folded into [ChecksumMismatch]: reaching this member means the local
     * decode passed and the proxy's did not, so naming a cause would be asserting one we cannot
     * know. Its copy says the phrase was rejected and stops there.
     */
    data object PhraseRejected : RecoveryError()

    /** Anything else, rendered from the shared catalogue by its key — never from a message. */
    data class Unexpected(val copyKey: ErrorCopyKey) : RecoveryError()
}

data class RecoveryUiState(
    val step: RecoveryStep = RecoveryStep.Entry,
    /** 24-element list of per-word inputs; index = word position. */
    val words: List<String> = List(24) { "" },
    /** Count of non-blank entries in [words]. Recover gates on `== 24`. */
    val wordCount: Int = 0,
    val error: RecoveryError? = null,
    /**
     * Counts how many decode/recovery attempts have failed in this session.
     * Crosses 5 → UI surfaces a "trouble recovering?" help link per spec
     * §7 "Recovery Failures — branching by Bip39InvalidMnemonicException
     * subtype" footer.
     */
    val failureCount: Int = 0,
)
