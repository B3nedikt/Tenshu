package app.tenshu.maps.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.flow.map
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule

/**
 * Every service schema this app's client registers, as one named list so
 * `MapsClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`; `photos-app.md` §18.4).
 */
val mapsSchemaConfigs: List<ModuleConfiguration> = listOf(
    // The sync service (`core-schema-skew-surface.md` 8.3.2, Option A), so this app can
    // read its own realm pull fence and the proxy's RuntimeIdentity and tell the user
    // which app to update. Control hosts :sync-service-proxy and there is
    // no per-app service allowlist, so declaring the client half here meets a proxy that
    // knows the service - no announce-gate skew.
    //
    // WARN: not free. The announce compares ONLY services a client declares, so
    // registering this joins the app to sync-service's lockstep set. Registering a service
    // in order to DISPLAY skew makes the app subject to more of it - accepted
    // deliberately, because the alternative is silence about a skew the app can see.
    org.tenshu.sync.common.syncServiceCommonModule,
    org.tenshu.sync.client.syncServiceClientModule,
    placeServiceCommonModule,
    placeServiceClientModule,
    mapRegionServiceCommonModule,
    mapRegionServiceClientModule,
    // Realm storage, for `RealmStorageBannerHost`. ⚠⚠ Registration is the load-bearing
    // half: without it the read fails at DISPATCH on a device, not at compile, and every
    // caller catches — the banner would simply never appear.
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
)

/**
 * Maps' client graph (`maps-region-delivery.md` D6 + `maps-app.md` §7): a `TenshuClient` that
 * reaches the **Control** proxy over IPC (Android Binder / desktop UDS). It carries both of Maps'
 * service surfaces:
 *
 *  - **`MapRegionService`** — the offline-basemap region picker.
 *  - **`:place-service`** — the app's data plane. Places are read and written in Control's durable,
 *    synced store, **shared with Timeline** (whose ingestion creates them), rather than in an
 *    in-process store that dies with the app (`maps-app.md` §7).
 *
 * Mirrors Drive's `tenshuClientModule`, trimmed to the two services Maps actually speaks.
 *
 * The platform half (`platformClientModule`, per source set) provides the `RemoteTaskExecutor` +
 * the transport + the platform-built `TenshuClient` (named `platformTenshuClient`).
 */
val mapsClientModule = module {

    /**
     * Where a cross-app open lands before the composition can collect it
     * (`control-cross-app-open.md` TT-2b).
     *
     * ⚠ A singleton: the Android shell delivers a cold-start target into it, the transport delivers
     * warm pushes from a Binder thread, and `App()` collects it. Three holders would be three
     * inboxes and two of them empty.
     */
    single { OpenTargetInbox() }

    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        mapsSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
    }

    single<TaskExecutor> { get<RemoteTaskExecutor>() }

    single {
        createRemoteTenshuEnvironment(
            tContext = get(),
            remoteTaskExecutor = get<RemoteTaskExecutor>(),
            schemaRegistry = get(),
        )
    }

    single<TenshuClient> {
        get<TenshuClient>(named("platformTenshuClient")).also { client ->
            // Schema announce (core-schema-evolution.md §6.4): installed as the
            // executor's session preflight, so the announce rides ahead of the
            // session's first call and of every post-reconnect resubscribe.
            val announcer = wireSchemaAnnounce(
                client = client,
                executor = get<RemoteTaskExecutor>(),
                appId = "app.tenshu.maps",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                // Announce at launch, not on the session's first call — an app whose opening screen
                // issues no operation would otherwise never register (agent.md §7).
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // Binds the refusal verdict (`core-schema-skew-surface.md`) so the shell banner
            // names the app to update instead of rendering "Can't reach Tenshu Control", which on
            // Android and desktop is both wrong and unactionable — the proxy is answering.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
            // The proxy's own RuntimeIdentity (which binary to name) and this
            // device's realm pull fence. Bound on the same object as the announce verdict so one
            // forgotten bind cannot leave the app silently unable to mention a problem it is
            // already detecting.
            get<MutableSchemaSkewSource>().bindHost(
                client.syncService.runtimeIdentity().map { it.isControlHosted() },
                get<CoroutineScope>(),
            )
            get<MutableSchemaSkewSource>().bindFence(
                client.syncService.schemaFence().map { it?.fencedServices() ?: emptyList() },
                get<CoroutineScope>(),
            )
        }
    }
}
