package app.tenshu.control.ui.screens.settings

import androidx.compose.runtime.Composable

/**
 * Settings entries that exist on one platform only, rendered near the bottom of the shared
 * [SettingsScreenContent] list:
 *  - **desktop** — the macOS menu-bar agent's "Close to menu bar" and "Start at login"
 *    (desktop-macos-integration.md §4 slice 1E);
 *  - **Android** — "Let other apps open your Drive files", the master switch for Drive in the
 *    system file dialogs (cross-app-file-access.md D-D).
 */
@Composable
expect fun PlatformSettingsSection()
