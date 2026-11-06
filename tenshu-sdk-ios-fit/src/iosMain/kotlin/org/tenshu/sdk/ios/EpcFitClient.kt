package org.tenshu.sdk.ios

import app.tenshu.fit.data.FitProjectionRepository
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthImportBootstrap
import app.tenshu.fit.data.HealthKitImporter
import app.tenshu.fit.data.iosRecordedWorkoutOutbox
import app.tenshu.fit.model.CategoryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
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
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.storage.client.storageServiceClientModule
import org.tenshu.storage.common.storageServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * Assembles the Fit-on-iOS client over an established EPC channel — the live-EPC tier of the Fit app
 * (`docs/proposals/fit-app.md` §9.3 + §9.5). The Fit twin of [EpcDriveClient]: it hand-wires what
 * Android/desktop get from `:fit-shared-ui`'s Koin graph (the iOS umbrella has none), reusing the
 * production `coreCommonModule` + every service `-common`/`-client` schema config **plus the
 * `:health-service` schema** + the EPC channel as the `TaskTransport`, and builds the real
 * [FitProjectionRepository] — the read-model projector over the `Workout`/`DailyActivity`/… store.
 *
 * Installing the resulting repository through [FitSurface.installRealClient] swaps every Fit screen
 * off [app.tenshu.fit.data.MockFitRepository] with **zero SwiftUI change** (the Drive Tier-1/Tier-2
 * shape). Issuing an op (e.g. `addWorkout` → `recordWorkout`) marshals down to `channel.call(...)`;
 * the live op→reply (and op→push→Flow) round-trip needs a Control serving EPC over a reachable relay —
 * the on-device gate, same posture as [EpcDriveClient].
 */
object EpcFitClient {

    /** The assembled Fit client surface: the [TenshuClient] + the real [FitRepository] over it. */
    class FitClient internal constructor(
        val tenshuClient: TenshuClient,
        val fitRepository: FitRepository,
        /**
         * The background-refresh action (R-iOS-5, fit-app.md §11.5): replay the recorder outbox +
         * refresh Apple Health into the realm, gated on `autoRefreshReady`. Held here (a closure
         * over the repository + importer) so [FitSurface.runBackgroundRefresh] can drive it from
         * the `BGAppRefreshTask` without re-reaching into the DI graph.
         */
        val backgroundRefresh: suspend () -> String,
    )

    /**
     * Wire [channel] into a consume stack (server-push routing only). Mirrors
     * [EpcDriveClient.assembleConsume]; [scope] hosts the per-push callback coroutine.
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
     * Assemble the full Fit client over [channel]. Mirrors [EpcDriveClient.assembleClient]: the full
     * domain schema (every service config **including `:health-service`**), a `RemoteBinaryTaskExecutor`
     * over the channel (its `streamElementHandler` auto-wired by `coreCommonModule`'s
     * `DefaultInteractorProvider`), a remote `TenshuEnvironment`/`TenshuClient` (whose `init()` binds the
     * executor), and a real [FitProjectionRepository] over the resolved `TContext`.
     */
    fun assembleClient(channel: TaskTransport): FitClient {
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
                                appId = "app.tenshu.fit",
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
        // The projector needs a `TContext` to mint entities for `addWorkout` → `recordWorkout`; reuse the
        // graph's own. The importer is the **HealthKit actual** (P-iOS-2, fit-app.md §9.12): the live
        // source behind `availableSources` (Apple Health onboarding row) + `startImport`; where no
        // health store exists it offers no source at all (never the reference fixture).
        val importer = HealthKitImporter()
        // R-iOS-3: bind the durable recorder outbox (rooted in the app container) so a finished
        // recording is journalled before its `recordWorkout` op — surviving process death and the
        // iOS Control SIGKILL race (§9.16). Without it, `FitProjectionRepository.addWorkout` took
        // the throw path and a save against an unreachable Control was lost. Replay is the shared
        // `drainPendingWorkouts()` (HealthImportBootstrap on launch + reachability recovery).
        val repository = FitProjectionRepository(
            client,
            koin.get<TContext>(),
            importer = importer,
            outbox = iosRecordedWorkoutOutbox(),
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's"),
        )
        // Launch refresh, exactly as Android's app shell wires it: gated on a REAL source so the
        // reference fixture is never auto-written into the durable synced realm. Record ops are
        // content-addressed, so the refresh folds onto existing rows.
        //
        // ⚠⚠ Kept and handed to [FitSurface], not discarded — see `FitLocalRuntime.install` for the
        // gap that left (a live session that never re-read the health source; fit-app.md §9.18).
        val bootstrap = HealthImportBootstrap(
            repository = repository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // fit-app.md §9.13: the bootstrap now takes the importer itself and gates the
            // refresh on `autoRefreshReady` internally (supersedes the boolean `enabled`).
            importer = importer,
        )
        FitSurface.installForegroundRefresh { bootstrap.refresh(reason = "foreground") }
        // R-iOS-5: the same work `HealthImportBootstrap` does per launch, on demand from a
        // background wake — replay the recorder outbox (always), then refresh Apple Health into the
        // realm, gated on `autoRefreshReady` so an unattended import never writes fixture data or
        // nags a grant-less device (§9.15). Best-effort throughout: a background wake must never
        // throw out of the task handler.
        val backgroundRefresh: suspend () -> String = {
            runCatching { repository.drainPendingWorkouts() }
            val ready = runCatching { importer.autoRefreshReady() }.getOrDefault(false)
            if (ready) {
                runCatching { repository.startImport(CategoryId.entries.toSet(), automatic = true) }
                "bg-refresh: drained + imported"
            } else {
                "bg-refresh: drained (import not ready)"
            }
        }
        return FitClient(tenshuClient = client, fitRepository = repository, backgroundRefresh = backgroundRefresh)
    }
}
