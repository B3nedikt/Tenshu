package app.tenshu.contacts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.contacts.di.contactsClientModule
import app.tenshu.contacts.di.contactsModules
import app.tenshu.contacts.di.platformClientModule
import app.tenshu.contacts.ui.App
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.TenshuWindowIcon
import app.tenshu.ui.layout.rememberPersistedWindowState
import app.tenshu.ui.theme.TenshuTheme
import org.koin.core.context.startKoin
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

// Contacts desktop entry point (docs/proposals/contacts-app.md §6). Desktop has no Android
// ViewModelStore / SavedState infra, so we provide them manually (same shims as
// :maps-desktop) before wrapping in TenshuTheme { App() }.
fun main() {
    // The client graph (IPC to Control-desktop over UDS) + the app modules. Requires the
    // Control desktop app to be running, like Drive-desktop and Maps-desktop; when it is
    // not, the shell's reachability banner says so rather than showing an empty list.
    startKoin {
        modules(listOf(contactsClientModule, platformClientModule) + contactsModules())
    }

    application {
        // ⚠ The shared rule, not Compose's bare 800 × 600: a first launch sized to the screen, then
        // whatever the user sets, remembered — as every other Tenshu desktop app does.
        val windowState = rememberPersistedWindowState(
            key = "contacts",
            storageDir = DesktopAppPaths.root(),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Contacts",
            state = windowState,
        ) {
            TenshuWindowIcon()
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this instance.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "contacts", storageDir = DesktopAppPaths.root())
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
