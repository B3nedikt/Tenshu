package app.tenshu.photos.di

import android.app.Application
import org.koin.core.context.GlobalContext
import app.tenshu.ingest.backup.AndroidMediaSourceProvider
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.TenshuBackupPreferences
import app.tenshu.outbox.FileOutboxStore
import app.tenshu.photos.ui.screens.collections.CollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.JsonCollectionsSnapshotStore
import java.io.File
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.AndroidTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.module.coreCommonAndroidModule
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.transport.BinderTaskTransport
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.core.TComponent
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache

/**
 * Android platform half of Photos' client graph — binds to Control's `IProxyInterface` over Binder
 * (`BinderTaskTransport`) and hosts `ClientCallbackService` for the reverse push channel (declared
 * in the manifest).
 *
 * ⚠⚠ `platformSchemaConfigs` carries the OOB byte-array carriers, and for this app they are not
 * optional in any sense: **every tile in the feed is a byte-carrying property**. A thumbnail
 * crosses Binder as a `FileByteArray` / `InMemoryByteArray`, and a client that has not registered
 * these parses none of them — Fit shipped without them and every routed read came back absent,
 * which here would be a grid of empty squares with no error anywhere.
 *
 * The FD / SharedMemory caches are bound because `BinderTaskTransport` requires them.
 */
val platformClientModule: Module = module {
    // PH-4b: Photos can now *add* a backup source, not only manage one. Both bindings come from
    // `:app-ingest` — the same engine Drive runs, reading the same device-scoped selection rows,
    // so a folder ticked in Photos is backed up by whichever app is running the drain.
    // ⚠ Android only: desktop's drain lives in Control's `FolderWatcher`, and a picker there would
    // write a selection this process never acts on.
    single<MediaSourceProvider> { AndroidMediaSourceProvider(get()) }
    single<BackupPreferences> { TenshuBackupPreferences(get()) }

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
            context = get(),
        )
        executor.streamElementHandler = { element ->
            if (element is StreamElement<*>) {
                get<StreamElementDispatcher>().dispatchElement(element)
            }
        }
        executor
    }

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        listOf(coreCommonAndroidModule(fileDescriptorCache = get(), sharedMemoryCache = get()))
    }

    // §36 step 2: the last Collections tab drawn, in Photos' own CACHE directory — never read
    // through Control, which is exactly what is slow when this is needed. ⚠ The cache dir, not
    // filesDir: it is a cache, the OS may clear it, and losing it costs one placeholder start.
    single<CollectionsSnapshotStore> {
        val application = GlobalContext.get().get<Application>()
        JsonCollectionsSnapshotStore(
            FileOutboxStore(File(application.cacheDir, JsonCollectionsSnapshotStore.DIRECTORY)),
        )
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        AndroidTenshuClient(get()).apply { init() }
    }
}
