package app.tenshu.control.data

/**
 * Outcome of a presence check — the soft app-lock's gate prompt, and the
 * [Reauthenticator] prompt in front of an irreversible action.
 *
 * Lives in `commonMain` because both the desktop backend and the Android one
 * report through it.
 */
enum class AppLockAuthResult { Success, Cancelled, Unavailable, Failure }

/**
 * Title + subtitle for a re-authentication prompt.
 *
 * [systemReason] is for a prompt the OS words itself: macOS renders it as "Tenshu Control is trying
 * to <systemReason>.", so it is a lowercase verb phrase, never a sentence.
 */
data class ReauthPromptCopy(val title: String, val subtitle: String, val systemReason: String = subtitle)

/**
 * A **keyless presence check** in front of an action that cannot be undone —
 * revealing the recovery phrase, removing a device
 * (`docs/proposals/control-keep-device-unlocked.md` §6.1).
 *
 * ⚠⚠ Deliberately **independent of "Require unlock"** ([AppLockSettings]). A user who turned the
 * front door off has said nothing about their recovery phrase, and without this check would have
 * *no* authentication in front of A1's plaintext. Never gate a call to this on the app-lock
 * preference.
 *
 * ⚠ It is also **not** the app-lock's state machine: a one-shot presence check must not clear or
 * re-arm [AppLockController], which owns the front door's session.
 *
 * Like the soft lock, this is a **UI barrier and never a key gate** — it touches no key material,
 * so the threat-model posture of §5 is unchanged.
 */
interface Reauthenticator {

    /**
     * Presents the platform's biometric / device-credential sheet. [host] is the same Compose-tree
     * carrier the biometric-unlock delegate takes (`rememberBiometricPromptHost`).
     */
    suspend fun authenticate(host: BiometricPromptHost, copy: ReauthPromptCopy): AppLockAuthResult
}

/**
 * Binding for platforms with no prompt of their own. ⚠ Control-on-iOS is native SwiftUI and
 * presents its own `LAContext` sheet, so the Kotlin binding there is inert by design — the same
 * posture as [UnavailableAppLock].
 */
object UnavailableReauthenticator : Reauthenticator {
    override suspend fun authenticate(
        host: BiometricPromptHost,
        copy: ReauthPromptCopy,
    ): AppLockAuthResult = AppLockAuthResult.Unavailable
}

/**
 * The one place the **pass-through policy** is written down, so no call site can get it wrong:
 * proceed when the user proved presence, **and also when this device cannot ask at all**.
 *
 * ⚠⚠ A gate that cannot be satisfied is a lockout, not a safeguard: a default-on lock plus a
 * backend that can only answer `Unavailable` would seal the user out. Here it would be worse than a
 * lockout: the action being gated is *reading the recovery phrase*, so failing closed on a device
 * that cannot prompt would make the phrase permanently **unrecoverable** there rather than merely
 * unviewable. That is the common case on desktop (the macOS helper is opt-in and signing-gated;
 * Windows and Linux have no backend at all), not an edge case.
 *
 * A null [host] means the Compose tree cannot carry a prompt (Android UI not hosted by a
 * `FragmentActivity`) and is treated the same way.
 */
suspend fun Reauthenticator?.confirm(
    host: BiometricPromptHost?,
    copy: ReauthPromptCopy,
): Boolean {
    if (this == null || host == null) return true
    return when (authenticate(host, copy)) {
        AppLockAuthResult.Success, AppLockAuthResult.Unavailable -> true
        AppLockAuthResult.Cancelled, AppLockAuthResult.Failure -> false
    }
}
