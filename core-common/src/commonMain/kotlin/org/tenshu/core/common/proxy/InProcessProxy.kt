package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.InteractorProvider
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.NoOpDatabaseLock
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.time.SystemClock
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RoProvider
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.Clock
import org.tenshu.core.trait.TraitResolver

/**
 * A **local, in-process proxy**: the generic assembly of `SchemaRegistry` → hand-wired
 * [TContext] → [DatabaseImpl] over an [InMemoryObjectStore] → [ClientRegistry] →
 * [DefaultTenshuClient], exposing a real [TenshuClient] callers read/write over. There is no
 * inter-app IPC, no sync relay and no pairing — this is the pragmatic single-process client
 * graph for an app that hosts its own backend services in-process.
 *
 * This is the production twin of core-test-utils' `TestProxy` (same construction, production
 * types), factored out so any single-process app graph can stand up its services without
 * re-deriving the intricate init order. The circular refs (the schema registry needs a
 * [TContext] whose components include the registry; the task executor needs the not-yet-built
 * [executionContext]) are broken exactly as the proven `TestProxy`/`createTestContext` do it:
 * a [TContext] over a mutable component map populated in an `init` block, a lazy
 * [executionContext], and providers captured by lambda.
 *
 * @param clientId the client identity the single hosted session is minted under.
 * @param serviceScope the scope proxy machinery (per-session `SupervisorJob`, relation
 *   maintainers) is parented to; cancel it to tear the proxy down.
 * @param operationImplementationFactory resolves an op type to its implementation. Callers
 *   chain their service factories ahead of the core-common [implementationFactory] fallback.
 * @param schemaRegistryFactory builds the registry given a provider of this proxy's
 *   [TContext] (usually `createDefaultSchemaRegistry(it, extraConfigs = …)`).
 * @param syncCryptoManager the session's crypto component. No default, so a shipped graph can't
 *   silently end up on the test stub; production passes `createDefaultSyncCryptoManager()`.
 * @param clientConnectionFactory the inbound-update channel per session; defaults to a
 *   [LocalClientConnection] (no external transport).
 * @param initialization run once against the freshly minted session's [ExecutionContext];
 *   services register their relation-maintainer observers here (`initXService()`).
 * @param streamScope the scope the session's stream elements are delivered on
 *   ([forwardStreamElementsInOrder]); [serviceScope] unless a test needs to hold delivery alone
 *   (`StreamResubscribeStaleBacklogTest`), as `ProxyModule`'s `clientStreamScope` does.
 * @param clientScope where the client half runs a cancellable task's round trip
 *   ([DefaultInteractorProvider]'s `clientScope`). Its own default, a `Dispatchers.Default` scope,
 *   unless a test needs every dispatcher on the path to be its own: a `runTest` that passes its
 *   `backgroundScope` here and as [serviceScope] runs the whole stack on virtual time
 *   (`StreamSubscribeRetryTest`; `docs/testing.md` §10).
 */
class InProcessProxy(
    private val clientId: String,
    private val serviceScope: CoroutineScope,
    operationImplementationFactory: OperationImplementationFactory,
    schemaRegistryFactory: (tContextProvider: () -> TContext) -> SchemaRegistry,
    syncCryptoManager: SyncCryptoManager,
    clock: Clock = SystemClock(),
    clientConnectionFactory: (clientId: String) -> ClientConnection = { LocalClientConnection() },
    initialization: ExecutionContext.() -> Unit = {},
    private val streamScope: CoroutineScope = serviceScope,
    clientScope: CoroutineScope? = null,
) {

    private val componentsMap = mutableMapOf<String, TComponent>()

    val tContext: TContext = object : TContext {
        override val components: Map<String, TComponent> get() = componentsMap
    }

    val schemaRegistry: SchemaRegistry = schemaRegistryFactory { tContext }

    private val traitResolver: TraitResolver = TraitResolverImpl { tContext }

    // Refers to `executionContext` (lazy) — resolved on first task, after construction.
    val localTaskExecutor: AsyncTaskExecutor = object : CancellableLocalTaskExecutor, CancellableReadTaskExecutor {
        override fun execute(task: TList<Data>): TResult<Data> =
            LocalTaskExecutor(executionContext).execute(task)

        override suspend fun executeAsync(task: TList<Data>): TResult<Data> =
            LocalTaskExecutor(executionContext).executeAsync(task)

        override suspend fun executeAsyncCancellable(task: TList<Data>): TResult<Data> =
            LocalTaskExecutor(executionContext).executeAsyncCancellable(task)

        // ⚠⚠ The two-arg (TS-4 sync-lane) variant: its interface default drops the taskId, so
        // omitting it turns `withTask` batching off for everything running over this proxy —
        // silently, since only the block count changes. See `SystemTestProxyModule`.
        override suspend fun executeAsyncCancellable(
            task: TList<Data>,
            taskId: String?,
        ): TResult<Data> =
            LocalTaskExecutor(executionContext).executeAsyncCancellable(task, taskId)

        override suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data> =
            LocalTaskExecutor(executionContext).executeAsyncCancellableRead(task)
    }

    /**
     * Internal rather than private so the resubscribe pass
     * (`docs/proposals/core-client-resubscription.md` R1) can be driven directly in tests. An
     * in-process proxy never actually reconnects — it shares the client's process, which is exactly
     * why iOS route-1 and the web runtime are immune to that bug — but this is the only harness with
     * a real stream stack, so it is where the client-side half of the contract can be exercised.
     */
    internal val interactorProvider =
        if (clientScope == null) DefaultInteractorProvider { localTaskExecutor }
        else DefaultInteractorProvider(clientScope) { localTaskExecutor }

    private val pureOperationRunner: PureOperationRunner =
        PureOperationRunnerImplementation({ tContext }) { operationImplementationFactory }

    // Populate the context's components BEFORE anything that materialises schemas
    // (Database / ClientRegistry) is constructed — mirrors `createTestContext`.
    init {
        componentsMap[DataFactory.COMPONENT_ID] = schemaRegistry
        componentsMap[SchemaProvider.COMPONENT_ID] = schemaRegistry
        componentsMap[RoProvider.COMPONENT_ID] = schemaRegistry
        componentsMap[OperationResolver.COMPONENT_ID] = schemaRegistry
        componentsMap[LazyDataMaterializer.ID] = schemaRegistry
        componentsMap[TraitResolver.ID] = traitResolver
        componentsMap[PureOperationRunner.ID] = pureOperationRunner
        componentsMap[SyncCryptoManager.ID] = syncCryptoManager
        componentsMap[Clock.COMPONENT_ID] = clock
        componentsMap[InteractorProvider.COMPONENT_ID] = interactorProvider
    }

    private val objectStore = InMemoryObjectStore()

    private val database: Database = DatabaseImpl(
        context = tContext,
        objectStore = objectStore,
        dbEntryMapper = DbEntryMapper(
            context = tContext,
            schemaProvider = schemaRegistry,
            dataFactory = schemaRegistry,
            traitResolver = traitResolver,
        ),
        traitResolver = traitResolver,
        lock = NoOpDatabaseLock(),
    )

    private val materializer = CallstackMaterializer(schemaRegistry = schemaRegistry, tContext = tContext)

    private val clientRegistry = ClientRegistry(
        database = database,
        traitResolver = traitResolver,
        implementationFactory = operationImplementationFactory,
        // Deliver live stream elements (the array-change events a write emits) to the interactor,
        // so reactive `.flow()` subscribers receive post-subscription mutations — a freshly saved
        // entity shows up in an already-open list. Without this the initial snapshot arrives (it is
        // returned synchronously by the stream-start op) but subsequent changes are dropped on the
        // client connection, which reads as "writes persist but never appear live". Mirrors the
        // equivalent wiring in core-test-utils' `TestProxy`.
        clientConnectionFactory = { id ->
            clientConnectionFactory(id).also { connection ->
                if (connection is LocalClientConnection) {
                    // In wire order, on one consumer — see `forwardStreamElementsInOrder` for
                    // why a launch per element is not equivalent.
                    connection.forwardStreamElementsInOrder(streamScope, interactorProvider)
                }
            }
        },
        materializer = materializer,
        pureOperationRunner = pureOperationRunner,
        baseContext = tContext,
        serviceScope = serviceScope,
        initialization = initialization,
    )

    val executionContext: ExecutionContext by lazy { clientRegistry.getExecutionContext(clientId) }

    val client: TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = localTaskExecutor,
            schemaRegistry = schemaRegistry,
            tContext = tContext,
            requestChainFactory = { ctx -> LocalRequestChain(localTaskExecutor, ctx) },
        )
    )
}
