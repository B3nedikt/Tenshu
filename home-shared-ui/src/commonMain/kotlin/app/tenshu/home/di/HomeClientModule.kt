package app.tenshu.home.di

import app.tenshu.home.data.ClientDeviceControlSource
import app.tenshu.home.data.ClientDevicePairingSource
import app.tenshu.home.data.AutomationsRepository
import app.tenshu.home.data.ClientDeviceSource
import app.tenshu.home.data.ClientRoutineControlSource
import app.tenshu.home.data.ClientRoutineSource
import app.tenshu.home.data.MockAutomationsRepository
import app.tenshu.home.data.RoutineControlSource
import app.tenshu.home.data.SyncedAutomationsRepository
import kotlinx.datetime.TimeZone
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.util.generateId
import app.tenshu.home.data.ClientHubStatusSource
import app.tenshu.home.data.DeviceControlSource
import app.tenshu.home.data.DevicePairingSource
import app.tenshu.home.data.DeviceSource
import app.tenshu.home.data.HomeActivityRepository
import app.tenshu.home.data.HomeDevicesRepository
import app.tenshu.home.data.RoutineSource
import app.tenshu.home.data.SyncedHomeActivityRepository
import app.tenshu.home.data.syncedHomeDevicesRepository
import app.tenshu.home.data.HubStatusSource
import app.tenshu.home.ui.screens.devices.DevicesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.tenshu.home.data.homeControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.SupervisorJob
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.hub.management.client.hubManagementServiceClientModule
import org.tenshu.hub.management.common.hubManagementServiceCommonModule
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.MiaGrantsRepository
import app.tenshu.mia.data.TenshuConversationRepository
import app.tenshu.mia.data.TenshuMiaGrantsRepository
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus

/**
 * Every service schema this app's client registers, as one named list so
 * `HomeClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`; `photos-app.md` §18.4).
 */
val homeSchemaConfigs: List<ModuleConfiguration> = listOf(
    hubManagementServiceCommonModule,
    hubManagementServiceClientModule,
    homeDeviceServiceCommonModule,
    homeDeviceServiceClientModule,
    routineServiceCommonModule,
    routineServiceClientModule,
    // Home's embedded "Ask Mia" shares Mia's ChatScreen, so it shares its backend
    // too — Home already holds a client, so this is two schema configs and one
    // binding rather than a second client stack.
    miaServiceCommonModule,
    miaServiceClientModule,
    // Realm storage, for the storage banner. ⚠⚠ Registration is the load-bearing half:
    // an unregistered service fails at DISPATCH on a device, not at compile, and every
    // caller catches — so the banner would simply never appear.
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
    // ⚠ ADR 0025 §Decision 8, read-only: `getSyncStatus().ownerId` is how this app learns that
    // Control is installed but has no realm, so it shows the set-up wall instead of
    // a mock household. Unregistered, the read fails at DISPATCH and the wall silently never
    // fires. The accepted cost: the announce declares `:sync-service`, so a sync-service
    // version skew refuses this app's session.
    org.tenshu.sync.common.syncServiceCommonModule,
    org.tenshu.sync.client.syncServiceClientModule,
)

/**
 * Home's [TenshuClient] stack: the schemas in [homeSchemaConfigs], the announce, the
 * Control-reachability engine, and the synced repositories.
 *
 * Include this AFTER [homeModules] (Koin module order = override order, the
 * established Home/Mia pattern): its bindings replace the client-less defaults, and its
 * [HomeDevicesRepository] is `syncedHomeDevicesRepository` — bridged devices over the
 * hub-authored `HubStatus` tiles (hub proposal §13 H4) over nothing.
 *
 * The platform half (`homePlatformClientModule`) provides the transport +
 * `RemoteTaskExecutor` + the platform-built client (`platformTenshuClient`) —
 * the Fit/Maps client-assembly shape: UDS to Control-desktop on desktop, Binder
 * to Control on Android. iOS does not use this module; its route-1 runtime
 * (`HomeLocalRuntime`) composes the same devices chain in-process.
 */
val homeClientModule = module {

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
        homeSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
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
                appId = "app.tenshu.home",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                // Announce at launch, not on the session's first call — an app whose opening screen
                // issues no operation would otherwise never register (agent.md §7).
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // The announcer's refusal verdict feeds the reachability engine. Unbound, a
            // schema-skewed Home says "can't reach Control", which is both wrong and
            // unactionable: the proxy is answering, and refusing.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
        }
    }

    // The scope the reachability probe and the schema-skew mirror live on. ⚠ App-lifetime, not
    // screen-lifetime.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // The seam between the schema announce and the shell's words.
    single { MutableSchemaSkewSource() }

    /**
     * Home's one Control-reachability engine (`ADR 0025`).
     *
     * ⚠⚠ **No `schemaFence` / `proxyHost`.** Both read `syncService`, which Home registers for
     * the not-set-up wall (ADR 0025 §Decision 8, see [homeSchemaConfigs]), so wiring the fence is
     * a DI line rather than a schema-set change — but a separate decision. `Skew` works — its
     * verdict comes from the announce.
     */
    single {
        homeControlReachability(
            get<TenshuClient>(),
            probeScope = get(),
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            // ADR 0025 §Decision 8: Control installed and answering, no realm on this device → the
            // wall. The same two reads as Control's landing router; `get()` throws on a failed read,
            // which the engine reads as "cannot tell".
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired(get<TenshuClient>().let { client ->
                {
                    realmPresence(
                        isLocked = client.syncService.getUnlockStatus().get().isLocked.get(),
                        ownerId = client.syncService.getSyncStatus().get().ownerId?.get(),
                    )
                }
            }),
        )
    }

    // The embedded chat's real backend (mia-query-layer.md). ⚠ Deliberately not a
    // decorated mock like the sources below: an assistant that invents answers when the
    // proxy is unreachable is worse than one that says it cannot reach it.
    single<ConversationRepository> { TenshuConversationRepository(client = get()) }

    // The embedded chat obeys the same grants as Mia's own.
    single<MiaGrantsRepository> { TenshuMiaGrantsRepository(client = get()) }

    single<HubStatusSource> { ClientHubStatusSource(get()) }

    single<DeviceSource> { ClientDeviceSource(get()) }

    // "Add device" + actuation: the commands the Devices tab issues.
    single<DevicePairingSource> { ClientDevicePairingSource(get()) }

    single<DeviceControlSource> { ClientDeviceControlSource(get()) }

    // The Automations tab's real backing. Nothing falls back to the mock — a save
    // that silently landed in an in-memory mock would tell the user their
    // automation exists when nothing will ever run it.
    single<RoutineSource> { ClientRoutineSource(get()) }

    single<RoutineControlSource> { ClientRoutineControlSource(get()) }

    /**
     * The Activity feed over the engine's receipts (`routines-engine.md`).
     *
     * ⚠ It covers **one of the tab's three kinds**. Device events have no store at all and voice
     * has no pipeline, so the screen's empty state names each rather than implying a quiet home —
     * see `SyncedHomeActivityRepository`.
     */
    single<HomeActivityRepository> { SyncedHomeActivityRepository(get<RoutineSource>()) }

    single<AutomationsRepository> {
        SyncedAutomationsRepository(
            source = get<RoutineSource>(),
            control = get<RoutineControlSource>(),
            // ⚠⚠ The routine carries the AUTHORING device's zone (RE-D3): a
            // laptop that later travels must not shift "22:30", and a desktop
            // 400 km north must not compute a different sunset.
            timeZoneId = { TimeZone.currentSystemDefault().id },
            newId = { generateId() },
        )
    }

    // Re-bind the VM with the synced seams present (module order = override
    // order): a mock-only Home keeps the two-arg-less binding from
    // `viewModelsModule`, so it offers no pairing flow and no controls at all.
    viewModel {
        DevicesViewModel(
            repository = get(),
            deviceSource = get<DeviceSource>(),
            pairingSource = get<DevicePairingSource>(),
            controlSource = get<DeviceControlSource>(),
        )
    }

    // Two decorators over the empty repository: the storage-tile overlay (HubStatus
    // rows) underneath, the bridged devices (HD-D5) on top — each falls back to its
    // delegate whenever its source emits nothing, so with no hub the tab is empty.
    // ⚠ Composed in `:home-shared-logic` so the iOS route-1 runtime builds the same chain.
    single<HomeDevicesRepository> {
        syncedHomeDevicesRepository(
            hubStatus = get<HubStatusSource>(),
            devices = get<DeviceSource>(),
        )
    }
}
