package app.tenshu.mia

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.rememberPersistedWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.mia.data.OnboardingStore
import app.tenshu.mia.di.miaClientModule
import app.tenshu.mia.di.miaModules
import app.tenshu.mia.di.miaPlatformClientModule
import app.tenshu.mia.ui.App
import app.tenshu.ui.theme.TenshuTheme
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

// Mia desktop entry point (docs/proposals/home-and-mia-apps.md §6). Same shims as the
// sibling apps. Since MQ-1 it talks to Control-desktop over UDS for real answers; with no
// Control running every turn says so rather than falling back to a mocked reply. The
// desktop module (listed after the client modules → wins) makes the M5 onboarding gate
// durable.
fun main() {
    startKoin {
        modules(miaModules() + listOf(miaClientModule, miaPlatformClientModule) + miaDesktopModule())
    }

    application {
        val windowState = rememberPersistedWindowState(
            key = "mia",
            storageDir = DesktopAppPaths.root()
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Mia",
            state = windowState,
        ) {
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this
            // instance, so a new one per recomposition would re-read the file every frame.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "mia", storageDir = DesktopAppPaths.root())
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

private fun miaDesktopModule() = module {
    single<OnboardingStore> { PreferencesOnboardingStore() }
}

/** Desktop's durable onboarding flag — java.util.prefs, the platform's plain-prefs twin. */
private class PreferencesOnboardingStore : OnboardingStore {

    private val node = Preferences.userRoot().node("app/tenshu/mia")
    private val flow = MutableStateFlow(node.getBoolean(KEY_ONBOARDED, false))

    override fun onboarded(): Flow<Boolean> = flow

    override suspend fun setOnboarded() {
        node.putBoolean(KEY_ONBOARDED, true)
        flow.value = true
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
    }
}
