package app.tenshu.mia.di

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.LaunchPerformer
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import app.tenshu.ui.ipc.ControlServerCheck

/**
 * Desktop platform half of Mia's client (see `miaClientModule`) — connects to
 * Control-desktop's UDS, the transport `:drive-desktop`/`:fit-desktop` use.
 * Control-desktop must be running; when it is not, the connect fails and every turn
 * answers "I can't reach Control" rather than degrading to a mocked reply. Mia still
 * launches standalone.
 */
val miaPlatformClientModule: Module = module {
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

    // ⚠⚠ **Never reached on desktop, by construction — TT-3.** macOS has no
    // background-activity-launch rule, so Control launches AND raises the handler itself and hands
    // the caller an empty `launchTicketId`; `TargetOpener` then returns before consulting this.
    // Kept as a defensive no-op rather than deleted: an absent binding would make a future
    // ticket-bearing outcome a tap that silently does nothing (CO-D7).
    single<LaunchPerformer> { LaunchPerformer { false } }
}
