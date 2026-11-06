package org.tenshu.core.collection.list

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.asStreamInteractor
import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.proxyConnectivity
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import org.tenshu.core.trait.StreamOperation
import org.tenshu.core.trait.TerminalOperation

fun <T : Data> TList<T>.stream(signalOnly: Boolean = false): RequestObject {
    val operation = findOperation<Stream<T>>(Stream.ID)
    return operation.stream(signalOnly)
}

interface Stream<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    /**
     * [signalOnly] is the proxy-visible "the subscriber discards payloads"
     * bit: the proxy skips serialising/sending the initial full-list emission
     * (per-item change emissions still flow and *are* the signal — see
     * [changes]). The param rides the wire only when `true`, so plain streams
     * stay byte-identical and interoperate with older proxies.
     */
    fun stream(signalOnly: Boolean = false): RequestObject

    companion object {
        const val ID = "tenshu/core/collection/list/stream/start"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            params = {
                int(Params.STREAM_ID)
                boolean(Params.SIGNAL_ONLY, isOptional = true)
            },
            traits = listOf(TerminalOperation.Schema, StreamOperation.Schema)
        )
    }

    object Params {
        const val STREAM_ID = "$ID/streamId"
        const val SIGNAL_ONLY = "$ID/signalOnly"
    }
}

interface EndStream : Operation {

    override val schema: OperationSchema
        get() = Schema

    val streamId: TInteger
        get() = getField(Params.STREAM_ID)

    object Params {
        const val STREAM_ID = "${ID}/streamId"
    }

    companion object {
        const val ID = "tenshu/core/collection/list/stream/end"

        val Schema = operation(
            id = ID,
            params = {
                int(Params.STREAM_ID)
            },
            traits = listOf(TerminalOperation.Schema)
        )
    }
}

/**
 * Carrier for incoming stream signals so that the [flow] collector loop can
 * apply state mutations ([StreamRows.merge] / [StreamRows.remove]) serially. The
 * underlying [StreamCallback.element] is invoked concurrently per-element
 * by the IPC transport (e.g. `RemoteBinaryTaskExecutor`'s `onCallback`
 * launches a new coroutine per received element), so doing the merge
 * inline in the callback races and corrupts `list` — observed in
 * production as `IndexOutOfBoundsException` when a `Removed` shrank the
 * list between a concurrent `Value`'s index lookup and its `set` write.
 */
private sealed interface StreamSignal<T : Data> {
    class Event<T : Data>(val element: StreamElement<T>) : StreamSignal<T>

    /** A joiner's catch-up from the interactor's buffer ([StreamCallback.catchUp]): folded, then emitted ONCE. */
    class CatchUp<T : Data>(val elements: List<StreamElement<T>>) : StreamSignal<T>
    class TerminalError<T : Data>(val error: TError) : StreamSignal<T>

    /** The subscribe itself succeeded — see [changes]' resubscribe loop. */
    class Subscribed<T : Data> : StreamSignal<T>

    /**
     * The stream was re-subscribed against a restarted proxy — drop the accumulated [list] so the
     * snapshot that follows is authoritative rather than merged over stale rows
     * (see [StreamCallback.reset]). Rides the same channel as the events so it cannot overtake
     * them: the callback fires before the re-subscribe request goes out, and this loop is the sole
     * consumer, so the clear is guaranteed to be applied before the new snapshot merges.
     */
    class Reset<T : Data> : StreamSignal<T>
}

/**
 * State observation: the observed list folded across its initial snapshot and every subsequent
 * change, re-emitted whole on each one.
 *
 * ## Failure handling
 *
 * A failed subscribe is **retried**, not terminal — the same one-way door that
 * `core-client-resubscription.md` §4.1 documents for [changes]. `setInitialResult(Failure)`
 * delivers `start(error)` *and* `end(error)`, so the consumer's `finally` calls `endStream`, the
 * provider drops its entry, and both ends of the subscription are gone: nothing is left for
 * `DefaultInteractorProvider.resubscribeAll` to replay, and only restarting the *client process*
 * rebuilt the read. Recovery has to build a fresh request object and interactor, which is why it
 * lives here rather than in the provider.
 *
 * ⚠ The failure is emitted **once per failure run**, not once per attempt: it is a state signal
 * ("this read is broken"), and repeating it every 15 s would wake every consumer with no new
 * information. The first **element** that arrives re-arms the report and resets the backoff, so a
 * *later* failure is surfaced again. ⚠ Not the subscribe landing: a stream that subscribes and is
 * then ended with an error before delivering anything (its snapshot refused by the transport) is
 * one failure run, backing off to 15 s, not a 1 s loop of fresh failures.
 *
 * ⚠ Each attempt starts from an **empty list**. A retry means arbitrary changes were missed while
 * the read was down, so merging a fresh snapshot onto rows accumulated before the break would
 * resurrect deleted ones — the same hazard [StreamSignal.Reset] exists for, and the reason nothing
 * is emitted for the clear itself (a spurious "no rows" frame would flash on every recovery).
 *
 * A stream that ends *cleanly* is not retried: that is the observed list genuinely being done.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Data> TList<T>.flow(context: TContext? = null): Flow<TResult<TList<T>>> {
    val source = this
    return flow {
        val actualContext = context ?: (source as? TContext) ?: error("Context required for flow")
        var attempt = 0
        var failureReported = false

        while (true) {
            var interrupted = false
            val list = StreamRows<T> { it.getDataIdHash(actualContext) }
            val ro = source.stream()
            val interactor = ro.asStreamInteractor<T>()
            // Single-producer/single-consumer: callbacks (which may fire on
            // parallel coroutines) only `trySend`, the flow{} loop below is the
            // sole consumer — so `list`'s mutations run serially.
            val signals = Channel<StreamSignal<T>>(Channel.UNLIMITED)

            val listener = object : StreamCallback<T> {
                override fun start(error: TError?) {
                    if (error != null) {
                        signals.trySend(StreamSignal.TerminalError(error))
                        signals.close()
                    } else {
                        signals.trySend(StreamSignal.Subscribed())
                    }
                }

                override fun element(streamElement: StreamElement<T>) {
                    signals.trySend(StreamSignal.Event(streamElement))
                }

                override fun catchUp(elements: List<StreamElement<T>>) {
                    signals.trySend(StreamSignal.CatchUp(elements))
                }

                override fun reset() {
                    signals.trySend(StreamSignal.Reset())
                }

                override fun end(error: TError?) {
                    if (error != null) {
                        signals.trySend(StreamSignal.TerminalError(error))
                    }
                    signals.close()
                }
            }

            try {
                interactor.registerCallback(listener)
                for (signal in signals) {
                    when (signal) {
                        is StreamSignal.TerminalError -> {
                            interrupted = true
                            if (!failureReported) {
                                failureReported = true
                                emit(TResult.Failure.create(actualContext, signal.error))
                            }
                        }
                        // Deliberately does NOT emit: the re-subscribe's snapshot arrives as the
                        // very next element(s), so emitting the emptied list here would flash a
                        // spurious "no rows" frame at every proxy restart. If the snapshot never
                        // arrives the consumer keeps showing the pre-reconnect data — no worse
                        // than before R1.
                        is StreamSignal.Reset -> list.clear()
                        // ⚠⚠ A landed subscribe is NOT recovery: the backoff and the failure
                        // report are re-armed by the first element that arrives, below. A stream
                        // whose snapshot is refused every time (an emission too large for the
                        // transport — `core-client-resubscription.md` §4.8) subscribes fine and
                        // then ends with an error, and resetting here put it on a 1 s retry, with
                        // a fresh Failure to the consumer each time, for ever.
                        is StreamSignal.Subscribed -> Unit

                        // ⚠⚠ ONE emission for a joiner's whole catch-up, not one per element. The
                        // buffer is a log that starts with the snapshot from when the stream FIRST
                        // subscribed; emitted element by element, a joiner's first emission was that
                        // snapshot — a state the store may have left long ago. In Photos the consent
                        // read, re-subscribed after a write, showed `realm=false` over a stored yes.
                        // ⚠ Only the catch-up: live elements stay one emission each, which keeps
                        // `@defer`'s metadata-first frame (snapshot, then its patch) intact.
                        // ⚠ Current as of the interactor's buffer, not of the store: a write still on
                        // its way through the element queue arrives after, as its own emission.
                        // `StreamLateSubscriberTest.aLateSubscribersFirstEmissionIsTheBuffersState`.
                        is StreamSignal.CatchUp -> {
                            if (signal.elements.isEmpty()) continue
                            attempt = 0
                            failureReported = false
                            signal.elements.forEach { element ->
                                when (element) {
                                    is StreamElement.Value -> {
                                        val payload = element.payload
                                        if (payload is List<*>) list.merge(payload as Collection<T>) else list.merge(listOf(payload))
                                    }
                                    is StreamElement.Removed -> list.remove(element.removedId)
                                    is StreamElement.End -> Unit
                                }
                            }
                            emit(TResult.Success.create(actualContext, list.rows.toTList(actualContext)))
                        }

                        is StreamSignal.Event -> {
                            if (signal.element !is StreamElement.End) {
                                attempt = 0
                                failureReported = false
                            }
                            when (val streamElement = signal.element) {
                                is StreamElement.Value -> {
                                    val payload = streamElement.payload
                                    if (payload is List<*>) {
                                        list.merge(payload as Collection<T>)
                                    } else {
                                        list.merge(listOf(payload))
                                    }
                                    emit(
                                        TResult.Success.create(
                                            actualContext,
                                            list.rows.toTList(actualContext)
                                        )
                                    )
                                }

                                is StreamElement.Removed -> {
                                    list.remove(streamElement.removedId)
                                    emit(
                                        TResult.Success.create(
                                            actualContext,
                                            list.rows.toTList(actualContext)
                                        )
                                    )
                                }

                                is StreamElement.End -> {
                                    streamElement.error?.let {
                                        interrupted = true
                                        if (!failureReported) {
                                            failureReported = true
                                            emit(TResult.Failure.create(actualContext, it))
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    // Best-effort cleanup: this runs on every collection end,
                    // including one caused by the proxy session going away. In that
                    // state the round-trip `endStream` makes cannot build its result
                    // (no live context ⇒ no DataFactory) and throws. Letting that
                    // escape a `finally` is doubly wrong — it masks whatever really
                    // ended the flow, and it lands uncaught on the collector's
                    // dispatcher, where a test runner attributes it to an unrelated
                    // test. Teardown failure is not actionable by the collector, so
                    // swallow it; cancellation still propagates normally.
                    try {
                        interactor.endStream(listener)
                    } catch (_: Throwable) {
                    }
                }
            }

            if (!interrupted) return@flow
            attempt++
            if (awaitNextSubscribeAttempt(actualContext, attempt)) attempt = 0
        }
    }
}

/** 1s, 2s, 4s, 8s, then every 15s — shared by [flow] and [changes]. */
private fun resubscribeDelayMs(attempt: Int): Long =
    (1_000L shl (attempt - 1).coerceAtMost(3)).coerceAtMost(15_000L)

/**
 * Wait before the next subscribe attempt, and report whether the backoff should reset.
 *
 * Two waits, because there are two reasons a subscribe fails and only one of them has an event to
 * wait for:
 *
 *  - **The channel is down** (`ProxyConnectivity.isConnected == false`). Every attempt made before
 *    it returns is guaranteed to fail, so the timer buys nothing and costs one IPC round trip per
 *    15 s for as long as the read is collected — indefinitely, since a proxy can stay unreachable
 *    (uninstalled, disabled, Control-desktop not running). Sleep on the reconnect **edge** instead
 *    and retry at once when it lands: strictly fewer attempts *and* a faster recovery. The backoff
 *    resets, because the next failure is a new failure run rather than a continuation of this one.
 *  - **The channel is up and the read still failed** — the announce gate refusing the session on a
 *    schema skew is the standing case, and it can persist until the user updates an app. There is
 *    no edge to wait for, so this keeps the timer.
 *
 * With no [ProxyConnectivity] in the context (in-process proxy, tests) there is no channel to
 * lose, so this is exactly the timer it always was.
 */
private suspend fun awaitNextSubscribeAttempt(context: TContext?, attempt: Int): Boolean {
    val connectivity = context?.proxyConnectivity()
    if (connectivity != null && !connectivity.isConnected) {
        connectivity.awaitConnected()
        return true
    }
    delay(resubscribeDelayMs(attempt))
    return false
}

/**
 * One subscription attempt behind [changes], surfacing the raw signals so the
 * caller can tell a *failed subscribe* (retryable) from a stream that genuinely
 * ended (not).
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Data> TList<T>.changesOnce(context: TContext?): Flow<StreamSignal<T>> {
    val source = this
    return flow {
        (context ?: (source as? TContext)) ?: error("Context required for changes")
        val ro = source.stream(signalOnly = true)
        val interactor = ro.asStreamInteractor<T>()
        val signals = Channel<StreamSignal<T>>(Channel.UNLIMITED)

        val listener = object : StreamCallback<T> {
            override fun start(error: TError?) {
                if (error != null) {
                    signals.trySend(StreamSignal.TerminalError(error))
                    signals.close()
                } else {
                    signals.trySend(StreamSignal.Subscribed())
                }
            }

            override fun element(streamElement: StreamElement<T>) {
                signals.trySend(StreamSignal.Event(streamElement))
            }

            override fun reset() {
                signals.trySend(StreamSignal.Reset())
            }

            override fun end(error: TError?) {
                if (error != null) {
                    signals.trySend(StreamSignal.TerminalError(error))
                }
                signals.close()
            }
        }

        try {
            interactor.registerCallback(listener)
            for (signal in signals) {
                emit(signal)
                when (signal) {
                    is StreamSignal.TerminalError -> break
                    is StreamSignal.Event -> when (signal.element) {
                        is StreamElement.Value, is StreamElement.Removed -> Unit
                        is StreamElement.End -> break
                    }

                    // Never sent here: this listener keeps [StreamCallback.catchUp]'s per-element default.
                    is StreamSignal.Reset, is StreamSignal.Subscribed, is StreamSignal.CatchUp -> Unit
                }
            }
        } finally {
            withContext(NonCancellable) {
                // Best-effort cleanup: this runs on every collection end,
                // including one caused by the proxy session going away. In that
                // state the round-trip `endStream` makes cannot build its result
                // (no live context ⇒ no DataFactory) and throws. Letting that
                // escape a `finally` is doubly wrong — it masks whatever really
                // ended the flow, and it lands uncaught on the collector's
                // dispatcher, where a test runner attributes it to an unrelated
                // test. Teardown failure is not actionable by the collector, so
                // swallow it; cancellation still propagates normally.
                try {
                    interactor.endStream(listener)
                } catch (_: Throwable) {
                }
            }
        }
    }
}

/**
 * Signal-only observation: `Unit` per change to the observed list, **no
 * initial emission and no payload state**. Registers with
 * `stream(signalOnly = true)`, so the proxy never serialises the initial
 * full-list emission (for a large folder that was the whole list over IPC,
 * parsed client-side just to be dropped) — subsequent per-item change
 * emissions arrive as usual and each one becomes a `Unit` tick.
 *
 * This is the observer counterpart to [flow]: [flow] is a *state* stream
 * (fold + full-snapshot emissions, initial included), [changes] is an
 * *invalidation* stream (the consumer re-reads what it needs, e.g. a paging
 * window). Keeping them separate preserves [flow]'s snapshot contract.
 *
 * ## Why a failed subscribe is retried rather than terminal
 *
 * A subscribe that *fails* — the proxy is down, or its session was refused
 * because the two sides disagreed on a schema version — used to complete this
 * flow, which is a one-way door: `setInitialResult(Failure)` delivers
 * `start(error)` **and** `end(error)`, so the consumer's `finally` calls
 * `endStream`, the provider drops its entry, and nothing is left for
 * `DefaultInteractorProvider.resubscribeAll` to replay (it skips failed
 * subscribes for exactly that reason). The transport reconnecting could
 * therefore never bring the read back; only restarting the *client* process
 * rebuilt it. Observed as a Drive file listing that stayed empty after Control
 * restarted, long after Drive's session had been re-admitted.
 *
 * R1's reset/replay covers streams that were healthy when the proxy died. This
 * covers the other half: a subscription that never took. Each attempt builds a
 * *fresh* request object and interactor (the dead one is unreachable), backing
 * off 1s → 2s → 4s → 8s → 15s and resetting once a subscribe lands. A retry
 * that succeeds emits a tick, because arbitrary changes were missed while the
 * read was down — same reasoning as [StreamSignal.Reset].
 *
 * A stream that ends *cleanly* is not retried: that is the observed list
 * genuinely being done, not a failure to observe it.
 */
fun <T : Data> TList<T>.changes(
    context: TContext? = null,
    /**
     * Emit a tick as soon as the subscription is live, not only after a *re*-subscribe.
     *
     * ⚠ Closes a real staleness window. Registering the observer is a round-trip to the proxy, so a
     * write that lands between "the consumer subscribed" and "the proxy registered the listener" is
     * signalled to nobody and is simply lost — the listing then stays stale until some *later*
     * change happens to arrive. A consumer that pairs this flow with its own unconditional initial
     * load (`pagingSource` does) cannot see the gap either, because that first load is what makes
     * the screen look alive.
     *
     * Off by default: for a consumer whose initial read is driven by this flow alone, an extra tick
     * at subscribe is a duplicate. On for the ones that load first and observe second, where it is
     * the catch-up — and for `:core-paging`'s keyset source, which since `photos-app.md` §38 does NOT
     * load first: it waits for this tick and reads once, because reading first and re-reading here
     * paid the Photos feed's first page twice (and Control finished the cancelled read anyway).
     */
    emitOnSubscribe: Boolean = false,
    /**
     * Emit a tick when the **first** subscribe attempt fails, too.
     *
     * ⚠⚠ For a consumer whose first read waits for [emitOnSubscribe]'s tick. A failed subscribe is
     * retried silently (see above) and ticks only once one lands, so without this a consumer gated
     * on the tick would sit on "loading" for as long as the proxy is down — when the read it is
     * holding back is exactly what would have failed and told the user so. With it, that read runs
     * at once and fails as it always did, and the retry's own tick re-reads once a subscribe lands.
     *
     * Only the first attempt: later failures of the same run would each re-issue a read that is
     * certain to fail. Off by default.
     */
    emitOnFailedSubscribe: Boolean = false,
): Flow<Unit> {
    val source = this
    return flow {
        // Resolved the same way changesOnce resolves it, but out here too: the retry wait needs
        // the context to find its ProxyConnectivity. Not an error when absent — a source that is
        // not itself a TContext fails inside changesOnce, where it always did.
        val actualContext = context ?: (source as? TContext)
        // Two counters, because a reconnect resets one and must not reset the other: [attempt]
        // records *that* this read was broken (a landed subscribe then owes the consumer a tick —
        // arbitrary changes were missed), while [backoff] records only how long to wait next.
        var attempt = 0
        var backoff = 0
        // Whether any subscribe attempt has answered yet, either way — what scopes
        // [emitOnFailedSubscribe] to the first one.
        var answered = false
        while (true) {
            var interrupted = false
            source.changesOnce<T>(context).collect { signal ->
                when (signal) {
                    is StreamSignal.TerminalError -> {
                        interrupted = true
                        if (emitOnFailedSubscribe && !answered) emit(Unit)
                        answered = true
                    }
                    is StreamSignal.Subscribed -> {
                        answered = true
                        // `attempt > 0` = a re-subscribe, which always ticks (changes were missed
                        // while the read was down). [emitOnSubscribe] extends the same reasoning to
                        // the FIRST subscribe: changes can be missed before it lands, too.
                        if (emitOnSubscribe || attempt > 0) emit(Unit)
                        attempt = 0
                        backoff = 0
                    }
                    // A signal-only subscriber keeps no state to clear, but a reconnect means
                    // arbitrary changes were missed while the transport was down — so it must be
                    // reported as a change, or a refetch-on-signal consumer would never re-read.
                    is StreamSignal.Reset -> emit(Unit)
                    is StreamSignal.Event -> when (val element = signal.element) {
                        is StreamElement.Value, is StreamElement.Removed -> emit(Unit)
                        is StreamElement.End -> if (element.error != null) interrupted = true
                    }
                    // Never produced by [changesOnce]'s listener (per-element catch-up).
                    is StreamSignal.CatchUp -> emit(Unit)
                }
            }
            if (!interrupted) return@flow
            attempt++
            backoff++
            if (awaitNextSubscribeAttempt(actualContext, backoff)) backoff = 0
        }
    }
}
