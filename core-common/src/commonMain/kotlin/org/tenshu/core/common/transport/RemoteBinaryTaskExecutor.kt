package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.Operation
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.UserInitiatedElement
import kotlinx.coroutines.currentCoroutineContext
import org.tenshu.core.messageOrNull
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.StreamElementReceipt
import org.tenshu.core.common.StreamElementReceipts
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.CallOutcome
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import org.tenshu.core.common.request.CancellableRemoteTaskExecutor
import org.tenshu.core.common.request.ReconnectingTaskExecutor
import org.tenshu.core.common.request.SynchronousViolationException
import org.tenshu.core.core.TContext
import org.tenshu.core.id
import org.tenshu.core.isPure
import org.tenshu.core.pureOperationRunner
import org.tenshu.core.schema.Type
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import org.tenshu.core.TenshuException
import org.tenshu.core.UnannouncedSessionError
import kotlin.concurrent.Volatile

/**
 * Implementation of [RemoteTaskExecutor] that uses binary communication over a [TaskTransport].
 * This class contains the logic for converting task lists into the platform's binary protocol.
 */
@OptIn(ExperimentalAtomicApi::class)
class RemoteBinaryTaskExecutor(
    private val serializer: BinarySerializer,
    private val parser: BinaryParser,
    private val transport: TaskTransport,
    private val context: TContext,
    private val externalScope: CoroutineScope? = null
) : CancellableRemoteTaskExecutor, CancellableReadTaskExecutor, ReconnectingTaskExecutor {

    private var binderJob: Job? = null
    private var binderScope: CoroutineScope? = null

    var streamElementHandler: (suspend (Data) -> Unit)? = null

    /**
     * Set by [org.tenshu.core.common.DefaultInteractorProvider] to replay its live stream
     * subscriptions against a restarted proxy (`core-client-resubscription.md` R1).
     */
    override var onReconnected: (suspend () -> Unit)? = null

    /**
     * Session preflight (`core-schema-evolution.md` §6.4/§6.5): runs once
     * before the first remote call of a session and again after every
     * transport reconnect, ahead of anything else — including the
     * resubscription replays, which arrive through [executeAsync] and
     * therefore announce first ("announce becomes the first thing replayed").
     * Set by `wireSchemaAnnounce` to the `AnnounceSession` call; `null` (test
     * harnesses without announce wiring) disables the preflight entirely.
     *
     * A throwing preflight (transport failure, or a *refused* admission
     * surfaced as a typed [TenshuException]) fails the pending caller with
     * that error and stays un-latched, so the next call retries the announce.
     */
    var sessionPreflight: (suspend () -> Unit)? = null

    private val preflightMutex = Mutex()

    @Volatile
    private var preflightDone = false

    /**
     * Counts admitted announces. A call remembers the value it was sent under, so a refusal re-arms
     * the preflight only if nobody has announced since — N calls refused by one stale session cost
     * one re-announce, not N.
     */
    @Volatile
    private var preflightEpoch = 0L

    /** Marks the preflight's own call so it skips [ensureSessionPreflight]. */
    private class PreflightMarker : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PreflightMarker>
    }

    /** Runs the announce if this session still owes it; returns the epoch the caller proceeds under. */
    private suspend fun ensureSessionPreflight(): Long {
        val preflight = sessionPreflight ?: return preflightEpoch
        if (preflightDone) return preflightEpoch
        // The announce's own round-trip re-enters here — let it through.
        if (coroutineContext[PreflightMarker.Key] != null) return preflightEpoch
        preflightMutex.withLock {
            if (preflightDone) return preflightEpoch
            withContext(PreflightMarker()) { preflight() }
            preflightEpoch++
            preflightDone = true
            return preflightEpoch
        }
    }

    /** The proxy refused a call sent under [epoch] as unannounced: owe the announce again. */
    private suspend fun rearmPreflight(epoch: Long) {
        preflightMutex.withLock {
            if (preflightEpoch == epoch) preflightDone = false
        }
    }

    /**
     * Whether [result] is the proxy's reject-if-silent refusal of an ordinary call — the one reply a
     * resend after a fresh announce can fix. Never for the announce itself (it is the fix), and
     * never without a preflight to run (a harness with no announce wiring would only be refused
     * again).
     */
    private suspend fun isUnannouncedRefusal(result: TResult<Data>): Boolean =
        result is TResult.Failure &&
            result.error.id == UnannouncedSessionError.ID &&
            sessionPreflight != null &&
            coroutineContext[PreflightMarker.Key] == null

    override val isConnected: Boolean get() = transport.isConnected

    override fun setOnConnectionChanged(callback: ((Boolean) -> Unit)?) =
        transport.setOnConnectionChanged(callback)

    private var onCallOutcome: ((CallOutcome) -> Unit)? = null

    override fun setOnCallOutcome(callback: ((CallOutcome) -> Unit)?) {
        onCallOutcome = callback
    }

    /**
     * The push lane of the current session: pushed frames, in arrival order, and the one consumer
     * that drains them. Opened per `bind()` (or once, on [externalScope]), closed by [unbind].
     *
     * ⚠⚠ One consumer, not a `scope.launch { parse; dispatch }` per frame. Every transport hands
     * frames over in the order the proxy sent them (Binder, the UDS reader thread, the EPC channel
     * all serialise), and a launch per frame would throw that order away right here: two launches
     * are two coroutines with no order between them, so a deferred-property *patch* could reach the
     * client's interactor before the *snapshot* it patches, and `TList.flow()` — which keeps enqueue
     * order — would present the patched row first. `RemoteBinaryTaskExecutorPushOrderTest` forces
     * the worst case; the in-process twin is `forwardStreamElementsInOrder`.
     *
     * ⚠⚠ A lane per session, not one channel for the executor's lifetime. Frames that arrive with
     * no lane (pre-`bind()`, or after `unbind()`) are dropped — with no scope there are no live
     * streams to deliver to either. But a frame can
     * be *in flight* across the unbind: Android recreates activities routinely (`client.destroy()`
     * unbinds, the next `client.init()` rebinds) and a Binder callback that has already read the
     * lane enqueues after `unbind()` ran. With one shared channel that frame would sit there until
     * the next bind's consumer delivered it into a *new* session — a stale patch or terminal on a
     * fresh subscription. Closing the old lane's channel makes that late enqueue
     * fail; a frame that made it in before the close belongs to a channel nothing will ever read
     * again. `RemoteBinaryTaskExecutorRebindTest` pins the contract.
     */
    private class PushLane(
        /** Each frame with its [StreamElementReceipts] stamp, taken on the transport's thread. */
        val frames: Channel<Pair<Long, ByteArray>>,
        val consumer: Job,
        /** Set when the session this lane served is gone: its stream frames are dropped on delivery. */
        val stale: AtomicBoolean,
    )

    private val pushLane = AtomicReference<PushLane?>(null)

    /** Test seam: whether a lane is open, i.e. whether a push arriving now would be queued. */
    internal fun hasOpenPushLane(): Boolean = pushLane.load() != null

    /**
     * Opens a fresh lane on [scope] and hands whatever lane it displaced to [displaced] — [retire],
     * [staleClose] or [drainClose], by what the displaced lane's queued frames still mean.
     */
    private fun openPushLane(scope: CoroutineScope, displaced: (PushLane) -> Unit) {
        val frames = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
        val stale = AtomicBoolean(false)
        // ⚠⚠ Deliberately NOT a child of [scope]. A consumer parked on an empty channel never
        // completes, and a structured scope — `withTimeout`, `runTest`, any `coroutineScope {}` —
        // waits for its children before it returns, and callers hand this scopes they expect to
        // close (a test's would hang). It runs on the scope's dispatcher and context, but under a
        // Job of its own that is cancelled when the scope's job completes — dies with the scope,
        // never holds it open.
        val owner = scope.coroutineContext[Job]
        val consumer = CoroutineScope(scope.coroutineContext + Job()).launch {
            for ((receipt, bytes) in frames) {
                // ⚠ `receive` hands out a buffered frame WITHOUT checking cancellation, so a lane
                // closed with a backlog would still deliver it; this is what makes `unbind` final.
                ensureActive()
                try {
                    val parsed = parser.parse<Data>(bytes)
                    // A stale lane's STREAM frames are a dead session's deltas: dropped. Everything
                    // else it carried — a cross-app `OpenTargetRequest` is the production case —
                    // is a push in its own right and is still delivered.
                    if (parsed is StreamElement<*> && stale.load()) continue
                    // The receipt rides to `DefaultInteractorProvider.dispatchElement`, which drops a
                    // frame received before the interactor it would reach was registered — an
                    // ended subscription's, queued here behind this consumer (`StreamElementReceipts`).
                    // Same dispatcher, so no hop.
                    withContext(StreamElementReceipt(receipt)) { streamElementHandler?.invoke(parsed) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A frame this build cannot read or a handler that threw must not stop the
                    // frames behind it.
                    println("IPC Client -> push dropped: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        val lane = PushLane(frames, consumer, stale)
        // ⚠ One atomic step, and the displacer ends what it displaced. "Close the current lane,
        // then store" is two steps, and two on-demand round trips entering `bind()` together could
        // both close and then both store — the first lane overwritten un-ended, its consumer
        // parked on an unreachable channel for the process lifetime.
        pushLane.exchange(lane)?.let(displaced)
        // The owner ending retires the LANE, not just its consumer: an open channel with no reader
        // would queue every later push for the transport's lifetime (an external scope has no
        // `unbind()` to do this).
        owner?.invokeOnCompletion { retire(lane) }?.let { handle ->
            consumer.invokeOnCompletion { handle.dispose() }
        }
    }

    /**
     * Ends [lane] at once — its queued frames are discarded — and, if it is still the current one,
     * forgets it; idempotent. For `unbind()` and the owner scope ending, where in-flight pushes
     * have no session left to reach.
     */
    private fun retire(lane: PushLane) {
        pushLane.compareAndSet(lane, null)
        lane.frames.close()
        lane.consumer.cancel()
    }

    /**
     * Lets a displaced [lane] drain and end on its own, with its **stream** frames dropped: they are
     * a dead proxy session's deltas, but a non-stream push it carried (`OpenTargetRequest`) is a
     * request in its own right and is delivered across a reconnect. Cancelling
     * would lose it, and could lose the one frame the consumer took between the cancel and its
     * `ensureActive()`; closing loses nothing.
     */
    private fun staleClose(lane: PushLane) {
        lane.stale.store(true)
        lane.frames.close()
    }

    /** Lets a displaced [lane] drain in full and end: displaced by a concurrent first `bind()`, it holds nothing stale. */
    private fun drainClose(lane: PushLane) {
        lane.frames.close()
    }

    private fun closePushLane() {
        pushLane.load()?.let { retire(it) }
    }

    init {
        transport.onCallback = { request ->
            // No lane, no delivery — and a lane closed by `unbind()` refuses the frame too.
            // Stamped on arrival, before the queue — see `StreamElementReceipts`.
            pushLane.load()?.frames?.trySend(StreamElementReceipts.stamp() to request)
        }
        externalScope?.let { openPushLane(it, ::drainClose) }
        // The transport fires this on the platform's callback thread and cannot suspend, so hop
        // onto the same scope the element path uses. Best-effort by construction: with no scope
        // yet (pre-`bind()`) there are no live streams to replay either.
        transport.setOnReconnected {
            // The restarted proxy has a fresh session with no admitted
            // announce — re-arm the preflight BEFORE the resubscribe replays
            // run, so the first replayed call announces first (§6.4).
            preflightDone = false
            val scope = binderScope ?: externalScope
            if (scope != null) {
                // ⚠⚠ Rotate the lane BEFORE the replay. Neither Binder nor UDS calls `bind()` on an
                // automatic reconnect, so the lane still holds whatever the dead proxy pushed last;
                // the replay resets every interactor and re-sends the snapshots, and a stale patch,
                // removal or End draining in between would land on the recovered read.
                // `RemoteBinaryTaskExecutorLaneLifecycleTest` pins it.
                openPushLane(scope, ::staleClose)
                scope.launch { onReconnected?.invoke() }
            }
        }
    }

    override suspend fun controlInstalled(): Boolean? = transport.controlInstalled()

    override fun bind() {
        if (externalScope == null) {
            if (binderJob == null || binderJob?.isCompleted == true) {
                val job = SupervisorJob()
                binderJob = job
                val scope = CoroutineScope(Dispatchers.Default + job)
                binderScope = scope
                openPushLane(scope, ::drainClose)
            }
        }
        transport.bind()
    }

    override fun unbind() {
        transport.unbind()
        binderJob?.cancel("Executor unbound")
        binderJob = null
        binderScope = null
        // The binder lane goes with the session; an external scope's lane is the caller's to end.
        if (externalScope == null) closePushLane()
        // A future rebind may land on a fresh proxy session; re-announcing on
        // a surviving session is an idempotent no-op, so re-arm unconditionally.
        preflightDone = false
    }

    override fun execute(task: TList<Data>): TResult<Data> {
        // Attempt to run the task locally using the PureOperationRunner.
        // If all operations in the stack are PureOperations, the call succeeds without IPC.

        var currentData: Data? = null
        val callstack = task.toList()

        for (protocol in callstack) {
            if (protocol is Operation) {
                if (protocol.isPure()) {
                    currentData = context.pureOperationRunner().execute(protocol, currentData)
                } else {
                    throw SynchronousViolationException(
                        "Synchronous Context Violation: Operation [${protocol.id}] attempted to perform IO or " +
                                "suspend in a sync-only execution domain."
                    )
                }
            } else {
                if (currentData == null) {
                    currentData = protocol
                } else {
                    throw SynchronousViolationException(
                        "Synchronous Context Violation: Protocol [${protocol.id}] cannot be resolved locally in a sync-only execution domain."
                    )
                }
            }
        }

        return TResult.Success.create(context, currentData)
    }

    override suspend fun executeAsync(task: TList<Data>): TResult<Data> =
        roundTrip(task) { transport.call(it) }

    override suspend fun executeAsyncCancellable(
        task: TList<Data>,
        taskId: String
    ): TResult<Data> =
        // ⚠ Read from the caller's context: `userInitiatedWork { }` installs it, and the interactor
        // carries it into the detached scope a Background op runs in (DefaultInteractorProvider).
        if (currentCoroutineContext()[UserInitiatedElement] != null) {
            roundTrip(task) { transport.callCancellableUserInitiated(it, taskId) }
        } else {
            roundTrip(task) { transport.callCancellable(it, taskId) }
        }

    override fun cancel(taskId: String) {
        transport.cancel(taskId)
    }

    /** `cancellableReads { }` — the transport stops the proxy's walk if the caller is cancelled (§6.2.1). */
    override suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data> =
        roundTrip(task) { transport.callCancellableRead(it) }

    private suspend fun roundTrip(
        task: TList<Data>,
        invoke: suspend (ByteArray) -> ByteArray
    ): TResult<Data> {
        if (!isConnected) {
            bind()
            // The Android Binder bind is asynchronous: `bind()` returns before
            // `onServiceConnected` lands, so firing the call immediately races the
            // connection and throws "Proxy not connected". Await the connection
            // first (a no-op for synchronous transports). If it never comes up,
            // surface a Failure rather than throwing — callers expect a TResult,
            // not an uncaught exception (e.g. background backup status reporting
            // that fires at launch).
            if (!transport.awaitConnected()) {
                return connectionFailure(task)
            }
        }

        // §6.5: the announce rides ahead of the first call of every session.
        val epoch = when (val preflight = preflight(task)) {
            is Preflight.Ready -> preflight.epoch
            is Preflight.Failed -> return preflight.result
        }

        // ⚠⚠ Inside a try for the same reason the response parse is — the contract this class
        // states: *callers get a `TResult`, never a throw*. Uncaught, a request this build cannot
        // encode throws straight past every executor guard, reaches an app as an ordinary
        // exception, and every repository meets it in a broad `catch`.
        //
        // ⚠ **No `CallOutcome`**, deliberately. Nothing was sent and the proxy said nothing, so
        // there is no round trip to classify. In particular it is NOT `Unintelligible`: that means
        // *the two builds disagree*, whereas failing to encode our own request is this build's own
        // registry, and telling the user to update would be a wrong instruction for a local bug.
        val requestBytes = try {
            serializer.serialize(
                task,
                isTopLevel = true,
                expectedType = Type.create(baseSchema = TList.Schema)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("IPC Client -> unserialisable request: ${e.message}")
            return TResult.Failure.create(
                context = context,
                error = GenericError.create(context, "Unserialisable request: ${e.message}"),
            )
        }
        var result = send(task, requestBytes, invoke)
        // ⚠⚠ **The session changed under this call** (a call can beat the re-announce after a
        // Control restart). A transport publishes a new connection before its
        // `onReconnected` re-arms the preflight — Binder narrows that to a few instructions, the UDS
        // transport cannot (it fires the callback outside its connect lock) — so a call can still
        // reach the new session on the old session's announce. The gate refused it before running
        // anything, so announcing and resending is safe. ⚠ Once: a second refusal is a proxy that
        // will not admit this session, and is the caller's answer.
        if (result is SendResult.Answered && isUnannouncedRefusal(result.result)) {
            println("IPC Client -> unannounced session, re-announcing: ${task.toList().joinToString(" -> ") { it.id }}")
            rearmPreflight(epoch)
            (preflight(task) as? Preflight.Failed)?.let { return it.result }
            result = send(task, requestBytes, invoke)
        }
        return when (result) {
            is SendResult.Answered -> answered(task, result.result)
            is SendResult.Abandoned -> result.result
        }
    }

    private sealed interface Preflight {
        class Ready(val epoch: Long) : Preflight
        class Failed(val result: TResult<Data>) : Preflight
    }

    /**
     * A refused admission (typed TenshuException) or a failed announce fails THIS caller with the
     * same error, exactly as the proxy-side reject-if-silent gate would — but with the richer
     * client-side mismatch summary.
     */
    private suspend fun preflight(task: TList<Data>): Preflight =
        try {
            Preflight.Ready(ensureSessionPreflight())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TenshuException) {
            Preflight.Failed(TResult.Failure.create(context = context, error = e.error))
        } catch (e: Throwable) {
            Preflight.Failed(connectionFailure(task, "session preflight failed: ${e.message}"))
        }

    private sealed interface SendResult {
        /** The proxy answered with a readable [result]; not yet reported to [onCallOutcome]. */
        class Answered(val result: TResult<Data>) : SendResult

        /** No usable answer; [result] is the failure, already reported. */
        class Abandoned(val result: TResult<Data>) : SendResult
    }

    private suspend fun send(
        task: TList<Data>,
        requestBytes: ByteArray,
        invoke: suspend (ByteArray) -> ByteArray,
    ): SendResult {
        // Belt-and-suspenders for a mid-flight disconnect between the await above
        // and the call: convert the transport's not-connected throw into a Failure
        // so an IPC race can never crash a caller.
        val responseBytes = try {
            invoke(requestBytes)
        } catch (e: CancellationException) {
            // ⚠ Must precede the IllegalStateException arm: on JVM `CancellationException` IS an
            // `IllegalStateException`, so the arm below would swallow every cancellation and
            // report it as "not connected". A superseded paging load (`flatMapLatest` builds a new
            // generation) would then surface as `LoadResult.Error` — the listing's *Try again*
            // empty state — for a load that was simply not wanted any more.
            throw e
        } catch (e: IllegalStateException) {
            return SendResult.Abandoned(connectionFailure(task, e.message))
        }

        // ⚠⚠ The parse is inside the try, and that is the point of this block. An
        // `UnknownTypeTagException` here means the proxy ANSWERED and this build cannot read the
        // answer — version skew, not an outage. It is an `IllegalStateException`, so escaping from
        // here it would reach an app as an ordinary failed read and every repository would report it
        // as "Control is unreachable": an outage the user cannot fix, instead of the update they can.
        // Folding it keeps the executor's contract — callers get a `TResult`, never a throw — and
        // the outcome below is what lets a reachability engine say the true thing.
        val result: TResult<Data> = try {
            parser.parse(
                data = responseBytes,
                expectedType = Type.create(baseSchema = TResult.Schema)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onCallOutcome?.invoke(CallOutcome.Unintelligible)
            println("IPC Client -> unintelligible response: ${e.message}")
            return SendResult.Abandoned(
                TResult.Failure.create(
                    context = context,
                    error = GenericError.create(context, "Unreadable response: ${e.message}"),
                )
            )
        }
        return SendResult.Answered(result)
    }

    /** Reports and logs the answer the caller gets. A refusal that was resent is never reported. */
    private fun answered(task: TList<Data>, result: TResult<Data>): TResult<Data> {
        // ⚠⚠ The result, not just the parse. A refused session answers with a readable typed
        // failure, so classifying every parseable response as `Answered` would report a proxy that
        // serves nothing as reachable — see `CallOutcome.Answered`.
        onCallOutcome?.invoke(
            if (result is TResult.Failure) CallOutcome.AnsweredWithError else CallOutcome.Answered
        )

        if (result is TResult.Failure) {
            val callstack = task.toList()
            val msg = result.error.messageOrNull()
            val errorSuffix = if (msg != null) "${result.error.id} (\"$msg\")" else result.error.id
            println("IPC Client -> Failure: ${callstack.joinToString(" -> ") { it.id }} -> $errorSuffix")
        }

        return result
    }

    /** A typed Failure for "the proxy connection wasn't available for this call". */
    private fun connectionFailure(task: TList<Data>, detail: String? = null): TResult<Data> {
        // ⚠ Every path that abandons the channel funnels through here, so this is the one place
        // "not connected" has to be reported from.
        onCallOutcome?.invoke(CallOutcome.NotConnected)
        val ids = task.toList().joinToString(" -> ") { it.id }
        println("IPC Client -> not connected: $ids${detail?.let { " ($it)" } ?: ""}")
        return TResult.Failure.create(
            context = context,
            error = GenericError.create(context, detail ?: "Proxy not connected"),
        )
    }
}
