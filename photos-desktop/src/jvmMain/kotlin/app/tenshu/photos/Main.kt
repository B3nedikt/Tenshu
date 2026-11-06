package app.tenshu.photos

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.photos.di.photosClientModule
import app.tenshu.photos.di.photosModules
import app.tenshu.photos.di.platformClientModule
import app.tenshu.photos.ui.App
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.rememberPersistedWindowState
import app.tenshu.ui.theme.TenshuTheme
import org.koin.core.context.startKoin
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

/**
 * Photos desktop entry point (`docs/proposals/photos-app.md` PH-1).
 *
 * Desktop has no Android ViewModelStore / SavedState infrastructure, so the shims go in manually —
 * the same ones `:contacts-desktop` and `:maps-desktop` use — before `TenshuTheme { App() }`.
 *
 * Requires Control-desktop to be running, like every other desktop shell here: the feed is a read
 * against its proxy. When it is not, the feed's `Unreachable` state says so rather than showing an
 * empty grid.
 */
fun main() {
    startKoin {
        modules(listOf(photosClientModule, platformClientModule) + photosModules())
    }

    application {
        // ⚠ Size and place survive a relaunch, as in Drive and Control. A photo grid is the app
        // where this matters most: the column count follows the window's width.
        val windowState = rememberPersistedWindowState(
            key = "photos",
            storageDir = DesktopAppPaths.root(),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Photos",
            state = windowState,
        ) {
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this instance, so
            // a new one per recomposition would re-read the file every frame.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "photos", storageDir = DesktopAppPaths.root())
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
