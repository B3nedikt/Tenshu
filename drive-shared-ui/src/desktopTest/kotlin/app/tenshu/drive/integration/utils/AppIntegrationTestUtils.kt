package app.tenshu.drive.integration.utils

import app.tenshu.control.di.platformProxyModule
import app.tenshu.control.di.proxyModule
import app.tenshu.drive.data.FilesRepositoryPlatformDelegate
import app.tenshu.drive.data.FolderListing
import app.tenshu.drive.data.foldPlatformContentHash
import app.tenshu.drive.di.domainModule
import app.tenshu.drive.di.filesRepositoryModule
import app.tenshu.drive.di.platformClientModule
import app.tenshu.drive.di.viewModelsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.core.dataFactory
import org.tenshu.core.file.GetPreview
import org.tenshu.core.TaskHandle
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.replaceFileBackground
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.file.system.uploadFileBackground
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.file.client.fileService
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.jvmFileServiceProxyImplementationFactory
import org.tenshu.notifications.proxy.notificationServiceProxyImplementationFactory
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.MockSyncRelay
import app.tenshu.drive.data.driveControlReachability
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource

class FakeFilesRepositoryPlatformDelegate : FilesRepositoryPlatformDelegate {
    override fun getPlatformFileName(uriString: String): String {
        return uriString.substringAfterLast("/")
    }

    override suspend fun listFolderEntries(uriString: String): FolderListing =
        FolderListing(rootFolderName = uriString.substringAfterLast("/"), entries = emptyList())

    override suspend fun uploadPlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?> {
        // Generate a valid PNG sized by the URI's `w=,h=` query so tests can
        // upload distinguishable images and assert via `ImageBitmap.width/height`
        // which one the detail view actually renders. URIs without size hints
        // fall through to the 1x1 default so existing tests stay unchanged in
        // pixel content (still a valid PNG instead of the previous 3-byte
        // garbage — the UI-side decodeToImageBitmap silently dropped that).
        return client.fileService.defaultFileSystem.uploadFile(
            parentPath = parentPath,
            fileName = fileName,
            data = mockPngBytesForUri(uriString)
        )
    }

    override suspend fun replacePlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?> {
        return client.fileService.defaultFileSystem.replaceFile(
            parentPath = parentPath,
            fileName = fileName,
            data = mockPngBytesForUri(uriString)
        )
    }

    /** ⚠ The same bytes [uploadPlatformFileCancellable] would send, so the hash is honest. */
    override suspend fun contentHashOf(uriString: String): String? {
        val bytes = mockPngBytesForUri(uriString)
        var offset = 0
        return foldPlatformContentHash { buffer ->
            if (offset >= bytes.size) {
                -1
            } else {
                val n = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + n)
                offset += n
                n
            }
        }
    }

    override suspend fun uploadPlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?> {
        return client.fileService.defaultFileSystem.uploadFileBackground(
            parentPath = parentPath,
            fileName = fileName,
            data = mockPngBytesForUri(uriString)
        )
    }

    override suspend fun replacePlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?> {
        return client.fileService.defaultFileSystem.replaceFileBackground(
            parentPath = parentPath,
            fileName = fileName,
            data = mockPngBytesForUri(uriString)
        )
    }
}

/**
 * Generates a tiny valid PNG whose dimensions encode the test's intent.
 * Supports `mock://path?w=<n>&h=<m>`; absent params default to 1×1.
 */
internal fun mockPngBytesForUri(uriString: String): ByteArray {
    val (w, h) = parseSizeHint(uriString)
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color((uriString.hashCode() and 0xFFFFFF))
    g.fillRect(0, 0, w, h)
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "png", out)
    return out.toByteArray()
}

private fun parseSizeHint(uri: String): Pair<Int, Int> {
    val query = uri.substringAfter('?', "")
    if (query.isEmpty()) return 1 to 1
    val parts = query.split('&').mapNotNull {
        val (k, v) = it.split('=', limit = 2).let { kv ->
            if (kv.size == 2) kv[0] to kv[1] else return@mapNotNull null
        }
        k to v
    }.toMap()
    val w = parts["w"]?.toIntOrNull() ?: 1
    val h = parts["h"]?.toIntOrNull() ?: 1
    return w to h
}

class InProcessTaskTransport(
    private val clientId: String,
    private val gateway: () -> ProxyGateway
) : TaskTransport {
    override val isConnected: Boolean = true
    override fun bind() {}
    override fun unbind() {}
    override suspend fun call(request: ByteArray): ByteArray {
        return gateway().call(clientId, request)
    }

    override fun sendFile(uri: String) {}
    override var onCallback: ((ByteArray) -> Unit)? = null
}

/**
 * The app's real DI graph: the production modules, with the integration overrides layered on
 * top. Extracted so the graph has exactly one definition and the two entry points below differ
 * only in **where** they install it.
 */
private fun appIntegrationModules(
    clientId: String,
    additionalModules: List<Module>,
    objectStore: ObjectStore,
    realPreviews: Boolean = false,
    controlHasRealm: suspend () -> Boolean? = { true },
): List<Module> = listOf(
    platformClientModule,
    domainModule,
    filesRepositoryModule(),
    viewModelsModule(),
    platformProxyModule,
    proxyModule,
    module {
        single<FilesRepositoryPlatformDelegate> { FakeFilesRepositoryPlatformDelegate() }

        // ⚠⚠ These tests drive the FILE BROWSER, which presupposes a device that belongs to a
        // realm. The in-process proxy's SyncManager has none, so the production wiring correctly
        // reports `NotSetUp` and `App()` renders the set-up wall instead (ADR 0025 §Decision 8) —
        // every click then lands on nothing. Stated here rather than faked inside the proxy: the
        // wall's own judgement is pinned in `:app-reachability`'s `NotSetUpTest`.
        //
        // ⚠⚠ A wired probe is still ASKED, on the probe scope, and until it answers `App()` renders
        // the non-interactive checking gate — so a test must wait for the shell before its first
        // click, however instant the answer here is (`FilesScreenIntegrationTest.openFilesTab`).
        single<ControlReachability> {
            driveControlReachability(
                get<TenshuClient>(),
                probeScope = get(),
                schemaSkew = get<MutableSchemaSkewSource>().flow,
                controlHasRealm = app.tenshu.reachability.RealmProbe.Wired(controlHasRealm),
            )
        }

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

        single<TaskTransport> {
            InProcessTaskTransport(clientId) { get() }
        }

        // --- Provide manual client connection factory ---
        single<(String) -> ClientConnection>(named("platformClientConnectionFactory")) {
            val transport: TaskTransport = get()
            val factory: (String) -> ClientConnection = { cid ->
                if (cid == clientId) {
                    LocalClientConnection().apply {
                        addListener { data ->
                            // Link: Proxy -> Client callback (binary)
                            val bytes = BinarySerializer(get()).serialize(data)
                            (transport as InProcessTaskTransport).onCallback?.invoke(bytes)
                        }
                    }
                } else {
                    LocalClientConnection()
                }
            }
            factory
        }

        // --- Override platformImplementationFactory to include mock GetPreview ---
        single<(String) -> OperationImplementation>(named("platformImplementationFactory")) {
            val fileDatabase: FileDatabase = get()
            val syncRelay: SyncRelay = MockSyncRelay(get())
            val syncManager: SyncManager = get()
            val cryptoManager: SyncCryptoManager = get()
            val factory: (String) -> OperationImplementation = { operationType ->
                implementationFactory(operationType)
                    ?: org.tenshu.sync.proxy.syncProxyImplementationFactory(
                        operationType,
                        syncRelay,
                        syncManager,
                        cryptoManager
                    )
                    ?: fileServiceProxyImplementationFactory(
                        operationType,
                        fileDatabase,
                        // Wire the device id so File ops emit FileModification
                        // history (the emit hooks no-op without it) — needed for
                        // the FileDetailsScreen activity section. Mirrors prod,
                        // where SyncManager.currentDeviceId is threaded in.
                        deviceId = get(named("platformDeviceId"))
                    )
                    ?: notificationServiceProxyImplementationFactory(operationType)
                    ?: preferenceServiceProxyImplementationFactory(operationType)
                    // The desktop's own renderer, for the tests that look at what a thumbnail IS.
                    ?: (if (realPreviews) jvmFileServiceProxyImplementationFactory(operationType, fileDatabase) else null)
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
                    ?: error("No executor found for operation $operationType")
            }
            factory
        }
    }
) + additionalModules

/**
 * Builds the app graph in a container of this test's own, and returns a handle to it.
 *
 * ⚠⚠ Deliberately **not** `startKoin`: the graph is not installed in `GlobalContext`. That
 * slot is one process-wide variable shared by every test class in this source set, which is
 * why this function used to open with a defensive `stopKoin()`. That line quietly absorbed a
 * previous test that had failed to tear down — turning "the suite left state behind" into a
 * symptom that surfaces somewhere else entirely, which is the evidence-destroying shape
 * `scripts/gate-run.sh` exists to close elsewhere. A container held in a field cannot do that,
 * and asking the wrong one for a dependency is now a compile error rather than ambient luck.
 *
 * ⚠ This is **not** a fix for the leaked-coroutine flakes (`docs/tech-debt.md`). A loop that
 * outlives its test still leaks; it simply now holds a container nothing else can observe.
 * [AppIntegrationKoin.stop] is still what actually stops it.
 *
 * The two Compose tests keep the global one — `koinViewModel()` resolves from it — via
 * [startGlobalAppIntegrationKoin]; converting those wants `KoinContext(koin) { … }` around the
 * composition and is a change of its own.
 *
 * ⚠⚠ **One container at a time. A private container is not an isolated graph.** Four of the six
 * modules below are top-level `val module { … }` singletons (`platformClientModule`,
 * `domainModule`, `platformProxyModule`, `proxyModule`), and Koin caches a `single`'s instance
 * *inside the Module object*, not inside the container. So two containers built from the same
 * Module `val` hand out the **same** `ClientRegistry`, `SchemaRegistry` and `TenshuClient` —
 * measured, and controlled against `buildProxyModule()`, which is a function and does give two
 * distinct graphs. Sequential use is unaffected (`close()` drops the cached instances), which is
 * why the suite is correct today; but two live containers would silently share a proxy, and the
 * second one's `stop()` would drop the shared factory out from under the first. Recorded in
 * `docs/tech-debt.md`; making all four freshly-buildable is what a parallel suite needs.
 */
fun startAppIntegrationKoin(
    clientId: String = "app.tenshu.drive.test",
    additionalModules: List<Module> = emptyList(),
    // See control-shared AppIntegrationTestUtils: InMemory by default,
    // isolated per-call temp SqliteObjectStore under env TENSHU_TEST_SQLITE=true.
    objectStore: ObjectStore = integrationObjectStore(),
    /**
     * `GetPreview` is a stub by default: a fixed 1x1 PNG for ANY file at any size, so every
     * upload — a text file included — mints identical thumbnail classes. `true` wires the JVM
     * renderer production uses instead, so an image gets real classes and a non-image none.
     */
    realPreviews: Boolean = false,
): AppIntegrationKoin {
    val koinApp = koinApplication {
        allowOverride(true)
        modules(appIntegrationModules(clientId, additionalModules, objectStore, realPreviews))
    }
    koinApp.bindClient(clientId)
    return AppIntegrationKoin(koinApp)
}

/**
 * The globally-installed variant, for the Compose tests whose composition resolves through
 * `GlobalContext`; pair it with [stopAppIntegrationKoin] in the test's teardown. ⚠ No defensive
 * `stopKoin()` first, for the reason [startAppIntegrationKoin] gives: a Koin left running by an
 * earlier class must fail here, loudly, rather than be torn down silently
 * (`scripts/check-test-koin.py`).
 */
fun startGlobalAppIntegrationKoin(
    clientId: String = "app.tenshu.drive.test",
    additionalModules: List<Module> = emptyList(),
    objectStore: ObjectStore = integrationObjectStore(),
    /** Control's answer to "does this device have a realm" — a test may hold it to see the gate. */
    controlHasRealm: suspend () -> Boolean? = { true },
): TenshuClient {
    val koinApp = startKoin {
        allowOverride(true)
        modules(
            appIntegrationModules(clientId, additionalModules, objectStore, controlHasRealm = controlHasRealm)
        )
    }
    koinApp.bindClient(clientId)
    return koinApp.koin.get()
}

/** Mutually bind Proxy -> Client, whichever container the graph lives in. */
private fun KoinApplication.bindClient(clientId: String) {
    val clientRegistry: ClientRegistry = koin.get()
    clientRegistry.getExecutionContext(clientId).init()
}

/**
 * A running app graph plus the teardown that has to accompany it.
 *
 * Every dependency is asked of [koin], so a test can never accidentally read another test's
 * world — the thing `GlobalContext.get()` could not promise.
 */
class AppIntegrationKoin internal constructor(
    @PublishedApi internal val koinApp: KoinApplication,
) {
    val koin: Koin get() = koinApp.koin

    /** The client the graph was built for — what `startAppIntegrationKoin` used to return. */
    val client: TenshuClient get() = koin.get()

    inline fun <reified T : Any> get(): T = koin.get()

    /**
     * Cancel the proxy's per-client session scope(s) **before** dropping the container.
     *
     * Why this is not just `close()`: `initSyncService` starts long-lived background loops
     * (`SyncScheduler`, `ScheduleDrainLoop`) on each client's `ExecutionContext.sessionScope`.
     * Closing Koin drops the DI singletons but does **not** cancel those scopes, so the loops
     * keep ticking on `Dispatchers.Default`. `ScheduleDrainLoop` then resolves `DataFactory`
     * through the now-closed container (it's null), NPEs every tick, and that uncaught
     * exception lands on whichever `runTest`-based test runs next as
     * `UncaughtExceptionsBeforeTest` — making the whole suite order-dependently flaky.
     *
     * [ClientRegistry.shutdownAndJoin] cancels **and joins** every session scope (and the
     * fallback detach scope that carries detached Layer-2 tasks), so no loop or detached
     * `executeAsync` is still running when the next test starts. `clear()` alone is
     * insufficient: it is a routine-unbind that deliberately lets detached tasks outlive the
     * session, and its cancellation is fire-and-forget, so a tick can still land after
     * teardown. (The single-loop case was first worked around in `FileWriteBridgeRoundTripTest`.)
     */
    fun stop() {
        runCatching {
            runBlocking { koin.get<ClientRegistry>().shutdownAndJoin() }
        }
        koinApp.close()
    }
}

/**
 * Teardown counterpart to [startGlobalAppIntegrationKoin] — the same shutdown-then-drop as
 * [AppIntegrationKoin.stop], reaching through `GlobalContext` because that is where the
 * globally-installed variant put the container. See `stop()` for why the join is load-bearing.
 */
fun stopAppIntegrationKoin() {
    runCatching {
        GlobalContext.getOrNull()?.get<ClientRegistry>()?.let { registry ->
            runBlocking { registry.shutdownAndJoin() }
        }
    }
    stopKoin()
}

// Mirror of control-shared's helper: InMemory by default; isolated
// per-call temp-dir SqliteObjectStore under env TENSHU_TEST_SQLITE=true so
// the whole drive integration suite can run the durable backend.
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
