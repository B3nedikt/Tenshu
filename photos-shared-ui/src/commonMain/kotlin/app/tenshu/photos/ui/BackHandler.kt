package app.tenshu.photos.ui

import androidx.compose.runtime.Composable

/**
 * Intercept the platform's back gesture — the `drive-shared-ui` seam, verbatim.
 *
 * ⚠⚠ Photos navigates with a plain `Destination` held in Compose state, not a nav library, so
 * **nothing was listening for system back**: pressing it anywhere below the feed left the app
 * entirely. That was survivable while every screen was one tap deep; it stopped being so when the
 * viewer became somewhere you dwell (PH-8/PH-9) — found by pressing back on a device, where it
 * dropped straight to the launcher.
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
