package org.tenshu.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.CancellableTaskInteractor
import org.tenshu.core.Data
import org.tenshu.core.Interactor
import org.tenshu.core.InteractorProvider
import org.tenshu.core.Operation
import org.tenshu.core.ProxyConnectivity
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.StreamInteractor
import org.tenshu.core.SyncInteractor
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.TaskHandle
import org.tenshu.core.TaskInteractor
import org.tenshu.core.CancellableReadsElement
import org.tenshu.core.UserInitiatedElement
import org.tenshu.core.WithTaskElement
import kotlin.coroutines.EmptyCoroutineContext
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.common.request.CancellableRemoteTaskExecutor
import org.tenshu.core.common.request.ReconnectingTaskExecutor
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import kotlin.random.Random
import org.tenshu.core.common.request.streamId
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.trait.isBackground
import org.tenshu.core.trait.isIo
import org.tenshu.core.trait.isStream

/**
 * @param clientScope hosts the in-flight [kotlinx.coroutines.Deferred] that
 * drives a [CancellableTaskInteractor]'s IPC round-trip, so `execute()` can
 * return a [TaskHandle] without blocking and the caller can cancel from an
 * unrelated coroutine. Provider-internal and self-provisioned identically on
 * every client (production DI and the JS runtime construct this class the same
 * single-lambda way); lifetime = the provider singleton. Cancelling a handle
 * cancels only that handle's child job, never the scope.
 */
class DefaultInteractorProvider(
    private val clientScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val taskExecutorProvider: () -> TaskExecutor,
) : InteractorProvider, StreamElementDispatcher, ProxyConnectivity {

    /**
     * A live stream plus the callstack that created it. Retaining `startTask` is what makes
     * [resubscribeAll] possible: re-sending it re-registers the stream on a restarted proxy, and
     * because `streamId` is derived deterministically from the callstack
     * (`LocalRequestChain.createStreamId`) the *same* [interactor] — with all its callbacks — is
     * what the new elements route back to. See `core-client-resubscription.md` §2.2.
     */
    private class StreamEntry(
        val interactor: StreamInteractorImpl<*>,
        val startTask: TList<Data>,
        /**
         * [StreamElementReceipts.current] when [interactor] was registered, taken before its start
         * is sent: an element received at or before it is an earlier subscription's — see
         * [dispatchElement]. Moved forward by [resubscribeAll] before it re-sends the start.
         * [Long.MAX_VALUE] (drop everything) while the start waits on a predecessor's end — see
         * [awaitPredecessorEnd].
         */
        var registeredAt: Long,
    )

    private val interactors = mutableMapOf<Long, StreamEntry>()

    /**
     * Per stream id, the `EndStream`s a new subscription of that id found still in flight when it
     * replaced a closed entry — kept here because the entry that carried one can be dropped before
     * it completes. Present only while an overlap is being waited out; see [awaitPredecessorEnd].
     */
    private val closingEnds = mutableMapOf<Long, List<Deferred<Unit>>>()
    private val mutex = Mutex()

    private val taskExecutor by lazy {
        taskExecutorProvider().also { executor ->
            // Self-wiring, deliberately: the alternative is a line in every app's
            // PlatformClientModule (one per app per platform) that fails *silently* when forgotten —
            // streams would simply never recover. An in-process executor does not implement
            // ReconnectingTaskExecutor, so this is a no-op exactly where reconnects cannot happen.
            (executor as? ReconnectingTaskExecutor)?.onReconnected = {
                // Bumped BEFORE the replay so a Stream retry waiting on awaitConnected wakes and
                // rebuilds its own subscription in parallel — resubscribeAll structurally cannot
                // reach it (a failed subscribe leaves no entry to replay; that is R1b's whole
                // subject), so the two paths are disjoint, not racing.
                reconnects.value += 1
                resubscribeAll()
            }
        }
    }

    /**
     * Counts reconnect edges, so [awaitConnected] can sleep on the *edge* rather than poll a
     * level. A counter rather than a flag because the wait must be immune to a reconnect that
     * lands while the caller is still deciding to wait — see [ProxyConnectivity.awaitConnected].
     */
    private val reconnects = MutableStateFlow(0)

    /**
     * Whether the channel is up (`core-client-resubscription.md` R5).
     *
     * This provider is already the one thing that self-wires to the executor's reconnect edge, so
     * it is also where `:core` can be told about the channel without a second listener: the R4
     * `setOnConnectionChanged` slot is single-occupancy and belongs to `TenshuClient.proxyConnected`.
     * An executor that is not remote cannot lose a connection, hence `true`.
     */
    override val isConnected: Boolean
        get() = (taskExecutor as? RemoteTaskExecutor)?.isConnected ?: true

    override suspend fun awaitConnected() {
        // ⚠ Read the edge counter FIRST. Sampling it after the isConnected check would drop a
        // reconnect that landed in between, and the caller would then sleep until the *next* one
        // — which, for a proxy that comes back and stays up, never arrives.
        val seen = reconnects.value
        if (isConnected) return
        reconnects.first { it != seen }
    }

    /**
     * Replay every live stream against a **restarted proxy**
     * (`docs/proposals/core-client-resubscription.md` R1).
     *
     * The proxy's `StreamRegistry` is in-memory and died with its process, so these streams are
     * subscribed to nothing; the transport's silent recovery is what makes that invisible. Each
     * live entry is reset (so consumers drop state accumulated against the old proxy — deletions
     * that happened while disconnected are unknowable) and its start callstack re-sent, which the
     * proxy answers with a fresh full snapshot.
     *
     * Best-effort per stream: one failure must not abandon the rest, and a stream left
     * un-resubscribed is exactly as stale as before this ran, never worse.
     */
    suspend fun resubscribeAll() {
        val entries = mutex.withLock { interactors.values.toList() }
        for (entry in entries) {
            // Skips closed/ended streams and those whose original subscribe failed — their
            // consumers have already terminated, so re-registering would leak a proxy-side
            // listener nobody reads.
            if (!entry.interactor.prepareForResubscribe()) continue
            // The restarted proxy answers only what is sent from here on, so anything received
            // before this point is the dead proxy's. The executor's lane rotation already drops
            // those frames; this makes the provider's filter say the same, for any transport.
            mutex.withLock { entry.registeredAt = StreamElementReceipts.current() }
            try {
                taskExecutor.executeOrAsync(entry.startTask)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Reconnect races a proxy that is still coming up; the next reconnect retries.
            }
        }
    }

    override suspend fun <T : Data?> getInteractor(ro: RequestObject): Interactor<T> {
        val terminal = ro.requestChain.terminalOperationSchema()
        return when {
            terminal.isStream() -> {
                @Suppress("UNCHECKED_CAST")
                buildStreamInteractor<Data>(ro) as Interactor<T>
            }

            terminal.isIo() -> buildTaskInteractor(ro)
            terminal.isBackground() -> buildCancellableTaskInteractor(
                ro,
                // TS-5: a withTask block covers Background ops too — the TASK's id
                // becomes the L2 correlation id (folder upload's Bg upload variants).
                withTaskId = currentCoroutineContext()[WithTaskElement]?.taskId,
                // ⚠ Captured HERE, the last point that still runs in the caller's context: the call
                // itself runs in `clientScope`, which would otherwise drop the user-started mark.
                userInitiated = currentCoroutineContext()[UserInitiatedElement] != null,
            )
            else -> buildSyncInteractor(ro)
        }
    }

    /**
     * Routes [element] to the interactor registered under its stream id.
     *
     * ⚠⚠ By stream id **and receipt**. The id is a callstack hash, so a subscription of the same
     * chain registered after an earlier one ended has the same id — and that earlier one's
     * elements may still be queued in front of this call (the consumer lags). An element received
     * at or before the current interactor's registration cannot be its own (its start is sent
     * after it registers, see [StreamElementReceipts]), so it is dropped: handed on, a dead
     * subscription's snapshot was the new read's first emission and, merged, kept a row deleted in
     * between for good (`StreamResubscribeStaleBacklogTest`). Decided here, at dispatch, so
     * subscribing never waits on the queue — a barrier that did turned a Photos read test red.
     */
    override suspend fun dispatchElement(element: StreamElement<*>) {
        val streamId = element.streamId.get()
        // ⚠⚠ Fails open: no receipt ⇒ routed by id alone, the bug this guards against. The receipt
        // reaches here only through the coroutine context, so a push handler that adds a launch or
        // a hop between the queue's consumer and this call loses it and silently brings the bug back.
        val receivedAt = currentCoroutineContext()[StreamElementReceipt]?.stamp
        val interactor = mutex.withLock {
            interactors[streamId]
                ?.takeIf { receivedAt == null || receivedAt > it.registeredAt }
                ?.interactor
        }
        interactor?.dispatchElement(element)
    }

    private fun <T : Data?> buildSyncInteractor(ro: RequestObject): SyncInteractor<T> =
        object : SyncInteractor<T> {
            @Suppress("UNCHECKED_CAST")
            override fun execute(): TResult<T> {
                val task = ro.context.dataFactory().createList<Data>(ro.requestChain.getCallstack())
                return taskExecutor.execute(task) as TResult<T>
            }
        }

    private fun <T : Data?> buildTaskInteractor(ro: RequestObject): TaskInteractor<T> =
        object : TaskInteractor<T> {
            @Suppress("UNCHECKED_CAST")
            override suspend fun execute(): TResult<T> {
                val task = ro.context.dataFactory().createList<Data>(ro.requestChain.getCallstack())
                val executor = taskExecutor

                // Task composite (core-task-composite.md §5): a `withTask` block
                // installs a WithTaskElement in the caller's coroutine context —
                // readable here because TaskInteractor.execute() is suspend. When
                // present, ride the Phase-L2 cancellable envelope with the TASK's
                // id (zero new transport surface) so ProxyGateway can thread it
                // to the request's ExecutionContext and the op's saves join the
                // task's sync lane. The op stays an ordinary one-shot IoOperation
                // in every other respect; without the element, byte-identical to
                // before.
                val withTask = currentCoroutineContext()[WithTaskElement]
                if (withTask != null && executor is CancellableRemoteTaskExecutor) {
                    return executor.executeAsyncCancellable(task, withTask.taskId) as TResult<T>
                }
                if (withTask != null && executor is CancellableLocalTaskExecutor) {
                    // In-process runtimes (JS SDK, iOS route-1, test harness):
                    // same tagging, no wire (TS-4).
                    return executor.executeAsyncCancellable(task, withTask.taskId) as TResult<T>
                }

                // `cancellableReads { }` (`core-background-processing.md` §6.2.1): the caller
                // declared this call a read it may abandon, so the proxy may stop it when it does.
                // ⚠ Never the default — a plain call is not stopped by its caller's cancellation,
                // and a write launched from a closing screen relies on that.
                if (currentCoroutineContext()[CancellableReadsElement] != null &&
                    executor is CancellableReadTaskExecutor
                ) {
                    return executor.executeAsyncCancellableRead(task) as TResult<T>
                }

                return if (executor is AsyncTaskExecutor) {
                    executor.executeAsync(task) as TResult<T>
                } else {
                    executor.execute(task) as TResult<T>
                }
            }
        }

    /**
     * [withTaskId] (TS-5, `core-task-composite.md` §5): when the op was
     * requested inside a `withTask` block, the TASK's id replaces the
     * per-call minted id — the L2 envelope then doubles as the sync-lane
     * carrier (`ProxyGateway` → `requestSyncLaneId`), so a Bg-variant
     * upload's saves join the task's lane exactly like an IoOperation's.
     * Consequences: cancel-by-id addresses the task's CURRENT in-flight
     * call (the in-flight registry is keyed `(clientId, taskId)` and
     * overwrites on overlap), so constituent Bg calls under one task must
     * be serial — which every bulk-upload loop is.
     */
    private fun <T : Data?> buildCancellableTaskInteractor(
        ro: RequestObject,
        withTaskId: String? = null,
        userInitiated: Boolean = false,
    ): CancellableTaskInteractor<T> =
        object : CancellableTaskInteractor<T> {
            override fun execute(): TaskHandle<T> {
                val taskId = withTaskId ?: newTaskId()
                val task = ro.context.dataFactory().createList<Data>(ro.requestChain.getCallstack())
                val executor = taskExecutor

                // Only a BackgroundOperation (and not an IoOperation) resolves
                // here — see getInteractor. It MUST run awaited/cancellable, so
                // the executor must be one of the two cancellable kinds. A bare
                // in-process AsyncTaskExecutor would fall to `executeAsync`,
                // which for a background op takes ExecutionContext's legacy
                // detached path (`sessionScope.launch` + immediate
                // `Success(null)`) — `await()` then returns an instant bogus
                // result and the real work runs fire-and-forget (the "pairing
                // timed out instantly while the pairing actually worked" bug).
                // Fail loud at the wiring mistake instead of silently swallowing
                // it: an in-process executor that runs background ops MUST
                // implement CancellableLocalTaskExecutor.
                check(
                    executor is CancellableRemoteTaskExecutor ||
                        executor is CancellableLocalTaskExecutor
                ) {
                    "BackgroundOperation requires a CancellableRemoteTaskExecutor " +
                        "or CancellableLocalTaskExecutor to run awaited; got " +
                        "${executor::class.simpleName}. An in-process executor " +
                        "that dispatches background operations must implement " +
                        "CancellableLocalTaskExecutor (else its TaskHandle.await() " +
                        "resolves instantly to a bogus Success(null))."
                }

                @Suppress("UNCHECKED_CAST")
                val deferred = clientScope.async(
                    if (userInitiated) UserInitiatedElement else EmptyCoroutineContext,
                ) {
                    when (executor) {
                        is CancellableRemoteTaskExecutor ->
                            executor.executeAsyncCancellable(task, taskId)

                        is CancellableLocalTaskExecutor ->
                            executor.executeAsyncCancellable(task, taskId)

                        // Unreachable — guarded by the `check` above; kept so the
                        // `when` stays exhaustive over the executor surface.
                        else -> error("unreachable: non-cancellable executor passed the guard")
                    } as TResult<T>
                }

                return TaskHandleImpl(
                    taskId = taskId,
                    deferred = deferred,
                    // No-op for an in-process executor (LocalTaskExecutor / the
                    // JS SDK): there is no wire, and TaskHandleImpl.cancel()
                    // cancelling the Deferred already unwinds the real work
                    // cooperatively.
                    onWireCancel = { (executor as? CancellableRemoteTaskExecutor)?.cancel(taskId) },
                )
            }
        }

    private fun newTaskId(): String =
        Random.nextBytes(16).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private suspend fun <T : Data> buildStreamInteractor(ro: RequestObject): StreamInteractor<T> {
        val context = ro.context
        val requestChain = ro.requestChain
        val streamId = requestChain.streamId

        val startTask = context.dataFactory().createList<Data>(requestChain.getCallstack())

        val endTask = context.dataFactory().createList<Data>(
            listOf(
                OperationDto(
                    schema = EndStream.Schema,
                    params = mapOf(
                        EndStream.Params.STREAM_ID to streamId.toInteger()
                    )
                )
            )
        )

        val (interactor, isNew, predecessorEnds) =
            allocateOrReuseStreamInteractor<T>(streamId, startTask, endTask, context)

        if (isNew) {
            // ⚠⚠ A start that THROWS must still seed the stream. The entry is already registered
            // (above), under a stream id derived from the callstack, so an entry left with no initial
            // result is found again by every later subscribe of the same chain — `isNew` false, no
            // start sent, a callback waiting on a seed that never comes: the read hung for ever. And
            // the throw escaped into the collector's scope, which aborts a K/N process when it has no
            // handler. It throws in practice: the first in-process call mints the session, and a
            // mint over iOS's not-ready stand-in or a fenced store throws from `initFileService`
            // (`ios-shared-store-sqlite.md` B.16.15, follow-up). Seeded as a Failure, it takes
            // `TList.flow`'s existing path: one failure reported, the entry ended and dropped by the
            // consumer's `endStream`, the subscribe retried with backoff.
            var superseded = false
            val result: TResult<Data?> = try {
                // Only when the previous subscription of this id is still ending — never otherwise:
                // the common subscribe neither suspends nor gains a hop here.
                if (predecessorEnds != null && !awaitPredecessorEnd(streamId, interactor, predecessorEnds)) {
                    superseded = true
                    startFailure(IllegalStateException("superseded while waiting"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    taskExecutor.executeOrAsync(startTask) as TResult<Data?>
                }
            } catch (e: CancellationException) {
                // Not retried (the caller is going away), but never left unseeded either: a joiner
                // that reused the entry meanwhile needs the seed. ⚠ And then ended and dropped if
                // nobody holds it — this caller never reaches the `endStream` its consumer's
                // finally makes, so the entry would stay registered, its proxy-side listener alive,
                // and the next subscriber of the chain would be handed this Failure.
                withContext(NonCancellable) {
                    (interactor as StreamDispatcher<T>).setInitialResult(startFailure(e))
                    try {
                        interactor.abandonIfUnreferenced()
                    } catch (_: Exception) {
                        // Best-effort: the end is a round trip to a proxy that may be going away.
                    }
                }
                throw e
            } catch (e: Exception) {
                startFailure(e)
            }
            // ⚠ Ended while it waited (a joiner reused it and left, which sent its end): a start sent
            // now would register a proxy listener nobody reads, after that end. Subscribe afresh —
            // the new entry waits for this one's end in turn.
            if (superseded) return buildStreamInteractor(ro)
            (interactor as StreamDispatcher<T>).setInitialResult(result)
        }

        return interactor
    }

    private data class Allocation<T : Data>(
        val interactor: StreamInteractorImpl<T>,
        val isNew: Boolean,
        /** Earlier subscriptions' `EndStream`s of this id still in flight — see [awaitPredecessorEnd]. */
        val predecessorEnds: List<Deferred<Unit>>?,
    )

    /**
     * Looks up the active [StreamInteractorImpl] for [streamId] under [mutex], or creates a
     * fresh one and registers it. Returns the interactor along with a flag indicating whether
     * it was newly created (callers seed `setInitialResult` only on a newly-allocated stream),
     * and the ends of earlier subscriptions of the id still in flight.
     */
    private suspend fun <T : Data> allocateOrReuseStreamInteractor(
        streamId: Long,
        startTask: TList<Data>,
        endTask: TList<Data>,
        context: TContext,
    ): Allocation<T> = mutex.withLock {
        @Suppress("UNCHECKED_CAST")
        val existing = interactors[streamId]?.interactor as? StreamInteractorImpl<T>
        if (existing != null && !existing.isClosed) {
            Allocation(existing, isNew = false, predecessorEnds = null)
        } else {
            // ⚠ Every end of this id still in flight, not just the replaced entry's: a subscriber
            // cancelled while it waited sends its own end at once and is dropped, and the one after
            // it must still wait for the end the cancelled one was waiting on.
            val predecessorEnds = buildList {
                closingEnds[streamId]?.filterTo(this) { !it.isCompleted }
                existing?.pendingEnd?.let { add(it) }
            }
            if (predecessorEnds.isEmpty()) closingEnds.remove(streamId) else closingEnds[streamId] = predecessorEnds
            val newInteractor = StreamInteractorImpl<T>(
                context = context,
                onEndStream = { taskExecutor.executeOrAsync(endTask) },
                onCleanup = { instance ->
                    mutex.withLock {
                        if (interactors[streamId]?.interactor === instance) {
                            interactors.remove(streamId)
                        }
                    }
                }
            )
            val registeredAt = if (predecessorEnds.isNotEmpty()) Long.MAX_VALUE else StreamElementReceipts.current()
            interactors[streamId] = StreamEntry(newInteractor, startTask, registeredAt)
            Allocation(newInteractor, isNew = true, predecessorEnds = predecessorEnds.takeIf { it.isNotEmpty() })
        }
    }

    /**
     * ⚠⚠ A subscription that **overlaps** the previous one's end waits for that end's round trip
     * before sending its start. Stream ids are callstack hashes, and a closed interactor is replaced
     * at once, before its `EndStream` has reached the proxy — so two collectors of one chain, one
     * ending while the other subscribes (a screen transition), raced it there:
     * - the proxy removes a listener **by stream id** (`ListStreamEndImplementation`), so an end it
     *   processed after the new start removed the *new* subscription's listener: its snapshot
     *   arrived and its updates never did — a silently frozen list;
     * - until the end is processed the old registration is the live one, so what it emitted after
     *   the new registration passed both the receipt stamp and the proxy's registration token.
     *
     * So the new entry drops everything ([Long.MAX_VALUE]) while it waits, and is stamped afresh
     * once the end has returned: every element the old registration sent before the proxy
     * processed its end has been received by then (the in-process queue and the IPC transports
     * stamp on hand-over, in order), and the proxy's token stops anything still evaluating.
     *
     * Bounded, failing open: an end that fails returns (and so releases the wait), and one that
     * hangs (a proxy going away mid-end) costs at most [PREDECESSOR_END_WAIT_MS] before the start is
     * sent as it was before this wait existed (logged), and is then forgotten for later subscribes.
     * Measured on a real clock, never a test's virtual one. Cancellable: a subscriber cancelled here
     * takes the cancelled-start path in [buildStreamInteractor]. Returns false when the new
     * interactor was itself closed meanwhile (a joiner reused it and left): it must not start.
     * `StreamResubscribeOverlapTest`.
     */
    private suspend fun awaitPredecessorEnd(
        streamId: Long,
        interactor: StreamInteractorImpl<*>,
        predecessorEnds: List<Deferred<Unit>>,
    ): Boolean {
        overlapWaits.update { it + 1 }
        var timedOut = false
        try {
            // ⚠ On a real clock: under `runTest` the end's round trip runs on a real dispatcher, the
            // test scheduler goes idle, and a virtual-time timeout would fire at once — a false green
            // for any overlap test written that way. Only the overlap path pays this hop.
            timedOut = withContext(Dispatchers.Default) {
                withTimeoutOrNull(PREDECESSOR_END_WAIT_MS) { predecessorEnds.forEach { it.await() } }
            } == null
            if (timedOut) {
                println(
                    "DefaultInteractorProvider -> stream $streamId: the previous subscription's end did not " +
                        "return within ${PREDECESSOR_END_WAIT_MS}ms; starting anyway (an end processed later " +
                        "would remove this subscription's listener)"
                )
            }
            return mutex.withLock {
                // Read under the lock, as the paths that close it take this lock to drop the entry.
                if (interactor.isClosed) return@withLock false
                interactors[streamId]?.takeIf { it.interactor === interactor }?.registeredAt =
                    StreamElementReceipts.current()
                true
            }
        } finally {
            // Drop what has completed, so an id overlapped once holds nothing for ever. What is
            // still in flight stays — a cancelled waiter's successor must wait for it too — unless
            // this wait timed out on it: an end that never returns must cost one wait, not one per
            // later subscribe of the id.
            withContext(NonCancellable) {
                mutex.withLock {
                    val pending = closingEnds[streamId]?.filter {
                        !it.isCompleted && !(timedOut && it in predecessorEnds)
                    }
                    if (pending.isNullOrEmpty()) closingEnds.remove(streamId) else closingEnds[streamId] = pending
                }
            }
        }
    }

    /**
     * How many subscribes have waited out an overlapped predecessor's end ([awaitPredecessorEnd]).
     * Observable so a test can tell "waiting" from "not yet subscribed" without a timing window.
     */
    internal val overlapWaits = MutableStateFlow(0)

    /** A stream start that threw, as the typed failure [LocalTaskExecutor]'s catch-all would give. */
    private fun startFailure(e: Throwable): TResult<Data?> =
        ResultFailureDto(
            when (e) {
                is TenshuException -> e.error
                else -> InternalErrorDto(internalDetail = "the stream's start threw: $e")
            }
        )

    private fun RequestChain.terminalOperationSchema(): OperationSchema {
        val last = getCallstack().lastOrNull()
        if (last is Operation) return last.schema
        error("Terminal operation not found in callstack")
    }

    private suspend fun TaskExecutor.executeOrAsync(task: TList<Data>): TResult<Data> =
        if (this is AsyncTaskExecutor) executeAsync(task) else execute(task)

    private companion object {
        /** How long a start waits on an overlapped predecessor's end before going ahead regardless. */
        const val PREDECESSOR_END_WAIT_MS = 5_000L
    }
}
