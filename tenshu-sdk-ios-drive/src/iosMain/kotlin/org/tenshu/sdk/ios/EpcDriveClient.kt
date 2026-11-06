package org.tenshu.sdk.ios

import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.FileMapper
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.TenshuContactsRepository
import app.tenshu.drive.data.TenshuFilesRepository
import app.tenshu.drive.data.TenshuMembershipRepository
import app.tenshu.drive.domain.LogUnexpectedErrorUseCase
import kotlinx.coroutines.CoroutineScope
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
 * Assembles the Drive-on-iOS client over an established EPC channel (proposal §3c-consume → the
 * on-device client scaffold). It hand-wires what Android/desktop get from `:drive-shared-ui`'s Koin
 * graph — the iOS umbrella has none — reusing the production `coreCommonModule` + every service
 * `-common`/`-client` schema config + the EPC channel as the `TaskTransport`.
 *
 * Two entry points:
 *  - [assembleConsume] — the consume-only stack (§3c-consume), used where only push routing is needed.
 *  - [assembleClient] — the **full** client: a real `TenshuClient` that issues domain ops over the
 *    channel + a real [TenshuFilesRepository] (replacing the canned one). Issuing an op marshals down
 *    to `channel.call(...)`; the live data round-trip needs a Control serving EPC over a reachable
 *    relay (the on-device gate). Replacing `DriveReadSurface`'s canned repo with this is the last step.
 */
object EpcDriveClient {

    /**
     * The assembled Drive client surface: the [TenshuClient] + the real repositories over it — files
     * ([FilesRepository]), contacts ([ContactsRepository]), and shared-realm membership
     * ([MembershipRepository]). All three back the full app (files + the sharing cluster); installing
     * them swaps the entire UI off the fakes (see [DriveReadSurface.installRealClient]).
     */
    class DriveClient internal constructor(
        val tenshuClient: TenshuClient,
        val filesRepository: FilesRepository,
        val contactsRepository: ContactsRepository,
        val membershipRepository: MembershipRepository,
    )

    /**
     * Wire [channel] (an `EpcRendezvousChannel`, or any [TaskTransport]) into a consume stack. [scope]
     * hosts the per-push callback coroutine — tie it to the client's lifetime.
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
     * Assemble the full Drive client over [channel]. Mirrors the desktop `tenshuClientModule` +
     * `PlatformClientModule`: the full domain schema (every service config), a `RemoteBinaryTaskExecutor`
     * over the channel (its `streamElementHandler` auto-wired by `coreCommonModule`'s
     * `DefaultInteractorProvider`), a remote `TenshuEnvironment`/`TenshuClient` (whose `init()` binds the
     * executor), and a real [TenshuFilesRepository].
     */
    fun assembleClient(channel: TaskTransport): DriveClient {
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
                                appId = "app.tenshu.drive",
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
        val repository = TenshuFilesRepository(
            client,
            FileMapper(),
            LogUnexpectedErrorUseCase(),
            IosFilesRepositoryPlatformDelegate(),
        )
        // Mirror `:drive-shared-ui`'s `filesRepositoryModule()`: the contacts + membership repos are
        // one-arg wrappers over the same `TenshuClient`.
        return DriveClient(
            tenshuClient = client,
            filesRepository = repository,
            contactsRepository = TenshuContactsRepository(client),
            membershipRepository = TenshuMembershipRepository(client),
        )
    }
}
