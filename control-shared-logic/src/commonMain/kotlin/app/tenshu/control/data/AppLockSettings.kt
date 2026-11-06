package app.tenshu.control.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tenshu.core.common.delegate.SecureStorageDelegate

/**
 * Device-local **"Require unlock"** preference for Control's soft app-lock
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2, toggle 1).
 *
 * Persisted through the *non-secret*, non-synced [SecureStorageDelegate] string
 * bucket: it is a **per-device UI choice**, deliberately not a synced setting (a
 * shared or borrowed device must not push its lock preference onto the user's
 * other devices) and it never touches key material — the data plane stays
 * headless, so background sync is unaffected whether it's on or off.
 *
 * Default **on** (privacy-first, near-zero friction). The value is read once at
 * construction and mirrored into [requireUnlock] so both the Settings screen and
 * the [AppLockController] observe changes reactively.
 */
class AppLockSettings(
    private val secureStorage: SecureStorageDelegate,
) {

    private val _requireUnlock = MutableStateFlow(
        secureStorage.getString(KEY)?.toBooleanStrictOrNull() ?: DEFAULT_REQUIRE_UNLOCK,
    )

    /** The current device-local require-unlock choice. */
    val requireUnlock: StateFlow<Boolean> = _requireUnlock.asStateFlow()

    fun setRequireUnlock(enabled: Boolean) {
        secureStorage.storeString(KEY, enabled.toString())
        _requireUnlock.value = enabled
    }

    private companion object {
        const val KEY = "app_lock_require_unlock"
        const val DEFAULT_REQUIRE_UNLOCK = true
    }
}
