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
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.StreamElementRemovedDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.fields
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
            if (--holdDepth == 0 && listenersDirty) {
                listenersDirty = false
                publishListeners()
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

        val onUpdate: suspend (Boolean) -> Unit = onUpdate@{ isInitial ->
            updateMutex.lock()
            try {
                val currentCapturedRefs = mutableSetOf<ArrayDatabaseReference>()
                currentCapturedRefs.add(reference)

                val processedDataResult: Data? =
                    executionContext.referenceResolver.collectingReferences(
                        into = currentCapturedRefs,
                    ) {
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
                        // TenshuException on typed failure. The catch block below
                        // wraps it into a StreamElement.End error.
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

                // Ended (or re-registered) while this evaluated: anything sent now, or any state
                // written, would belong to a subscription that no longer exists — see
                // [streamIdToRegistration].
                if (!isLive(streamId, registration)) return@onUpdate

                if (processedDataResult != null) {
                    if (processedDataResult.schema.isSubtypeOf("tenshu/core/error")) {
                        val element =
                            executionContext.context.dataFactory().createComposite<StreamElement<*>>(
                                type = StreamElement.End.ID,
                                // Explicit `<Data?>` is load-bearing: without it Kotlin's
                                // vararg inference picks the second arg's concrete type
                                // for the array element (e.g. `TError[]`), and the
                                // `TIntegerDto` returned by `createValue` then fails to
                                // store into that typed array at runtime
                                // (`java.lang.ArrayStoreException`).
                                fields = listOf<Data?>(
                                    executionContext.context.dataFactory()
                                        .createValue("tenshu/core/number/integer", streamId),
                                    processedDataResult
                                )
                            )
                        executionContext.clientConnection.send(element)
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
                    val discoveredRefs = findDatabaseReferences(processedDataResult)
                        .filterIsInstance<ArrayDatabaseReference>()

                    val allCurrentRefs = currentCapturedRefs + discoveredRefs

                    // One guarded step: the liveness check, the diff baseline and the ref
                    // bookkeeping. A stream ended since the check above must not re-register refs
                    // (a listener nobody would ever remove) — so the check is repeated in here.
                    val oldItems = guarded {
                        if (!isLive(streamId, registration)) return@onUpdate
                        val currentStreamRefs =
                            streamIdToNestedRefs.getOrPut(streamId) { mutableSetOf() }

                        val addedRefs = allCurrentRefs - currentStreamRefs
                        val removedRefs = currentStreamRefs - allCurrentRefs

                        addedRefs.forEach { ref ->
                            registerRefForStream(executionContext, streamId, ref)
                        }
                        removedRefs.forEach { ref ->
                            unregisterRefForStream(executionContext, streamId, ref)
                        }
                        if (isInitial) emptyList() else streamIdToItems[streamId] ?: emptyList()
                    }

                    if (isInitial) {
                        // Signal-only subscribers discard payloads client-side;
                        // the initial full-list emission (the whole folder over
                        // IPC, parsed just to be dropped) is skipped entirely.
                        // Everything ABOVE stays: the evaluation seeds
                        // `streamIdToItems` as the diff baseline (without it the
                        // first change would re-emit every item as "new") and
                        // discovers nested refs for invalidation. Subsequent
                        // per-item diff emissions are the subscriber's signal.
                        if (!signalOnly) {
                            executionContext.clientConnection.send(
                                StreamElementValueDto(
                                    streamId,
                                    executionContext.context.dataFactory().createList<Data>(newItems)
                                )
                            )
                        }
                    } else {
                        emitChanges(executionContext, streamId, oldItems, newItems)
                    }
                    guarded { if (isLive(streamId, registration)) streamIdToItems[streamId] = newItems }

                    // Deferred markers that survived materialisation get a
                    // background resolution + per-row patch. Signal-only
                    // subscribers discard payloads — a patch would be a
                    // spurious wake-up, so skip scheduling entirely.
                    if (!signalOnly) {
                        scheduleDeferredResolutions(executionContext, streamId, newItems)
                    }
                }
            } catch (e: Throwable) {
                // If anything fails in the background update, we stop the stream.
                // `docs/proposals/core-error-channel.md` §6 Phase 4: unwrap typed
                // [TenshuException] to its [org.tenshu.core.TError]; anything else
                // becomes [InternalErrorDto] (the proxy-local catch-all shape).
                e.printStackTrace()
                val typedError = when (e) {
                    is TenshuException -> e.error
                    else -> InternalErrorDto(internalDetail = e.message)
                }
                val element =
                    executionContext.context.dataFactory().createComposite<StreamElement<*>>(
                        type = StreamElement.End.ID,
                        // Explicit `<Data?>` is load-bearing — see the matching note on
                        // the sibling End-element construction above.
                        fields = listOf<Data?>(
                            executionContext.context.dataFactory()
                                .createValue("tenshu/core/number/integer", streamId),
                            typedError
                        )
                    )
                // An ended stream's failure has no subscription left to end — and would end the next.
                if (isLive(streamId, registration)) executionContext.clientConnection.send(element)
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

    fun removeDatabaseListener(executionContext: ExecutionContext, streamId: Long) {
        executionContext.clientConnection.forgetStream(streamId)
        var jobs: List<Job> = emptyList()
        val wasRegistered = guarded {
            streamIdToRegistration.remove(streamId)
            val wasRegistered = streamIdToRef.remove(streamId) != null
            streamIdToUpdateCallback.remove(streamId)
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
        guarded { clearLocked() }
    }

    private fun clearLocked() {
        streamIdToItems.clear()
        streamIdToRef.clear()
        streamIdToNestedRefs.clear()
        streamIdToUpdateCallback.clear()
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
                onAdded = { _, _ -> triggerUpdate(executionContext, hash) },
                onRemoved = { _, _ -> triggerUpdate(executionContext, hash) }
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
     */
    private fun triggerUpdate(executionContext: ExecutionContext, hash: Long) {
        val streamsToUpdate = listenersByArray.load()[hash] ?: return
        streamsToUpdate.forEach { streamId: Long ->
            executionContext.sessionScope.launch {
                guarded { streamIdToUpdateCallback[streamId] }?.invoke(false)
            }
        }
    }

    private companion object {
        /** Concurrent deferred resolutions across all streams — see [deferredSemaphore]. */
        const val DEFERRED_RESOLUTION_PARALLELISM = 4
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
