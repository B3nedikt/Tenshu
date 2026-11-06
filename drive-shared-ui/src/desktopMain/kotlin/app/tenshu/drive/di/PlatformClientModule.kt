package app.tenshu.drive.di

import app.tenshu.ingest.backup.BackupEnvironment
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.drive.data.DesktopFilesRepositoryPlatformDelegate
import app.tenshu.drive.data.FilesRepositoryPlatformDelegate
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.wireClientPushDispatch
import org.tenshu.core.FileUploader
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.common.transport.TransportFileUploader
import app.tenshu.ui.ipc.ControlServerCheck

val platformClientModule: Module = module {
    single<FilesRepositoryPlatformDelegate> { DesktopFilesRepositoryPlatformDelegate() }
    single<app.tenshu.drive.data.StorageSetupLauncher> {
        app.tenshu.drive.data.DesktopStorageSetupLauncher()
    }
    // Synced Folders auto-backup source + environment (desktop actuals).
    single<app.tenshu.ingest.backup.MediaSourceProvider> {
        app.tenshu.drive.data.DesktopMediaSourceProvider(preferences = get<BackupPreferences>())
    }
    single<app.tenshu.ingest.backup.BackupEnvironment> {
        app.tenshu.drive.data.DesktopBackupEnvironment()
    }
    // Desktop folder backup is Control-hosted (§4.2). The shared ViewModel uses this seam
    // to request a Control-owned security-scoped grant; Android keeps its SAF implementation.
    single<app.tenshu.drive.data.BackupFolderGrants> {
        app.tenshu.drive.data.ControlOwnedBackupFolderGrants(get())
    }

    single<TaskTransport> {
        ControlServerCheck.transport(get())
    }

    single<RemoteTaskExecutor> {
        val executor = RemoteBinaryTaskExecutor(
            serializer = get(),
            parser = get(),
            transport = get(),
            context = get()
        )

        // ⚠⚠ Not the inline form: a cross-app open is an UNSOLICITED composite (TT-3).
        // ⚠⚠ Providers, not instances — resolving the dispatcher here closes a DI cycle.
        executor.wireClientPushDispatch(
            streams = { get<StreamElementDispatcher>() },
            openTargets = { get<OpenTargetInbox>() },
        )

        executor
    }

    single<FileUploader> { TransportFileUploader(get()) }

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        emptyList()
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        DefaultTenshuClient(get()).apply { init() }
    }
}
