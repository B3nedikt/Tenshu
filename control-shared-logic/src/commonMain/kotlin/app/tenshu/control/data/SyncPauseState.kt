package app.tenshu.control.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Device-local "pause sync" flag — the bgproc §5.3 tray *Paused* state as a
 * user control (`core-background-processing.md` Phase 6 Slice C). When paused,
 * the in-process `SyncScheduler` skips its whole drain/pull cycle: the desktop
 * `syncGate` composes this ahead of [ConstraintSyncGate] (`!paused &&
 * constraintAllowsSync(...)`).
 *
 * This is a **device-local** control, not synced realm state — pausing sync on
 * one device must not pause it on the user's other devices — so it persists via
 * a platform-supplied [persist] callback (desktop: `DesktopControlSettings`),
 * not the preference-service. Distinct from `SyncManager.isEnabled()` (the
 * realm/unlock gate) and from the network constraint gate: this is the user's
 * explicit "hold off" switch.
 */
class SyncPauseState(
    initiallyPaused: Boolean = false,
    private val persist: (Boolean) -> Unit = {},
) {
    private val _paused = MutableStateFlow(initiallyPaused)

    /** Observable pause flag (the gate reads `.value` each tick). */
    val paused: StateFlow<Boolean> get() = _paused

    fun isPaused(): Boolean = _paused.value

    /** Idempotent; persists device-locally so a paused daemon stays paused. */
    fun setPaused(value: Boolean) {
        if (_paused.value == value) return
        _paused.value = value
        persist(value)
    }
}
