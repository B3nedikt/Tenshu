package app.tenshu.ui.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.tenshu.ui.ipc.ClientPeerTrust
import app.tenshu.windows.WindowsInstalledPackages

/**
 * Desktop's update pages (`core-schema-skew-surface.md` §8.5). Windows: the Microsoft Store page of each
 * copy that came from the Store ([windowsAppUpdatePages]). macOS and Linux: none yet — Developer-ID
 * bundles have no store and no updater, so the words carry the fix.
 */
@Composable
actual fun rememberAppUpdatePages(): AppUpdatePages = remember {
    if (!isWindows()) return@remember AppUpdatePages.NONE
    windowsAppUpdatePages(
        own = WindowsInstalledPackages.current(),
        controlOrigin = installedControlOrigin(ClientPeerTrust.FIRST_PARTY_WINDOWS_PUBLISHER_IDS),
    )
}
