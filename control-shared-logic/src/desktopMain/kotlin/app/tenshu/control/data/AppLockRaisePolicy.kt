package app.tenshu.control.data

/**
 * Should the desktop entry point pull Control's window to the front?
 *
 * Extracted as a **pure function** on purpose. The raise splits cleanly into two halves, and only
 * one of them is reachable by a test:
 *  - *policy* — when a raise is warranted. That is this function.
 *  - *effect* — whether `Desktop.requestForeground` actually wins activation from the macOS window
 *    server. No unit test in this repo reaches that; it needs a device.
 *
 * Same shape as `app.tenshu.control.ui.appLockGateActive`, the gate's own predicate.
 *
 * ⚠⚠ **Only on the lock→unlock transition.** Firing whenever *either* the lock state or the window
 * visibility changes also fires on the tray's *Open Control* — a path where nothing was ever
 * locked. Control then activates itself on top of the tray's own `NSRunningApplication.activate()`,
 * and the tray's popover stays on screen instead of closing. Raising is only ever warranted by an
 * unlock that just happened.
 *
 * ⚠ [windowVisible] is still required: a close-to-tray or autostarted-hidden window must not be
 * yanked forward just because the gate's state settled behind it.
 */
fun shouldRaiseOnUnlock(
    wasLocked: Boolean,
    isLocked: Boolean,
    windowVisible: Boolean,
): Boolean = wasLocked && !isLocked && windowVisible
