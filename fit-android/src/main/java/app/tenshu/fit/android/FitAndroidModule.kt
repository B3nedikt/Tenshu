package app.tenshu.fit.android

import app.tenshu.fit.android.recorder.AndroidRecordingEngine
import app.tenshu.fit.data.ActivityFileParser
import app.tenshu.fit.data.HealthConnectImporter
import app.tenshu.fit.data.HealthFilePicker
import app.tenshu.fit.data.TakeoutParser
import app.tenshu.fit.data.xml.JvmXmlReader
import app.tenshu.fit.data.HealthImporter
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.RecordedWorkoutOutbox
import app.tenshu.fit.data.RecordingEngineFactory
import app.tenshu.liveness.AppForegroundState
import app.tenshu.outbox.FileOutboxStore
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-only bindings layered over `fitModules()` (docs/proposals/fit-app.md §9.5 / §9.8):
 *  - the live-import [HealthImporter] → [HealthConnectImporter] (P5.2), which offers no source
 *    at all when Health Connect is absent — never sample data (see its KDoc, fit-app.md §9.15).
 *    The concrete importer is exposed so [MainActivity] can drive the permission grant.
 *  - the recorder — a [RecordingEngineFactory] over [AndroidRecordingEngine] (the GPS / BLE-HR /
 *    step-counter capture, §9.8); `fitModules()` falls back to the mock engine when this is absent
 *    (desktop/iOS). Uses the app-scope `CoroutineScope` bound by `realDataModule`.
 */
fun fitAndroidModule(): Module = module {
    single { HealthConnectImporter(androidContext()) }
    single<HealthImporter> { get<HealthConnectImporter>() }
    // The permission prompt, reachable from the import-status banner's "Try again" (see
    // AndroidHealthPermissionRequester — MainActivity lends it the launcher while it is alive).
    single { AndroidHealthPermissionRequester() }
    single<HealthPermissionRequester> { get<AndroidHealthPermissionRequester>() }
    // The Google Takeout / activity-file import (§9.6b). The parser is bound here rather than in the
    // shared module because it needs a platform XML reader; MainActivity lends the SAF launcher.
    single { TakeoutParser(ActivityFileParser(xml = JvmXmlReader())) }
    single { AndroidHealthFilePicker() }
    single<HealthFilePicker> { get<AndroidHealthFilePicker>() }
    // Process foregroundedness, shared by anything that must reason about FGS-start allowances.
    // Bound here (not constructed inline) so there is exactly one ProcessLifecycle observer.
    single { AppForegroundState.observeProcess() }
    // Starts the import foreground service on the idle → in-flight edge (see FitImportForegroundService).
    single {
        FitImportServiceLauncher(
            appContext = androidContext(),
            repository = get(),
            foreground = get<AppForegroundState>().isForeground,
        )
    }
    single<RecordingEngineFactory> {
        RecordingEngineFactory { repository ->
            AndroidRecordingEngine(
                androidContext(),
                repository,
                get(),
                foreground = get<AppForegroundState>().isForeground,
            )
        }
    }
    // The durable recorder outbox (§9.13): app-private files, same posture as Timeline's
    // visit outbox (route + HR are A34 data — never outside filesDir).
    single { RecordedWorkoutOutbox(FileOutboxStore(File(androidContext().filesDir, "workout_outbox"))) }
}
