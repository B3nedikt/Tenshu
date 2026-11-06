package app.tenshu.calendar.di

import app.tenshu.reachability.MutableSchemaSkewSource
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted
import kotlinx.coroutines.flow.map
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.core.common.TenshuClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule

/**
 * Every service schema this app's client registers, as one named list so
 * `CalendarClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`; `photos-app.md` §18.4).
 */
val calendarSchemaConfigs: List<ModuleConfiguration> = listOf(
    calendarServiceCommonModule,
    calendarServiceClientModule,
    preferenceServiceCommonModule,
    preferenceServiceClientModule,
    // ⚠ **Not optional, and not scope creep.** Every per-device preference above is keyed
    // by this device's sync id, and the only way to learn it is
    // `syncService.getSyncStatus()`, which `calendarRepositoryModule` calls. Without these
    // two that call throws `No schema registered for service id: tenshu/sync/service`
    // (`CalendarDeviceIdentity` latches it rather than crashing).
    syncServiceCommonModule,
    syncServiceClientModule,
    // Realm storage, for the storage banner. ⚠⚠ Registration is the load-bearing half:
    // an unregistered service fails at DISPATCH on a device, not at compile, and every
    // caller catches — so the banner would simply never appear.
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
)

/**
 * Calendar's client graph — a `TenshuClient` reaching the **Control**
 * proxy over IPC (Android Binder / desktop UDS), carrying the services Calendar
 * speaks:
 *
 * - **`:calendar-service`** — calendars, events, overrides.
 * - **`:preference-service`** — the §6.4 headless defaults and the per-device
 *   `HiddenCalendars` set. ⚠ Not optional: without it the repository cannot
 *   resolve visibility and `SaveEvent` cannot resolve a default calendar.
 * - **`:sync-service`** — read-only here, and only for `getSyncStatus().deviceId`:
 *   the key every *per-device* preference row above is stored under.
 *
 * ⚠⚠ If a service is unlisted here, **nothing may call it**; the list is the
 * contract, not an aspiration.
 *
 * Mirrors `contactsClientModule`.
 */
val calendarClientModule = module {

    // Where a cross-app open lands before the composition can collect it
    // (`control-cross-app-open.md` TT-1c).
    //
    // ⚠ A singleton, and it must be: the Android shell delivers the cold-start target into it from
    // `onCreate`, the transport delivers warm pushes into it from a Binder thread, and `App()`
    // collects it. Three holders would be three different inboxes and two of them empty.
    single { OpenTargetInbox() }

    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        calendarSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
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
            // Schema announce (core-schema-evolution.md §6.4) as the executor's
            // session preflight, so it rides ahead of the first call and of
            // every post-reconnect resubscribe.
            val announcer = wireSchemaAnnounce(
                client = client,
                executor = get<RemoteTaskExecutor>(),
                appId = "app.tenshu.calendar",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                // Announce at launch, not on the session's first call — an app whose opening screen
                // issues no operation would otherwise never register (agent.md §7).
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // `core-schema-skew-surface.md`: binds the refusal verdict so a schema-skewed Calendar
            // names the app to update rather than rendering "Can't reach Tenshu Control", which is
            // both wrong and unactionable: the proxy is answering, and refusing.
            //
            // ⚠ Bound into a source the repository already holds rather than passed to it: the
            // repository is built from its own module and may exist before this single resolves.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
            // SK-2/SK-3: which binary owns the runtime, and this device's realm pull fence. Bound
            // on the same object as the verdict so one forgotten bind cannot leave the app silently
            // unable to mention a problem it is already detecting.
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
