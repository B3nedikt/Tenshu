package app.tenshu.ui.components

import androidx.compose.runtime.Composable

/**
 * Draw the OS bars' icons light while this composable is in the tree, for a screen that is dark in
 * either theme and draws under the bars — Photos' viewer, Fit's live recording.
 *
 * ⚠⚠ The app shells' launch theme is light in light mode (`values/` vs `values-night/`), and the
 * window draws the bars' icons to match it: dark icons, invisible on these screens. Restores the
 * previous appearance on the way out — the controller is the window's, not the composable's, so a
 * screen left without restoring would hand the next one light icons on a light background.
 *
 * ⚠ Android only. A deliberate no-op on desktop (no OS bars) and iOS (the hosting controller owns
 * the status-bar style; the SwiftUI apps do not render these Compose screens).
 */
@Composable
expect fun LightSystemBarIcons()
