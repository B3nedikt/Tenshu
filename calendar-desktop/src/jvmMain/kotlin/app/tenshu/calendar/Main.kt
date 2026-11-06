package app.tenshu.calendar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.calendar.di.calendarClientModule
import app.tenshu.calendar.di.calendarModules
import app.tenshu.calendar.di.platformClientModule
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.ui.App
import app.tenshu.calendar.ui.external.desktopDeviceCalendarModule
import app.tenshu.ui.layout.rememberPersistedWindowState
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

/**
 * Calendar desktop entry point (`calendar-app.md` CA-2b).
 *
 * ⚠ Requires the Control desktop app to be running, like Drive-desktop and
 * Contacts-desktop: Calendar has no proxy of its own and reaches Control's over
 * the UDS transport.
 *
 * Desktop has no Android `ViewModelStore` / `SavedState` infrastructure, so the
 * same shims as `:contacts-desktop` are provided manually before
 * `TenshuTheme { App(openTargets = GlobalContext.get().get<OpenTargetInbox>().targets) }`.
 */
fun main(args: Array<String>) {
    startKoin {
        // ⚠ `desktopDeviceCalendarModule` binds the macOS reader only in a packaged `.app` that
        // declares the calendar usage string (CX-2c); elsewhere it binds nothing.
        modules(listOf(calendarClientModule, platformClientModule) + calendarModules() + desktopDeviceCalendarModule())
    }
    // *Keep in sync* (CX-2c): this Mac's mirrors refresh at launch and while the app runs.
    GlobalContext.get().getOrNull<MirrorBackgroundScheduler>()?.ensureScheduled()

    // ⚠⚠ Before `application { }`, not inside it. On macOS a double-click on a
    // `.ics` both launches the app and fires the open-file event, and a handler
    // registered once the window is composing arrives after that event has already
    // been dropped — the file association would then work only for a second file
    // opened while the app was already running. See `IcsDocumentOpens`.
    //
    // ⚠ `SupervisorJob` on a scope that outlives nothing in particular: the app's
    // lifetime *is* this function, and cancelling the reads at any earlier point
    // would drop the file the user just opened.
    val documentOpens = IcsDocumentOpens(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    documentOpens.start(args)

    application {
        // ⚠⚠ `rememberPersistedWindowState`, not the bare `rememberWindowState()` this
        // used to call. That default is 800×600 and forgotten on quit, so Calendar reopened
        // cramped no matter how the user had sized it. The shared helper opens every Tenshu
        // desktop app at one screen-relative size (`ui-core/ui-agent.md` §8). The month grid
        // is the view that pays for a small window: see `MonthGridView`.
        val windowState = rememberPersistedWindowState(
            key = "calendar",
            storageDir = DesktopAppPaths.root(),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Calendar",
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
                    App(openedIcsFiles = documentOpens.files)
                }
            }
        }
    }
}
