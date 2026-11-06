package app.tenshu.maps.di

import android.app.Application
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.wireClientPushDispatch
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
import app.tenshu.mapregion.app.JvmRegionBundleStager
import app.tenshu.mapregion.app.RegionBundleStager
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache

/**
 * Android platform half of Maps' client graph — binds to Control's `IProxyInterface` over Binder
 * (`BinderTaskTransport`) and hosts `ClientCallbackService` for the reverse push channel (declared
 * in the manifest). Maps needs no File-Service parser modules (region ops are request/reply), so
 * `platformSchemaConfigs` carries the OOB byte-array carriers (see [coreCommonAndroidModule]);
 * the FD/SharedMemory caches are bound because
 * `BinderTaskTransport` requires them (and keep full parity if downloads ever stream OOB bytes).
 */
val platformClientModule: Module = module {
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
        // ⚠⚠ Not the inline `if (element is StreamElement<*>)` this used to be: a cross-app open
        // is an UNSOLICITED composite and the inline form drops it silently (TT-2b).
        // ⚠⚠ Providers, not instances — `StreamElementDispatcher` is built FROM this executor, so
        // resolving it here closes a DI cycle that dies as a `StackOverflowError` at launch.
        executor.wireClientPushDispatch(
            streams = { get<StreamElementDispatcher>() },
            openTargets = { get<OpenTargetInbox>() },
        )
        executor
    }

    // The Android OOB byte-array carriers: any byte-carrying property Control resolves from its
    // FileDatabase crosses Binder as a `FileByteArray` / `InMemoryByteArray`, and a client that
    // has not registered them cannot parse the response ("No type id registered for bytes: …").
    // Fit shipped without them and every routed workout read as absent — see
    // [coreCommonAndroidModule].
    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        listOf(coreCommonAndroidModule(fileDescriptorCache = get(), sharedMemoryCache = get()))
    }

    // Render-hop terminus: region bundles stage into app-private filesDir, ready for
    // MapLibre's pmtiles://file:// (maps-region-delivery.md §7).
    single<RegionBundleStager> {
        val application = GlobalContext.get().get<Application>()
        JvmRegionBundleStager(application.filesDir.resolve("map-regions"))
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        AndroidTenshuClient(get()).apply { init() }
    }
}
