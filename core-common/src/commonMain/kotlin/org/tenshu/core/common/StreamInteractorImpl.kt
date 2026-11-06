package org.tenshu.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.Data
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamInteractor
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getDataIdHash
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.getDataIdHash
import kotlin.concurrent.Volatile

/**
 * @param context the client `TContext`, used only to resolve a buffered element's row identity —
 * see [buffer]. Optional so a test double can omit it; without it the buffer still replays
 * faithfully, it just cannot compact.
 */
class StreamInteractorImpl<T : Data>(
    private val onEndStream: suspend () -> Unit,
    private val onCleanup: suspend (StreamInteractorImpl<T>) -> Unit,
    private val context: TContext? = null,
) : StreamInteractor<T>, StreamDispatcher<T> {

    override var initialResult: TResult<Data?>? = null
        private set

    private val mutex = Mutex()

    private val callbacks = mutableListOf<StreamCallback<T>>()

    /**
     * What a **late** subscriber is caught up from, since it registers after the subscribe and so
     * cannot be sent a snapshot of its own (`core-client-resubscription.md` R3).
     *
     * ⚠ It must be a faithful event log, which means **removals belong in it too**. Buffering only
     * the additions replayed a history in which nothing was ever deleted, so a second collector of
     * the same relation — same callstack ⇒ same `streamId` ⇒ same interactor — rendered rows the
     * first one had already seen deleted.
     *
     * Entries are keyed by row identity where one can be resolved, and a new event for a row
     * **replaces** that row's previous one: per-row last-wins is exactly the semantics `merge` and
     * `remove` apply on replay, so the collapsed log converges to the same list while bounding the
     * buffer by the number of distinct rows rather than by the number of changes the stream has
     * ever carried. The initial snapshot has no single row identity (its payload is the whole
     * list), so it is never compacted and stays at the front where the replay needs it.
     *
     * Bounded by **live** rows rather than rows *ever seen* — see [compactRemovals] for the part
     * of that which is not obvious.
     */
    private val buffer = mutableListOf<BufferedElement<T>>()

    private class BufferedElement<T : Data>(val rowId: Long?, val element: StreamElement<T>)

    /**
     * Row ids carried by the buffer's uncompacted (whole-list) payloads — i.e. the rows a late
     * subscriber would receive from the snapshot before any per-row event is replayed to it.
     *
     * Computed at most once per subscribe run and only if a removal ever needs it; `null` means
     * "not computed yet". Invalidated wherever the set of uncompacted payloads can change, which
     * is exactly two places: a new one entering [buffer], and [prepareForResubscribe] clearing it.
     */
    private var snapshotRowIds: Set<Long>? = null

    /**
     * Buffer size at which the next [compactRemovals] runs. Doubles after each pass so compaction
     * is amortised O(1) per dispatch rather than an O(buffer) scan on every removal once a stream
     * is legitimately large.
     */
    private var compactionFloor = COMPACTION_FLOOR

    private fun rowIdOf(element: StreamElement<T>): Long? = when (element) {
        is StreamElement.Removed -> element.removedId.getDataIdHash()
        is StreamElement.Value -> context?.let { element.payload.getDataIdHash(it) }
        else -> null
    }

    private var refCount = 0

    /** Read by the provider without this class's lock; set after [endSent], see [pendingEnd]. */
    @Volatile
    internal var isClosed = false
        private set

    /** Set, under [mutex] and before [isClosed], by the two paths that send an `EndStream`. */
    @Volatile
    private var endSent = false
    private val endDone = CompletableDeferred<Unit>()

    /**
     * This stream's `EndStream` round trip while it is still in flight; `null` once it returned
     * (or failed), and for a stream that never sent one (ended by the proxy, or still live).
     *
     * ⚠⚠ What `DefaultInteractorProvider` makes the next subscription of the same id wait for
     * before sending its start. The proxy removes a listener **by stream id**, so an end processed
     * after the next start removes the *new* subscription's listener — and until it is processed,
     * the old registration is still the live one there. Seen with [isClosed]: [endSent] is written
     * first, so a reader that sees the stream closed by an end also sees that the end is owed.
     */
    internal val pendingEnd: Deferred<Unit>?
        get() = if (endSent && !endDone.isCompleted) endDone else null

    /** Sends the end, then marks it done whatever came of it — a failed end must not hold a successor. */
    private suspend fun sendEnd() {
        try {
            onEndStream()
        } finally {
            endDone.complete(Unit)
        }
    }
    private var isEnded = false
    private var endElement: StreamElement.End<T>? = null

    /**
     * ⚠ Seeding is **not** skipped for a stream that has already ended, only for one torn down by
     * [endStream]. The two are both `isClosed` and they are not the same thing.
     *
     * A proxy can push the terminal element before the subscribing call has even returned the
     * result seeded here: `DefaultInteractorProvider.buildStreamInteractor` registers the
     * interactor, *then* calls the op, *then* seeds — and the proxy-side collector is already
     * running by the time the call returns. A stream that ends immediately (a restarted proxy's
     * empty in-memory holder, an op that finds nothing to stream) therefore routinely races the
     * seed and wins whenever this thread is descheduled in between.
     *
     * Treating that as "closed, nothing to do" stranded the stream permanently: no initial result,
     * so [registerCallback] had nothing to replay either, and the consumer's flow hung open with no
     * element, no end and no failure — indistinguishable from a stream that is merely quiet. A
     * stream closed by [endStream] is the other case: its consumer is already gone and there is
     * genuinely nothing left to deliver.
     */
    override suspend fun setInitialResult(result: TResult<Data?>) {
        val endToReplay = mutex.withLock {
            if (initialResult != null || (isClosed && !isEnded)) return@withLock null
            initialResult = result
            // Delivered under the lock — see [dispatchElement] for why.
            if (result is TResult.Failure<*>) {
                callbacks.forEach {
                    it.start(result.error)
                    it.end(result.error)
                }
            } else {
                callbacks.forEach { callback ->
                    callback.start(null)
                    buffer.forEach { callback.element(it.element) }
                    endElement?.let {
                        callback.element(it)
                        callback.end(it.error)
                    }
                }
            }
            endElement
        }

        // The terminal landed before the seed, so [dispatchElement] could not run the cleanup that
        // normally follows it (it has no initial result to deliver against). Run it here instead,
        // now that the stream really has been delivered, so the provider drops its entry.
        if (endToReplay != null) onCleanup(this)
    }

    override suspend fun registerCallback(callback: StreamCallback<T>) {
        mutex.withLock {
            // An ended stream still accepts subscribers: the terminal may have arrived before this
            // one did (see [setInitialResult]), and it is precisely what it needs to be told.
            if (isClosed && !isEnded) return@withLock
            refCount++
            // A joiner: the stream is live for someone else, so what the buffer holds is history.
            val joiner = callbacks.isNotEmpty()
            callbacks.add(callback)
            // The replay is delivered under the lock — see [dispatchElement] for why. A callback
            // registered here therefore sees the whole buffer before any element dispatched after
            // it registered, which is the order the buffer promises.
            val res = initialResult ?: return@withLock
            if (res is TResult.Failure<*>) {
                callback.start(res.error)
                callback.end(res.error)
            } else {
                callback.start(null)
                // ⚠⚠ A joiner is handed its catch-up as ONE batch ([StreamCallback.catchUp]): the
                // buffer opens with the snapshot from when the stream first subscribed, and a
                // consumer rendering element by element showed that first. The stream's own first
                // subscriber keeps per-element delivery — its buffer is its own fresh history, and
                // `@defer`'s snapshot-then-patch frames depend on it.
                if (joiner) callback.catchUp(buffer.map { it.element }) else buffer.forEach { callback.element(it.element) }
                endElement?.let {
                    callback.element(it)
                    callback.end(it.error)
                }
            }
        }
    }

    /**
     * End and drop this stream **if no consumer holds it**; true when it did.
     *
     * ⚠ For a subscribe whose start was cancelled (`DefaultInteractorProvider.buildStreamInteractor`):
     * `TList.flow` and the other consumers take the interactor *before* the try/finally that calls
     * [endStream], so a caller cancelled during the start never registers and never ends. Without
     * this the entry stayed registered with no reference, holding its seeded Failure, its
     * proxy-side listener never told to stop, and the next subscriber of the same chain reused it and
     * was handed that Failure. A consumer that already registered (a joiner that reused the entry
     * between allocate and seed) keeps it: then this does nothing.
     */
    internal suspend fun abandonIfUnreferenced(): Boolean {
        val abandon = mutex.withLock {
            if (refCount > 0 || isClosed) {
                false
            } else {
                endSent = true
                isClosed = true
                true
            }
        }
        if (abandon) {
            sendEnd()
            onCleanup(this)
        }
        return abandon
    }

    override suspend fun endStream(callback: StreamCallback<T>) {
        val shouldEnd = mutex.withLock {
            if (callbacks.remove(callback)) {
                refCount--
            }
            if (refCount <= 0 && !isClosed) {
                endSent = true
                isClosed = true
                true
            } else false
        }
        if (shouldEnd) {
            sendEnd()
            onCleanup(this)
        }
    }

    /**
     * Prepare this stream to be re-subscribed against a **restarted proxy**
     * (`docs/proposals/core-client-resubscription.md` R1). Returns `false` when the stream is no
     * longer live and must be skipped.
     *
     * Drops the buffered elements/[endElement] — they are deltas relative to a proxy that no
     * longer exists, so replaying them to a callback registered after the reconnect would resurrect
     * stale rows — and tells every current callback to discard its own accumulated state via
     * [StreamCallback.reset].
     *
     * Deliberately does **not** touch [initialResult]. The initial payload reaches consumers as
     * stream *elements*, not through that field (see `ListStreamImplementation`), and
     * [dispatchElement] only requires it to be a non-null `Success` — which it already is from the
     * original subscribe. So the write-once contract stays intact and there is no re-seed to get
     * wrong; a `Failure` initial result means the consumer already terminated, hence the guard.
     *
     * Called *before* the start callstack is re-sent, so the reset is ordered ahead of the
     * snapshot it invalidates.
     */
    suspend fun prepareForResubscribe(): Boolean {
        mutex.withLock {
            if (isClosed || isEnded) return false
            val res = initialResult
            if (res == null || res is TResult.Failure<*>) return false
            buffer.clear()
            snapshotRowIds = null
            compactionFloor = COMPACTION_FLOOR
            endElement = null
            callbacks.forEach { it.reset() }
        }
        return true
    }

    /**
     * Drop buffered removals that no longer cancel anything, which is what keeps [buffer] bounded
     * by **live** rows rather than by rows *ever seen*.
     *
     * Per-row last-wins already collapses repeated edits, but a removal could not simply be
     * dropped: it may be the only thing cancelling a row inside the initial snapshot, which is
     * replayed whole and cannot be rewritten. So every removal was retained, and a long-lived
     * stream over a high-churn row set accumulated one entry per row it had ever carried — a row
     * added and deleted after the snapshot left a tombstone for a row no replay would ever mention.
     *
     * The missing distinction is simply *whether the snapshot contains that row*. It does not, for
     * anything added after it, and a removal whose row appears in no uncompacted payload cancels
     * nothing on replay — its own `Value` was already displaced from the buffer by the same
     * last-wins rule. Those are dropped; removals of snapshot rows are kept, and are bounded by
     * the snapshot's size rather than by time.
     *
     * ⚠ Costs one `getDataIdHash` pass over the snapshot's rows, once per subscribe run — the same
     * work a replaying subscriber's `merge` does anyway, and it runs under [mutex], which is why it
     * is deferred behind [compactionFloor] instead of being paid by every stream that ever sees a
     * removal. Without a [context] no identity can be resolved at all, so nothing is dropped:
     * over-retention is a bounded memory cost, and dropping the wrong tombstone resurrects a
     * deleted row.
     */
    private fun compactRemovals() {
        val ctx = context ?: return
        val ids = snapshotRowIds ?: uncompactedRowIds(ctx).also { snapshotRowIds = it }
        buffer.removeAll { entry ->
            entry.element is StreamElement.Removed && entry.rowId != null && entry.rowId !in ids
        }
    }

    /** Row ids reachable through the buffer's whole-list payloads. See [snapshotRowIds]. */
    private fun uncompactedRowIds(ctx: TContext): Set<Long> = buildSet {
        buffer.forEach { entry ->
            if (entry.rowId != null) return@forEach
            val payload = (entry.element as? StreamElement.Value<T>)?.payload ?: return@forEach
            // The snapshot arrives as one element whose payload is the whole list — the same shape
            // `TList.flow` unpacks with `payload is List<*>`.
            if (payload is List<*>) {
                payload.forEach { row -> (row as? Data)?.getDataIdHash(ctx)?.let { add(it) } }
            }
        }
    }

    /**
     * ⚠⚠ Callbacks are invoked **under [mutex]**, here and in [registerCallback] /
     * [setInitialResult] / [prepareForResubscribe]. Deciding *what* to deliver under the lock and
     * delivering *outside* it — the shape this had until 2026-09-14 — let a subscriber that was
     * registering while a live element landed receive them reversed: `registerCallback` took its
     * replay (the snapshot) and released the lock, `dispatchElement` entered, found the callback
     * already registered and delivered the patch, and only then did the replay's `element(snapshot)`
     * run. The observed shape was a details row whose first emission already carried its deferred
     * preview (`docs/tech-debt.md`, gate-flake entry, sighting 2026-09-14, second of the day);
     * `StreamInteractorImplDeliveryOrderTest` forces it. Holding the lock across delivery is safe
     * because [StreamCallback]'s methods are not suspending — they cannot re-enter this class —
     * and every implementation only enqueues (`Stream.kt` `trySend`s into an unbounded channel).
     * [onCleanup] and [onEndStream] stay outside: they take the provider's lock.
     */
    override suspend fun dispatchElement(element: StreamElement<*>) {
        @Suppress("UNCHECKED_CAST")
        val typedElement = element as StreamElement<T>

        val shouldCleanup = mutex.withLock {
            if (isClosed && typedElement !is StreamElement.End) return@withLock false

            if (typedElement is StreamElement.End) {
                endElement = typedElement
            } else {
                val rowId = rowIdOf(typedElement)
                if (rowId != null) buffer.removeAll { it.rowId == rowId }
                // A payload with no resolvable row identity is a whole-list snapshot, so the
                // cached set of "rows the replay starts from" no longer describes the buffer.
                if (rowId == null) snapshotRowIds = null
                buffer.add(BufferedElement(rowId, typedElement))
                if (typedElement is StreamElement.Removed && buffer.size >= compactionFloor) {
                    compactRemovals()
                    compactionFloor = maxOf(COMPACTION_FLOOR, buffer.size * 2)
                }
            }

            val isEnd = typedElement is StreamElement.End
            val cleanup = if (isEnd && !isEnded) {
                isEnded = true
                isClosed = true
                true
            } else false

            val res = initialResult
            if (res == null || res !is TResult.Success<*>) return@withLock false
            callbacks.forEach { it.element(typedElement) }
            if (cleanup) {
                val end = typedElement as StreamElement.End<T>
                callbacks.forEach { it.end(end.error) }
            }
            cleanup
        }

        if (shouldCleanup) onCleanup(this)
    }

    private companion object {
        /**
         * Buffer entries before the first compaction pass. Chosen to be comfortably above any
         * normal stream's steady state, so the common case never pays the snapshot hash pass —
         * this exists for the long-lived high-churn stream, not for the typical listing.
         */
        const val COMPACTION_FLOOR = 64
    }
}
