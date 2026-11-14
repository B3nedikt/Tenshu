package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Owns the per-session set of [DataObserver]s and the snapshot-then-iterate
 * dispatch on `saveData` / `deleteData`.
 *
 * Part of the [ExecutionContext] decomposition in
 * `docs/adr/0008-execution-context-decomposition.md`. The registry instance is shared
 * across the per-request contexts derived via
 * [ExecutionContext.withEvaluationScope] so cross-process IPC routed through
 * `ProxyGateway.call` still fires the observers `initialization` registered on
 * the session context.
 *
 * The snapshot-then-iterate invariant is the only subtle
 * behaviour: an observer's `onDataSaved` can transitively call [add] (e.g. via
 * a stream emission whose materialiser dispatches an
 * `OnDemandResolution.resolve` that constructs a new
 * `SyncProcessor`/`TombstoneRegistry`, the latter registers itself). Without
 * the snapshot that mutation lands on the underlying list mid-iteration
 * and throws `ConcurrentModificationException`.
 *
 * Storage is an [AtomicReference] over an immutable [List]: dispatch reads a
 * snapshot via `load()`, mutators CAS to a new immutable list. The same-thread
 * reentrant-add case is covered — the in-flight iteration uses the
 * `load()`ed snapshot taken before the transitive `add` swung in — and the
 * cross-thread case (observer fanout running on one `Dispatchers.Default`
 * worker while another registers an observer or dispatches a parallel save)
 * is safe too. Read path is allocation-free; mutations are rare so the
 * CAS-rebuild cost is fine.
 */
@OptIn(ExperimentalAtomicApi::class)
class ObserverRegistry {

    private val observers = AtomicReference<List<DataObserver>>(emptyList())

    fun add(observer: DataObserver) {
        while (true) {
            val current = observers.load()
            if (observers.compareAndSet(current, current + observer)) return
        }
    }

    /**
     * Whether any registered observer matches [predicate]. Used for idempotent
     * installation of a singleton observer — `installRelationMaintainer()` in
     * particular, which several `init<X>Service()` hooks call but which must add
     * exactly one instance per context.
     */
    fun any(predicate: (DataObserver) -> Boolean): Boolean = observers.load().any(predicate)

    fun onDataSaved(data: Data, origin: WriteOrigin) = onDataSaved(data, origin, syncLaneId = null)

    /**
     * Lane-aware dispatch (task-scoped sync lane §10.3 D3): the three-arg
     * overload reaches only [LaneAwareDataObserver]s; every other observer
     * keeps the two-arg contract untouched.
     */
    fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?) =
        onDataSaved(data, origin, syncLaneId, redundant = false)

    /**
     * [redundant] reaches only [SaveOutcomeAwareDataObserver]s; see its KDoc. Defaulting to `false`
     * everywhere else is the safe direction — a save not known to be redundant is news.
     */
    fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?, redundant: Boolean) {
        observers.load().forEach {
            when (it) {
                is LaneAwareDataObserver -> it.onDataSaved(data, origin, syncLaneId)
                is SaveOutcomeAwareDataObserver -> it.onDataSaved(data, origin, redundant)
                else -> it.onDataSaved(data, origin)
            }
        }
    }

    /**
     * Whether any registered observer wants [SaveOutcomeAwareDataObserver]'s redundancy bit for
     * [data] — the store comparison costs a read per written entry, so a save skips it when nobody
     * would act on the answer ([SaveOutcomeAwareDataObserver.wantsSaveOutcome]).
     */
    fun wantsSaveOutcome(data: Data): Boolean =
        observers.load().any { it is SaveOutcomeAwareDataObserver && it.wantsSaveOutcome(data) }

    /**
     * The lane a save tagged with [syncLaneId] may stage into: [syncLaneId] itself, or `null` (an
     * ordinary save) when any [LaneAwareDataObserver] refuses it ([LaneAwareDataObserver.admitsToLane]).
     * ⚠ Must be asked BEFORE the write — see that method.
     */
    fun laneFor(data: Data, origin: WriteOrigin, syncLaneId: String?): String? {
        if (syncLaneId == null) return null
        val refused = observers.load().any {
            it is LaneAwareDataObserver && !it.admitsToLane(data, origin, syncLaneId)
        }
        return if (refused) null else syncLaneId
    }

    /**
     * Everything a save decides before its write: the lane ([laneFor]) and each lane-aware observer's
     * [LaneAwareDataObserver.prepareSave] token. ⚠ Must be asked BEFORE the write.
     */
    fun prepareSave(data: Data, origin: WriteOrigin, syncLaneId: String?): PreparedSave {
        val lane = laneFor(data, origin, syncLaneId)
        val tokens = observers.load().mapNotNull { observer ->
            if (observer !is LaneAwareDataObserver) return@mapNotNull null
            observer.prepareSave(data, origin)?.let { observer to it }
        }
        return PreparedSave(lane, tokens)
    }

    /**
     * Dispatch for a save [prepareSave] prepared: each lane-aware observer gets its own token back,
     * and each [SaveOutcomeAwareDataObserver] whether the save was [redundant].
     */
    fun onDataSaved(data: Data, origin: WriteOrigin, prepared: PreparedSave, redundant: Boolean = false) {
        observers.load().forEach { observer ->
            when (observer) {
                is LaneAwareDataObserver -> {
                    val token = prepared.tokens.firstOrNull { it.first === observer }?.second
                    observer.onDataSaved(data, origin, prepared.lane, token)
                }
                is SaveOutcomeAwareDataObserver -> observer.onDataSaved(data, origin, redundant)
                else -> observer.onDataSaved(data, origin)
            }
        }
    }

    /** The pre-delete fanout ([DataObserver.beforeDataDeleted]), inside the delete's transaction. */
    fun beforeDataDeleted(dataId: DataId, origin: WriteOrigin) {
        observers.load().forEach { it.beforeDataDeleted(dataId, origin) }
    }

    fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        observers.load().forEach { it.onDataDeleted(dataId, origin) }
    }

    fun clear() {
        observers.store(emptyList())
    }
}

/** What [ObserverRegistry.prepareSave] decided before a write. */
class PreparedSave internal constructor(
    /** The lane the save stages into, or null for an ordinary save. */
    val lane: String?,
    internal val tokens: List<Pair<LaneAwareDataObserver, Any>>,
)
