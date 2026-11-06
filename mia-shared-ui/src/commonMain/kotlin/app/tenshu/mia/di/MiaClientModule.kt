package app.tenshu.mia.di

import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.MiaGrantsRepository
import app.tenshu.mia.data.TenshuConversationRepository
import app.tenshu.mia.data.TenshuMiaGrantsRepository
import app.tenshu.mia.ui.screens.settings.GrantsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import app.tenshu.mia.ui.LaunchAnnouncer
import org.tenshu.core.common.app.LaunchPerformer
import org.tenshu.core.common.app.SchemaAnnouncer
import org.tenshu.core.common.app.ControlTargetOpener
import org.tenshu.core.common.app.TargetOpener
import app.tenshu.mia.data.miaControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus

/**
 * Every service schema this app's client registers, as one named list so
 * `MiaClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`; `photos-app.md` §18.4).
 */
val miaSchemaConfigs: List<ModuleConfiguration> = listOf(
    miaServiceCommonModule,
    miaServiceClientModule,
    // The grant switches are ordinary realm preferences, so Mia's client needs the
    // preference service too — still no file/timeline/… deps, because the
    // cross-service reads happen on the PROXY (MQ-D2).
    preferenceServiceCommonModule,
    preferenceServiceClientModule,
    // Realm storage, for the storage banner. ⚠⚠ Registration is the load-bearing half:
    // an unregistered service fails at DISPATCH on a device, not at compile, and every
    // caller catches — so the banner would simply never appear.
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
    // ⚠ ADR 0025 §Decision 8, read-only: `getSyncStatus().ownerId` is how this app learns that
    // Control is installed but has no realm, so it shows the set-up wall instead of
    // an assistant with nothing to read. Unregistered, the read fails at DISPATCH and the wall silently never
    // fires. The accepted cost: the announce declares `:sync-service`, so a sync-service
    // version skew refuses this app's session.
    org.tenshu.sync.common.syncServiceCommonModule,
    org.tenshu.sync.client.syncServiceClientModule,
)

/**
 * Mia's [TenshuClient] (`mia-query-layer.md` MQ-D2).
 *
 * Minimal on purpose: the core client plus [miaSchemaConfigs] — `:mia-service-client` and the
 * preference, storage and sync schemas the shell itself reads. Mia reads across
 * every service, but it does so **on the proxy** — that is the whole point of MQ-D2, and
 * it is why this module does not (and must not) grow a dependency on file-service,
 * timeline-service and the rest. One op crosses the wire; the fan-out happens where the
 * rows are.
 *
 * Include this **after** [miaModules] — Koin module order is override order, the
 * established Home/Mia pattern — so its [ConversationRepository] binding replaces the
 * keyword-routed mock.
 *
 * ⚠ **The mock is not a fallback.** `TenshuConversationRepository` answers "I can't reach
 * Control" rather than degrading to invented replies; a shell that swapped back to the
 * mock on failure would show fabricated photos and places as real.
 *
 * The platform half (`miaPlatformClientModule`) provides the transport +
 * `RemoteTaskExecutor` + the platform-built client: Binder to Control on Android, UDS to
 * Control-desktop on desktop. iOS route-1 is in-process and is wired in `MiaLocalRuntime`
 * (`:tenshu-sdk-ios-mia`), not here — this module is Compose-side and does not build for iOS.
 */
val miaClientModule = module {
    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        miaSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
    }

    single<TaskExecutor> { get<RemoteTaskExecutor>() }

    single {
        createRemoteTenshuEnvironment(
            tContext = get(),
            remoteTaskExecutor = get<RemoteTaskExecutor>(),
            schemaRegistry = get(),
        )
    }

    // The announcer, **exposed** rather than discarded, so the app root can fire it at launch.
    //
    // ⚠⚠ `wireSchemaAnnounce` does **not** announce — it installs `executor.sessionPreflight`, so
    // the handshake rides the session's *first call*. For an app whose first screen issues no
    // operation that is never, and Mia's chat is exactly that: empty until you type. Without the
    // launch announce the app connects, holds a socket, works when used — and is invisible in
    // Control until its first message, because `appRegistrationTrigger` has no session to fire on.
    single<SchemaAnnouncer> {
        wireSchemaAnnounce(
            client = get<TenshuClient>(named("platformTenshuClient")),
            executor = get<RemoteTaskExecutor>(),
            appId = "app.tenshu.mia",
            tContext = get(),
            schemaProvider = get<SchemaRegistry>(),
        )
    }

    /** The app root's launch announce, over the announcer wired above. */
    single<LaunchAnnouncer> { LaunchAnnouncer { get<SchemaAnnouncer>().announce() } }

    single<TenshuClient> {
        get<TenshuClient>(named("platformTenshuClient")).also {
            // Resolving the announcer is what installs the preflight, so it still rides ahead of
            // the session's first call and of every post-reconnect resubscribe (§6.4). The eager
            // announce at launch lives in `MiaAppViewModel`; this keeps the lazy path intact for
            // anything that reaches the client without going through the app root.
            // Binds the refusal verdict, so a schema-skewed Mia names the app to update rather
            // than saying "can't reach Control" — wrong and unactionable, since the proxy is
            // answering and refusing.
            get<MutableSchemaSkewSource>().bind(get<SchemaAnnouncer>(), get<CoroutineScope>())
            get<SchemaAnnouncer>()
        }
    }

    // The scope the reachability probe and the schema-skew mirror live on. ⚠ App-lifetime, not
    // screen-lifetime: the surface has to keep telling the truth while the user is on a screen that
    // reads nothing.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // SK-1b: the seam between the schema announce and the shell's words.
    single { MutableSchemaSkewSource() }

    // Mia's one Control-reachability engine (`ADR 0025`).
    //
    // ⚠⚠ **No `schemaFence` / `proxyHost`**, so a fenced Mia renders no banner. Both read
    // `syncService`, which [miaSchemaConfigs] registers (for ADR 0025 §Decision 8's not-set-up
    // wall), so `Fenced` is a DI line away rather than a schema-set change; wiring it is a
    // separate decision. `Skew` works: its verdict comes from the announce direction, not from a
    // sync read.
    single {
        miaControlReachability(
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

    single<ConversationRepository> { TenshuConversationRepository(client = get()) }

    // Cross-app open (`control-cross-app-open.md` TT-1b) — what a tapped citation calls.
    //
    // ⚠ `getOrNull` on the performer: a platform with no launcher bound still gets an opener that
    // refuses honestly, rather than a DI failure at app start over a feature the user has not
    // touched.
    single<TargetOpener> {
        ControlTargetOpener(
            client = get(),
            performLaunch = getOrNull() ?: LaunchPerformer { false },
        )
    }

    single<MiaGrantsRepository> { TenshuMiaGrantsRepository(client = get()) }

    // Re-bind with the repository present (module order = override order), so the Settings
    // switches become live rather than the informational not-connected rendering.
    viewModel { GrantsViewModel(get<MiaGrantsRepository>()) }
}
