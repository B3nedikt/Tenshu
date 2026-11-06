package app.tenshu.contacts.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.common.TenshuClient
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
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule

/**
 * Every service schema this app's client registers, as one named list so
 * `ContactsClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`; `photos-app.md` §18.4).
 */
val contactsSchemaConfigs: List<ModuleConfiguration> = listOf(
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
    contactServiceCommonModule,
    contactServiceClientModule,
    preferenceServiceCommonModule,
    preferenceServiceClientModule,
    // Realm storage, for `RealmStorageBannerHost`. ⚠⚠ Registration is the
    // load-bearing half: an unregistered service fails at DISPATCH on a device, not
    // at compile, and every caller catches — the banner would never appear.
    org.tenshu.storage.common.storageServiceCommonModule,
    org.tenshu.storage.client.storageServiceClientModule,
)

/**
 * Contacts' client graph (`docs/proposals/contacts-app.md` §6): a `TenshuClient` that
 * reaches the **Control** proxy over IPC (Android Binder / desktop UDS). It carries the two
 * service surfaces Contacts speaks:
 *
 *  - **`:contact-service`** — the contact graph. Control hosts it, so the rows are the same
 *    ones Drive's *Manage access* flows write, durable and synced across the user's devices.
 *  - **`:preference-service`** — for `OwnerPreferences.Name`, the platform-level own name
 *    (D6). Reading it here rather than storing an app-private copy is what keeps one answer
 *    to "what is my name".
 *
 * Mirrors Maps' `mapsClientModule`, trimmed to those two (plus the sync and storage schemas
 * [contactsSchemaConfigs] registers for the shell banners).
 *
 * The platform half (`platformClientModule`, per source set) provides the
 * `RemoteTaskExecutor`, the transport, and the platform-built `TenshuClient` (named
 * `platformTenshuClient`).
 */
val contactsClientModule = module {
    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        contactsSchemaConfigs + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
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
            // Schema announce (core-schema-evolution.md §6.4): installed as the executor's
            // session preflight, so the announce rides ahead of the session's first call
            // and of every post-reconnect resubscribe.
            val announcer = wireSchemaAnnounce(
                client = client,
                executor = get<RemoteTaskExecutor>(),
                appId = "app.tenshu.contacts",
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
        }
    }
}
