package app.tenshu.maps.di

import java.io.File
import org.tenshu.core.common.storage.DesktopAppPaths
import app.tenshu.mapregion.app.JvmRegionBundleStager
import app.tenshu.mapregion.app.RegionBundleStager
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.wireClientPushDispatch
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
 * Desktop platform half of Maps' client graph — connects to Control-desktop's UDS
 * (`UnixSocketTaskTransport`, the shared `SocketPathResolver` `ipc.sock`). Requires the Control
 * desktop app to be running (like Drive-desktop).
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
        // ⚠⚠ Not the inline form: a cross-app open is an UNSOLICITED composite (TT-3).
        // ⚠⚠ Providers, not instances — resolving the dispatcher here closes a DI cycle.
        executor.wireClientPushDispatch(
            streams = { get<StreamElementDispatcher>() },
            openTargets = { get<OpenTargetInbox>() },
        )
        executor
    }

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) { emptyList() }

    // Desktop staging dir (the schematic basemap ignores it today, but the store is built
    // so the swap is ready if MapLibre's desktop offline path lands — §9 impl notes).
    // ⚠ Under DesktopAppPaths.root() — the App Group container on a packaged Mac — never a
    // hard-coded ~/.tenshu, which a packaged build must not touch.
    single<RegionBundleStager> {
        JvmRegionBundleStager(File(DesktopAppPaths.root(), "maps/map-regions"))
    }

    single<TenshuClient>(named("platformTenshuClient")) {
        DefaultTenshuClient(get()).apply { init() }
    }
}
