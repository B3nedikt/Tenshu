package org.tenshu.systemtest.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.CallstackMaterializer
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.forwardStreamElementsInOrder
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.trait.TraitResolver
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.hub.management.client.hubManagementServiceClientModule
import org.tenshu.hub.management.common.hubManagementServiceCommonModule
import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * Mirrors the production `app.tenshu.control.di.proxyModule` (`:control-shared`)
 * structurally. SystemTests can't depend on `:control-shared` (Control would
 * cycle if it ever consumed SystemTests), so the bindings are duplicated here.
 *
 * Drift risk: if production wiring changes and this isn't updated, `TestSystem`
 * will resolve the *old* graph. The pairing SystemTest is the canary for that
 * (it touches enough of the graph to fail loudly). See
 * `docs/proposals/testing-system-tests.md` §7.1.
 *
 * Excludes Control-app-specific bindings (`SyncRepository`,
 * `LandingStateRepository`) — those don't belong in a generic test framework.
 *
 * The platform-bound names (`platformPackageName`, `platformImplementationFactory`,
 * `platformClientConnectionFactory`, `platformProxyInitialization`,
 * `syncRelayUrl`, `HttpClient`, `SyncRelay`, `SyncManager`, `FileDatabase`,
 * `SecureStorageDelegate`, `SemanticMergerRegistry`, `SyncProcessor`,
 * `TombstoneEmitterHolder`) are provided by [systemTestPlatformModule].
 */
fun systemTestProxyModule() = module {
    includes(coreCommonModule())

    // One CoroutineScope shared across the client (ClientRegistry,
    // SyncScheduler, AsyncTaskExecutor stream dispatch). Owned by Koin so
    // `TestSystem.shutdown` can cancel it before the Koin scope closes —
    // without that, the per-client background coroutines (especially the
    // SyncScheduler loop) outlive the test, keep hitting the now-closed
    // Koin scope with `ClosedScopeException`, and starve `Dispatchers.Default`
    // workers shared with the next test — a cumulative starvation that
    // surfaces as flakes in unrelated tests.
    single<CoroutineScope>(named("clientScope")) {
        CoroutineScope(Dispatchers.Default)
    }

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        listOf(
            fileServiceCommonModule,
            fileServiceClientModule,
            healthServiceCommonModule,
            healthServiceClientModule,
            notificationServiceCommonModule,
            notificationServiceClientModule,
            // Calendar: `CalendarMirrorStopSystemTest` needs two real devices to
            // show a stopped *Keep in sync* mirror stays stopped. Schema only here; the ops and
            // the relation maintainer are in `systemTestPlatformModule`, as in production.
            calendarServiceCommonModule,
            calendarServiceClientModule,
            // Photos (ED-3): `PhotoEditSyncSystemTest` syncs a `PhotoEdit` between two devices.
            org.tenshu.photo.common.photoServiceCommonModule,
            org.tenshu.photo.client.photoServiceClientModule,
            preferenceServiceCommonModule,
            preferenceServiceClientModule,
            hubManagementServiceCommonModule,
            hubManagementServiceClientModule,
            homeDeviceServiceCommonModule,
            miaServiceCommonModule,
            miaServiceClientModule,
            homeDeviceServiceClientModule,
            routineServiceCommonModule,
            routineServiceClientModule,
            contactServiceCommonModule,
            contactServiceClientModule,
            identityServiceCommonModule,
            identityServiceClientModule,
            syncServiceCommonModule,
            syncServiceClientModule
        ) + (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
    }

    single<TraitResolver> { TraitResolverImpl(get()) }

    // Each TestClient gets its own ReentrantLock-backed DatabaseLock — the
    // SystemTests run multiple background coroutines (SyncScheduler, observer
    // fanout, AsyncTaskExecutor stream dispatch) on `Dispatchers.Default`, so
    // a NoOp lock would expose the same read-modify-write races on
    // `DatabaseImpl.addToArray` / `hashToDataId` that production hits.
    single<DatabaseLock> { ReentrantLockDatabaseLock() }

    // Bound as its own single (mirrors production `ProxyModule`'s `objectStore = get<ObjectStore>()`)
    // rather than inlined into `DatabaseImpl`, so tests can reach the store — e.g. to model an
    // object-store wipe for the sync cursor/store desync self-heal.
    //
    // ⚠ Lock-checked: an access that reaches the store without the `DatabaseLock` fails the test at
    // `TestSystem.shutdown` — see [LockCheckedObjectStore].
    single<ObjectStore> {
        val lock = get<DatabaseLock>() as ReentrantLockDatabaseLock
        LockCheckedObjectStore(lockHeldByCurrentThread = { lock.isHeldByCurrentThread })
    }

    single<Database> {
        DatabaseImpl(
            context = get(),
            objectStore = get<ObjectStore>(),
            dbEntryMapper = DbEntryMapper(
                context = get(),
                schemaProvider = get(),
                dataFactory = get(),
                traitResolver = get()
            ),
            traitResolver = get(),
            lock = get<DatabaseLock>()
        )
    }

    single<OperationImplementationFactory> {
        val factory = get<(String) -> OperationImplementation>(named("platformImplementationFactory"))
        OperationImplementationFactory { id -> factory(id) }
    }

    single {
        ProxyGateway(
            clientRegistry = get(),
            parser = get(),
            serializer = get()
        )
    }

    single {
        ClientRegistry(
            database = get(),
            traitResolver = get(),
            implementationFactory = get(),
            materializer = CallstackMaterializer(get(), get()),
            pureOperationRunner = get(),
            baseContext = get(),
            serviceScope = get(named("clientScope")),
            clientConnectionFactory = get<(String) -> ClientConnection>(named("platformClientConnectionFactory")),
            initialization = get<ExecutionContext.() -> Unit>(named("platformProxyInitialization"))
        )
    }

    single<AsyncTaskExecutor> {
        val clientRegistry: ClientRegistry = get()
        val clientId = get<String>(named("platformPackageName"))
        val scope = get<CoroutineScope>(named("clientScope"))

        // Must be a CancellableLocalTaskExecutor, NOT a bare AsyncTaskExecutor
        // (mirrored from Control's ProxyModule): BackgroundOperations (AwaitPairing, AwaitInviteAck)
        // route through `executeAsyncCancellable` only when the executor
        // implements it — a bare AsyncTaskExecutor resolves their
        // TaskHandle.await() instantly to a bogus Success(null).
        object : CancellableLocalTaskExecutor, CancellableReadTaskExecutor {
            private val delegate by lazy {
                val executionContext = clientRegistry.getExecutionContext(clientId)
                val dispatcher = get<StreamElementDispatcher>()

                val connection = executionContext.clientConnection
                if (connection is LocalClientConnection) {
                    // In wire order, on one consumer (mirrors Control's ProxyModule) — see
                    // `forwardStreamElementsInOrder`.
                    connection.forwardStreamElementsInOrder(scope, dispatcher)
                }

                executionContext.init()
                LocalTaskExecutor(executionContext)
            }

            override fun execute(task: TList<Data>) =
                delegate.execute(task)

            override suspend fun executeAsync(task: TList<Data>) =
                delegate.executeAsync(task)

            override suspend fun executeAsyncCancellable(task: TList<Data>) =
                delegate.executeAsyncCancellable(task)

            // ⚠⚠ The TWO-ARG variant, and forgetting it is invisible. Its interface default
            // drops the taskId and calls the one-arg form, so a `withTask`-tagged call reaches
            // the proxy with `requestSyncLaneId = null`, every save takes the per-save path, and
            // the sync lane batches NOTHING — while every existing assertion still passes,
            // because batching changes only the block COUNT and the rows converge either way.
            // `BulkWriteTaskSystemTest` counts the blocks, so it is the test that fails. Control
            // binds `SessionAwareLocalTaskExecutor`, which threads it — which is why the harness
            // has to mirror that wiring rather than approximate it.
            override suspend fun executeAsyncCancellable(task: TList<Data>, taskId: String?) =
                delegate.executeAsyncCancellable(task, taskId)

            // Mirrors Control's `SessionAwareLocalTaskExecutor`: `cancellableReads { }` stops the
            // read in-process (`core-background-processing.md` §6.2.1).
            override suspend fun executeAsyncCancellableRead(task: TList<Data>) =
                delegate.executeAsyncCancellableRead(task)
        }
    }

    single<TaskExecutor> { get<AsyncTaskExecutor>() }

    single {
        val taskExecutor: AsyncTaskExecutor = get()
        TenshuEnvironment(
            taskExecutor = taskExecutor,
            schemaRegistry = get(),
            tContext = get(),
            requestChainFactory = { ctx -> LocalRequestChain(taskExecutor, ctx) }
        )
    }

    single<TenshuClient> { DefaultTenshuClient(get()) }
}
