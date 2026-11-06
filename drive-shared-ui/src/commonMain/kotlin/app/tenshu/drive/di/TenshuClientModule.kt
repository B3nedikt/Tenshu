package app.tenshu.drive.di

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
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.ingest.client.ingestServiceClientModule
import org.tenshu.ingest.common.ingestServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.storage.client.storageServiceClientModule
import org.tenshu.storage.common.storageServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * Every service schema this app's client registers, as one named list so
 * `DriveClientGraphTest` can check it.
 *
 * ⚠⚠ **A service the app calls but does not list here fails SILENTLY.**
 * `TenshuEnvironment.getService` errors on an unregistered schema and app repositories catch, so
 * the screen renders as though the data were absent rather than crashing. The announce gate cannot
 * catch it either — it compares only services the client *declares*, so an unregistered one has no
 * version to disagree about (`AnnounceSessionImplementation`). Photos lost three phases to exactly
 * this (`photos-app.md` §18.4).
 */
val driveSchemaConfigs: List<ModuleConfiguration> = listOf(
        fileServiceCommonModule,
        fileServiceClientModule,
        // Device wire + typed RO, so a resolved FileModification.Device parses + reads
        // on the Drive client (core-reference-property.md §3.4).
        identityServiceCommonModule,
        identityServiceClientModule,
        notificationServiceCommonModule,
        notificationServiceClientModule,
        timelineServiceCommonModule,
        timelineServiceClientModule,
        healthServiceCommonModule,
        healthServiceClientModule,
        placeServiceCommonModule,
        placeServiceClientModule,
        ingestServiceCommonModule,
        ingestServiceClientModule,
        syncServiceCommonModule,
        syncServiceClientModule,
        preferenceServiceCommonModule,
        preferenceServiceClientModule,
        storageServiceCommonModule,
        storageServiceClientModule,
        contactServiceCommonModule,
        contactServiceClientModule
    )

val tenshuClientModule = module {

    /**
     * Where a cross-app open lands before the composition can collect it
     * (`control-cross-app-open.md` TT-2a).
     *
     * ⚠ A singleton, and it must be: the Android shell delivers a cold-start target into it from
     * `onCreate`, the transport delivers warm pushes from a Binder thread, and `App()` collects it.
     * Three holders would be three inboxes and two of them empty.
     */
    single { OpenTargetInbox() }

    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        driveSchemaConfigs + getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")).let {
            it ?: emptyList()
        }
    }

    // TaskExecutor alias for RemoteTaskExecutor provided by platformClientModule
    single<TaskExecutor> { get<RemoteTaskExecutor>() }

    single {
        createRemoteTenshuEnvironment(
            tContext = get(),
            remoteTaskExecutor = get<RemoteTaskExecutor>(),
            schemaRegistry = get()
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
                appId = "app.tenshu.drive",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                // Announce at launch, not on the session's first call — an app whose opening screen
                // issues no operation would otherwise never register (agent.md §7).
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // ⚠⚠ SK-1: the refusal verdict was computed here and DISCARDED — the return value was
            // not even assigned. So a schema-refused Drive fell through `SyncBanner.evaluate`'s two
            // `null` guards to no banner at all: an empty file list with nothing to explain it, in
            // the app whose whole job is showing you your files.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
        }
    }

}
