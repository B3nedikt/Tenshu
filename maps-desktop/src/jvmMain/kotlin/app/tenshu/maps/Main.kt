package app.tenshu.maps

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.maps.di.mapsClientModule
import app.tenshu.maps.di.mapsModules
import app.tenshu.maps.di.platformClientModule
import app.tenshu.maps.ui.App
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.rememberPersistedWindowState
import app.tenshu.ui.theme.TenshuTheme
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

// Maps desktop entry point (docs/proposals/maps-app.md §6 Ph0). Desktop has no Android
// ViewModelStore / SavedState infra, so we provide them manually (same shims as
// :timeline-desktop) before wrapping in TenshuTheme { App(openTargets = GlobalContext.get().get<OpenTargetInbox>().targets) } — ready for the Ph3 VMs.
fun main() {
    // The client graph (IPC to Control-desktop over UDS) + the app modules. Requires the Control
    // desktop app running (like Drive-desktop) so the region picker reaches the real service (D6).
    startKoin {
        modules(listOf(mapsClientModule, platformClientModule) + mapsModules())
    }

    application {
        // ⚠ The shared rule, not Compose's bare 800 × 600: a first launch sized to the screen, then
        // whatever the user sets, remembered — as every other Tenshu desktop app does.
        val windowState = rememberPersistedWindowState(
            key = "maps",
            storageDir = DesktopAppPaths.root(),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Maps",
            state = windowState,
        ) {
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this instance.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "maps", storageDir = DesktopAppPaths.root())
            }

            CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelStoreOwner,
                LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
                LocalLifecycleOwner provides savedStateRegistryOwner,
                LocalNavRailPreference provides navRailPreference,
            ) {
                TenshuTheme {
                    App()
                }
            }
        }
    }
}
