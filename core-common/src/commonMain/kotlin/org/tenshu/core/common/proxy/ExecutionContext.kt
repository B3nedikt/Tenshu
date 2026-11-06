package org.tenshu.core.common.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import org.tenshu.core.BackgroundScopeProvider
import org.tenshu.core.Data
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.PureOperationImplementation
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.trait.TraitResolver
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.core.collection.list.toList
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.isBackground

/**
 * ExecutionContext is the core of the Platform logic on the proxy side.
 * It manages:
 * - Data retrieval and storage via [Database].
 * - Trait resolution and execution via [TraitResolver].
 * - Operation execution using implementations from [OperationImplementationFactory].
 * - Navigation through the protocol graph (services, entities, lists, properties).
 * - Reactive streaming of list updates to [ClientConnection].
 */
class ExecutionContext(
    val context: TContext,
    val database: Database,
    val traitResolver: TraitResolver,
    val implementationFactory: OperationImplementationFactory,
    val clientConnection: ClientConnection,
    val pureOperationRunner: PureOperationRunner,
    val materializer: CallstackMaterializer,
    val sessionScope: CoroutineScope,
    val referenceResolver: ReferenceResolver = ReferenceResolverImpl(context, database, materializer),
    /**
     * Owns per-stream listener state — which `ArrayDatabaseReference`s have
     * listeners, which stream IDs are subscribed to which references, and what
     * items each stream has most recently emitted. Shared by [withEvaluationScope]
     * across per-request contexts derived from the same session: stream lifecycle
     * (start, listener-fire, end) crosses request boundaries, so the backing maps
     * cannot live on a per-request context.
     */
    val streamRegistry: StreamRegistry = StreamRegistry(),
    /**
     * Owns the per-session set of [DataObserver]s and the snapshot-then-iterate
     * dispatch on [saveData] / [deleteData]. Shared by [withEvaluationScope] for
     * the same reason as [streamRegistry]: observer registration happens on the
     * session context (via `initialization`), but observer fires happen on
     * per-request contexts when `ProxyGateway`-routed operations (cross-process
     * IPC: Drive → Control via Binder, or anything entering through
     * `gateway.call`) call `executionContext.saveData(...)`.
     *
     * Without this share, the wrapped per-request context would have an empty
     * observer set, so `SyncObserver` would never emit a block,
     * `RelationMaintainer` / `ParentFilesRelationMaintainer` /
     * `NotificationRelationMaintainer` would never maintain their relations,
     * and the IPC-invoked write would effectively vanish from anyone but the
     * local DB.
     */
    val observerRegistry: ObserverRegistry = ObserverRegistry(),
    /**
     * Per-session schema-announce state (`core-schema-evolution.md` §6.4),
     * written by `AnnounceSessionImplementation`. Shared by
     * [withEvaluationScope] like [streamRegistry]/[observerRegistry]: the
     * announce op executes on a per-request context, but the admitted vector
     * is a property of the session.
     */
    val sessionAnnouncement: SessionAnnouncement = SessionAnnouncement(),
    /**
     * Who this session belongs to — the identity the transport *attested*, never one the
     * client stated: the calling package on Android (`Binder.getCallingUid()` →
     * `PackageManager`), the peer's code-signing identifier on desktop, the proxy's own
     * package for the in-process client, [ClientRegistry.SYSTEM_CLIENT_ID] for the system
     * session. `null` only for a context minted outside [ClientRegistry] (tests, in-process
     * fixtures).
     *
     * Carried on the context so a proxy-side implementation can scope per-client state to
     * the caller it is running for. First consumer: the Android upload path, whose
     * `ParcelFileDescriptor` cache is one process-wide map that every Binder client writes
     * into — without an identity to key on, one client could seed or overwrite the entry
     * another was about to upload under. Shared by [withEvaluationScope] like the rest of
     * the session-scoped state.
     */
    val clientId: String? = null,
    val evaluationScope: CoroutineScope? = null,
    /**
     * Phase L2 (docs/proposals/core-background-processing.md §8). When `true`,
     * an `AsyncOperationImplementation` whose schema carries
     * [org.tenshu.core.trait.BackgroundOperation] runs as an *awaited*
     * [AsyncReference] on [evaluationScope] (the per-request scope) instead of
     * the legacy detached `sessionScope.launch` + immediate `Success(null)`.
     * That keeps the work inside `requestJob`, so a wire `cancel(taskId)` —
     * which cancels `requestJob` — actually unwinds it, and the real result
     * flows back through the [TaskHandle]. Set only by `ProxyGateway.call`
     * when the request carried a client-minted task id; the legacy
     * fire-and-forget path (e.g. `StartPairing` via `asTaskInteractor`, polled
     * by the pairing wizard) is unchanged until the §7.5 migration.
     */
    val runBackgroundInRequestScope: Boolean = false,
    /**
     * Task-scoped sync lane (TS-3, `core-task-composite.md` §5): the task id
     * the current request rode in on (the Phase-L2 cancellable envelope's
     * client-minted id), threaded here by `ProxyGateway.call` via
     * [withEvaluationScope]. [saveData] falls back to it when the caller
     * passes no explicit `syncLaneId`, so a constituent op running under
     * `withTask` joins the task's sync lane with **zero op changes**. Safe
     * for non-task cancellable calls (e.g. `AwaitPairing`): `SyncObserver`
     * only stages into lanes that were **explicitly opened** by
     * `RegisterTask` — an unopened lane id falls through to the ordinary
     * per-save path.
     */
    val requestSyncLaneId: String? = null,
    /**
     * Set only on the per-request context of a **cancellable read** (`ProxyGateway` with a read id,
     * `LocalTaskExecutor.executeAsyncCancellableRead`): the row seams below ([getArrayData],
     * [getArrayDataWindow], [resolveList]) and `evaluateCallstack`'s step loop call its
     * [RequestCancellation.checkpoint], so an abandoned read stops walking instead of finishing in
     * the dark (`core-background-processing.md` §6.2.1). `null` everywhere else — no check, no cost.
     */
    val requestCancellation: RequestCancellation? = null,
    /**
     * Phase 5 of `docs/proposals/core-error-channel.md`: proxy-side diagnostic
     * recorder, invoked by [LocalTaskExecutor]'s catch-all whenever an
     * exception escapes an op body (typed [org.tenshu.core.TenshuException]
     * or an unexpected `Throwable`). `null` when no `:logging-service-proxy`
     * is wired (test harnesses, JS in-process runtime); production wires a
     * real [ErrorEventRecorder] in `PlatformProxyModule` post-construction
     * (the recorder needs `this` `ExecutionContext` to save into the realm,
     * so it can't be constructor-supplied without a two-pass dance).
     */
    var errorEventRecorder: ErrorEventRecorder? = null,
    private val initialization: ExecutionContext.() -> Unit = {}
) : TContext {

    val schemaProvider get() = context.schemaProvider()

    /**
     * Every declared relation's [RelationSpec][org.tenshu.core.common.relation.RelationSpec],
     * indexed (`core-relation-system.md` §9.2). The one place a relation's
     * `ArrayDatabaseReference` is derived, so a **reader** and the
     * [RelationMaintainer][org.tenshu.core.common.relation.RelationMaintainer]
     * that writes the same array cannot disagree about `isEntity` or
     * `elementSchemaId` — the D4 duplication. Reach it through
     * [relationReference][org.tenshu.core.common.relation.relationReference]
     * rather than hand-building the reference.
     *
     * Lazy because the module graph is fully registered only after construction.
     */
    val relationRegistry: org.tenshu.core.common.relation.RelationRegistry by lazy {
        org.tenshu.core.common.relation.RelationRegistry.from(schemaProvider)
    }

    override val components: Map<String, TComponent> by lazy {
        val base = context.components
        base + (BackgroundScopeProvider.ID to object : BackgroundScopeProvider {
            override val serviceScope: CoroutineScope = this@ExecutionContext.sessionScope
        })
    }

    private var initializationRan = false

    fun init() {
        clientConnection.init()
    }

    /**
     * Runs the proxy-wide `initialization` hook (service wiring: observers,
     * the sync scheduler, the schedule-drain loop, the sync-health collector…).
     *
     * Deliberately **not** run from the constructor. The hook starts background
     * work that re-enters [ClientRegistry.getExecutionContext] for the *same*
     * clientId — the in-process `SessionAwareLocalTaskExecutor` path
     * (`sessionProvider = { clientRegistry.getExecutionContext(clientId) }`),
     * reached from the schema announce and from any client-side stream the
     * wiring opens. While this context was still inside its constructor it was
     * not yet in the registry's session map, so that re-entry did not find it
     * and minted a *second* session — which ran `initialization` again and
     * re-entered once more. Every session but the last was then dropped from
     * the map (same clientId, copy-on-write overwrite) without its
     * `sessionScope` ever being cancelled, so its scheduler / drain loop /
     * health collector ticked on forever against DI singletons that teardown
     * had already dropped.
     *
     * [ClientRegistry] therefore registers the session **first** and calls this
     * after: the re-entrant lookup now finds and reuses the one true session.
     * Idempotent, so a second call (or a caller that also calls [init]) is a
     * no-op.
     */
    fun runInitialization() {
        if (initializationRan) return
        initializationRan = true
        initialization()
    }

    fun destroy() {
        streamRegistry.clear()
        observerRegistry.clear()
        clientConnection.destroy()
    }

    /**
     * Deterministic, suspend teardown: stop the session's background work and
     * **wait for it to unwind** before disposing the context. Cancelling
     * [sessionScope] alone is not enough — cancellation is cooperative and
     * asynchronous, so a tick already in flight (e.g. the sync scheduler or the
     * constraint-gate collector, which do synchronous, non-suspending context
     * access like `dataFactory()` between suspension points) can still touch a
     * half-disposed context or a dropped DI singleton after a plain `cancel()`
     * returns. [cancelAndJoin] gives us the missing *join*: it returns only once
     * every coroutine on [sessionScope] (the loops launched there by
     * `initSyncService`, the constraint-gate collector, stream fan-out) has
     * actually completed. Only then do we [destroy].
     *
     * This is why teardown is `suspend`: a cancel-and-join is not expressible
     * via `runBlocking` in `commonMain` (and is impossible on the JS event
     * loop). Callers that cannot suspend keep using the synchronous
     * [ClientRegistry] teardown path; callers that can (tests, app/JS shutdown)
     * route through [ClientRegistry.shutdownAndJoin] to close the teardown race.
     */
    suspend fun quiesce() {
        sessionScope.coroutineContext[Job]?.cancelAndJoin()
        destroy()
    }

    fun addDataObserver(observer: DataObserver) {
        observerRegistry.add(observer)
    }

    suspend fun setDatabaseListener(
        streamId: Long,
        reference: ArrayDatabaseReference,
        executionStack: List<Data>,
        signalOnly: Boolean = false,
    ): Data? = streamRegistry.setDatabaseListener(
        executionContext = this,
        streamId = streamId,
        reference = reference,
        executionStack = executionStack,
        signalOnly = signalOnly,
    )

    fun removeDatabaseListener(streamId: Long) {
        streamRegistry.removeDatabaseListener(executionContext = this, streamId = streamId)
    }

    fun withEvaluationScope(
        scope: CoroutineScope,
        runBackgroundInRequestScope: Boolean = false,
        requestSyncLaneId: String? = null,
        requestCancellation: RequestCancellation? = null,
    ): ExecutionContext {
        return ExecutionContext(
            context = context,
            database = database,
            traitResolver = traitResolver,
            implementationFactory = implementationFactory,
            clientConnection = clientConnection,
            pureOperationRunner = pureOperationRunner,
            materializer = materializer,
            sessionScope = sessionScope,
            // Share the resolver so its `collectingReferences` scope (set up by
            // `setDatabaseListener` on the parent session) is observable from
            // per-request contexts evaluating the same stream.
            referenceResolver = referenceResolver,
            // Share the stream registry — stream lifecycle (start/listener-fire/end)
            // crosses request boundaries, so the per-stream maps cannot live on a
            // per-request context.
            streamRegistry = streamRegistry,
            // Share observers — see the `observerRegistry` doc on the constructor.
            // Required so cross-process IPC routed through `ProxyGateway.call`
            // (which wraps the session via this method) still fires the
            // observers `initialization` registered on the session context.
            observerRegistry = observerRegistry,
            // Share the announce state — the announce op executes on a
            // per-request context but records session-scoped state (§6.4).
            sessionAnnouncement = sessionAnnouncement,
            // Forward the session's identity: an operation runs on a per-request context,
            // so a caller-scoped lookup (the Android FD cache) would see `null` without it.
            clientId = clientId,
            evaluationScope = scope,
            runBackgroundInRequestScope = runBackgroundInRequestScope,
            requestSyncLaneId = requestSyncLaneId,
            requestCancellation = requestCancellation,
            // Forward the recorder so per-request contexts (created by
            // `ProxyGateway.call` wrapping the session) keep firing it from
            // the catch-all. Without this, in-flight requests would silently
            // skip diagnostic capture for the duration they're scoped to
            // the per-request context.
            errorEventRecorder = errorEventRecorder,
            initialization = {} // Don't re-initialize
        )
    }

    /**
     * Reads the contents of an [ArrayDatabaseReference]. **Not** a thin
     * delegate to [Database.getArrayData] — routes through
     * [referenceResolver] so that stream-evaluation scopes
     * ([ReferenceResolver.collectingReferences]) observe every array
     * reference resolved inside the scope. Replacing call sites with
     * `executionContext.database.getArrayData(...)` would silently break
     * nested-ref discovery in [StreamRegistry.setDatabaseListener]'s
     * `onUpdate` closure.
     *
     * The peer `Database.addToArray` and `removeFromArray` methods are
     * *not* mirrored here — those are pure delegates and call sites use
     * `executionContext.database.X(...)` directly.
     */
    fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection? = null,
    ): Sequence<Data>? =
        when (val c = requestCancellation) {
            null -> referenceResolver.getArrayData(reference, projection)
            // Into the store too: its read is eager, so the wrapper alone would fire only after it.
            else -> referenceResolver.getArrayData(reference, projection, c::checkpoint)?.checkpointedBy(c)
        }

    /**
     * Windowed variant of [getArrayData] — same routing rule: goes through
     * [referenceResolver] so windowed reads register for stream invalidation
     * exactly like full reads.
     */
    fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection? = null,
        offset: Int,
        limit: Int,
    ): Sequence<Data>? =
        when (val c = requestCancellation) {
            null -> referenceResolver.getArrayDataWindow(reference, projection, offset, limit)
            else -> referenceResolver.getArrayDataWindow(reference, projection, offset, limit, c::checkpoint)
                ?.checkpointedBy(c)
        }

    /**
     * The array's member hashes without deserialisation — routed through
     * [referenceResolver] so the observed array registers for stream
     * invalidation even when the caller then skips reading it (the by-key
     * search fast path).
     */
    fun getArrayMemberHashes(reference: ArrayDatabaseReference): List<Long>? =
        referenceResolver.getArrayMemberHashes(reference)

    /**
     * Materialises a [CompositeDatabaseReference] — the single-composite analogue
     * of [getArrayData]. Routes through [referenceResolver] for symmetry with the
     * array seam (there is no stream-collector concern for single composites in
     * Phase 1, but the indirection keeps the seam in one place).
     */
    fun getComposite(reference: CompositeDatabaseReference): Data? =
        referenceResolver.getCompositeData(reference)

    fun resolveList(data: Any?): Sequence<Data>? =
        when (val c = requestCancellation) {
            null -> referenceResolver.resolveList(data)
            else -> referenceResolver.resolveList(data, c::checkpoint)?.checkpointedBy(c)
        }

    fun resolveProperty(
        data: Data,
        propertyReference: PropertyTypeReference,
        params: TList<Data>? = null,
    ): Data? = referenceResolver.resolveProperty(this, data, propertyReference, params)

    /** Several properties of one row at once — see [ReferenceResolver.resolveProperties]. */
    fun resolveProperties(
        data: Data,
        propertyReferences: List<PropertyTypeReference>,
    ): List<Data?> = referenceResolver.resolveProperties(this, data, propertyReferences)

    fun resolveDynamicProperty(
        data: Data,
        propertyReference: DynamicPropertyTypeReference,
        params: TList<Data>?,
    ): Data? = referenceResolver.resolveDynamicProperty(this, data, propertyReference, params)

    fun resolveServiceType(
        currentServiceId: String?,
        referencedServiceId: String,
    ): ServiceTypeReference = referenceResolver.resolveServiceType(currentServiceId, referencedServiceId)

    /**
     * Unified resolver for `ServiceDataRelation` — handles both the single-entity
     * case (relation has [DataId.EntityDataId] dataId) and the collection case
     * (relation type is `TList<X>`, no dataId).
     */
    fun resolveServiceData(
        service: ServiceTypeReference,
        ref: Reference,
    ): Data = referenceResolver.resolveServiceData(service, ref)

    /**
     * Phase 4 of `docs/proposals/core-error-channel.md` (Shape A flip): returns
     * `T` directly; typed failure travels as a thrown
     * [org.tenshu.core.TenshuException] up to [LocalTaskExecutor]'s wire-side
     * catch. Background fire-and-forget returns `null` (cast through `T?`).
     * The async path wraps the impl in an [AsyncReference] whose
     * [AsyncReference.asyncCall] resolves to the impl's `Data?` result;
     * unhandled exceptions there propagate the same way.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Data?> execute(
        operationId: String,
        caller: Any?,
        params: Map<String, Data>? = null,
        operationContext: OperationContext? = null
    ): T {
        val implementation = implementationFactory(operationId)
            ?: error("No implementation found for operation: $operationId")

        val schema = context.schemaProvider().getSchema(operationId) as? OperationSchema
            ?: error("Schema not found for operation: $operationId")

        return when (implementation) {
            is PureOperationImplementation -> {
                implementation.execute<T>(
                    context = context,
                    implementationFactory = implementationFactory,
                    caller = caller,
                    params = params
                )
            }

            is AsyncOperationImplementation -> {
                // Phase L2: a cancellable request (taskId present →
                // runBackgroundInRequestScope) runs a BackgroundOperation in
                // the request scope and awaits it, like the IoOperation path,
                // so requestJob cancellation reaches it and the result flows
                // back via the TaskHandle. Without a taskId this stays the
                // legacy detached fire-and-forget (StartPairing / pairing).
                if (schema.isBackground() && !runBackgroundInRequestScope) {
                    sessionScope.launch {
                        // `Data?` for the same Kotlin/Native checked-downcast reason as the
                        // awaited branch below — a fire-and-forget op returning nothing must
                        // not blow up the launched coroutine.
                        implementation.execute<Data?>(
                            executionContext = this@ExecutionContext,
                            operationContext = operationContext,
                            implementationFactory = implementationFactory,
                            caller = caller,
                            params = params
                        )
                    }
                    null as T
                } else {
                    AsyncReference.create(
                        asyncCall = {
                            val scope =
                                if (evaluationScope?.isActive == true) evaluationScope else sessionScope
                            // A dead session must fail loudly, not silently. `async {}` on a
                            // cancelled scope mints an already-cancelled Deferred whose
                            // `await()` throws CancellationException — which every layer up
                            // (LocalTaskExecutor, viewModelScope) treats as *caller*
                            // cancellation and swallows, so the op appears to hang forever
                            // (the "Registering your realm" incident: ProxyService.onDestroy
                            // quiesced the session while the process lived on).
                            //
                            // ⚠⚠ The liveness question is asked of [sessionScope] SPECIFICALLY,
                            // never of `scope` — the two have opposite lifetimes and only one
                            // of them is an incident. `evaluationScope` is the per-request
                            // scope `LocalTaskExecutor.executeAsyncCancellable` opens with
                            // `coroutineScope { }`; it dies at the end of every request, and
                            // its death is ordinary lifecycle, not teardown. Asking
                            // `!scope.isActive` conflated them, so a request scope dying in
                            // the window between the pre-check and `await()` was reported as
                            // a fatal session teardown — a race the JVM usually wins and
                            // Kotlin/Native consistently loses (it emptied every in-process
                            // reactive read on iOS; see DeadSessionScopeTest). Only the
                            // session's death is a teardown; a request scope ending is just a
                            // cancellation, and it propagates as one.
                            //
                            // Caller cancellation is checked FIRST: during a teardown the
                            // callers riding the same sessionScope are cancelled along with
                            // it, and throwing a non-cancellation exception out of an
                            // already-cancelled coroutine escalates to the uncaught-exception
                            // handler. Only a live caller gets the typed failure.
                            currentCoroutineContext().ensureActive()
                            if (!sessionScope.isActive) throw sessionTornDown()
                            try {
                                // ⚠⚠ `execute<Data?>`, NEVER `execute<Data>`. An op that
                                // returns nothing resolves this to `null`, and on
                                // Kotlin/Native `Deferred<Data>.await()` emits a CHECKED
                                // downcast (`kotlin.native.internal.downcast`) that throws
                                // NullPointerException on it. The JVM erases the same cast,
                                // so this was invisible there while it failed EVERY void
                                // async op on iOS route-1 — a write reported `error/internal`
                                // and the reads that followed were correctly empty, which
                                // reads like a sync bug and is not one.
                                scope.async {
                                    implementation.execute<Data?>(
                                        executionContext = this@ExecutionContext,
                                        operationContext = operationContext,
                                        implementationFactory = implementationFactory,
                                        caller = caller,
                                        params = params
                                    )
                                }.await()
                            } catch (e: CancellationException) {
                                currentCoroutineContext().ensureActive()
                                if (!sessionScope.isActive) throw sessionTornDown()
                                throw e
                            }
                        },
                        remainingCallstack = emptyList()
                    ) as T
                }
            }

            is SyncOperationImplementation -> {
                implementation.execute<T>(
                    executionContext = this,
                    operationContext = operationContext,
                    implementationFactory = implementationFactory,
                    caller = caller,
                    params = params
                )
            }

            else -> error("Unsupported implementation type: ${implementation::class.simpleName}")
        }
    }

    /**
     * Typed failure for an op dispatched after this session's scope died
     * (service teardown while the process lives, e.g. Android's
     * `ProxyService.onDestroy`). Travels as an ordinary [TenshuException] so
     * `LocalTaskExecutor`'s catch-all returns a `TResult.Failure` the caller
     * actually sees — instead of a CancellationException that upstream
     * coroutine machinery silently swallows.
     */
    private fun sessionTornDown() = TenshuException(
        InternalErrorDto(
            internalDetail = "Proxy session torn down — the session scope is no longer active. " +
                "The client must re-resolve its session before retrying."
        )
    )

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Data?> executeAsync(
        operationId: String,
        caller: Any?,
        params: Map<String, Data>? = null,
        operationContext: OperationContext? = null
    ): T {
        val effectiveContext = if (evaluationScope == null) {
            withEvaluationScope(sessionScope)
        } else {
            this
        }

        // `Data?`, not `Data` — a void op resolves the type argument to `null`, and a
        // non-null pin makes Kotlin/Native emit a checked downcast that throws NPE.
        var result: Data? = effectiveContext.execute<Data?>(
            operationId, caller, params, operationContext
        )

        while (true) {
            if (result is AsyncReference) {
                result = result.asyncCall.await()
            } else {
                return result as T
            }
        }
    }

    fun getDataId(data: Data): DataId? {
        if (data is DataId) return data
        if (data.schema.id.get() == "tenshu/core/proxy/asyncReference") return null
        if (data.schema.isSubtypeOf("tenshu/core/error")) return null

        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id
        val dataIdProvider = traitResolver.getTrait<DataIdProvider<out Data>>(
            typeId = typeId,
            traitId = DataIdProvider.ID
        )

        if (dataIdProvider != null) {
            val traitRequirements = dataIdProvider.traitRequirements.toList()
            val requirement = traitRequirements.find {
                it.schema.isSubtypeOf(DataIdProvider.Requirements.DATA_ID)
            }
                ?: error("DATA_ID requirement not found in provider ${dataIdProvider.schema.id.get()} for $typeId. Available: ${traitRequirements.map { it.schema.id.get() }}")

            return pureOperationRunner.execute<DataId>(requirement, data)
        }

        // Fallback for simple values
        return when (data) {
            is TString -> DataId.StringDataId.create(
                context = context,
                type = data.schema.id.get(),
                keyValue = data.get()
            )

            is TInteger -> DataId.LongDataId.create(
                context = context,
                type = data.schema.id.get(),
                keyValue = data.get()
            )

            else -> null
        }
    }

    /**
     * [syncLaneId] tags this save as belonging to an open task-scoped sync
     * lane (`core-background-processing.md` §10.3 D3): a lane-aware observer
     * (`SyncObserver`) stages the change into the lane's delta batch instead
     * of emitting a per-save `SyncBlock`. `null` (the default, and every
     * existing call site) is the ordinary per-save reactive path. A lane-aware
     * observer may still refuse the lane for one save, before it is written
     * ([LaneAwareDataObserver.admitsToLane]); that save then takes the per-save path.
     */
    fun saveData(data: Data, origin: WriteOrigin = WriteOrigin.USER, syncLaneId: String? = null) {
        // ⭐ A bare composite never clears content (CR-2 §10.12 Q5). A peer's row is applied as written.
        val toSave = if (origin == WriteOrigin.SYNC_INGEST) data else
            storedContentReferences?.keptAcross(data, ::getDataId) ?: data
        // Explicit lane id wins; else the request's own (withTask, TS-3). ⚠⚠ Decided BEFORE the write:
        // a lane-aware observer may refuse the lane because of the row this save replaces, which is
        // unreadable once it lands (CR-2 §10.12 Q7).
        // ⭐ CR-2 B4 (§2.3): and, in the same breath, what each lane-aware observer needs of the row
        // being replaced (the content a save clears), carried to the dispatch as a token.
        val prepared = observerRegistry.prepareSave(toSave, origin, syncLaneId ?: requestSyncLaneId)
        database.saveData(toSave)
        observerRegistry.onDataSaved(toSave, origin, prepared)
    }

    private val storedContentReferences: org.tenshu.core.common.content.StoredContentReferences? by lazy {
        (schemaProvider as? SchemaRegistry)?.let {
            org.tenshu.core.common.content.StoredContentReferences(it, database, context)
        }
    }

    /**
     * The row removal and the observer fanout are ONE transaction — deliberately unlike
     * [saveData], which releases the DB lock before dispatching.
     *
     * A delete's observers do not merely react to it, they *complete* it: `TombstoneRegistry`
     * stamps the entity's short-circuit tombstone from `onDataDeleted`. Dispatching that
     * unlocked leaves a window in which the row is already gone but nothing records that it
     * was deleted, and a concurrent `SyncProcessor` ingest of an older block for the same
     * entity — the relay echoes this device's own pushes back, so one is usually in flight —
     * finds no tombstone, no row, and re-saves it. The row comes back from the dead and the
     * next write at that key fails with `AlreadyExistsError` (the "ghost file" symptom
     * `DeleteAndReuploadFolderSystemTest` guards; see `docs/tech-debt.md`).
     *
     * Holding the lock across the fanout makes "deleted" atomic with "recorded as deleted",
     * so ingest either runs entirely before the delete (its save is then removed by it) or
     * entirely after (it sees the tombstone and drops). The lock is reentrant, so observers
     * that open their own `transaction { }` are unaffected; deletes are rare enough that the
     * extra hold time is not a contention concern.
     */
    fun deleteData(dataId: DataId, origin: WriteOrigin = WriteOrigin.USER) {
        database.transaction {
            // ⭐ CR-2 B4 (§2.3): the row is still readable here and nowhere after.
            observerRegistry.beforeDataDeleted(dataId, origin)
            database.deleteData(dataId)
            observerRegistry.onDataDeleted(dataId, origin)
        }
    }

}
