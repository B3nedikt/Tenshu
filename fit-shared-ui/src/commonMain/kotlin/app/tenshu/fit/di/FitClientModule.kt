package app.tenshu.fit.di

import app.tenshu.fit.data.RegionBundleResolver
import app.tenshu.fit.data.TerminusRegionBundleResolver
import app.tenshu.mapregion.app.RegionBundleStager
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.flow.map
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import app.tenshu.mapregion.app.RegionTerminus
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule

/**
 * Every service schema this app's client registers, as one named list so
 * `FitClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`; `photos-app.md` §18.4).
 */
val fitSchemaConfigs: List<ModuleConfiguration> = listOf(
    healthServiceCommonModule,
    healthServiceClientModule,
    // fit-automatic-activity-detection.md §14.2: Timeline's `MovementTrace` rows,
    // read-only, so a captured walk/run/ride can be promoted into a `Workout`. Control
    // hosts :timeline-service-proxy and there is no per-app service allowlist, so
    // registering the client half here meets a proxy that already knows the service —
    // no announce-gate skew, the same argument the preference registration below makes.
    org.tenshu.timeline.common.timelineServiceCommonModule,
    org.tenshu.timeline.client.timelineServiceClientModule,
    mapRegionServiceCommonModule,
    mapRegionServiceClientModule,
    // The task-composite client surface (client.taskService) so the
    // import registers a BackgroundTask and its writes batch on the sync
    // lane. Ops resolve against Control; on a Control without them they return
    // unavailable and the import falls back to per-save sync.
    org.tenshu.sync.common.syncServiceCommonModule,
    org.tenshu.sync.client.syncServiceClientModule,
    // fit-app.md §18.5: goals + the owner's person persist as REALM preference
    // rows. Control hosts :preference-service-proxy, so declaring it here meets a proxy
    // that already knows the service (no announce-gate skew).
    org.tenshu.preference.common.preferenceServiceCommonModule,
    org.tenshu.preference.client.preferenceServiceClientModule,
    // The device roster, read-only, so a source row can carry the name the user
    // gave that device rather than the platform name stamped at register time. Control
    // hosts :identity-service-proxy and Drive already registers this pair, so declaring
    // it here meets a proxy that already knows the service — no announce-gate skew.
    //
    // ⚠⚠ Registration is the load-bearing half: without it the read fails at DISPATCH on
    // a device, not at compile, and the label silently falls back to the stamp.
    org.tenshu.identity.common.identityServiceCommonModule,
    org.tenshu.identity.client.identityServiceClientModule,
    // The realm-wide storage answer, for `RealmStorageBanner`. `StorageSyncGate` gates the
    // personal realm's PUSH at the scheduler, so an unfunded realm stops Fit's workouts
    // leaving the device exactly as it stops Drive's photos.
    //
    // ⚠⚠ Registration is the load-bearing half: without it the read fails at DISPATCH on a
    // device, not at compile, and every caller catches — so the banner would simply never
    // appear.
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
)

/**
 * Fit's [TenshuClient] — the **one** client, reaching the **Control** proxy over IPC (Android
 * Binder / desktop UDS). It carries both of Fit's service surfaces:
 *
 *  - **`:health-service`** — the app's data plane. Every workout, daily activity, sleep session,
 *    sample and medical record is read and written here, exactly as Drive does its files. Control
 *    already hosts the service (schema + ops + relation maintainers) over its durable store, so
 *    Fit gets persistence, cross-device sync and the sealed realm for free.
 *  - **`MapRegionService`** — best-effort offline basemap regions for workout routes
 *    (`maps-region-delivery.md` D6 / §7); when it is unavailable the route falls back to the
 *    bundled demo basemap.
 *
 * **Fit requires Control.** Health data lives in Control's store and nowhere else — Fit hosts no
 * `:health-service` of its own, so nothing it records lives only in its process; it is a Control
 * client like Drive. See `docs/proposals/fit-app.md` §10.
 *
 * The platform half (`platformClientModule`, per source set) provides the `RemoteTaskExecutor` +
 * the transport + the platform-built `TenshuClient` (named `platformTenshuClient`) + the
 * `RegionBundleStager`. The reverse push channel the reactive reads need is in place on
 * both platforms (Android: `ClientCallbackService` in the manifest; desktop: the UDS callback).
 */
val fitClientModule = module {

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
        fitSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
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
                appId = "app.tenshu.fit",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                // Announce at launch, not on the session's first call — an app whose opening screen
                // issues no operation would otherwise never register (agent.md §7).
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // `core-schema-skew-surface.md`: binding the announcer's refusal verdict here is what
            // lets the shell banner name the app to update; unbound, a schema-skewed device
            // renders "Can't reach Tenshu Control", which is both wrong and unactionable.
            //
            // ⚠ Bound into a source the repository already holds, rather than passed to it: the
            // repository is constructed from its own module and may exist before this client
            // single is ever resolved. The source's flow is valid from construction and starts
            // mirroring whenever the announcer appears, so neither order can drop the verdict.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
            // The other half of the same signal — this device's realm pull fence. Bound on
            // the same object as the announce verdict so one forgotten `bind` cannot leave the
            // app silently unable to mention a problem it is already detecting.
            // Who hosts this runtime, straight from the proxy's own published row.
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

    single<RegionBundleResolver> {
        TerminusRegionBundleResolver(
            RegionTerminus(get<TenshuClient>(), getOrNull<RegionBundleStager>()),
        )
    }
}
