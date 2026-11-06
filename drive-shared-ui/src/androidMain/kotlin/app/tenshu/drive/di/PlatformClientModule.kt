package app.tenshu.drive.di

import app.tenshu.ingest.backup.BackupEnvironment
import app.tenshu.ingest.backup.BackupSource
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.documentTreeBackupSource
import android.app.Application
import app.tenshu.drive.data.AndroidFilesRepositoryPlatformDelegate
import app.tenshu.drive.data.FilesRepositoryPlatformDelegate
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.FileUploader
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.AndroidTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.transport.BinderTaskTransport
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.wireClientPushDispatch
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.common.module.coreCommonAndroidModule
import org.tenshu.core.common.transport.TransportFileUploader
import org.tenshu.core.core.TComponent
import org.tenshu.core.file.FileService
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache

val platformClientModule: Module = module {
    single<FilesRepositoryPlatformDelegate> { AndroidFilesRepositoryPlatformDelegate(get()) }
    single<app.tenshu.drive.data.StorageSetupLauncher> {
        app.tenshu.drive.data.AndroidStorageSetupLauncher(get())
    }
    // Synced Folders auto-backup source + environment (android actuals).
    single<app.tenshu.ingest.backup.MediaSourceProvider> {
        app.tenshu.ingest.backup.AndroidMediaSourceProvider(get())
    }
    // Android is the only platform with a partial-media-access state (API 34+).
    single<app.tenshu.ingest.backup.MediaAccessProvider> {
        app.tenshu.ingest.backup.AndroidMediaAccessProvider(get())
    }
    single<app.tenshu.ingest.backup.BackupEnvironment> {
        app.tenshu.ingest.backup.AndroidBackupEnvironment(get())
    }
    // Phase 3b — the SAF document-tree source (bound by concrete type so it does
    // not clobber the gallery MediaSourceProvider binding above) + the persistent
    // grant-release seam + the extra-source contribution the engine folds in.
    single { app.tenshu.drive.data.AndroidDocumentTreeSourceProvider(get()) }
    single<app.tenshu.drive.data.BackupFolderGrants> {
        app.tenshu.drive.data.AndroidBackupFolderGrants(get())
    }
    single<List<app.tenshu.ingest.backup.BackupSource>>(named("extraBackupSources")) {
        listOf(
            app.tenshu.ingest.backup.documentTreeBackupSource(
                get<app.tenshu.drive.data.AndroidDocumentTreeSourceProvider>(), get(),
            ),
        )
    }

    // Per-process caches for FDs / SharedMemory arriving from Control via
    // the Binder callback stub. Exposed as named TComponents so anything
    // resolving via `TContext.components` finds them via the generic
    // fallback in `coreCommonModule`.
    single { ParcelFileDescriptorCache() }
    single { SharedMemoryCache() }
    single<TComponent>(named(ParcelFileDescriptorCache.ID)) { get<ParcelFileDescriptorCache>() }
    single<TComponent>(named(SharedMemoryCache.ID)) { get<SharedMemoryCache>() }

    single<TaskTransport> {
        val application = GlobalContext.get().get<Application>()
        BinderTaskTransport(application, get(), get())
    }

    single<RemoteTaskExecutor> {
        val executor = RemoteBinaryTaskExecutor(
            serializer = get(),
            parser = get(),
            transport = get(),
            context = get()
        )
        // ⚠⚠ Not the inline `if (element is StreamElement<*>)` this used to be: a cross-app open
        // is an UNSOLICITED composite — it answers no pending task and belongs to no stream — and
        // the inline form drops it silently (`control-cross-app-open.md` TT-2a).
        //
        // ⚠⚠ Providers, not instances: `StreamElementDispatcher` is built FROM this executor, so
        // resolving it here closes a DI cycle that dies as a `StackOverflowError` at launch.
        executor.wireClientPushDispatch(
            streams = { get<StreamElementDispatcher>() },
            openTargets = { get<OpenTargetInbox>() },
        )

        executor
    }

    single<FileUploader> { TransportFileUploader(get()) }

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        listOf(
            coreCommonAndroidModule(
                fileDescriptorCache = get(),
                sharedMemoryCache = get()
            ),
        )
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        AndroidTenshuClient(get()).apply { init() }
    }
}
