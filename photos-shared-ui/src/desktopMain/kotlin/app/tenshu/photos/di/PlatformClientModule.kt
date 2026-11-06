package app.tenshu.photos.di

import app.tenshu.outbox.FileOutboxStore
import app.tenshu.photos.ui.screens.collections.CollectionsSnapshotStore
import app.tenshu.photos.ui.screens.collections.JsonCollectionsSnapshotStore
import java.io.File
import org.koin.core.module.Module
import org.tenshu.core.common.storage.DesktopAppPaths
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
 * Desktop platform half of Photos' client graph — connects to Control-desktop's UDS
 * (`UnixSocketTaskTransport`, the shared `SocketPathResolver` `ipc.sock`).
 *
 * Requires the Control desktop app to be running, like every other desktop shell here. When it is
 * not, the feed's `Unreachable` state is what says so — never an empty grid.
 */
val platformClientModule: Module = module {
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

    // §36 step 2: the last Collections tab drawn, kept in Photos' own data directory — never read
    // through Control, which is exactly what is slow when this is needed.
    single<CollectionsSnapshotStore> {
        JsonCollectionsSnapshotStore(
            FileOutboxStore(File(DesktopAppPaths.dataDir("app.tenshu.photos.desktop"), JsonCollectionsSnapshotStore.DIRECTORY)),
        )
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        DefaultTenshuClient(get()).apply { init() }
    }
}
