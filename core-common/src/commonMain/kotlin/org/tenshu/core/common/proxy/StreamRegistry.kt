package org.tenshu.core.common.proxy

import kotlinx.atomicfu.locks.reentrantLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.Filter as FilterOp
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.SortBy as SortByOp
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Traverse as TraverseOp
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.StreamElementRemovedDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.fields
import org.tenshu.core.id
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.schema.isSubtypeOf

/**
 * Owns per-session stream lifecycle: which `ArrayDatabaseReference`s have
 * listeners, which stream IDs are subscribed to which references, and what
 * items each stream has most recently emitted.
 *
 * Part of the decomposition in
 * `docs/adr/0008-execution-context-decomposition.md`: each session's
 * [ExecutionContext] holds a `StreamRegistry` instance, and
 * [ExecutionContext.withEvaluationScope] shares it with per-request derived
 * contexts (mirroring the `referenceResolver` and `observerRegistry` propagation).
 *
 * `setDatabaseListener` takes [ExecutionContext] as a parameter because its
 * inner `onUpdate` closure recurses into [evaluateCallstack] (which requires
 * a context), reads through [ExecutionContext.getArrayData] /
 * [ExecutionContext.getDataId], and writes back to
 * [ExecutionContext.clientConnection]. Passing the context as a parameter is
 * the same shape resolveProperty uses (ADR 0008) and keeps the registry
 * itself stateless with respect to the context.
 *
 * ⚠⚠ **Every map below is guarded by [state], and the registry is reached from many threads at
 * once.** A screen that `combine`s several reads subscribes them concurrently, each on its own
 * `Dispatchers.Default` worker, and nothing above this class serialises one session's requests —
 * the in-process executor runs them where they land. Unguarded, two registrations writing
 * [streamIdToRegistration] at once could lose one token: that stream's initial evaluation then
 * found itself "not live" and **sent no snapshot**, so the read never emitted at all and stayed
 * blank until a later subscription of the chain. Contacts on iOS lost 12 of 150 lists this way;
 * the other maps lose an update callback or a ref's listener set the same way, which is a stream
 * that never hears its invalidation (`core-client-resubscription.md` §4.9,
 * `StreamRegistryConcurrentRegistrationTest`).
 *
 * ⚠ The lock is never held across a suspension or a send — only around map reads and writes and
 * the database's listener (un)registration, which it must cover so a ref's 0↔1 count and its
 * handle move together. That makes the order **registry → database**; the database fires its
 * listeners under its own lock, so [triggerUpdate] must not take this one — it reads
 * [listenersByArray], an immutable copy republished under the lock, and each launched update looks
 * its callback up under the lock from its own coroutine.
 *
 * ⚠⚠ That relies on the session scope **dispatching**. Under `Dispatchers.Unconfined` (or any
 * dispatcher whose `isDispatchNeeded` is false) the launched update runs inline on the writer's
 * thread, still inside the database's lock, and its first `guarded` inverts the order. Production
 * session scopes are `Dispatchers.Default`-based; the unit tests that use an unconfined scope do so
 * over lock-free fake databases, which is why they cannot deadlock — and also why they cannot
 * catch a scope that stopped dispatching.
 */
@OptIn(ExperimentalAtomicApi::class)
class StreamRegistry {

    private val state = reentrantLock()

    /** Nesting depth of [guarded] on the holding thread; read and written only under [state]. */
    private var holdDepth = 0

    /** [refToStreamListeners] changed in the current hold — republished when it ends. Under [state]. */
    private var listenersDirty = false

    private inline fun <R> guarded(block: () -> R): R {
        state.lock()
        holdDepth++
        try {
            return block()
        } finally {
            // Once per outermost hold, not per change: a re-evaluation can register or drop many
            // refs in one step, and each copy is O(arrays).
            if (--holdDepth == 0) {
                if (listenersDirty) {
                    listenersDirty = false
                    publishListeners()
                }
                if (pumpsDirty) {
                    pumpsDirty = false
                    pumpsByStream.store(streamIdToPump.toMap())
                }
            }
            state.unlock()
        }
    }

    private val streamIdToItems = mutableMapOf<Long, List<Data>>()
    private val streamIdToRef = mutableMapOf<Long, ArrayDatabaseReference>()
    private val streamIdToNestedRefs = mutableMapOf<Long, MutableSet<ArrayDatabaseReference>>()
    private val streamIdToUpdateCallback =
        mutableMapOf<Long, suspend (isInitial: Boolean) -> Unit>()
    private val refToStreamListeners = mutableMapOf<Long, MutableSet<Long>>()

    /**
     * [refToStreamListeners] as an immutable snapshot, for [triggerUpdate]: it runs inside the
     * database's lock, where taking [state] would invert the registry → database order. Republished
     * at the end of every hold of [state] that changed the map ([listenersDirty]).
     */
    private val listenersByArray = AtomicReference<Map<Long, Set<Long>>>(emptyMap())

    /** ⚠ Under [state] only. */
    private fun publishListeners() {
        listenersByArray.store(refToStreamListeners.mapValues { it.value.toSet() })
    }
    private val refToStreamCount = mutableMapOf<Long, Int>()

    /** Each signal-only stream's [SignalPump]; a stream without one takes the full path. */
    private val streamIdToPump = mutableMapOf<Long, SignalPump>()

    /** [streamIdToPump] changed in the current hold — republished when it ends. Under [state]. */
    private var pumpsDirty = false

    /** [streamIdToPump] as an immutable snapshot, for [triggerUpdate] — same reason as [listenersByArray]. */
    private val pumpsByStream = AtomicReference<Map<Long, SignalPump>>(emptyMap())
    // This registry's array-listener handle per array hash (from `database.setArrayListener`), so
    // teardown removes *this* session's registration without disturbing another session's.
    private val refToListenerHandle = mutableMapOf<Long, Long>()

    // ---- deferred-property patching (`core-deferred-properties.md` §5) ----
    // Per-stream mutex shared between the onUpdate closure and the patch path
    // so a patch never interleaves with a full re-evaluation's read-diff-write
    // of `streamIdToItems` (Q3: last-write-wins per row, serialised).
    private val streamIdToMutex = mutableMapOf<Long, Mutex>()

    /**
     * Which registration of each stream id is the live one: a fresh token per [setDatabaseListener],
     * dropped by [removeDatabaseListener] / [clear].
     *
     * ⚠⚠ An update already running when its stream is ended must not send. `triggerUpdate` launches
     * the evaluation on `sessionScope`, and an `EndStream` neither waits for it nor stops it. Stream
     * ids are callstack hashes, so the client may already have registered the next subscription of
     * the same chain by the time that update finishes. The update's elements are produced after
     * that registration, so the client's receipt filter (`StreamElementReceipts`) passes them, and
     * a row the update read before it was deleted reached the new read and, merged, stayed.
     * Each update checks its token after evaluating and before any side effect, so `EndStream` stays
     * instant and never waits on an evaluation (`StreamRegistryTest.anUpdateStillRunningWhenItsStreamEndsSendsNothing`).
     */
    private val streamIdToRegistration = mutableMapOf<Long, Any>()

    private fun isLive(streamId: Long, registration: Any) = guarded { streamIdToRegistration[streamId] === registration }
    private val streamIdToDeferredJobs = mutableMapOf<Long, MutableSet<Job>>()

    /** The job a push stream's implementation runs for each stream id — see [ownStreamJob]. Under [state]. */
    private val streamIdToOwnedJob = mutableMapOf<Long, Job>()

    // Dedupe: one in-flight resolution per (stream, row, property). A later
    // re-evaluation that re-writes the same marker while a resolution runs
    // must not double-resolve; once the patch lands the key clears and a
    // fresh marker schedules again.
    private val inFlightPatchKeys = mutableSetOf<String>()

    // Bounded parallelism across ALL streams — deferred resolvers do real IO
    // (that's why they're deferred); an unbounded fan-out on a 2k-row folder
    // would recreate the very stampede this feature removes.
    private val deferredSemaphore = Semaphore(DEFERRED_RESOLUTION_PARALLELISM)

    private val _activeStreamCount = MutableStateFlow(0)

    /**
     * Number of streams currently registered via [setDatabaseListener] and not yet
     * removed via [removeDatabaseListener] / [clear]. Observable so consumers can
     * react to the idle ⇄ hot transition — `SyncScheduler` drops to a fast tick
     * while this is non-zero (`docs/proposals/core-background-processing.md` Layer 1).
     */
    val activeStreamCount: StateFlow<Int> = _activeStreamCount.asStateFlow()

    suspend fun setDatabaseListener(
        executionContext: ExecutionContext,
        streamId: Long,
        reference: ArrayDatabaseReference,
        executionStack: List<Data>,
        signalOnly: Boolean = false,
    ): Data? {
        // A fresh subscription under this id: nothing the transport held for an earlier one applies.
        executionContext.clientConnection.forgetStream(streamId)
        val registration = Any()

        // Serialises concurrent listener fires for this stream — without it, two
        // simultaneous fires can both observe an empty streamIdToItems and emit
        // the same item twice. Held in `streamIdToMutex` (not a closure local) so
        // the deferred-patch path serialises against full re-evaluations too.
        val updateMutex = guarded {
            streamIdToRegistration[streamId] = registration
            streamIdToMutex.getOrPut(streamId) { Mutex() }
        }
        afterRegistrationStamped?.invoke()

        // Evaluates the stream's chain once, recording every array it reads into [into].
        val evaluate: (into: MutableSet<ArrayDatabaseReference>) -> Data? = { into ->
            into.add(reference)
            executionContext.referenceResolver.collectingReferences(into = into) {
                // Evaluate the deferred stack against the *reference*
                // (not a pre-materialised list) so a leading
                // `drop`/`take` prefix hits the window fusion in
                // [evaluateCallstack] — the page-load stream then
                // deserialises only the page window (and gains the
                // `captureProjection` pushdown). An empty stack
                // materialises the full array in the final
                // materialisation step.
                //
                // `docs/proposals/core-error-channel.md` §6 Phase 4:
                // evaluateCallstack returns the value directly and throws
                // TenshuException on typed failure. The callers' catch blocks
                // wrap it into a StreamElement.End error.
                val value = evaluateCallstack(
                    executionContext = executionContext,
                    contextData = reference.copy(executionStack = emptyList()),
                    callstack = executionStack,
                    allowDeferring = false
                )

                if (value is ArrayDatabaseReference) {
                    executionContext.getArrayData(value)
                        ?.toList()
                        ?.toTList(executionContext.context)
                } else if (value is TList<*>) {
                    value
                } else {
                    listOfNotNull<Data>(value).toTList(executionContext.context)
                }
            }
        }

        if (signalOnly) {
            return setSignalListener(executionContext, streamId, reference, registration, updateMutex, executionStack, evaluate)
        }

        val onUpdate: suspend (Boolean) -> Unit = onUpdate@{ isInitial ->
            updateMutex.lock()
            try {
                val currentCapturedRefs = mutableSetOf<ArrayDatabaseReference>()
                val processedDataResult: Data? = evaluate(currentCapturedRefs)

                // Ended (or re-registered) while this evaluated: anything sent now, or any state
                // written, would belong to a subscription that no longer exists — see
                // [streamIdToRegistration].
                if (!isLive(streamId, registration)) return@onUpdate

                if (processedDataResult != null) {
                    if (processedDataResult.schema.isSubtypeOf("tenshu/core/error")) {
                        executionContext.clientConnection.send(endElement(executionContext, streamId, processedDataResult))
                        return@onUpdate
                    }

                    // Materialise any `PendingOnDemandProperty` placeholders
                    // left by `Select` BEFORE emission — otherwise the
                    // unresolved marker crosses the IPC boundary and the
                    // SDK-side parser would see an inert placeholder
                    // instead of the value. `Get` does
                    // this for one-shot terminals; the same helper handles
                    // the per-element walk for streams.
                    val rawItems = (processedDataResult as? TList<*>)?.toList()
                        ?: listOf(processedDataResult)
                    val newItems = rawItems.map { item ->
                        materialisePendingOnDemandProperties(
                            executionContext,
                            item as? Data
                        ) ?: item
                    }
                    // Checked again: materialising on-demand properties can suspend.
                    if (!isLive(streamId, registration)) return@onUpdate
                    val allCurrentRefs = currentCapturedRefs + findDatabaseReferences(processedDataResult)
                        .filterIsInstance<ArrayDatabaseReference>()

                    // One guarded step: the liveness check, the diff baseline and the ref
                    // bookkeeping. A stream ended since the check above must not re-register refs
                    // (a listener nobody would ever remove) — so the check is repeated in here.
                    val oldItems = guarded {
                        if (!reconcileRefsLocked(executionContext, streamId, registration, allCurrentRefs)) {
                            return@onUpdate
                        }
                        if (isInitial) emptyList() else streamIdToItems[streamId] ?: emptyList()
                    }

                    if (isInitial) {
                        executionContext.clientConnection.send(
                            StreamElementValueDto(
                                streamId,
                                executionContext.context.dataFactory().createList<Data>(newItems)
                            )
                        )
                    } else {
                        emitChanges(executionContext, streamId, oldItems, newItems)
                    }
                    guarded { if (isLive(streamId, registration)) streamIdToItems[streamId] = newItems }

                    // Deferred markers that survived materialisation get a
                    // background resolution + per-row patch.
                    scheduleDeferredResolutions(executionContext, streamId, newItems)
                }
            } catch (e: Throwable) {
                // If anything fails in the background update, we stop the stream.
                // `docs/proposals/core-error-channel.md` §6 Phase 4: unwrap typed
                // [TenshuException] to its [org.tenshu.core.TError]; anything else
                // becomes [InternalErrorDto] (the proxy-local catch-all shape).
                e.printStackTrace()
                val typedError = typedErrorOf(e)
                // An ended stream's failure has no subscription left to end — and would end the next.
                if (isLive(streamId, registration)) {
                    executionContext.clientConnection.send(endElement(executionContext, streamId, typedError))
                }
            } finally {
                updateMutex.unlock()
            }
        }

        // ⚠ Installed only if the stream is still the live registration: an `EndStream` processed
        // since the stamp above must not leave a listener, a callback and an active-stream count
        // behind for a subscription that no longer exists. `streamIdToRef` is set here, with the
        // count, so a removal before this point does not decrement a count this never raised.
        val installed = guarded {
            if (!isLive(streamId, registration)) return@guarded false
            streamIdToRef[streamId] = reference
            streamIdToUpdateCallback[streamId] = onUpdate
            registerRefForStream(executionContext, streamId, reference)
            _activeStreamCount.update { it + 1 }
            true
        }
        if (!installed) return null

        onUpdate(true)

        return null
    }

    /**
     * Test seam: runs between [setDatabaseListener]'s registration stamp and its install, the window
     * a concurrent [removeDatabaseListener] can land in. `null` in production.
     */
    internal var afterRegistrationStamped: (() -> Unit)? = null

    /**
     * The signal-only half of [setDatabaseListener] (`changes()`): the stream answers "something it
     * reads may have changed", never *what*.
     *
     * ⚠⚠ **A write costs one signal, not one evaluation.** The full path re-evaluates the chain and
     * diffs it against the last item list on every write to any array it reads; for a whole-tree
     * `root.files.traverse().changes()` (the Photos feed's observer) that is a walk of the realm per
     * write — ≈ 0.27 ms per file per write, inline on the tab's thread in the browser runtime
     * (`photos-app.md` §45.3). Here a write only marks the stream's [SignalPump] dirty; one drain
     * per burst sends one signal. Over-signalling is allowed (a write the reader would not show
     * still ticks it — the reader re-reads); silence across a real change is not.
     *
     * The chain is still evaluated — at subscribe, and again when a write could have changed
     * **which arrays** the stream reads ([mayOpenArrays]): a folder created inside an observed tree
     * brings arrays nobody listens to yet, and a write into them would otherwise never be heard.
     * The rediscovery registers the new arrays **before** the signal goes out, so a write landing in
     * one between the evaluation and its registration is covered by that same signal (the reader
     * re-reads after it).
     */
    private suspend fun setSignalListener(
        executionContext: ExecutionContext,
        streamId: Long,
        reference: ArrayDatabaseReference,
        registration: Any,
        updateMutex: Mutex,
        executionStack: List<Data>,
        evaluate: (MutableSet<ArrayDatabaseReference>) -> Data?,
    ): Data? {
        val pump = SignalPump(
            executionContext, streamId, reference.arrayId, registration, updateMutex, evaluate,
            viewDependsOnOtherRows = executionStack.any { it.id !in ROW_INDEPENDENT_OPS },
        )
        // Same install contract as the full path (see there).
        val installed = guarded {
            if (!isLive(streamId, registration)) return@guarded false
            streamIdToRef[streamId] = reference
            streamIdToPump[streamId] = pump
            pumpsDirty = true
            registerRefForStream(executionContext, streamId, reference)
            _activeStreamCount.update { it + 1 }
            true
        }
        if (!installed) return null

        // The subscribe discovery: registers every array the chain reads, and sends nothing — a
        // signal-only subscriber gets no initial emission. The subscribe confirmation the client
        // receives is sent after this returns, so a write before it is covered by the reader's own
        // first read.
        updateMutex.withLock { pump.discover() }
        return null
    }

    /**
     * One signal-only stream's coalescing state — see [setSignalListener].
     *
     * [request] runs on the writer's thread inside the database's lock (from [triggerUpdate]), so it
     * touches nothing but [pending]: a lock-free flag word plus the rows added since the last drain.
     * The drain runs on `sessionScope`, under the stream's [updateMutex] (shared with nothing else
     * here, since a signal-only stream schedules no deferred patches), and loops until no request
     * arrived during its last round — so a write that lands mid-drain is never dropped, only folded
     * into the next round.
     */
    private inner class SignalPump(
        private val executionContext: ExecutionContext,
        private val streamId: Long,
        private val rootArrayId: DataId,
        private val registration: Any,
        private val updateMutex: Mutex,
        private val evaluate: (MutableSet<ArrayDatabaseReference>) -> Data?,
        /**
         * Whether a row can enter the chain's view because of a write to ANOTHER row — a window, or
         * any op not known to be row-independent. Then any write can bring in rows the chain never
         * read, with arrays nobody listens to: deleting the first folder under `take(1).traverse()`
         * exposes the second, and so does re-saving a visible leaf so it sorts behind it under
         * `sortBy(…).take(1)`. Such a stream rediscovers on **every** write; one built only from
         * [ROW_INDEPENDENT_OPS] has read every row it can ever show, so only [mayOpenArrays]' rows
         * can change what it reads.
         */
        private val viewDependsOnOtherRows: Boolean,
    ) {
        private val pending = AtomicReference(Pending.IDLE)

        /** What the last discovery saw; `null` until one has completed (every add then rediscovers). */
        private val shape = AtomicReference<StreamShape?>(null)

        /** [added] is the row an add (or a re-save) wrote; `null` for a removal. */
        fun request(added: Data?) {
            while (true) {
                val old = pending.load()
                if (pending.compareAndSet(old, old.withSignal(added, rediscover = viewDependsOnOtherRows))) {
                    if (!old.running) executionContext.sessionScope.launch { drain() }
                    return
                }
            }
        }

        private suspend fun drain() {
            while (true) {
                val taken = pending.load()
                if (!taken.dirty) {
                    if (pending.compareAndSet(taken, Pending.IDLE)) return
                    continue
                }
                if (!pending.compareAndSet(taken, Pending.RUNNING)) continue
                val live = updateMutex.withLock {
                    if (!isLive(streamId, registration)) return@withLock false
                    val rediscover = taken.rediscover || taken.added.any { mayOpenArrays(it) }
                    if (rediscover && !discover()) return@withLock false
                    if (!isLive(streamId, registration)) return@withLock false
                    executionContext.clientConnection.send(signalElement())
                    true
                }
                // Ended (or failed and sent its End): nothing further belongs to this subscription.
                // A later registration of the same id installs a fresh pump.
                if (!live) {
                    pending.store(Pending.IDLE)
                    return
                }
            }
        }

        /**
         * Evaluate the chain and register exactly the arrays it read. `false` when the stream is
         * over — it ended meanwhile, or the evaluation failed and its End was sent. ⚠ Under
         * [updateMutex] only.
         */
        fun discover(): Boolean = try {
            val captured = mutableSetOf<ArrayDatabaseReference>()
            val result = evaluate(captured)
            when {
                !isLive(streamId, registration) -> false
                result == null -> {
                    shape.store(null)
                    true
                }
                result.schema.isSubtypeOf("tenshu/core/error") -> {
                    executionContext.clientConnection.send(endElement(executionContext, streamId, result))
                    false
                }
                else -> {
                    val all = captured + findDatabaseReferences(result).filterIsInstance<ArrayDatabaseReference>()
                    val reconciled = guarded { reconcileRefsLocked(executionContext, streamId, registration, all) }
                    if (reconciled) shape.store(StreamShape.of(result, all))
                    reconciled
                }
            }
        } catch (e: Throwable) {
            // As on the full path: a failed evaluation ends the stream.
            e.printStackTrace()
            if (isLive(streamId, registration)) {
                executionContext.clientConnection.send(endElement(executionContext, streamId, typedErrorOf(e)))
            }
            false
        }

        /**
         * Whether [added] could make the chain read an array it does not read now — and so needs a
         * rediscovery before the signal. ⚠⚠ **Errs towards `true`**: a wrong `true` costs one
         * evaluation, a wrong `false` is a stream that never hears writes inside the new arrays.
         *
         *  - a row whose type has a `TList` field (a folder: the arrays `traverse`/`searchAll`
         *    descend into, and any `it.someList` a predicate reads), or one carrying a database
         *    reference anywhere (the arrays the full path finds in its result);
         *  - a row of a type the last discovery never saw in the result — what the chain does with
         *    it is unknown, including an empty result (it has seen nothing);
         *  - any row, for a stream that reads per-entity relation arrays
         *    (`DataId.EntityPropertyDataId`): those are keyed by the row and appear on no field, so
         *    only an evaluation finds the new row's.
         */
        private fun mayOpenArrays(added: Data): Boolean {
            val seen = shape.load() ?: return true
            if (seen.followsRelations) return true
            val typeId = typeIdOf(added) ?: return true
            if (typeId !in seen.rowTypes) return true
            if (hasNestedListFields(typeId, executionContext)) return true
            return findDatabaseReferences(added).isNotEmpty()
        }

        /**
         * The tick: a `Removed` of the stream's **own root array id**, every time.
         *
         * ⚠⚠ Both halves of that are load-bearing:
         *  - **One fixed identity.** The client's per-stream replay buffer (`StreamInteractorImpl`)
         *    keeps one entry per row id, last wins; a signal with no identity (an empty-list
         *    `Value`) is kept as a whole-list snapshot instead, so every write would add an entry —
         *    a buffer growing for the life of the stream (`StreamLateSubscriberTest`). Under one
         *    id, a late joiner replays one tick.
         *  - **An id no row carries.** A `flow()` that shares this stream id (ids are callstack
         *    hashes) folds the element into its state; removing an array's id removes nothing.
         */
        private fun signalElement(): Data = StreamElementRemovedDto(streamId, rootArrayId)
    }

    /** [SignalPump]'s pending work, swapped whole by compare-and-set (identity). */
    private class Pending(
        val running: Boolean,
        val dirty: Boolean,
        val rediscover: Boolean,
        val added: List<Data>,
    ) {
        fun withSignal(row: Data?, rediscover: Boolean): Pending = when {
            this.rediscover || rediscover -> Pending(running = true, dirty = true, rediscover = true, added = emptyList())
            row == null -> Pending(running = true, dirty = true, rediscover = false, added = added)
            // A long burst stops collecting rows and simply rediscovers once.
            added.size >= MAX_PENDING_ADDED -> Pending(running = true, dirty = true, rediscover = true, added = emptyList())
            else -> Pending(running = true, dirty = true, rediscover = false, added = added + row)
        }

        companion object {
            val IDLE = Pending(running = false, dirty = false, rediscover = false, added = emptyList())
            val RUNNING = Pending(running = true, dirty = false, rediscover = false, added = emptyList())
        }
    }

    /** What a signal-only stream's last discovery read — the input to [SignalPump.mayOpenArrays]. */
    private class StreamShape(val rowTypes: Set<String>, val followsRelations: Boolean) {
        companion object {
            fun of(result: Data, refs: Set<ArrayDatabaseReference>): StreamShape {
                val rows = (result as? TList<*>)?.toList() ?: listOf(result)
                return StreamShape(
                    rowTypes = rows.mapNotNullTo(HashSet()) { (it as? Data)?.let(::typeIdOf) },
                    followsRelations = refs.any {
                        it.arrayId !is DataId.EntityFieldDataId && it.arrayId !is DataId.RelationDataId
                    },
                )
            }
        }
    }

    /**
     * Bring [streamId]'s registered arrays to exactly [refs]. `false`, touching nothing, when the
     * stream is no longer [registration] — an ended stream must not re-register a listener nobody
     * would ever remove. ⚠ Under [state] only.
     */
    private fun reconcileRefsLocked(
        executionContext: ExecutionContext,
        streamId: Long,
        registration: Any,
        refs: Set<ArrayDatabaseReference>,
    ): Boolean {
        if (!isLive(streamId, registration)) return false
        val currentStreamRefs = streamIdToNestedRefs.getOrPut(streamId) { mutableSetOf() }
        val addedRefs = refs - currentStreamRefs
        val removedRefs = currentStreamRefs - refs
        addedRefs.forEach { registerRefForStream(executionContext, streamId, it) }
        removedRefs.forEach { unregisterRefForStream(executionContext, streamId, it) }
        return true
    }

    private fun typedErrorOf(e: Throwable): Data = when (e) {
        // `docs/proposals/core-error-channel.md` §6 Phase 4: unwrap typed [TenshuException] to its
        // [org.tenshu.core.TError]; anything else becomes [InternalErrorDto] (the proxy-local
        // catch-all shape).
        is TenshuException -> e.error
        else -> InternalErrorDto(internalDetail = e.message)
    }

    private fun endElement(executionContext: ExecutionContext, streamId: Long, error: Data): Data =
        executionContext.context.dataFactory().createComposite<StreamElement<*>>(
            type = StreamElement.End.ID,
            // Explicit `<Data?>` is load-bearing: without it Kotlin's vararg inference picks the
            // second arg's concrete type for the array element (e.g. `TError[]`), and the
            // `TIntegerDto` returned by `createValue` then fails to store into that typed array at
            // runtime (`java.lang.ArrayStoreException`).
            fields = listOf<Data?>(
                executionContext.context.dataFactory().createValue("tenshu/core/number/integer", streamId),
                error
            )
        )

    /**
     * Binds [job] — the coroutine a **push** stream's implementation runs to feed [streamId] — to the
     * stream's lifetime: it is cancelled when the stream's `EndStream` arrives
     * ([removeDatabaseListener], which every `EndStream` reaches) and when the session ends ([clear]).
     *
     * ⚠⚠ Without it a push stream that stays open leaks: `EndStream` tears down only database
     * listeners, so the implementation's own collector would go on sending to a dead stream id for
     * the life of the session — once per subscribe (`inference-service.md` §30.2).
     * ⚠ A second job for the same id (a resubscribe) cancels the first: one id, one feeder.
     */
    fun ownStreamJob(streamId: Long, job: Job) {
        val previous: Job? = guarded { streamIdToOwnedJob.put(streamId, job) }
        // Cancelled outside the lock: a cancellation runs completion handlers, which take it again.
        if (previous !== job) previous?.cancel()
        job.invokeOnCompletion { guarded { if (streamIdToOwnedJob[streamId] === job) streamIdToOwnedJob.remove(streamId) } }
    }

    fun removeDatabaseListener(executionContext: ExecutionContext, streamId: Long) {
        executionContext.clientConnection.forgetStream(streamId)
        var jobs: List<Job> = emptyList()
        val owned: Job? = guarded { streamIdToOwnedJob.remove(streamId) }
        owned?.cancel()
        val wasRegistered = guarded {
            streamIdToRegistration.remove(streamId)
            val wasRegistered = streamIdToRef.remove(streamId) != null
            streamIdToUpdateCallback.remove(streamId)
            if (streamIdToPump.remove(streamId) != null) pumpsDirty = true
            val refs = streamIdToNestedRefs[streamId]?.toList() ?: emptyList()
            refs.forEach { unregisterRefForStream(executionContext, streamId, it) }
            streamIdToNestedRefs.remove(streamId)
            streamIdToItems.remove(streamId)
            jobs = dropDeferredResolutions(streamId)
            if (wasRegistered) _activeStreamCount.update { (it - 1).coerceAtLeast(0) }
            wasRegistered
        }
        // Cancelled outside the lock: a cancellation runs completion handlers, which take it again.
        jobs.forEach { it.cancel() }
    }

    fun clear() {
        guarded {
            streamIdToDeferredJobs.keys.toList().flatMap { dropDeferredResolutions(it) }
        }.forEach { it.cancel() }
        guarded { streamIdToOwnedJob.values.toList().also { streamIdToOwnedJob.clear() } }.forEach { it.cancel() }
        guarded { clearLocked() }
    }

    private fun clearLocked() {
        streamIdToItems.clear()
        streamIdToRef.clear()
        streamIdToNestedRefs.clear()
        streamIdToUpdateCallback.clear()
        streamIdToPump.clear()
        pumpsDirty = true
        streamIdToRegistration.clear()
        refToStreamListeners.clear()
        listenersDirty = true
        refToStreamCount.clear()
        streamIdToMutex.clear()
        inFlightPatchKeys.clear()
        _activeStreamCount.value = 0
    }

    /**
     * Walk freshly-emitted [items] for [PendingDeferredProperty] markers and
     * launch one bounded background resolution per (row, property) — each
     * completed resolution re-emits that row as an ordinary per-item
     * [StreamElementValueDto] patch (`core-deferred-properties.md` §4/§5).
     *
     * Accepted behaviour: a later array change re-runs the projection,
     * which re-writes fresh markers for still-deferred properties — the row
     * transiently re-emits with the property null, then re-patches. Resolver
     * caches (stored thumbnails, `PreviewCache`) make the re-resolution
     * cheap; carry-forward of already-patched values is a possible later
     * optimisation.
     */
    private fun scheduleDeferredResolutions(
        executionContext: ExecutionContext,
        streamId: Long,
        items: List<Data>,
    ) {
        items.forEach { item ->
            val entity = item as? Entity<*> ?: return@forEach
            entity.properties.entries.forEach entry@{ (keyRef, value) ->
                val marker = value as? PendingDeferredProperty ?: return@entry
                val key = patchKey(streamId, marker)
                if (!guarded { inFlightPatchKeys.add(key) }) return@entry
                val job = executionContext.sessionScope.launch {
                    try {
                        deferredSemaphore.withPermit {
                            resolveAndPatch(executionContext, streamId, keyRef.get(), marker)
                        }
                    } finally {
                        guarded { inFlightPatchKeys.remove(key) }
                    }
                }
                guarded { streamIdToDeferredJobs.getOrPut(streamId) { mutableSetOf() }.add(job) }
                job.invokeOnCompletion { guarded { streamIdToDeferredJobs[streamId]?.remove(job) } }
            }
        }
    }

    /**
     * Resolve one deferred marker and patch its row. The resolution itself
     * mirrors [materialiseEntityPlaceholders]'s per-marker path (DB parent →
     * `resolveDynamicProperty`) — deliberately NOT a re-dispatched
     * `EntitySelect`: any concurrent DB change already triggers a full
     * re-projection, so re-projecting the row's other fields buys nothing.
     *
     * Q1 (failed/null resolution): the patch DROPS the property key — the row
     * settles, the client reads `null`, and the next full re-evaluation
     * writes a fresh marker (natural retry). Q3 (race with a re-evaluation):
     * the patch applies only while the current row still carries a deferred
     * marker for this property, all under the stream's mutex.
     */
    private suspend fun resolveAndPatch(
        executionContext: ExecutionContext,
        streamId: Long,
        propertyKey: String,
        marker: PendingDeferredProperty,
    ) {
        val resolved: Data? = try {
            val parentData = executionContext.database.getData(marker.entityId)
            val typeRef = executionContext.context.schemaProvider()
                .getReference(marker.propertyId.get()) as? DynamicPropertyTypeReference
            if (parentData == null || typeRef == null) null
            else executionContext.resolveDynamicProperty(
                data = parentData,
                propertyReference = typeRef,
                params = marker.params
            )
        } catch (e: Throwable) {
            // Q1: settle the row with the property dropped; the error is
            // proxy-local (the stream itself stays healthy).
            e.printStackTrace()
            null
        }

        val mutex = guarded { streamIdToMutex[streamId] } ?: return // stream closed meanwhile
        mutex.withLock {
            val items = guarded { streamIdToItems[streamId] } ?: return
            val targetHash = marker.entityId.getDataIdHash()
            val index = items.indexOfFirst { row ->
                executionContext.getDataId(row)?.getDataIdHash() == targetHash
            }
            if (index == -1) return // row removed meanwhile — nothing to patch

            val row = items[index] as? Entity<*> ?: return
            val stillPending = row.properties.entries.any { (k, v) ->
                k.get() == propertyKey && v is PendingDeferredProperty
            }
            if (!stillPending) return // re-projected without the marker — drop

            val mergedProperties = row.properties.mapKeys { it.key.get() }.toMutableMap()
            if (resolved != null) {
                mergedProperties[propertyKey] = resolved
            } else {
                mergedProperties.remove(propertyKey)
            }
            val merged = executionContext.context.dataFactory().createEntity<Composite>(
                type = row.type.get(),
                fields = row.fields.mapKeys { it.key.get() },
                relations = mergedProperties
            )

            // Write back FIRST so the next diff doesn't re-emit the patched row.
            guarded { streamIdToItems[streamId] = items.toMutableList().also { it[index] = merged } }
            executionContext.clientConnection.send(StreamElementValueDto(streamId, merged))
        }
    }

    /** Forgets [streamId]'s deferred resolutions and returns their jobs, for the caller to cancel **after** releasing [state]. */
    private fun dropDeferredResolutions(streamId: Long): List<Job> = guarded {
        val jobs = streamIdToDeferredJobs.remove(streamId)?.toList() ?: emptyList()
        streamIdToMutex.remove(streamId)
        inFlightPatchKeys.removeAll { it.startsWith("$streamId/") }
        jobs
    }

    private fun patchKey(streamId: Long, marker: PendingDeferredProperty): String =
        "$streamId/${marker.entityId.getDataIdHash()}/${marker.propertyId.get()}/" +
            (marker.params?.toList()?.hashCode() ?: 0)

    /**
     * The per-change diff a stream emits: a `Removed` for every old row whose identity is gone,
     * and a `Value` for every new row that is new or no longer equal to its old self.
     *
     * ⚠⚠ **Linear, not old×new.** Looking each old row up in the new list and each new row up in
     * the old list, computing `getDataId` (a trait lookup plus a pure op) inside both scans, is
     * O(n²) identity computations per change, on Control, for a listing that already paid an O(N)
     * evaluation to get here. On a 2 300-row folder that is ~5 million `DataId` derivations per
     * write, and a backup writes once per file (`drive-sorted-folder-paging.md` §2.1, SP-5). So
     * each identity is derived once and both sides are matched through a hash map.
     *
     * The semantics are those of the pairwise scan:
     *  - identity is the `DataId` hash, and the *first* old row carrying a
     *    hash is the one a new row is compared against (`putIfAbsent`, mirroring `find`);
     *  - an old row **without** an identity is never reported removed (there is no id to send),
     *    so nothing is computed for it;
     *  - a new row without an identity still falls back to equality against the old rows — a
     *    linear scan, kept because it is the rare shape (a value-typed stream) and `Data`
     *    implementations are not all `hashCode`-consistent enough to put in a set.
     */
    private fun emitChanges(
        executionContext: ExecutionContext,
        streamId: Long,
        oldItems: List<Data>,
        newItems: List<Data>,
    ) {
        val oldByHash = HashMap<Long, Data>(oldItems.size)
        val oldIds = oldItems.map { old ->
            executionContext.getDataId(old)?.also { id ->
                val hash = id.getDataIdHash()
                if (hash !in oldByHash) oldByHash[hash] = old
            }
        }
        val newIds = newItems.map { executionContext.getDataId(it) }
        val newHashes = HashSet<Long>(newItems.size)
        newIds.forEach { id -> if (id != null) newHashes.add(id.getDataIdHash()) }

        // Removed: an old row with an identity that no new row carries.
        oldIds.forEach { oldId ->
            if (oldId != null && oldId.getDataIdHash() !in newHashes) {
                executionContext.clientConnection.send(StreamElementRemovedDto(streamId, oldId))
            }
        }

        // Value: a new row that is new, or whose old self differs.
        newItems.forEachIndexed { index, newItem ->
            val newId = newIds[index]
            val oldItem = if (newId != null) {
                oldByHash[newId.getDataIdHash()]
            } else {
                oldItems.find { it == newItem }
            }
            if (oldItem == null || oldItem != newItem) {
                executionContext.clientConnection.send(StreamElementValueDto(streamId, newItem))
            }
        }
    }

    /** ⚠ Under [state] only — see the class KDoc. */
    private fun registerRefForStream(
        executionContext: ExecutionContext,
        streamId: Long,
        ref: ArrayDatabaseReference,
    ) {
        val hash = ref.arrayId.getDataIdHash()
        val currentStreamRefs = streamIdToNestedRefs.getOrPut(streamId) { mutableSetOf() }
        if (currentStreamRefs.contains(ref)) return

        val count = refToStreamCount.getOrElse(hash) { 0 }
        if (count == 0) {
            // Keep this session's handle so [unregisterRefForStream] removes exactly this
            // registration (multiple sessions may listen on the same array over the shared DB).
            refToListenerHandle[hash] = executionContext.database.setArrayListener(
                reference = ref,
                onAdded = { added, _ -> triggerUpdate(executionContext, hash, added) },
                onRemoved = { _, _ -> triggerUpdate(executionContext, hash, added = null) }
            )
        }
        refToStreamListeners.getOrPut(hash) { mutableSetOf() }.add(streamId)
        listenersDirty = true
        refToStreamCount[hash] = count + 1
        currentStreamRefs.add(ref)
    }

    /** ⚠ Under [state] only — see the class KDoc. */
    private fun unregisterRefForStream(
        executionContext: ExecutionContext,
        streamId: Long,
        ref: ArrayDatabaseReference,
    ) {
        val hash = ref.arrayId.getDataIdHash()
        val currentStreamRefs = streamIdToNestedRefs[streamId] ?: return
        if (!currentStreamRefs.contains(ref)) return

        val count = refToStreamCount.getOrElse(hash) { 0 }
        if (count > 0) {
            val newCount = count - 1
            refToStreamCount[hash] = newCount
            if (newCount == 0) {
                refToListenerHandle.remove(hash)?.let {
                    executionContext.database.removeArrayListener(it)
                }
            }
        }
        currentStreamRefs.remove(ref)
        // ⚠ Listeners are per ARRAY, a stream's references are per reference, and two references
        // to one array differ only in shape. The stream leaves the array's listeners with its
        // last reference to it, not its first — or it goes deaf to an array it still reads.
        if (currentStreamRefs.none { it.arrayId.getDataIdHash() == hash }) {
            refToStreamListeners[hash]?.remove(streamId)
            listenersDirty = true
        }
    }

    /**
     * ⚠ Runs on the writer's thread, inside the database's lock (it is an array listener), so it
     * must not take [state] — that would invert the registry → database order
     * [registerRefForStream] holds. It reads the published snapshot instead, and each update
     * takes its callback under [state] from its own coroutine, one launch per stream as before.
     * A signal-only stream instead marks its [SignalPump] (lock-free), which launches at most one
     * drain per burst. [added] is the row an add wrote, `null` for a removal.
     */
    private fun triggerUpdate(executionContext: ExecutionContext, hash: Long, added: Data?) {
        val streamsToUpdate = listenersByArray.load()[hash] ?: return
        val pumps = pumpsByStream.load()
        streamsToUpdate.forEach { streamId: Long ->
            val pump = pumps[streamId]
            if (pump != null) {
                pump.request(added)
                return@forEach
            }
            executionContext.sessionScope.launch {
                guarded { streamIdToUpdateCallback[streamId] }?.invoke(false)
            }
        }
    }

    private companion object {
        /** Concurrent deferred resolutions across all streams — see [deferredSemaphore]. */
        const val DEFERRED_RESOLUTION_PARALLELISM = 4

        /**
         * Chain ops after which every row the chain can show was read by its evaluation: no window,
         * nothing that depends on another row. A write under these exposes no row it did not read.
         * ⚠ An allowlist on purpose — an op missing here costs a rediscovery per write, an op wrongly
         * here is a stream that goes deaf.
         */
        val ROW_INDEPENDENT_OPS = setOf(TraverseOp.ID, FilterOp.ID, SortByOp.ID, Select.ID)

        /** Rows a [SignalPump] collects between drains before it stops looking and rediscovers once. */
        const val MAX_PENDING_ADDED = 64
    }

    private fun findDatabaseReferences(data: Data): Set<DatabaseReference> {
        val refs = mutableSetOf<DatabaseReference>()
        if (data is DatabaseReference) refs.add(data)

        if (data is TList<*>) {
            data.toList().forEach { refs.addAll(findDatabaseReferences(it)) }
        } else {
            if (data is Entity<*>) {
                data.properties.values.forEach { refs.addAll(findDatabaseReferences(it)) }
                data.fields.values.forEach { refs.addAll(findDatabaseReferences(it)) }
            }

            if (data is Composite) {
                data.fields.values.forEach { refs.addAll(findDatabaseReferences(it)) }
            }
        }
        return refs
    }
}

/** A row's concrete type id, as [hasNestedListFields] takes it (an entity's is its `type`, not its schema). */
private fun typeIdOf(row: Data): String? =
    if (row is Entity<*>) row.type.get() else row.id
