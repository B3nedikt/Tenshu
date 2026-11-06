package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable

/**
 * A way into the settings that govern whether this app may keep running in the background — the
 * second half of surviving an OEM (§13.5d), and the half Android gives no API for.
 *
 * Battery-optimisation exemption ([BackgroundRunGate]) is AOSP and addressable. The auto-start /
 * "allow background activity" list that ColorOS, MIUI, EMUI and friends keep *on top of it* is not:
 * it cannot be read, cannot be set, and is not part of any public contract.
 *
 * ⚠⚠ **And it frequently cannot even be opened.** Measured on the ColorOS device that killed a live
 * recording (§13.5b): its auto-start screen is `exported=true` and resolves cleanly, yet starting it
 * throws `SecurityException … requires oplus.permission.OPLUS_COMPONENT_SAFE` — a vendor signature
 * permission no third-party app can hold. The restriction is a vendor hook in
 * `checkStartAnyActivityPermission`, **not** a manifest permission, so `PackageManager` cannot see
 * it: resolution says yes and the launch still fails. That rules out "check, then offer" and leaves
 * only **attempt, then fall back** — try the vendor screen, and land on the app's own OS settings
 * page (always present, and where ColorOS keeps "Battery usage → Allow background activity") when
 * the vendor refuses. The user always arrives somewhere they can act.
 */
interface OemBackgroundSettings {

    /** Whether this platform has any background settings to open at all. False on desktop. */
    fun canOpen(): Boolean

    /**
     * Opens the vendor auto-start screen if this device permits it, else the app's own settings
     * page. Never throws, and never leaves the user on the screen they tapped from.
     */
    fun openBackgroundSettings()
}

@Composable
expect fun rememberOemBackgroundSettings(): OemBackgroundSettings
