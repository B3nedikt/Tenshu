package app.tenshu.control.integration.utils

import app.tenshu.control.di.dataModule
import app.tenshu.control.di.platformProxyModule
import app.tenshu.control.di.proxyModule
import app.tenshu.control.di.viewModelsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.core.dataFactory
import org.tenshu.core.file.GetPreview
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.identity.proxy.identityProxyImplementationFactory
import org.tenshu.notifications.proxy.notificationServiceProxyImplementationFactory
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

fun startAppIntegrationKoin(
    clientId: String = "app.tenshu.drive.test",
    additionalModules: List<Module> = emptyList(),
    // Overrides the platform SqliteObjectStore so the suite stays ephemeral
    // and isolated. Default = InMemory, OR an isolated per-call temp-dir
    // SqliteObjectStore when env `TENSHU_TEST_SQLITE=true` (the dual-backend
    // sweep — runs the whole existing suite against SQLite, per-test files).
    objectStore: ObjectStore = integrationObjectStore()
): TenshuClient {
    stopAppIntegrationKoin()
    val koinApp = startKoin {
        allowOverride(true)
        modules(
            dataModule,
            platformProxyModule,
            proxyModule,
            viewModelsModule,
            module {
                single(named("platformPackageName")) { clientId }
                single(named("platformDeviceId")) { "test-device-id" }

                // Override the platform durable store (isolation, see param).
                single<ObjectStore> { objectStore }

                // ⚠ Same duty for content BYTES. Since 2026-08-03 the desktop
                // platform module binds a durable `JvmFileDatabase` rooted at the
                // real `<data-dir>/content/`; without this override an integration
                // run would write test content into the developer's actual app data
                // directory and leak state between runs.
                single<FileDatabase> { InMemoryFileDatabase() }

                // --- Mock network components for integration tests ---
                single<SyncRelay> { org.tenshu.sync.proxy.MockSyncRelay(get()) }

                // --- Override platformImplementationFactory to include mock GetPreview ---
                single<(String) -> OperationImplementation>(named("platformImplementationFactory")) {
                    val fileDatabase: FileDatabase = get()
                    val syncManager: SyncManager = get()
                    val cryptoManager: org.tenshu.core.security.SyncCryptoManager = get()

                    // Lazy provider breaks the circular dependency.
                    val syncRelayProvider = { get<SyncRelay>() }

                    val factory: (String) -> OperationImplementation = { operationType ->
                        implementationFactory(operationType)
                            ?: org.tenshu.sync.proxy.syncProxyImplementationFactory(
                                operationType,
                                syncRelayProvider(),
                                syncManager,
                                cryptoManager
                            )
                            ?: identityProxyImplementationFactory(operationType)
                            ?: fileServiceProxyImplementationFactory(operationType, fileDatabase)
                            ?: notificationServiceProxyImplementationFactory(operationType)
                            ?: org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory(
                                operationType
                            )
                            ?: (if (operationType == GetPreview.ID) object :
                                SyncOperationImplementation {
                                override fun <T : Data?> execute(
                                    executionContext: ExecutionContext,
                                    operationContext: OperationContext?,
                                    implementationFactory: OperationImplementationFactory,
                                    caller: Any?,
                                    params: Map<String, Data>?
                                ): T {
                                    // Mock implementation for Desktop/Integration tests: 1x1 black PNG
                                    val mockPreview = byteArrayOf(
                                        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                                        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
                                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(),
                                        0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
                                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                                        0x08.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(),
                                        0x00.toByte(), 0x90.toByte(), 0x77.toByte(), 0x53.toByte(),
                                        0xDE.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                                        0x0C.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
                                        0x54.toByte(), 0x08.toByte(), 0xD7.toByte(), 0x63.toByte(),
                                        0x60.toByte(), 0x60.toByte(), 0x60.toByte(), 0x00.toByte(),
                                        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
                                        0x01.toByte(), 0x27.toByte(), 0x34.toByte(), 0x5B.toByte(),
                                        0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                                        0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(),
                                        0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(),
                                        0x82.toByte()
                                    )
                                    @Suppress("UNCHECKED_CAST")
                                    return executionContext.context.dataFactory().createValue(
                                        TByteArray.ID,
                                        mockPreview
                                    ) as T
                                }
                            } as OperationImplementation? else null)
                            // So a test can seed a row in an array-held realm-data type
                            // (RealmDataPresenceIntegrationTest).
                            ?: org.tenshu.health.proxy.healthServiceProxyImplementationFactory(
                                operationType,
                                fileDatabase,
                            )
                            ?: error("No executor found for operation $operationType")
                    }
                    factory
                }

                // --- Provide manual client transport link ---
                single<ClientConnection>(named("platformClientConnection")) {
                    val gateway: ProxyGateway = get()
                    object : ClientConnection {
                        override fun send(data: Data) {
                            // Link: Proxy -> Client
                            // (In real apps, this would be an IPC call back to ClientCallbackService)
                            runBlocking {
                                gateway.call(clientId, BinarySerializer().serialize(data))
                            }
                        }

                        override fun init() {}

                        override fun destroy() {}
                    }
                }
            }
        )
        modules(additionalModules)
    }

    // Mutually bind Proxy -> Client
    val clientRegistry: ClientRegistry = koinApp.koin.get()
    val executionContext = clientRegistry.getExecutionContext(clientId)
    executionContext.init()

    return koinApp.koin.get()
}

/**
 * Teardown counterpart to [startAppIntegrationKoin]. Quiesces the running proxy
 * — cancels **and joins** its background loops (sync scheduler, schedule-drain,
 * constraint gate) via [ProxyGateway.shutdownAndJoin] — *before* dropping the
 * Koin singletons, so a loop mid-tick can't touch a disposed context / dropped
 * singleton (the "Proxy Background Lifecycle" teardown race). `runBlocking` is
 * fine here: this is desktop test teardown, and the cancel-and-join is not
 * expressible synchronously in `commonMain`. Safe to call when no Koin is
 * running (the leading call in [startAppIntegrationKoin] relies on that).
 */
fun stopAppIntegrationKoin() {
    GlobalContext.getOrNull()?.getOrNull<ProxyGateway>()?.let { gateway ->
        runBlocking { gateway.shutdownAndJoin() }
    }
    stopKoin()
}

// InMemory by default (fast, ephemeral, isolated). With env
// `TENSHU_TEST_SQLITE=true` every `startAppIntegrationKoin` call gets a
// fresh isolated temp-dir SqliteObjectStore — runs the whole existing
// integration suite against the real durable backend (dual-backend sweep,
// catches hidden ordering/contract assumptions). Per-call temp dir keeps
// tests mutually isolated since the harness recreates Koin per test. Env
// (not -D) so it propagates to the forked Gradle test JVM without a
// build-file change.
internal fun integrationObjectStore(): ObjectStore {
    if (System.getenv("TENSHU_TEST_SQLITE") != "true") return InMemoryObjectStore()
    val path = createTempDirectory("tenshu-it-objstore").resolve("objects.db").toString()
    return runBlocking {
        openSqliteObjectStore(
            path = path,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
