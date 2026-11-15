package app.tenshu.sdk.internal

import app.tenshu.diagnostics.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.sync.common.SyncGate

/**
 * The browser's "pause sync" switch — the web twin of native Control's `SyncPauseState`
 * (`web-apex-home.md` §4.7).
 *
 * ⚠⚠ **Device-local, never realm state.** Pausing this browser must not pause the user's other
 * devices, so it lives in `localStorage` (per origin = per browser profile, the same scope as this
 * browser's device identity in IndexedDB), not in the preference service.
 *
 * [gate] is what `initSyncService` receives as its `pauseGate`: closed, the scheduler skips its whole
 * tick and the content plane parks uploads (`SyncGate` KDoc). ⚠ That is stronger than the desktop
 * tray's pause, which composes into the policy `syncGate` — a gate that, since
 * `sync-metered-plane-split.md`, holds file content only.
 *
 * Tabs of one origin share the switch: a `storage` event (fired in every *other* tab of the origin)
 * re-reads it, so pausing in one tab cannot leave a second tab's runtime pushing.
 */
internal object SyncPause {
    const val KEY = "tenshu.sync.paused"

    private val _paused = MutableStateFlow(read())
    private var listening = false

    /** Set by the sync plugin: wakes the scheduler on resume so the user does not wait out a tick. */
    var onResumed: (() -> Unit)? = null

    val paused: StateFlow<Boolean>
        get() {
            listenAcrossTabs()
            return _paused
        }

    /** The scheduler's pause gate: open while **not** paused. Reads the flag at every tick. */
    val gate: SyncGate = SyncGate { !_paused.value }

    /** Idempotent. Persists before it publishes, so a reload straight after keeps the choice. */
    fun setPaused(value: Boolean) {
        listenAcrossTabs()
        write(value)
        apply(value, source = "this tab")
    }

    private fun apply(value: Boolean, source: String) {
        if (_paused.value == value) return
        _paused.value = value
        Diagnostics.i("Sync", if (value) "paused by the user ($source)" else "resumed by the user ($source)")
        if (!value) onResumed?.invoke()
    }

    private fun listenAcrossTabs() {
        if (listening) return
        listening = true
        val win: dynamic = js("(typeof window !== 'undefined' && window.addEventListener) ? window : null")
        if (win == null) return
        win.addEventListener("storage", { event: dynamic ->
            // `key === null` is `localStorage.clear()` from another tab: re-read rather than guess.
            if (event.key == null || event.key == KEY) apply(read(), source = "another tab")
        })
    }

    private fun storage(): dynamic = js("(typeof localStorage !== 'undefined') ? localStorage : null")

    private fun read(): Boolean = try {
        storage()?.getItem(KEY) == "1"
    } catch (_: Throwable) {
        // Blocked storage reads as "not paused": the switch then lasts this page only.
        false
    }

    private fun write(value: Boolean) {
        try {
            val s = storage() ?: return
            if (value) s.setItem(KEY, "1") else s.removeItem(KEY)
        } catch (_: Throwable) {
            // Blocked or full storage: the pause still holds for this page, just not across a reload.
        }
    }

    /** Test seam: re-read the persisted value as a fresh page would. */
    internal fun reloadForTest() {
        _paused.value = read()
    }
}
