package app.tenshu.mia.di

import android.app.Application
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.AndroidTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.LaunchPerformer
import org.tenshu.core.common.app.fireLaunchTicket
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
 * Android platform half of Mia's client (see `miaClientModule`) — binds to Control's
 * `IProxyInterface` over Binder and hosts `ClientCallbackService` for the reverse push
 * channel (declared in `:mia-android`'s manifest). The desktop twin is the same assembly
 * over the UDS transport.
 *
 * Mia's whole wire surface is one request/reply op (`AskMia`), so nothing here moves
 * bytes — but the FD / SharedMemory caches are still bound because
 * `BinderTaskTransport` requires them, and `platformSchemaConfigs` still carries the OOB
 * byte-array carriers: a client that has not registered them cannot parse a response that
 * happens to contain one ("No type id registered for bytes: …"), which is how Fit read
 * every routed workout as absent.
 *
 * If Control is not installed or is sealed, `bindService` returns false without throwing
 * and every turn answers "I can't reach Control" — ⚠ never a mocked reply (see
 * `TenshuConversationRepository`). Mia always launches standalone.
 */
val miaPlatformClientModule: Module = module {
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

    // The Android OOB byte-array carriers: any byte-carrying property Control resolves from its
    // FileDatabase crosses Binder as a `FileByteArray` / `InMemoryByteArray`, and a client that
    // has not registered them cannot parse the response ("No type id registered for bytes: …").
    // Fit shipped without them and every routed workout read as absent — see
    // [coreCommonAndroidModule].
    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        listOf(coreCommonAndroidModule(fileDescriptorCache = get(), sharedMemoryCache = get()))
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        AndroidTenshuClient(get()).apply { init() }
    }

    // Cross-app open (control-cross-app-open.md TT-1b): Control resolves the handler and mints
    // the launch; this app fires it, because it is the one in the foreground holding the tap.
    single<LaunchPerformer> { LaunchPerformer { ticketId -> fireLaunchTicket(ticketId) } }
}
