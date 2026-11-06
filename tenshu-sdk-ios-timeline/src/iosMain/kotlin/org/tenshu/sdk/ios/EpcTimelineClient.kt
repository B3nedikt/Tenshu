package org.tenshu.sdk.ios

import app.tenshu.timeline.data.TimelineProjectionRepository
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.data.geocode.IosReverseGeocoder
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.preference.client.TenshuOwnerProfile
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.FileUploader
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.RemoteConsumeStack
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.wireSchemaAnnounce
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.assembleRemoteConsumeStack
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.common.transport.TransportFileUploader
import org.tenshu.core.core.TContext
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.ingest.client.ingestServiceClientModule
import org.tenshu.ingest.common.ingestServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.storage.client.storageServiceClientModule
import org.tenshu.storage.common.storageServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule

/**
 * Assembles the Timeline-on-iOS client over an established EPC channel — the live-EPC tier of the
 * Timeline app (`docs/proposals/ios-proxy-architecture.md` §9.3). The Timeline twin of [EpcFitClient]
 * / [EpcDriveClient]: it hand-wires what Android/desktop get from `:timeline-shared-ui`'s Koin graph
 * (the iOS umbrella has none), reusing the production `coreCommonModule` + every service
 * `-common`/`-client` schema config **plus the `:timeline-service` / `:place-service` /
 * `:health-service` schemas** + the EPC channel as the `TaskTransport`, and builds the real
 * [TimelineProjectionRepository] — the read-model projector over the `Moment`/`Trip`/`Place` spine.
 *
 * Installing the resulting repository through [TimelineSurface.installRealClient] swaps every Timeline
 * screen off [app.tenshu.timeline.data.MockTimelineRepository] with **zero SwiftUI change** (the Drive
 * Tier-1/Tier-2 shape). The live op→reply (and op→push→Flow) round-trip needs a Control serving EPC
 * over a reachable relay — the on-device gate, same posture as [EpcDriveClient].
 */
object EpcTimelineClient {

    /**
     * The assembled Timeline client surface: the [TenshuClient] + the real [TimelineRepository]
     * (+ the consent-gated [PlaceLabelEnricher] when a [PlaceGeocodingControl] was supplied —
     * place-geocoding.md G5).
     */
    class TimelineClient internal constructor(
        val tenshuClient: TenshuClient,
        val timelineRepository: TimelineRepository,
        val placeLabelEnricher: PlaceLabelEnricher? = null,
    )

    /**
     * Wire [channel] into a consume stack (server-push routing only). Mirrors
     * [EpcFitClient.assembleConsume]; [scope] hosts the per-push callback coroutine.
     */
    fun assembleConsume(channel: TaskTransport, scope: CoroutineScope): RemoteConsumeStack {
        val koin = koinApplication { modules(coreCommonModule()) }.koin
        return assembleRemoteConsumeStack(
            transport = channel,
            serializer = koin.get<BinarySerializer>(),
            parser = koin.get<BinaryParser>(),
            context = koin.get<TContext>(),
            scope = scope,
        )
    }

    /**
     * Assemble the full Timeline client over [channel]. Mirrors [EpcFitClient.assembleClient]: the full
     * domain schema (every service config **including `:timeline-service`/`:place-service`/
     * `:health-service`**), a `RemoteBinaryTaskExecutor` over the channel (its `streamElementHandler`
     * auto-wired by `coreCommonModule`'s `DefaultInteractorProvider`), a remote
     * `TenshuEnvironment`/`TenshuClient` (whose `init()` binds the executor), and a real
     * [TimelineProjectionRepository] (which reads its `TContext` off `client.environment`).
     */
    fun assembleClient(channel: TaskTransport, geocodingControl: PlaceGeocodingControl? = null): TimelineClient {
        val koin = koinApplication {
            modules(
                coreCommonModule(),
                module {
                    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
                        listOf(
                            fileServiceCommonModule, fileServiceClientModule,
                            identityServiceCommonModule, identityServiceClientModule,
                            notificationServiceCommonModule, notificationServiceClientModule,
                            ingestServiceCommonModule, ingestServiceClientModule,
                            syncServiceCommonModule, syncServiceClientModule,
                            preferenceServiceCommonModule, preferenceServiceClientModule,
                            storageServiceCommonModule, storageServiceClientModule,
                            contactServiceCommonModule, contactServiceClientModule,
                            healthServiceCommonModule, healthServiceClientModule,
                            timelineServiceCommonModule, timelineServiceClientModule,
                            placeServiceCommonModule, placeServiceClientModule,
                        )
                    }
                    single<TaskTransport> { channel }
                    single<RemoteTaskExecutor> {
                        RemoteBinaryTaskExecutor(
                            serializer = get(),
                            parser = get(),
                            transport = get(),
                            context = get(),
                        )
                    }
                    single<TaskExecutor> { get<RemoteTaskExecutor>() }
                    single<FileUploader> { TransportFileUploader(get()) }
                    single {
                        createRemoteTenshuEnvironment(
                            tContext = get(),
                            schemaRegistry = get(),
                            remoteTaskExecutor = get<RemoteTaskExecutor>(),
                        )
                    }
                    single<TenshuClient> {
                        DefaultTenshuClient(get()).apply { init() }.also { client ->
                            // §6.4: EPC rides the gated serialized boundary — announce as preflight.
                            wireSchemaAnnounce(
                                client = client,
                                executor = get<RemoteTaskExecutor>(),
                                appId = "app.tenshu.timeline",
                                tContext = get(),
                                schemaProvider = get<SchemaRegistry>(),
                            )
                        }
                    }
                },
            )
        }.koin

        // Resolve the provider so `coreCommonModule` wires the executor's streamElementHandler → dispatch
        // (server-push consume) before any op runs.
        koin.get<DefaultInteractorProvider>()

        val client = koin.get<TenshuClient>()
        // TContext rides along so renamePlace (place-geocoding.md G4) works over EPC too.
        val context = koin.get<TContext>()
        val repository = TimelineProjectionRepository(client, context, ownerProfile = TenshuOwnerProfile(client), geocodingConsent = geocodingControl, controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's"))
        // The geocoding rail (G5): the whole enricher stack is common Kotlin — only the
        // CLGeocoder tier is platform code — so it assembles here over the same EPC client.
        val enricher = geocodingControl?.let {
            PlaceLabelEnricher(client, context, IosReverseGeocoder(), it)
        }
        return TimelineClient(tenshuClient = client, timelineRepository = repository, placeLabelEnricher = enricher)
    }
}
