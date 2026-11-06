package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId

interface Database {

    /**
     * Whether the backing object store holds no persisted objects — the sync
     * cursor/store desync self-heal reads this at init (see
     * `ObjectStore.isEmpty` / `sync-cursor-store-lifecycle.md`). Default
     * `false` (conservative: never trigger a reset); [DatabaseImpl] delegates
     * to its [org.tenshu.core.common.proxy.db.ObjectStore].
     */
    fun isEmpty(): Boolean = false

    fun getData(
        dataId: DataId,
        asEntity: Boolean = false,
        projection: Projection? = null,
    ): Data?

    /**
     * Whether a row exists at [dataId], **without deserialising it**.
     *
     * The point is the cost profile. `getData(dataId) != null` answers the same question but
     * pays a full deserialise whenever the answer is *yes* — and "yes" is the common case for
     * the caller this exists for: an importer re-reading a source window wants to know how many
     * rows were genuinely new, and almost none of them are.
     *
     * Defaulted to the read so every [Database] keeps working; [org.tenshu.core.common.proxy.db.ObjectStore.hasObject]
     * is where a store can answer from an index instead.
     */
    fun contains(dataId: DataId): Boolean = getData(dataId) != null

    fun saveData(data: Data)

    fun addToArray(
        reference: ArrayDatabaseReference,
        value: Data,
        origin: WriteOrigin = WriteOrigin.USER
    )

    fun removeFromArray(
        reference: ArrayDatabaseReference,
        dataId: DataId,
        origin: WriteOrigin = WriteOrigin.USER
    )

    /**
     * Drop references to members whose rows are **provably absent** from the store, and return how
     * many were dropped. A no-op (returning 0) on a healthy relation.
     *
     * ⚠⚠ The one removal in this interface that cannot destroy data, and the reason it exists as
     * its own method rather than as a caller-side loop: [removeFromArray] needs a `DataId`, and a
     * missing row cannot supply one — the hash is all that survives. Without this method a
     * dangling reference is *unexpressible* as a repair, so a caller like `PendingBlockRestamper`
     * could only leave dangling members in place for every reader to keep paying for.
     *
     * ⚠ It removes a reference only when `getObject(hash)` returns null, i.e. only where a read
     * would have skipped the member anyway. It never removes a member it can read, so it cannot
     * lose an unsent write — the failure mode `RetiredBlockSweeper`'s keep-set guards against.
     *
     * ⚠ [WriteOrigin.DERIVED], not [WriteOrigin.USER]: this repair is **local hygiene**, and the
     * peer's copy of the relation is not damaged just because ours is. `DERIVED` is the origin
     * that must never be broadcast, which is exactly the property wanted — a `USER` write here
     * would ship a rewritten relation to every device to fix a row only this one lost.
     *
     * Default `0` so an implementation without an object store (or a test double) is unaffected.
     */
    fun removeDanglingArrayMembers(
        reference: ArrayDatabaseReference,
        origin: WriteOrigin = WriteOrigin.DERIVED,
    ): Int = 0

    /**
     * Bulk sibling of [addToArray] — append [values] in one array rewrite.
     *
     * ⚠ This is not sugar. [addToArray] reads the array's whole ref list,
     * appends one member and stores the whole list back, so appending N members
     * one at a time is **O(N²)** in list writes over a list that is itself
     * growing. On a bulk producer (a realm-data reset queuing N tombstones)
     * that is the difference between seconds and never — see
     * `control-data-reset.md` §11. Listeners fire once per added value, exactly
     * as if [addToArray] had been called N times.
     *
     * The default implementation loops, so an implementation that does not care
     * (test fakes, in-memory stores) needs no change; [DatabaseImpl] overrides
     * it with the single rewrite.
     */
    fun addAllToArray(
        reference: ArrayDatabaseReference,
        values: List<Data>,
        origin: WriteOrigin = WriteOrigin.USER
    ) {
        values.forEach { addToArray(reference, it, origin) }
    }

    /**
     * Bulk sibling of [removeFromArray] — drop every member in [dataIds] in one
     * array rewrite. Same O(N²)-in-list-writes reasoning as [addAllToArray];
     * this is the read side of the same bulk-delete problem. Ids that are not
     * members are ignored (mirroring [removeFromArray]'s no-op), and listeners
     * fire once per **actually removed** id.
     */
    fun removeAllFromArray(
        reference: ArrayDatabaseReference,
        dataIds: List<DataId>,
        origin: WriteOrigin = WriteOrigin.USER
    ) {
        dataIds.forEach { removeFromArray(reference, it, origin) }
    }

    fun deleteData(
        dataId: DataId
    )

    fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection? = null,
    ): Sequence<Data>?

    /**
     * Windowed variant of [getArrayData]: deserialises only the members in
     * `[offset, offset + limit)` of the array's insertion order. The default
     * implementation windows *after* a full read (correct everywhere); real
     * stores override it to slice the member-hash list before deserialising,
     * making page reads O(limit) instead of O(array size). Out-of-range
     * windows clamp — an offset past the end yields an empty sequence.
     */
    fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection? = null,
        offset: Int,
        limit: Int,
    ): Sequence<Data>? = getArrayData(reference, projection)
        ?.drop(offset)
        ?.take(limit)

    /**
     * [getArrayData] with [checkpoint] called before each member is fetched and deserialised — the
     * cancellable-read hook (`core-background-processing.md` §6.2.1). It may throw
     * `CancellationException`; an implementation must let it propagate *and release its lock*, and
     * must not keep anything from the abandoned read beyond whole rows its store already caches.
     *
     * ⚠⚠ The hook exists because a real store materialises the array **eagerly, under its lock**
     * (`DatabaseImpl` returns a `Sequence` over an already-built list), so a check on the returned
     * sequence fires only after every row has been read. Default: no hook — correct, just not
     * stoppable mid-read.
     */
    fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        checkpoint: () -> Unit,
    ): Sequence<Data>? = getArrayData(reference, projection)

    /** [getArrayDataWindow] with a per-member [checkpoint] — see the checkpointed [getArrayData]. */
    fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
        checkpoint: () -> Unit,
    ): Sequence<Data>? = getArrayDataWindow(reference, projection, offset, limit)

    /**
     * The array's member hashes in insertion order, with **no member
     * deserialisation** — the raw content of the array's own entry. `null`
     * means "unsupported by this implementation" (the default) or "array
     * missing"; callers must treat `null` as *unknown* and fall back to a
     * full read, never as "empty".
     */
    fun getArrayMemberHashes(
        reference: ArrayDatabaseReference,
    ): List<Long>? = null

    /**
     * Materialises a single deferred composite ([CompositeDatabaseReference]) by
     * its stored hash — the single-composite analogue of [getArrayData]. Returns
     * `null` when no entry exists at the reference's hash. Lock-guarded and fully
     * materialised before returning, mirroring [getArrayData]'s race-avoidance.
     */
    fun getComposite(
        reference: CompositeDatabaseReference,
    ): Data?

    /**
     * Register a listener fired when entries are added to / removed from the
     * array. [onAdded]/[onRemoved] receive the [WriteOrigin] of the mutation.
     *
     * Targeted [addToArray]/[removeFromArray] fire the callbacks **directly**
     * with the exact value/dataId at the call site (no per-write full-array
     * diff). Array changes that bypass those methods — a [saveData] that
     * rewrites an embedded list field (e.g. a semantic merger's only-grow
     * output) — are still caught by an internal diff fallback, which fires with
     * [WriteOrigin.USER] (the save-layer origin is not visible at the store
     * boundary).
     *
     * Returns an opaque **handle**; pass it to
     * [removeArrayListener] to remove exactly this registration.
     *
     * Multiple listeners may be registered for the same array — every one is notified, and
     * removing a handle leaves the others intact. (Two client sessions, [StreamRegistry]
     * instances, observing the same relation over the one shared proxy `Database` is the common
     * case; a single-listener model would silently replace the first registration with the
     * second — a stale cross-session reactive read.)
     */
    fun setArrayListener(
        reference: ArrayDatabaseReference,
        onAdded: (Data, WriteOrigin) -> Unit,
        onRemoved: (DataId, WriteOrigin) -> Unit
    ): Long

    /**
     * Signal that [value] — **already a member of [reference], exactly once** —
     * was saved again, without rewriting the array row.
     *
     * ⚠ **Why this exists (`core-relation-system.md` §10.4, R2b).** The generic
     * relation maintainer writes nothing when a re-saved member is already present
     * (probe-first membership). A `removeFromArray` + `addToArray` pair would do
     * *two* jobs: converge membership, and — because [setArrayListener] is the only
     * thing that re-evaluates a reactive read — serve as the **change notification**
     * for every stream over the relation. Without that second job, a `DeviceState`
     * row re-saved with a new value would update the store and never reach the
     * Home UI. This method is that job, separated: no store write, no membership
     * change, listeners notified once.
     *
     * The default is a no-op, so a `Database` that has no listeners (every test
     * double) need not implement it.
     */
    fun notifyArrayMemberUpdated(
        reference: ArrayDatabaseReference,
        value: Data,
        origin: WriteOrigin = WriteOrigin.USER,
    ) {
    }

    fun removeArrayListener(handle: Long)

    /**
     * Run [block] holding the database lock — no other `Database` method on
     * this instance can run concurrently from another thread. Use this to
     * compose multi-step sequences (e.g. `read count → if missing, remove +
     * add`) atomically. The lock is reentrant: individual `Database` methods
     * called inside the block re-acquire the same thread's existing hold,
     * so nesting is safe.
     *
     * Without `transaction`, individual mutating methods (`saveData`,
     * `addToArray`, `removeFromArray`, `deleteData`) are still atomic with
     * respect to each other, but a caller-level read-modify-write across
     * multiple methods can interleave. Wrap such sequences in `transaction { }`
     * when atomicity across them matters.
     */
    fun <R> transaction(block: () -> R): R
}
