package app.tenshu.fit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.TenshuWindowIcon
import app.tenshu.ui.layout.rememberPersistedWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.fit.data.ActivityFile
import app.tenshu.fit.data.ActivityFileSource
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.JvmArchiveReader
import app.tenshu.fit.data.TakeoutParser
import app.tenshu.fit.data.RecordedWorkoutOutbox
import app.tenshu.fit.data.desktopTakeoutParser
import app.tenshu.fit.di.fitClientModule
import app.tenshu.fit.di.fitModules
import app.tenshu.fit.di.platformClientModule
import app.tenshu.fit.ui.App
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.context.startKoin
import org.koin.dsl.module
import app.tenshu.outbox.FileOutboxStore
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner
import java.awt.FileDialog
import java.io.File

// Fit desktop entry point (docs/proposals/fit-app.md). Desktop has no Android
// ViewModelStore / SavedState infra, so we provide them manually (same shims as
// :timeline-desktop) before wrapping in TenshuTheme { App(openTargets = GlobalContext.get().get<OpenTargetInbox>().targets) }. The default window is
// phone-portrait-ish — the design's primary frame is 412 px — but resizing exercises
// the nav-rail / drawer branches of AdaptiveNavScaffold.
//
// P5.3b/§9.6b: a native File menu lets the user pick a Google Takeout .zip (or a bare GPX/TCX/FIT)
// and import it through the repository's streaming importFiles (parser → shared HealthImportWriter,
// reported via importStatus). The reactive projector flows re-emit as rows land — no manual refresh.
fun main() {
    // Fit's data graph over the Control-desktop proxy (UDS): `:health-service` is the app's store,
    // MapRegionService is the best-effort basemap source (maps-region-delivery.md §7). Control-desktop
    // must be running — health reads/writes go there and nowhere else (fit-app.md §10). fitDesktopModule
    // adds the Takeout / GPX / TCX file-import parser (§9.6b).
    val koin = startKoin { modules(listOf(fitClientModule, platformClientModule, fitDesktopModule()) + fitModules()) }.koin

    application {
        val windowState = rememberPersistedWindowState(
            key = "fit",
            storageDir = DesktopAppPaths.root()
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Fit",
            state = windowState,
        ) {
            TenshuWindowIcon()
            ImportMenuBar(koin)

            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this
            // instance, so a new one per recomposition would re-read the file every frame.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "fit", storageDir = DesktopAppPaths.root())
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

@Composable
private fun FrameWindowScope.ImportMenuBar(koin: Koin) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Import activity file…", mnemonic = 'I') {
                pickAndImport(koin)
            }
        }
    }
}

/**
 * Opens a native picker and imports the chosen file **through the repository**, so the result lands
 * in `importStatus` and the app reports it like any other import — where importing straight through
 * the writer (as this did) wrote rows and told the user nothing.
 *
 * Accepts a Google Takeout `.zip` alongside a bare activity file: the archive is where a user's real
 * Google Fit history lives, since Google Fit never fed Health Connect (§9.5b).
 */
private fun FrameWindowScope.pickAndImport(koin: Koin) {
    val dialog = FileDialog(window, "Import Google Takeout (.zip) or an activity file (GPX / TCX / FIT)", FileDialog.LOAD).apply {
        setFilenameFilter { _, name ->
            name.endsWith(".zip", true) || name.endsWith(".gpx", true) ||
                name.endsWith(".tcx", true) || name.endsWith(".fit", true)
        }
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return

    val repository = koin.get<FitRepository>()
    val parser = koin.get<TakeoutParser>()
    val scope = koin.get<CoroutineScope>()
    scope.launch {
        val file = File(dir, name)
        // Streamed, not slurped: the archive reader opens the zip on demand and yields one member at
        // a time, so a huge Takeout is never held in memory whole. `importFiles` writes through the
        // Control-backed client the repository reads from, so rows land in the observed, durable store.
        val source = if (name.endsWith(".zip", true)) {
            JvmArchiveReader.source(
                openStream = { file.inputStream() },
                isRelevant = parser::isRelevant,
                archiveName = name,
                archiveBytes = file.length().takeIf { it > 0 },
            )
        } else {
            ActivityFileSource.of(listOf(ActivityFile(name, file.readBytes())))
        }
        withContext(Dispatchers.IO) { repository.importFiles(source) }
    }
}

/**
 * Desktop-only bindings: the file-import parser. It lives here rather than in `fitModules()` because
 * it needs a JVM XML reader (GPX/TCX) and the Garmin SDK decoder (binary FIT) — neither of which the
 * shared module can name. Mirrors `fitAndroidModule()`.
 */
private fun fitDesktopModule(): Module = module {
    single { desktopTakeoutParser() }
    // The durable recorder outbox (§9.13): journals a finished recording before the store write,
    // so a save against an unreachable Control survives the process. Same app-data root as the
    // rest of desktop Tenshu (DesktopAppPaths).
    single { RecordedWorkoutOutbox(FileOutboxStore(File(DesktopAppPaths.dataDir("app.tenshu.fit.desktop"), "workout_outbox"))) }
}
