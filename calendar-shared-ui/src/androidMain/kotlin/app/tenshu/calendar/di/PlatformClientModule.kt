package app.tenshu.calendar.di

import android.app.Application
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.AndroidTenshuClient
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.wireClientPushDispatch
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
 * Android platform half of Calendar's client graph — binds to Control's
 * `IProxyInterface` over Binder (`BinderTaskTransport`) and hosts
 * `ClientCallbackService` for the reverse push channel (declared in the manifest).
 *
 * The FD / SharedMemory caches are bound because `BinderTaskTransport` requires
 * them. ⚠ `platformSchemaConfigs` carries the OOB byte-array carriers, and is
 * **not** optional even though Calendar reads no byte field today: any
 * byte-carrying property Control resolves crosses Binder as a `FileByteArray` /
 * `InMemoryByteArray`, and a client that has not registered them cannot parse
 * the response. Fit shipped without these and every routed read came back
 * absent — a silent failure, not an error.
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
        // ⚠⚠ Not the usual `if (element is StreamElement<*>)` one-liner. Calendar is the first
        // app to receive an UNSOLICITED composite — a cross-app open answers no pending task and
        // belongs to no stream — and the inline form every app carries drops it silently
        // (`control-cross-app-open.md` TT-1b).
        // ⚠⚠ Providers, not instances: `StreamElementDispatcher` is built FROM this executor, so
        // resolving it here would close a DI cycle. Both resolve on the first push instead.
        executor.wireClientPushDispatch(
            streams = { get<StreamElementDispatcher>() },
            openTargets = { get<OpenTargetInbox>() },
        )
        executor
    }

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        listOf(coreCommonAndroidModule(fileDescriptorCache = get(), sharedMemoryCache = get()))
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        AndroidTenshuClient(get()).apply { init() }
    }
}
