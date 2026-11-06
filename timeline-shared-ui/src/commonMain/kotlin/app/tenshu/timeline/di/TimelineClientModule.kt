package app.tenshu.timeline.di

import app.tenshu.mapregion.app.RegionBundleStager
import app.tenshu.mapregion.app.RegionTerminus
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.TerminusRegionBundleResolver
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.TargetOpener
import org.tenshu.core.common.app.LaunchPerformer
import org.tenshu.core.common.app.ControlTargetOpener
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
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.getOrNull
import org.tenshu.file.client.declareWantedThumbnailClassesAtLaunch
import org.tenshu.preference.client.preferenceService
import org.tenshu.sync.getSyncStatus
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule

/**
 * Every service schema this app's client registers, as one named list so
 * `TimelineClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`). Photos lost three phases to exactly
 * this (`photos-app.md` §18.4).
 */
val timelineSchemaConfigs: List<ModuleConfiguration> = listOf(
    // SK-1b/SK-3 (`core-schema-skew-surface.md` 8.3.2, Option A): the sync service, so
    // this app can read its own realm pull fence and the proxy's RuntimeIdentity and tell
    // the user which app to update. Control hosts :sync-service-proxy already and there is
    // no per-app service allowlist, so declaring the client half here meets a proxy that
    // knows the service - no announce-gate skew.
    //
    // WARN: not free. The announce compares ONLY services a client declares, so
    // registering this joins the app to sync-service's lockstep set. Registering a service
    // in order to DISPLAY skew makes the app subject to more of it - accepted
    // deliberately, because the alternative is the silence this whole track exists to
    // remove.
    org.tenshu.sync.common.syncServiceCommonModule,
    org.tenshu.sync.client.syncServiceClientModule,
    timelineServiceCommonModule,
    timelineServiceClientModule,
    placeServiceCommonModule,
    placeServiceClientModule,
    healthServiceCommonModule,
    healthServiceClientModule,
    mapRegionServiceCommonModule,
    mapRegionServiceClientModule,
    // FP-6 (fit-app.md §18.13): the owner's units live in `OwnerPreferences`, so Timeline
    // reads the preference service like Drive and Fit do. Control already hosts the proxy
    // half, so declaring it here meets a proxy that knows the service.
    preferenceServiceCommonModule,
    preferenceServiceClientModule,
    // T-P1 (`timeline-photos.md`): the moment hero shows the photos you took, and photos
    // are Drive files — so Timeline reads `:file-service` like Drive does. Control hosts
    // it already, so declaring it here meets a proxy that knows the service.
    //
    // ⚠⚠ The **client** module is not optional decoration: `File.Preview` and
    // `File.Thumbnail` are dynamic / on-demand properties whose request objects resolve
    // through this module's `roFactory` registrations. Without it every select that
    // projects one dies as an opaque `error/internal` (the same failure as a missing
    // `Workout.Streams` factory). An in-process test registers everything and would
    // never show it; the on-device read is the gate.
    fileServiceCommonModule,
    fileServiceClientModule,
    // Realm storage, for `RealmStorageBannerHost`. ⚠⚠ Registration is the load-bearing
    // half: without it the read fails at DISPATCH on a device, not at compile, and every
    // caller catches — so the banner would simply never appear. Control hosts
    // :storage-service-proxy and Drive already registers this pair, so declaring it here
    // meets a proxy that already knows the service (no announce-gate skew).
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
)

/**
 * Timeline's [TenshuClient] — the **one** client, reaching the **Control** proxy over IPC (Android
 * Binder / desktop UDS). It carries every service surface the app uses:
 *
 *  - **`:timeline-service` + `:place-service` + `:health-service`** — the app's data plane. Moments,
 *    places and the activity rollups are read and written here, exactly as Drive does its files.
 *    Control already hosts all three (schema + ops + relation maintainers) over its durable store,
 *    so Timeline gets persistence, cross-device sync and the sealed realm for free.
 *  - **`MapRegionService`** — best-effort offline basemap regions for trip routes
 *    (`maps-region-delivery.md` D6 / §7); unavailable → `TripRouteMap` keeps the bundled demo.
 *
 * **Timeline requires Control.** Its data lives in Control's store and nowhere else — the app used
 * to host its own in-process, in-memory copy of these three services alongside this client, so
 * every captured visit and imported moment was lost when the process died (`timeline-app.md` §10,
 * and the same bug Fit had: `fit-app.md` §10).
 *
 * The platform half (`platformClientModule`, per source set) provides the `RemoteTaskExecutor` +
 * the transport + the platform-built `TenshuClient` (named `platformTenshuClient`) + the
 * `RegionBundleStager`.
 */
val timelineClientModule = module {

    /**
     * Where a cross-app open lands before the composition can collect it
     * (`control-cross-app-open.md` TT-2b).
     *
     * ⚠ A singleton: the Android shell delivers a cold-start target into it, the transport delivers
     * warm pushes from a Binder thread, and `App()` collects it. Three holders would be three
     * inboxes and two of them empty.
     */
    single { OpenTargetInbox() }


    /**
     * Cross-app open (`control-cross-app-open.md` TT-2a) — what a tapped photo calls.
     *
     * ⚠ `getOrNull` on the performer: a platform with no launcher bound still gets an opener that
     * refuses honestly, rather than a DI failure at app start over a feature nobody has touched.
     */
    single<TargetOpener> {
        ControlTargetOpener(
            client = get(),
            performLaunch = getOrNull() ?: LaunchPerformer { false },
        )
    }

    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        timelineSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
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
                appId = "app.tenshu.timeline",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                // Announce at launch, not on the session's first call — an app whose opening screen
                // issues no operation would otherwise never register (agent.md §7).
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // SK-1b (`core-schema-skew-surface.md`): binds the refusal verdict so the shell banner
            // names the app to update instead of rendering "Can't reach Tenshu Control", which on
            // Android and desktop is both wrong and unactionable — the proxy is answering.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
            // SK-2/SK-3: the proxy's own RuntimeIdentity (which binary to name) and this
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
            // W-1 app-side (drive-synced-thumbnails.md §15.11): the moment detail draws the
            // hero rung (`heroFidelity`), and a warm device pulls it only on a cache miss — so the
            // proxy's pull-path declaration would never see it. Said at launch instead.
            // ADR 0025 §8.6: through the realm gate, resolved lazily (the client is still being built).
            val koinScope = this
            val realmGate = { koinScope.getOrNull<app.tenshu.timeline.data.TimelineRepository>()?.realmWriteGate }
            get<CoroutineScope>().declareWantedThumbnailClassesAtLaunch(
                preferences = client.preferenceService,
                deviceId = {
                    client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()?.takeIf { it.isNotBlank() }
                },
                awaitWritable = { realmGate()?.awaitRealmWritable() },
                beforeWrite = { realmGate()?.refuseIfRealmWritesHeld() },
                retryAfter = { it is app.tenshu.reachability.RealmWritesHeldException },
            ) {
                // ⚠ Both, because the hero asks for 1600 px: `XXLARGE` is what answers it sharp and
                // `XLARGE` is what a realm no device has backfilled still has
                // (`drive-xxlarge-thumbnail-rung.md` §4.4, X-6). Declaring only `XLARGE` told the
                // realm's byte holders to mint a class this app then stretched 1.56x.
                setOf(GetStoredThumbnail.CLASS_XXLARGE, GetStoredThumbnail.CLASS_XLARGE)
            }
        }
    }

    single<RegionBundleResolver> {
        TerminusRegionBundleResolver(
            RegionTerminus(get<TenshuClient>(), getOrNull<RegionBundleStager>()),
        )
    }
}
