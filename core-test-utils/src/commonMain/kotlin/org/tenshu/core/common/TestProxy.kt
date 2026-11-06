package org.tenshu.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.CallstackMaterializer
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.forwardStreamElementsInOrder
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitResolver
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class TestProxy(
    val schemaRegistry: SchemaRegistry = createCoreTestRegistry(),
    val additionalComponents: Map<String, TComponent> = emptyMap(),
    val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    // Store + lock default to the in-memory store and a REAL reentrant lock
    // ([TestDatabaseLock] says why it must not be the no-op one). Injectable
    // so a test can pin the *production* pair (`SqliteObjectStore` +
    // `ReentrantLockDatabaseLock`) to reproduce store behaviour the in-memory
    // path can't — see `docs/proposals/drive-folder-listing-consistency.md` §4.
    //
    // Declared BEFORE implementationFactoryOverride so the latter stays the last
    // parameter — call sites use trailing-lambda syntax `TestProxy(...) { ... }`,
    // which binds to the last param.
    objectStore: ObjectStore = InMemoryObjectStore(),
    lock: DatabaseLock = TestDatabaseLock(),
    private val implementationFactoryOverride: ((String, TestProxy) -> OperationImplementation?)? = null,
) {

    /**
     * The scope long-lived proxy machinery is wired onto (the [ClientRegistry]
     * `serviceScope` whose per-session `SupervisorJob` is parented to it, and
     * the inbound-update fan-out `launch` below).
     *
     * When [coroutineScope] is a `runTest` [TestScope], route that work through
     * its [TestScope.backgroundScope]: `runTest` cancels `backgroundScope` when
     * the test body finishes, so the session SupervisorJob ClientRegistry
     * parents to serviceScope is not a never-completed *child of the test
     * job* — which is exactly what `runTest` reports as
     * `UncompletedCoroutinesError`. Cross-test isolation is preserved:
     * `backgroundScope` is still cancelled at test end, just by `runTest`
     * rather than a leaked orphan. Non-`TestScope` callers are unaffected.
     */
    private val effectiveScope: CoroutineScope =
        (coroutineScope as? TestScope)?.backgroundScope ?: coroutineScope

    private val _updates = AtomicReference<List<Data>>(emptyList())
    val updates: List<Data> get() = _updates.load()

    val additionalImplementations = mutableMapOf<String, OperationImplementation>()

    val parser = BinaryParser(schemaRegistry, schemaRegistry)

    val serializer = BinarySerializer()

    private val operationImplementationFactory = OperationImplementationFactory { operationType ->
        additionalImplementations[operationType]
            ?: implementationFactoryOverride?.invoke(operationType, this)
            ?: implementationFactory(operationType)
    }

    val testClientConnection = LocalClientConnection()

    val fileDatabase = object : FileDatabase {

        val files = mutableMapOf<String, TByteArray>()

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            files[filePath] = TByteArrayDto(bytes)
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) {
            files[filePath] = bytes
        }

        override fun readBytes(filePath: String): TByteArray? {
            return files[filePath]
        }

        // Faithful deletion (removes the key entirely, not an empty write) so
        // evict-style ops can be tested: a removed key makes readBytes return
        // null, which is what re-triggers an OnDemand re-fetch. Mirrors the
        // real InMemoryFileDatabase / AndroidFileDatabaseImpl overrides.
        override fun deleteFile(filePath: String) {
            files.remove(filePath)
        }
    }

    // CancellableLocalTaskExecutor (not just AsyncTaskExecutor) so the
    // DefaultInteractorProvider below routes a BackgroundOperation through the
    // in-process cancellable path — same shape as JsRuntime's real
    // LocalTaskExecutor (core-background-processing.md §8).
    val localTaskExecutor: AsyncTaskExecutor = object : CancellableLocalTaskExecutor, CancellableReadTaskExecutor {
        override fun execute(task: TList<Data>): TResult<Data> {
            return LocalTaskExecutor(executionContext).execute(task).also(::observeReply)
        }

        override suspend fun executeAsync(task: TList<Data>): TResult<Data> {
            return LocalTaskExecutor(executionContext).executeAsync(task).also(::observeReply)
        }

        override suspend fun executeAsyncCancellable(task: TList<Data>): TResult<Data> {
            return LocalTaskExecutor(executionContext).executeAsyncCancellable(task).also(::observeReply)
        }

        // ⚠⚠ The two-arg (sync-lane) variant must be forwarded too. Its interface default
        // silently drops the taskId, so a `withTask` block over this proxy would tag nothing and
        // batch nothing — with no failing assertion anywhere, since the rows still converge.
        // `SystemTestProxyModule` carries the same forward.
        override suspend fun executeAsyncCancellable(
            task: TList<Data>,
            taskId: String?,
        ): TResult<Data> {
            return LocalTaskExecutor(executionContext).executeAsyncCancellable(task, taskId).also(::observeReply)
        }

        override suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data> {
            return LocalTaskExecutor(executionContext).executeAsyncCancellableRead(task).also(::observeReply)
        }
    }

    /**
     * Sees every reply [localTaskExecutor] hands back to a client — `getAll`, `get`, op calls and a
     * stream's start reply (its later elements are pushed past it). `null` by default.
     *
     * ⚠ The seam for asserting a read is **bounded in bytes** ([ReplySizes.measure]): in process a
     * reply is an object, so a whole-realm reply and a 50-row page look alike to a test unless
     * someone serialises them the way the IPC transport would.
     */
    var replyObserver: ((TResult<Data>) -> Unit)? = null

    private fun observeReply(result: TResult<Data>) {
        replyObserver?.invoke(result)
    }

    val interactorProvider = DefaultInteractorProvider { localTaskExecutor }

    val tContext: TContext = createTestContext(
        schemaRegistry = schemaRegistry,
        interactorProvider = interactorProvider,
        traitResolver = object : TraitResolver {
            override fun <T : Trait> getTrait(typeId: String, traitId: String): T? =
                executionContext.traitResolver.getTrait(typeId, traitId)
        },
        pureOperationRunner = object : PureOperationRunner {
            override fun <T : Data?> execute(operation: Operation, caller: Data?): T =
                PureOperationRunnerImplementation({ tContext }) { operationImplementationFactory }.execute(
                    operation,
                    caller
                )
        },
        additionalComponents = additionalComponents
    )

    val database: Database = DatabaseImpl(
        context = tContext,
        objectStore = objectStore,
        dbEntryMapper = DbEntryMapper(
            context = tContext,
            schemaProvider = schemaRegistry,
            dataFactory = schemaRegistry,
            traitResolver = object : TraitResolver {
                override fun <T : Trait> getTrait(typeId: String, traitId: String): T? =
                    executionContext.traitResolver.getTrait(typeId, traitId)
            }
        ),
        traitResolver = object : TraitResolver {
            override fun <T : Trait> getTrait(typeId: String, traitId: String): T? =
                executionContext.traitResolver.getTrait(typeId, traitId)
        },
        lock = lock
    )

    private val materializer = CallstackMaterializer(
        schemaRegistry = schemaRegistry,
        tContext = tContext
    )

    val clientRegistry = ClientRegistry(
        database = database,
        traitResolver = TraitResolverImpl { tContext },
        implementationFactory = operationImplementationFactory,
        clientConnectionFactory = { testClientConnection },
        materializer = materializer,
        pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
        baseContext = tContext,
        serviceScope = effectiveScope
    )

    val executionContext: ExecutionContext by lazy {
        clientRegistry.getExecutionContext("test-client")
    }

    val gateway = ProxyGateway(
        clientRegistry = clientRegistry,
        parser = parser,
        serializer = serializer
    )

    /**
     * Pre-admit [clientId]'s session past the reject-if-silent gate
     * (`core-schema-evolution.md` §6.5) — for tests that drive `gateway.call`
     * directly without the production announce wiring. Production clients
     * announce for real (`SchemaAnnouncer` as the executor's session
     * preflight); `SchemaSessionGateTest` covers the gate itself.
     */
    fun admitSchemaSession(clientId: String) {
        clientRegistry.getExecutionContext(clientId).sessionAnnouncement.announcedVector =
            schemaRegistry.currentVersionVector(tContext)
    }

    /**
     * This registry's serialized frozen `VersionVector` — the stamp a hand-built
     * `SyncBlock` payload must carry (`core-schema-evolution.md` §4.1.2).
     *
     * ⚠ Required, not cosmetic: once ANY migration chain is registered (as in
     * production), a core-only/empty stamp is dropped BY DECISION on ingest
     * (§8.2 — its producing per-service versions cannot be proven), so
     * `BlockPayloadEnvelope.seal(bytes)` with no stamp makes
     * a test block silently un-appliable. Pass this.
     */
    fun versionStampBytes(): ByteArray =
        BinarySerializer().serialize(schemaRegistry.currentVersionVector(tContext))

    // ProxyGateway.call is suspend (so the class is JS-compatible).
    suspend fun handleRequest(request: ByteArray): ByteArray {
        admitSchemaSession("test-client")
        return gateway.call("test-client", request)
    }

    /**
     * Quiesce this proxy: cancel **and join** every session's background work, then dispose its
     * context — the same teardown `stopAppIntegrationKoin` performs for the app harnesses.
     *
     * ⚠⚠ **Call this from any test that ran `initSyncService`.** That starts a `SyncScheduler` and a
     * `ScheduleDrainLoop` (the latter ticks every second, forever) on the session scope, and
     * nothing else in the plain `TestProxy` path stops them. A loop still
     * ticking after its test has finished is not merely wasted CPU: when it throws, the JVM-global
     * `ExceptionCollector` that `kotlinx-coroutines-test` installs replays that throw into the
     * **next** `runTest` as `UncaughtExceptionsBeforeTest` — so the failure lands on an unrelated,
     * innocent test, which never reproduces in isolation: alone, there is no next test to blame.
     *
     * ⚠ Not needed when [coroutineScope] is a `runTest` `TestScope` — the work then rides
     * [TestScope.backgroundScope], which `runTest` cancels for you. Passing the scope is the better
     * fix where a test has one to pass; this exists for the fixtures that build a proxy in a field
     * or `@BeforeTest`, where no `TestScope` is in hand.
     *
     * ⚠ Suspends, because the join is the point — a bare cancel returns while a tick is still
     * unwinding, which is the race it is meant to close.
     */
    suspend fun shutdown() {
        clientRegistry.shutdownAndJoin()
    }

    init {
        testClientConnection.addListener { data ->
            println("TestProxy -> Received update: $data")
            while (true) {
                val current = _updates.load()
                val next = current + data
                if (_updates.compareAndSet(current, next)) break
            }
        }
        // In wire order, on one consumer — see `forwardStreamElementsInOrder`.
        testClientConnection.forwardStreamElementsInOrder(effectiveScope, interactorProvider)
    }
}
