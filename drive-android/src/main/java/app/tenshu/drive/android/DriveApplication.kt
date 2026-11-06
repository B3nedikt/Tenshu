package app.tenshu.drive.android

import app.tenshu.ingest.backup.NoopNewFolderAnnouncer
import android.app.Application
import app.tenshu.drive.android.backup.AndroidNewFolderAnnouncer
import app.tenshu.drive.android.backup.SyncedFolderServiceLauncher
import app.tenshu.ingest.backup.NewFolderAnnouncer
import app.tenshu.drive.android.upload.FolderUploadServiceLauncher
import app.tenshu.liveness.AppForegroundState
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.drive.data.BackupWorkScheduler
import app.tenshu.drive.di.filesRepositoryModule
import app.tenshu.drive.di.platformClientModule
import app.tenshu.drive.di.tenshuClientModule
import app.tenshu.drive.di.viewModelsModule
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

/**
 * Bindings that only exist in the Android app shell (they reach into
 * `drive-android` classes that `drive-shared-ui` can't see). Kept here rather than
 * in `platformClientModule` for exactly that dependency direction.
 */
private val driveAndroidModule = module {
    // The new-folder prompt's Android surface. Absent on desktop, where the shared
    // DI falls back to NoopNewFolderAnnouncer.
    single<NewFolderAnnouncer> { AndroidNewFolderAnnouncer(get()) }
}

class DriveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (smoke gate asserts Control + clients share this).
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))
        val koin = startKoin {
            androidLogger()
            androidContext(this@DriveApplication)
            modules(
                tenshuClientModule,
                platformClientModule,
                filesRepositoryModule(),
                viewModelsModule(),
                driveAndroidModule,
            )
        }.koin

        // Drive-side folder-upload foreground service seam
        // (docs/proposals/drive-folder-upload.md §5d): observe the same
        // app-scoped FolderUploadController `single` the ViewModel observes
        // and start the FGS while an upload is in flight, so a backgrounded
        // Drive process (hence the orchestration loop) is not OS-reclaimed
        // mid-upload. Mirrors Control's ProxyService + ForegroundTaskLiveness
        // seam; reads only Drive-owned state (isolation invariant).
        // ⚠ An FGS start is refusable since API 31, and an uncaught refusal is fatal. The
        // launchers attempt eagerly (a backgrounded Drive may still hold a while-in-use allowance
        // — see armForegroundService) and use this process-foreground signal to retry a refusal.
        val foreground = AppForegroundState.observeProcess()
        FolderUploadServiceLauncher(
            appContext = this,
            controller = koin.get<FolderUploadController>(),
            foreground = foreground.isForeground,
        ).start()

        // Synced Folders (drive-synced-folders-phase2.md §4.1a): start the loop so it
        // runs while the Drive process is alive even if the user never opens the screen.
        // ⚠⚠ The foreground service below carries ONLY a backup the user started
        // ("Back up now") — Play's dataSync policy rules it out for automatic backup,
        // which is WorkManager's (BackupWorkScheduler, next) and runs without one.
        val syncedFolderController = koin.get<SyncedFolderController>()
        syncedFolderController.start()
        SyncedFolderServiceLauncher(
            appContext = this,
            controller = syncedFolderController,
            foreground = foreground.isForeground,
        ).start()

        // Automatic backup (drive-synced-folders-phase2.md §4.1 / §4.1a): the WorkManager
        // jobs — a MediaStore content trigger, a periodic floor and, when a run's budget runs
        // out, a continuation — so backup runs while Drive is *closed*, with no foreground
        // service. The scheduler tracks the backup policy and arms/cancels them accordingly.
        BackupWorkScheduler(
            context = this,
            preferences = koin.get<BackupPreferences>(),
        ).start()
    }
}
