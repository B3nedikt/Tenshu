package app.tenshu.control.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the host window currently shows the UI to the user.
 *
 * On desktop the Control daemon keeps composing its (single) `Window` even while
 * hidden — `Main.kt` deliberately never drops the window from the composition so
 * `application { }` survives a close-to-tray (see
 * `docs/proposals/desktop-macos-integration.md` §4 slice 1A). That means the full
 * UI tree, including the Locked screen, composes for the autostarted-hidden
 * daemon (`TENSHU_CONTROL_START_HIDDEN`). Effects that present user-facing OS UI —
 * notably the biometric unlock prompt — must therefore gate on this so they don't
 * fire for a window the user can't see (which surfaced as a phantom Touch ID sheet
 * over a blank screen, then a second prompt once the window was actually opened).
 *
 * Defaults to `true`: any host that doesn't provide it (Android, where the UI is
 * only composed while in the foreground) is treated as visible, preserving the
 * existing behaviour.
 */
val LocalWindowVisible = staticCompositionLocalOf { true }
