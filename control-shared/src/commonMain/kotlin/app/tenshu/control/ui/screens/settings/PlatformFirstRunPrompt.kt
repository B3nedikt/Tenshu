package app.tenshu.control.ui.screens.settings

import androidx.compose.runtime.Composable

/**
 * A one-time question a platform asks once the device is set up. Windows asks whether Control may start at login
 * (desktop-windows-port.md §3.5); other platforms ask nothing.
 *
 * @param active whether the app is on Home with nothing else in front of it: no lock, no pairing request.
 */
@Composable
expect fun PlatformFirstRunPrompt(active: Boolean)
