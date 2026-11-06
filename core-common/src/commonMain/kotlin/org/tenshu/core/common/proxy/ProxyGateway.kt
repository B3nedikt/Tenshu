package org.tenshu.core.common.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TValue
import org.tenshu.core.TenshuException
import org.tenshu.core.common.parser.TokenString
import org.tenshu.core.core.TContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.Type
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Centrally manages the proxy side logic for binary communication.
 * This class is platform-independent and can be used in both production (via ProxyService)
 * and integration tests.
 *
 * `call` is suspend so that the class compiles unchanged on every KMP target including
 * Kotlin/JS (which has no `runBlocking`). Callers that arrive on a synchronous transport
 * (Android Binder, raw thread reading from a socket) are responsible for bridging — see
 * `ProxyService.kt` (Android) and `UnixSocketServer.kt` (desktop).
 */
@OptIn(ExperimentalAtomicApi::class)
class ProxyGateway(
    private val clientRegistry: ClientRegistry,
    private val parser: BinaryParser,
    private val serializer: BinarySerializer
) {

    init {
        // Option A (docs/proposals/core-background-processing.md, § "Known
        // gap — L2-LIVE is necessary but not sufficient"): let the registry
        // see whether a client still has a *detached* Layer-2 task before it
        // tears that client's session (ExecutionContext) down on unbind.
        clientRegistry.inFlightProbe = ::hasInFlightTask
    }
    // Phase L2 (docs/proposals/core-background-processing.md §8): connection-
    // scoped in-flight registry. A cancellable call registers
    // (clientId, taskId) -> requestJob on start and deregisters on completion;
    // a `cancel(clientId, taskId)` RPC over the same transport cancels the
    // requestJob, and structured concurrency unwinds every suspending IO point
    // for free (executeAsync already cooperates with requestScope). An unknown
    // or already-completed id is a no-op. `pendingCancels` closes the race
    // where a cancel frame is read off the wire before the call frame's
    // coroutine has registered (FIFO on one channel, but dispatch is async):
    // the cancel is parked and applied at registration time.
    private data class TaskKey(val clientId: String, val taskId: String)

    // Cancellable reads (`core-background-processing.md` §6.2.1). ⚠⚠ Deliberately NOT the task
    // registry above: an entry there detaches the request from its session, counts towards
    // `inFlightCount` (Android's foreground-service promotion) and names a sync lane. A read wants
    // none of that — only to be findable by a cancel — so it has its own map, its own lock and no
    // observable count.
    private data class ReadKey(val clientId: String, val readOwner: Any?, val readId: String)

    private class InFlightRead(val job: Job, val cancellation: RequestCancellation) {
        fun stop() {
            cancellation.cancel()
            job.cancel(CancellationException("Read cancelled by its caller"))
        }
    }

    private val readsMutex = Mutex()
    private val inFlightReads = mutableMapOf<ReadKey, InFlightRead>()
    private val pendingReadCancels = LinkedHashSet<ReadKey>()

    private val registryMutex = Mutex()
    private val inFlight = mutableMapOf<TaskKey, Job>()
    private val pendingCancels = LinkedHashSet<TaskKey>()

    /**
     * Lock-free snapshot of the keys in [inFlight], maintained copy-on-write
     * under [registryMutex] on every register/deregister. [hasInFlightTask]
     * (the [ClientRegistry] probe) is called *synchronously* on the
     * unbind/binder thread and cannot take the suspend [registryMutex];
     * reading the `inFlight` map directly there would race a concurrent
     * mutation. Same `AtomicReference<immutable>` pattern as
     * [ObserverRegistry] / `StreamRegistry`.
     */
    private val inFlightKeys = AtomicReference<Set<TaskKey>>(emptySet())

    private val _inFlightCount = MutableStateFlow(0)

    /**
     * Number of cancellable Layer-2 tasks currently registered in [inFlight]
     * (registered by a task-id-carrying [call], deregistered when it completes
     * or is cancelled). Observable so a per-platform process-liveness component
     * can react to the idle ⇄ in-flight transition: Android's
     * `ForegroundTaskLiveness` promotes Control to a foreground service while
     * this is non-zero and demotes when it returns to zero
     * (`docs/proposals/core-background-processing.md` §2.2 / §8 Phase L2-LIVE).
     * Mirrors `StreamRegistry.activeStreamCount`. `inFlight.size` is the source
     * of truth; the flow is updated under [registryMutex] on every mutation.
     */
    val inFlightCount: StateFlow<Int> = _inFlightCount.asStateFlow()

    /** The [inFlight] keys whose call was marked user-started; guarded by [registryMutex]. */
    private val userInitiatedKeys = mutableSetOf<TaskKey>()

    private val _userInitiatedInFlightCount = MutableStateFlow(0)

    /**
     * How many of [inFlightCount]'s tasks were marked **started by the user** (`userInitiated` on
     * [call]; `docs/proposals/control-background-work-off-fgs.md` §5.1).
     *
     * ⚠⚠ This, not [inFlightCount], is what Android's foreground-service promotion counts. Play
     * allows that service only for work the user started; an automatic task (a scheduled backup's
     * upload) is served identically but kept alive by Control's own jobs instead.
     */
    val userInitiatedInFlightCount: StateFlow<Int> = _userInitiatedInFlightCount.asStateFlow()

    /**
     * Told the task id of every call that arrives marked user-started — how a registered bulk task
     * (`TaskRegistry`, `:sync-service-proxy`) learns it is user-started and holds the foreground
     * service between its calls (`control-background-work-off-fgs.md` §5.2). Set by the Android
     * proxy; `null` elsewhere.
     */
    var onUserInitiatedTask: (suspend (taskId: String) -> Unit)? = null

    /**
     * Serves one request.
     *
     * [taskId] makes it a Layer-2 task (detached, counted, sync-laned — see below). [readId] makes
     * it a **cancellable read** (`core-background-processing.md` §6.2.1): registered under
     * `(clientId, readOwner, readId)` so [cancelRead] can stop it, and evaluated with a
     * [RequestCancellation] so the synchronous walk stops too. Nothing else changes for a read —
     * it stays session-parented, uncounted and lane-less, exactly like a plain call. A read
     * stopped by [cancelRead] throws [ReadCancelledException].
     */
    suspend fun call(
        clientId: String,
        request: ByteArray,
        sessionIdentity: Any? = null,
        taskId: String? = null,
        readId: String? = null,
        readOwner: Any? = sessionIdentity,
        userInitiated: Boolean = false,
    ): ByteArray {
        val sessionContext = clientRegistry.getExecutionContext(clientId, sessionIdentity)

        // Phase L2-LIVE Option A: a taskId-carrying (Layer-2) request must
        // outlive the client binding — `ProxyService.onUnbind` →
        // `unregisterClient()` → `ClientRegistry.unregisterClient()` →
        // `sessionScope.cancel()` fires on routine interaction (Back /
        // finish() / rotation), so a Layer-2 requestJob parented to the
        // per-client sessionScope would be collaterally cancelled. Parent it
        // instead to the registry's service-level detach scope (a per-client
        // sessionScope.cancel() no longer reaches it; a full proxy shutdown
        // still does — that scope is cancelled on real shutdown). The
        // sessionScope's *non-Job* context (dispatcher, etc.) is still
        // inherited; only the Job lineage is rerouted. An ordinary one-shot
        // RPC (taskId == null) keeps the legacy session-parented behaviour —
        // there cancel-on-client-death is correct (the client is
        // synchronously awaiting). Client-driven `cancel(clientId, taskId)`
        // keeps working regardless: it cancels via the `inFlight` map
        // directly, independent of job lineage.
        val detached = taskId != null
        val parentJob = if (detached) {
            clientRegistry.detachScope.coroutineContext[Job]
        } else {
            sessionContext.sessionScope.coroutineContext[Job]
        }
        val requestJob = SupervisorJob(parentJob)
        val baseContext = if (detached) {
            sessionContext.sessionScope.coroutineContext.minusKey(Job)
        } else {
            sessionContext.sessionScope.coroutineContext
        }
        val requestScope = CoroutineScope(baseContext + requestJob)
        // A task is cancelled through its job alone (`cancel` above): the cooperative checkpoint is
        // a read's, and only an explicit `cancelRead` trips it — never the request job ending.
        val readCancellation = if (readId != null && taskId == null) RequestCancellation() else null
        val executionContext = sessionContext.withEvaluationScope(
            requestScope,
            runBackgroundInRequestScope = detached,
            // TS-3: a withTask-tagged constituent call rides the cancellable
            // envelope with the TASK's id — thread it so the op's saves join
            // the task's sync lane (ExecutionContext.requestSyncLaneId KDoc).
            requestSyncLaneId = taskId,
            requestCancellation = readCancellation,
        )

        val key = taskId?.let { TaskKey(clientId, it) }
        // A user-started mark means nothing without a task: only a registered task is counted.
        if (key != null) registerInFlight(key, requestJob, userInitiated)
        if (key != null && userInitiated) onUserInitiatedTask?.invoke(key.taskId)
        val readKey = readCancellation?.let { ReadKey(clientId, readOwner, readId!!) }
        if (readKey != null) registerRead(readKey, InFlightRead(requestJob, readCancellation))

        try {
            return requestScope.async {
                // Phase 3 of `docs/proposals/core-error-channel.md` (v0.2 review
                // Option A): `LocalTaskExecutor.executeAsyncFromBytes` owns the
                // single catch — both parse failures and impl exceptions become
                // a `TResult.Failure` carrying a typed `TError` (unwrapped from
                // `TenshuException`, or wrapped as `InternalError(message)` for
                // genuinely unexpected `Exception`s). `ProxyGateway` is pure
                // transport now: parse → execute → serialize, no try/catch.
                // The previous IPC-failure `println` is gone; Phase 5's
                // `:logging-service` is the diagnostic sink.
                val result = LocalTaskExecutor(executionContext)
                    .executeAsyncFromBytes(request, parser)

                // An abandoned read is not serialised: nobody will parse it, and on Android the
                // tokenizer below would still copy every byte payload into shared memory.
                readCancellation?.checkpoint()
                serializer.serializeReply(
                    result = result,
                    context = executionContext.context,
                    // §2.4 Phase 1 of `core-chain-entity-typing.md`: route
                    // byte-bearing reply values through the same OOB
                    // tokenizer the stream-push path uses. On Android this
                    // wraps `TByteArray` payloads in
                    // `ParcelFileDescriptor`/`SharedMemory` ahead of the
                    // reply parcel, keeping the parcel under the ~1 MB
                    // Binder cap regardless of the payload size; the
                    // default impl (desktop's `UnixSocketClientConnection`
                    // / test-only stubs) returns `null` and bytes are
                    // serialized inline as before. With this in place a
                    // `.search { … }.select { relations(DataProperty) }
                    // .get()` chain carrying a 12 MB JPEG no longer kills
                    // the Drive process; consumers that took the
                    // `.searchAll → .select → .flow().first()` workaround
                    // for that reason (e.g. `TenshuFilesRepository.getRemoteFile`)
                    // can collapse to the one-shot `.search.get()` shape.
                    tokenize = executionContext.clientConnection::tokenize,
                )
            }.await()
        } catch (e: CancellationException) {
            // Tell an abandoned read from a teardown: only the first may be answered with nothing.
            if (readCancellation?.cancelledByClient == true) throw ReadCancelledException()
            throw e
        } finally {
            // ⚠ Disarm before the job ends: this context can outlive the request (a stream
            // subscribed through it re-evaluates on it), and must never throw there.
            readCancellation?.disarm()
            if (key != null) deregisterInFlight(key)
            // NonCancellable: this `finally` often runs in a cancelled coroutine (the read was just
            // stopped, or its connection closed), and a contended lock would throw there and leave
            // the entry registered for ever.
            if (readKey != null) withContext(NonCancellable) { deregisterRead(readKey) }
            requestJob.cancel()
        }
    }

    /**
     * [call] for a transport that must answer *something* — a Binder transaction returns a byte
     * array whatever happens. A read the client abandoned ([cancelRead]) is answered with an empty
     * array, which nobody reads: the client sent the cancel because its caller had already gone.
     * ⚠ Any other cancellation (a session teardown) still throws, as a plain call's does — an
     * empty reply to a caller who is still waiting would parse as an unreadable answer and be
     * reported as version skew.
     */
    suspend fun callCancellableRead(
        clientId: String,
        request: ByteArray,
        sessionIdentity: Any?,
        readId: String,
        readOwner: Any? = sessionIdentity,
    ): ByteArray = try {
        call(clientId, request, sessionIdentity, readId = readId, readOwner = readOwner)
    } catch (_: ReadCancelledException) {
        ByteArray(0)
    }

    /**
     * Stops the cancellable read [readId] that [clientId] registered under [readOwner]
     * (`core-background-processing.md` §6.2.1): trips its checkpoint, so the synchronous walk stops
     * at the next row, and cancels its job, so a suspended step unwinds.
     *
     * ⚠ A cancel must never reach another caller's read, and the key is how: always the attested
     * client, plus [readOwner] — the desktop socket, whose ids are a counter per client process; on
     * Android `null`, because a `oneway` Binder call reports the caller's uid but **not its pid**
     * (it reads 0), so there the id itself is a random UUID minted per call. An id with nothing in
     * flight is parked (bounded), because the cancel can overtake its own read — two Binder
     * threads, or two launches off one socket.
     */
    suspend fun cancelRead(clientId: String, readOwner: Any?, readId: String) {
        val key = ReadKey(clientId, readOwner, readId)
        readsMutex.withLock {
            val read = inFlightReads[key]
            if (read != null) {
                read.stop()
            } else {
                pendingReadCancels.add(key)
                if (pendingReadCancels.size > MAX_PENDING_CANCELS) {
                    val it = pendingReadCancels.iterator()
                    it.next()
                    it.remove()
                }
            }
        }
    }

    /** Number of cancellable reads in flight — a test seam; never part of liveness (§6.2.1). */
    internal suspend fun inFlightReadCount(): Int = readsMutex.withLock { inFlightReads.size }

    private suspend fun registerRead(key: ReadKey, read: InFlightRead) {
        readsMutex.withLock {
            if (pendingReadCancels.remove(key)) read.stop() else inFlightReads[key] = read
        }
    }

    private suspend fun deregisterRead(key: ReadKey) {
        readsMutex.withLock { inFlightReads.remove(key) }
    }

    /**
     * Out-of-band cancel for the in-flight task [taskId] on [clientId]'s
     * connection. Cancelling [Job] unwinds the request coroutine and every
     * suspending IO point under it. An unknown or already-completed id is a
     * no-op (it is parked in case the cancel raced ahead of registration).
     */
    suspend fun cancel(clientId: String, taskId: String) {
        val key = TaskKey(clientId, taskId)
        registryMutex.withLock {
            val job = inFlight[key]
            if (job != null) {
                job.cancel(CancellationException("Cancelled by client via TaskHandle"))
            } else {
                pendingCancels.add(key)
                if (pendingCancels.size > MAX_PENDING_CANCELS) {
                    val it = pendingCancels.iterator()
                    it.next()
                    it.remove()
                }
            }
        }
    }

    private suspend fun registerInFlight(key: TaskKey, requestJob: Job, userInitiated: Boolean) {
        registryMutex.withLock {
            if (pendingCancels.remove(key)) {
                // Cancel arrived before this call registered — honour it now.
                requestJob.cancel(CancellationException("Cancelled by client via TaskHandle"))
            } else {
                inFlight[key] = requestJob
                // ⚠ A re-registration under the same key (constituent calls of one bulk task share
                // its id) takes the NEW call's mark — an automatic call never inherits a user one.
                if (userInitiated) userInitiatedKeys += key else userInitiatedKeys -= key
                _inFlightCount.value = inFlight.size
                _userInitiatedInFlightCount.value = userInitiatedKeys.size
                inFlightKeys.store(inFlight.keys.toSet())
            }
        }
    }

    private suspend fun deregisterInFlight(key: TaskKey) {
        val clientDrained = registryMutex.withLock {
            if (inFlight.remove(key) == null) return@withLock false
            userInitiatedKeys -= key
            _inFlightCount.value = inFlight.size
            _userInitiatedInFlightCount.value = userInitiatedKeys.size
            inFlightKeys.store(inFlight.keys.toSet())
            // True iff this was the client's *last* detached task: only then
            // is it safe to run any teardown ClientRegistry deferred while
            // this task was holding the session's ExecutionContext alive.
            inFlight.keys.none { it.clientId == key.clientId }
        }
        if (clientDrained) clientRegistry.drainDeferredTeardown(key.clientId)
    }

    /**
     * Whether [clientId] currently has any detached Layer-2 task in flight.
     * Installed on [ClientRegistry] as its `inFlightProbe` so a session
     * teardown ([ClientRegistry.unregisterClient] / [ClientRegistry.clear])
     * is deferred while a detached task still references that session's
     * `ExecutionContext`. Called *synchronously* off the unbind/binder
     * thread, so it reads the lock-free [inFlightKeys] snapshot rather than
     * the suspend-mutex-guarded [inFlight] map.
     */
    fun hasInFlightTask(clientId: String): Boolean =
        inFlightKeys.load().any { it.clientId == clientId }

    fun unregisterAll() {
        clientRegistry.clear()
    }

    /**
     * Drops one client's session — the transport-level counterpart to [unregisterAll], for a
     * host that knows *which* client went away. Android's `ProxyService.onUnbind` uses it to
     * release exactly the Binder clients it served: the registry also holds the proxy's own
     * in-process session and any EPC-paired peer, and neither is a Binder client, so the
     * blanket [unregisterAll] tore down sessions whose transport was still perfectly alive.
     *
     * Teardown of a session with a detached Layer-2 task still defers until that task drains
     * ([ClientRegistry.unregisterClient]); an unknown id is a no-op.
     */
    fun unregisterClient(clientId: String) {
        clientRegistry.unregisterClient(clientId)
    }

    /**
     * Full proxy/service shutdown — tears every session down unconditionally
     * (including deferred ones) and cancels the fallback detach scope so no
     * detached Layer-2 task can leak past process shutdown. Routine client
     * unbind goes through [unregisterClient]/[ClientRegistry.unregisterClient],
     * which intentionally lets detached tasks outlive the binding.
     */
    fun shutdown() {
        clientRegistry.shutdown()
    }

    /**
     * Suspend counterpart to [shutdown] — quiesces every session (cancel **and
     * join** its background work) before returning, so callers that can suspend
     * (tests before `stopKoin`, app/JS shutdown) close the teardown race instead
     * of merely narrowing it. See [ClientRegistry.shutdownAndJoin].
     */
    suspend fun shutdownAndJoin() {
        clientRegistry.shutdownAndJoin()
    }

    /**
     * Service-lifecycle teardown that the *process* outlives — quiesces every
     * session (cancel **and join**) but keeps the registry open, so re-binding
     * Binder clients and the in-process local client can mint fresh sessions
     * afterwards. Android's `ProxyService.onDestroy` uses this; a full
     * process/test shutdown uses [shutdownAndJoin], which additionally closes
     * the registry against new sessions. See
     * [ClientRegistry.quiesceSessionsAndJoin].
     */
    suspend fun quiesceSessionsAndJoin() {
        clientRegistry.quiesceSessionsAndJoin()
    }

    private companion object {
        // Bounds the parked-cancel set so a stream of cancels for ids that
        // never arrive can't grow unboundedly; oldest is evicted (FIFO).
        const val MAX_PENDING_CANCELS = 256
    }
}

/**
 * A cancellable read stopped by its client ([ProxyGateway.cancelRead]) — as opposed to one cut short
 * by a session teardown, which surfaces as the plain [CancellationException] it always did.
 */
class ReadCancelledException : CancellationException("Read cancelled by its caller")

/**
 * Serialize a call's [result] for the wire, byte payloads through [tokenize].
 *
 * ⚠ A tokenizer that cannot hand a payload to the client throws a typed [TenshuException] (on
 * Android: a file descriptor whose `receiveFile` did not go out). The reply must never carry a
 * token that resolves to nothing — the read would fail later, far away, as *descriptor not found* —
 * so the caller gets that typed failure instead, the same shape an operation's own throw produces
 * (`core-client-resubscription.md` §4.8).
 */
internal fun BinarySerializer.serializeReply(
    result: TResult<Data>,
    context: TContext,
    tokenize: (TValue) -> TokenString?,
): ByteArray {
    val replyType = Type.create(baseSchema = TResult.Schema)
    return try {
        serialize(result, expectedType = replyType, interceptValueTokenization = tokenize)
    } catch (e: TenshuException) {
        serialize(TResult.Failure.create<Data>(context = context, error = e.error), expectedType = replyType)
    }
}
