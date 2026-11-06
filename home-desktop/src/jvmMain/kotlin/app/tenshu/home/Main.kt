package app.tenshu.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.home.di.homeClientModule
import app.tenshu.home.di.homeModules
import app.tenshu.home.di.homePlatformClientModule
import app.tenshu.home.ui.App
import app.tenshu.ui.theme.TenshuTheme
import org.koin.core.context.startKoin
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

// Home desktop entry point (docs/proposals/home-and-mia-apps.md §6, M1). Desktop has no
// Android ViewModelStore / SavedState infra, so we provide them manually (same shims as
// :fit-desktop) before wrapping in TenshuTheme { App() }. The default window is
// phone-portrait-ish — the design's primary frame is 412 px — but resizing exercises the
// nav-rail / drawer branches of AdaptiveNavScaffold.
//
// The storage tile is LIVE on desktop: homeClientModule (listed after homeModules(), so
// its repository binding wins — Koin module order = override order) connects to
// Control-desktop's UDS and overlays real hub `HubStatus` data onto the mock household.
// Without a running Control-desktop (or without a paired hub) every read comes back
// empty and the tile keeps the mock figures — Home still launches standalone.
fun main() {
    startKoin { modules(homeModules() + listOf(homeClientModule, homePlatformClientModule)) }

    application {
        val windowState = rememberWindowState(size = DpSize(450.dp, 920.dp))
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Home",
            state = windowState,
        ) {
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }

            CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelStoreOwner,
                LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
                LocalLifecycleOwner provides savedStateRegistryOwner,
            ) {
                TenshuTheme {
                    App()
                }
            }
        }
    }
}
