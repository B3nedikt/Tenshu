package app.tenshu.control.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure state machine behind Control's **soft app-lock**
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2, toggle 1).
 *
 * [locked] is true when a lock has been *requested* — a cold start or the app
 * going to background — while the device-local [AppLockSettings.requireUnlock] is
 * on. It is a UI gate only: it never touches keys, so the data plane stays
 * headless and background sync is unaffected.
 *
 * The preference is re-read at each transition (cold start + [onEnterBackground]),
 * which is sufficient because the gate obscures the whole UI while locked — the
 * user cannot reach Settings to change the toggle mid-lock, so no live
 * subscription to [AppLockSettings] is needed.
 *
 * The platform glue drives it: ProcessLifecycle `ON_STOP` → [onEnterBackground];
 * a successful biometric/device-credential prompt → [onAuthenticated].
 */
class AppLockController(
    private val settings: AppLockSettings,
) {

    private val _locked = MutableStateFlow(settings.requireUnlock.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** The app was backgrounded (ProcessLifecycle `ON_STOP`) — re-arm the gate. */
    fun onEnterBackground() {
        _locked.value = settings.requireUnlock.value
    }

    /** The user cleared the prompt — reveal the UI until the next background. */
    fun onAuthenticated() {
        _locked.value = false
    }

    /**
     * Clears the gate when this device **cannot prompt at all** (no secure lock screen, no
     * backend), and returns whether it did. Each platform gate calls it every time it engages,
     * before showing a prompt.
     *
     * ⚠⚠ A gate that cannot be satisfied is a lockout, not a safeguard. "Require unlock"
     * defaults ON whether or not the device has a screen lock, so an Android phone with no
     * PIN/pattern/biometric — or one whose user removes it later — would otherwise sit behind an
     * *Unlock* button that can never succeed, locking them out of Control and so every app behind
     * it. Passing through is no weaker than the OS itself: a device with no lock screen opens to
     * anyone. Probed per engagement rather than latched, so a screen lock added later is honoured
     * at the next re-arm.
     */
    suspend fun passIfUnenforceable(availability: AppLockAvailability): Boolean {
        if (!_locked.value || availability.isAvailable()) return false
        _locked.value = false
        return true
    }
}
