package app.tenshu.home.di

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import app.tenshu.ui.ipc.ControlServerCheck

/**
 * Desktop platform half of Home's client (see `homeClientModule`) — connects
 * to Control-desktop's UDS (`UnixSocketTaskTransport`), exactly the transport
 * `:drive-desktop`/`:fit-desktop` use. Control-desktop must be running for
 * real hub data; when it isn't, the socket connect fails silently and the
 * hub-status reads come back empty → the storage tile keeps its mock state
 * (the honest fallback). Home still launches standalone.
 */
val homePlatformClientModule: Module = module {
    single<TaskTransport> {
        ControlServerCheck.transport(get())
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

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) { emptyList() }

    single<TenshuClient>(named("platformTenshuClient")) {
        DefaultTenshuClient(get()).apply { init() }
    }
}
