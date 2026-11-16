package app.tenshu.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
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
import app.tenshu.takeout.StreamingTakeoutArchive
import app.tenshu.timeline.data.FileTimelineImporter
import app.tenshu.timeline.data.summary

import app.tenshu.timeline.data.TimelineImportFile
import app.tenshu.timeline.data.geocode.GeocodingBackfillTrigger
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.di.platformClientModule
import app.tenshu.timeline.di.timelineClientModule
import app.tenshu.timeline.di.timelineModules
import app.tenshu.timeline.ui.App
import app.tenshu.timeline.ui.components.ImportResultDialog
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// Timeline desktop entry point (docs/proposals/timeline-app.md §7 Ph7). Desktop has no
// Android ViewModelStore / SavedState infra, so we provide them manually (same shims as
// :drive-desktop) before wrapping in TenshuTheme { App(openTargets = GlobalContext.get().get<OpenTargetInbox>().targets) }.
//
// The File menu carries the Google-Timeline import (I2b, docs/proposals/timeline-ingestion.md
// §3): because Google exposes no API, import is a user-hands-us-the-file handoff — a short guide
// then the native picker → GoogleTimelineParser → the shared writer. Desktop is the first import
// surface; the reactive projector Flows re-emit as rows land, so no manual refresh.
fun main() {
    val koin = startKoin {
        // The standalone Timeline data graph + a best-effort region client reaching Control-desktop
        // over UDS for real offline basemaps (maps-region-delivery.md §7); absent Control → demo.
        modules(listOf(timelineClientModule, platformClientModule) + timelineModules() + timelineDesktopModule())
    }.koin

    // Place-name lookups (place-geocoding.md G3, mirrors TimelineApplication on Android):
    // backfill blank-label places on every opt-in edge — including app start when the toggle is
    // already on. Consent-gated inside the enricher, so with the toggle off this collector idles.
    // ⚠ ADR 0025 §8.6: the backfill's writes go through the realm gate, as on Android.
    koin.get<CoroutineScope>().launch {
        val gate = koin.get<app.tenshu.timeline.data.TimelineRepository>().realmWriteGate
        val enricher = koin.get<PlaceLabelEnricher>()
        GeocodingBackfillTrigger(
            control = koin.get<PlaceGeocodingControl>(),
            backfill = {
                try {
                    enricher.backfill(beforeEachWrite = { gate?.refuseIfRealmWritesHeld() })
                } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
                    println("timeline: geocoding backfill held by the realm gate")
                }
            },
        ).run()
    }

    application {
        val windowState = rememberPersistedWindowState(
            key = "timeline",
            storageDir = DesktopAppPaths.root()
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Timeline",
            state = windowState,
        ) {
            TenshuWindowIcon()
            val awtWindow = window
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }

            var showGuide by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf<ImportOutcome?>(null) }

            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("Import Google Timeline…", mnemonic = 'I') { showGuide = true }
                }
            }

            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this
            // instance, so a new one per recomposition would re-read the file every frame.
            val navRailPreference = remember {
                filePersistedNavRailPreference(
                    key = "timeline",
                    storageDir = DesktopAppPaths.root(),
                )
            }

            // B2: the Settings row picks through the same dialog the guide does.
            LaunchedEffect(koin, awtWindow) {
                koin.get<DesktopTimelineImportControl>().attach { chooseExportFile(awtWindow) }
            }

            CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelStoreOwner,
                LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
                LocalLifecycleOwner provides savedStateRegistryOwner,
                LocalNavRailPreference provides navRailPreference,
            ) {
                TenshuTheme {
                    // ⚠⚠ See the Android shell: the inbox was resolved and never handed over, so
                    // Timeline's receiving half (TT-2b) navigated nowhere on either platform.
                    App(openTargets = koin.get<OpenTargetInbox>().targets)

                    if (showGuide) {
                        GoogleTimelineGuideDialog(
                            onChooseFile = {
                                showGuide = false
                                pickAndImport(awtWindow, koin) { result = it }
                            },
                            onDismiss = { showGuide = false },
                        )
                    }
                    result?.let { outcome ->
                        // The place-name-lookup offer (place-geocoding.md G3, D5): only after a
                        // successful import, and only while lookups are still off — enabling is
                        // the same consent the Settings toggle drives; the backfill trigger
                        // then fires by itself.
                        val geocodingControl = remember(koin) { koin.get<PlaceGeocodingControl>() }
                        ImportResultDialog(
                            message = outcome.message,
                            geocodingControl = geocodingControl
                                .takeIf { outcome.imported && !it.enabled.value },
                            onDismiss = { result = null },
                        )
                    }
                }
            }
        }
    }
}

/** The guided handoff: how to get the export out of Google, then the picker (§3.2). */
@Composable
private fun GoogleTimelineGuideDialog(onChooseFile: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Google Timeline") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Google Timeline has no import API, so you bring the file yourself — nothing leaves your device.")
                Text("1.  Open Google Maps on your phone.")
                Text("2.  Tap your profile picture, then Your Timeline.")
                Text("3.  Open the ⋯ menu / Settings, then Export Timeline data (on iPhone: Save to Files).")
                Text("4.  Transfer the saved Timeline.json to this computer, then choose it below.")
            }
        },
        confirmButton = { TextButton(onClick = onChooseFile) { Text("Choose file…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A whole Takeout archive (B1) — one pick, every monthly export inside it.
 *
 * ⚠⚠ The summary reports what was **not** understood as well as what was. A German export that
 * silently imports nothing while saying "done" is the failure TB-D2 exists to prevent, and the only
 * way a user can tell us about it is if the number is on screen.
 */
private suspend fun importArchive(file: File, koin: Koin, onResult: (ImportOutcome) -> Unit) {
    // ⚠ Streamed, exactly as on the phone (B2): one entry in memory at a time, and the same reader
    // on both platforms — two import paths is how they drift.
    val archive = StreamingTakeoutArchive { file.inputStream() }
    val result = try {
        koin.get<FileTimelineImporter>().importArchive(archive)
    } catch (e: Exception) {
        onResult(
            ImportOutcome(
                "Import failed — the entries could not be written. Is Tenshu Control set up on " +
                    "this device? (${e.message})",
                imported = false,
            ),
        )
        return
    }
    onResult(ImportOutcome(result.summary(file.name), imported = result.imported > 0))
}

/** What an import attempt produced — the user-facing summary + whether anything landed. */
private data class ImportOutcome(val message: String, val imported: Boolean)

/**
 * Open the native file picker for a Google-Timeline JSON, parse + persist it through the shared
 * [FileTimelineImporter] (I2b). Reading + writing runs off the UI thread; [onResult] reports back
 * a user-facing summary. Untrusted input parses to nothing rather than crashing (§6), which we
 * surface as a hint instead of a silent no-op.
 */
/**
 * The picker itself, shared by the guided first run and the Settings → Import row (B2).
 *
 * ⭐ B1: the **zip** is the unit Google hands the user (TB-D1). The single JSON still works — an
 * unzipped export is a legitimate thing to have — but nobody should have to unzip a decade of
 * monthly files and pick them one at a time.
 */
private fun chooseExportFile(window: Frame): File? {
    val dialog = FileDialog(window, "Import Google Takeout (.zip) or Timeline.json", FileDialog.LOAD).apply {
        setFilenameFilter { _, name ->
            name.endsWith(".json", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)
        }
        isVisible = true
    }
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}

private fun pickAndImport(window: Frame, koin: Koin, onResult: (ImportOutcome) -> Unit) {
    val picked = chooseExportFile(window) ?: return
    val name = picked.name

    val scope = koin.get<CoroutineScope>()
    scope.launch {
        if (name.endsWith(".zip", ignoreCase = true)) {
            importArchive(picked, koin, onResult)
            return@launch
        }
        val bytes = picked.readBytes()
        // Writes through the Control-backed client the repository reads from, so an imported
        // timeline lands in the store the screens observe (and syncs like any other row). The
        // enricher hook (place-geocoding.md G3) names the freshly imported places inline when
        // the lookup toggle is already on — consent-gated + never-throwing inside.
        // A failed write now throws (TimelineImportWriter surfaces op failures instead of
        // discarding the TResult) — distinct from "parsed nothing".
        val count = try {
            FileTimelineImporter(koin.get(), koin.get(), koin.get<PlaceLabelEnricher>())
                .importFiles(listOf(TimelineImportFile(name, bytes)))
        } catch (e: Exception) {
            onResult(
                ImportOutcome(
                    "Import failed — the entries could not be written. Is Tenshu Control set up " +
                        "on this device? (${e.message})",
                    imported = false,
                ),
            )
            return@launch
        }
        onResult(
            if (count == 0) {
                ImportOutcome(
                    "No timeline data found in \"$name\". Make sure it's a Google Timeline export " +
                        "(Timeline.json) or an unzipped Semantic Location History JSON.",
                    imported = false,
                )
            } else {
                ImportOutcome("Imported $count entries from \"$name\".", imported = true)
            },
        )
    }
}
