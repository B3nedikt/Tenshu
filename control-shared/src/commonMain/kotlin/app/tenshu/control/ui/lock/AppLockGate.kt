package app.tenshu.control.ui.lock

import androidx.compose.runtime.Composable

/**
 * Control's soft app-lock UI gate
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2, "Require unlock"). The platform
 * actual draws an opaque overlay + biometric/device-credential prompt (Android `BiometricPrompt`
 * / desktop Touch-ID helper) when "Require unlock" is on; **UI-only** — it never touches keys, so
 * the realm stays headless and background sync is unaffected.
 *
 * [active] gates engagement: the overlay/prompt shows **only when the active app UI is on screen**
 * — a post-onboarding, unlocked realm (`App`'s `showNav`). It must NOT engage over the hard
 * `LockedScreen` (a sealed realm) or the onboarding screens, or the soft lock would stack on top of
 * the hard unlock into a *duplicated* unlock screen (the reported bug). When [active] is false the
 * gate is a pass-through.
 */
@Composable
expect fun AppLockGate(active: Boolean, content: @Composable () -> Unit)
