package org.tenshu.core.common.proxy.db

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.CompositeDatabaseReference
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.Projection
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.pureOperationRunner
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.TraitResolver

class DatabaseImpl(
    private val context: TContext,
    private val objectStore: ObjectStore,
    private val dbEntryMapper: DbEntryMapper,
    private val traitResolver: TraitResolver,
    /**
     * Lock guarding every method body. Injected per-platform (Koin binds a
     * `ReentrantLockDatabaseLock` on JVM/Android, the shared replica's own lock on iOS,
     * `NoOpDatabaseLock` on JS; tests get `TestDatabaseLock` unless every store access is on one
     * thread — see [NoOpDatabaseLock]). Reentrant so [transaction] nested calls
     * re-acquire cheaply.
     */
    private val lock: DatabaseLock
) : Database {

    private val hashToDataId = mutableMapOf<Long, DataId>()

    private class ArrayListeners(
        val onAdded: (Data, WriteOrigin) -> Unit,
        val onRemoved: (DataId, WriteOrigin) -> Unit,
    )

    /**
     * Per-array listener callbacks, keyed by the array's `dataIdHash` → the per-registration
     * handle. **Multiple** listeners may be registered for the same array — the common case is
     * two client sessions ([StreamRegistry] instances) observing the same relation over the one
     * shared proxy `Database` (`ClientRegistry` hands every session the same instance). Every
     * registered listener is fired; removing one leaves the others intact. With a *single*
     * listener per array a second session's registration would silently replace the first — a
     * stale cross-session reactive read (e.g. Drive's storage banner never updating once a second
     * subscriber attaches).
     */
    private val arrayListeners = mutableMapOf<Long, MutableMap<Long, ArrayListeners>>()

    /** Handle → the array `dataIdHash` it was registered against, for [removeArrayListener]. */
    private val arrayListenerHashByHandle = mutableMapOf<Long, Long>()

    private var nextArrayListenerHandle = 0L

    override fun isEmpty(): Boolean = objectStore.isEmpty()

    /** Fire every listener registered for [arrayHash]; a snapshot avoids concurrent-mutation. */
    private fun fireArrayAdded(arrayHash: Long, value: Data, origin: WriteOrigin) {
        arrayListeners[arrayHash]?.values?.toList()?.forEach { it.onAdded(value, origin) }
    }

    private fun fireArrayRemoved(arrayHash: Long, dataId: DataId, origin: WriteOrigin) {
        arrayListeners[arrayHash]?.values?.toList()?.forEach { it.onRemoved(dataId, origin) }
    }

    /**
     * Last-known ref list per listened array. Maintained by both the targeted
     * [addToArray]/[removeFromArray] path (pre-updated so the diff fallback
     * no-ops — no double-fire) and the diff fallback itself. Only present for
     * arrays that have a listener.
     */
    private val arraySnapshots = mutableMapOf<Long, MutableList<Long>>()

    override fun <R> transaction(block: () -> R): R = lock.withLock {
        val result = block()
        // Commit boundary: enqueued under the lock so this transaction's
        // writes and its barrier stay contiguous (no other thread's writes
        // can interleave between them). No-op for non-durable stores.
        objectStore.flushBarrier()
        result
    }

    override fun contains(dataId: DataId): Boolean = lock.withLock {
        objectStore.hasObject(dataId.getDataIdHash())
    }

    override fun getData(
        dataId: DataId,
        asEntity: Boolean,
        projection: Projection?,
    ): Data? = lock.withLock {
        val hash = dataId.getDataIdHash()
        hashToDataId[hash] = dataId

        val dbEntry = objectStore.getObject(
            dataIdHash = hash
        ) ?: return@withLock null

        dbEntryMapper.deSerialize(
            dbEntry = dbEntry,
            asEntity = asEntity,
            dataId = dataId,
            projection = projection,
            getEntry = { dataIdHash ->
                objectStore.getObject(dataIdHash) ?: error("Entry not found")
            }
        )
    }

    override fun saveData(data: Data): Unit = lock.withLock {
        val dataId = getDataId(data)
        hashToDataId[dataId.getDataIdHash()] = dataId

        dbEntryMapper.serialize(
            data = data,
            fallbackDataId = null,
            saveEntry = objectStore::storeObject,
            // Enable only-grow on parameterised list fields — see the doc on
            // `DbEntryMapper.serialize`. Lets the maintainer's
            // `addToArray` adds survive a concurrent `FolderMerger`-output
            // `saveData(merged folder)` that would otherwise wholesale rewrite
            // `Folder.FILES` with whatever the in-memory merge result has.
            getEntry = objectStore::getObject
        )
    }

    override fun addToArray(
        reference: ArrayDatabaseReference,
        value: Data,
        origin: WriteOrigin
    ): Unit = lock.withLock {
        val arrayDataIdHash = reference.arrayId.getDataIdHash()

        val oldArrayRefsObject = objectStore.getObject(arrayDataIdHash)

        val oldRefs =
            oldArrayRefsObject?.fields?.map { (it as ObjectStore.DbValue.LongValue).value }
                ?: emptyList()

        val dataId = getDataId(value)
        val newEntryRef = dataId.getDataIdHash()
        hashToDataId[newEntryRef] = dataId

        val newRefs = (oldRefs + newEntryRef).map { ObjectStore.DbValue.LongValue(it) }

        // Event-driven path: pre-update the snapshot to the post-write state so
        // the diff fallback installed by setArrayListener sees no delta for this
        // targeted write (no double-fire), then fire onAdded directly with the
        // exact value + origin the caller already holds.
        val hasListener = arrayListeners.containsKey(arrayDataIdHash)
        if (hasListener) {
            arraySnapshots[arrayDataIdHash] = (oldRefs + newEntryRef).toMutableList()
        }

        saveData(value)

        objectStore.storeObject(
            ObjectStore.DbEntry(
                type = TList.ID,
                dataIdHash = arrayDataIdHash,
                fields = newRefs,
                properties = emptyMap()
            )
        )

        if (hasListener) fireArrayAdded(arrayDataIdHash, value, origin)
    }

    override fun removeFromArray(
        reference: ArrayDatabaseReference,
        dataId: DataId,
        origin: WriteOrigin
    ): Unit = lock.withLock {
        val arrayDataIdHash = reference.arrayId.getDataIdHash()
        val oldArrayRefsObject = objectStore.getObject(arrayDataIdHash) ?: return@withLock

        val oldRefs =
            oldArrayRefsObject.fields.map { (it as ObjectStore.DbValue.LongValue).value }

        val targetDataIdHash = dataId.getDataIdHash()
        val remainingRefs = oldRefs.filter { it != targetDataIdHash }
        val newRefs = remainingRefs.map { ObjectStore.DbValue.LongValue(it) }

        // See addToArray: pre-update snapshot so the diff fallback no-ops, then
        // fire onRemoved directly — but only if the entry was actually present
        // (mirrors the diff path's "no change ⇒ no fire").
        val hasListener = arrayListeners.containsKey(arrayDataIdHash)
        val actuallyRemoved = remainingRefs.size != oldRefs.size
        if (hasListener) {
            arraySnapshots[arrayDataIdHash] = remainingRefs.toMutableList()
        }

        objectStore.storeObject(
            ObjectStore.DbEntry(
                type = TList.ID,
                dataIdHash = arrayDataIdHash,
                fields = newRefs,
                properties = emptyMap()
            )
        )

        if (hasListener && actuallyRemoved) {
            fireArrayRemoved(arrayDataIdHash, dataId, origin)
        }
    }

    /**
     * Fires the array's `onAdded` callbacks for a member that is already there —
     * see [Database.notifyArrayMemberUpdated] for why the notification has to be
     * separable from the write.
     *
     * `onAdded` rather than a new callback kind, deliberately: it already means
     * "this member is in the array, here it is" to every listener (the diff
     * fallback fires it for members a bulk row rewrite re-introduced), and
     * [StreamRegistry] — the only production listener — answers it by
     * re-evaluating the stream, which is exactly the wanted effect. The snapshot
     * is deliberately **not** touched: membership did not change.
     */
    override fun notifyArrayMemberUpdated(
        reference: ArrayDatabaseReference,
        value: Data,
        origin: WriteOrigin,
    ): Unit = lock.withLock {
        val arrayDataIdHash = reference.arrayId.getDataIdHash()
        if (!arrayListeners.containsKey(arrayDataIdHash)) return@withLock
        fireArrayAdded(arrayDataIdHash, value, origin)
    }

    /**
     * One rewrite for the whole batch (see [Database.addAllToArray]). Each value
     * is still `saveData`'d individually — only the array's own ref list, the
     * part [addToArray] re-reads and re-writes per member, is written once.
     */
    override fun addAllToArray(
        reference: ArrayDatabaseReference,
        values: List<Data>,
        origin: WriteOrigin
    ): Unit = lock.withLock {
        if (values.isEmpty()) return@withLock
        val arrayDataIdHash = reference.arrayId.getDataIdHash()

        val oldRefs = objectStore.getObject(arrayDataIdHash)
            ?.fields?.map { (it as ObjectStore.DbValue.LongValue).value }
            ?: emptyList()

        val addedRefs = values.map { value ->
            val dataId = getDataId(value)
            val hash = dataId.getDataIdHash()
            hashToDataId[hash] = dataId
            saveData(value)
            hash
        }

        val hasListener = arrayListeners.containsKey(arrayDataIdHash)
        if (hasListener) {
            arraySnapshots[arrayDataIdHash] = (oldRefs + addedRefs).toMutableList()
        }

        objectStore.storeObject(
            ObjectStore.DbEntry(
                type = TList.ID,
                dataIdHash = arrayDataIdHash,
                fields = (oldRefs + addedRefs).map { ObjectStore.DbValue.LongValue(it) },
                properties = emptyMap()
            )
        )

        if (hasListener) values.forEach { fireArrayAdded(arrayDataIdHash, it, origin) }
    }

    /**
     * One rewrite for the whole batch (see [Database.removeAllFromArray]). The
     * membership test is a hash **set**, so the pass is O(members) rather than
     * O(members × removed) — the per-id `filter` that [removeFromArray] does is
     * the other half of the quadratic when a bulk caller loops it.
     */
    override fun removeAllFromArray(
        reference: ArrayDatabaseReference,
        dataIds: List<DataId>,
        origin: WriteOrigin
    ): Unit = lock.withLock {
        if (dataIds.isEmpty()) return@withLock
        val arrayDataIdHash = reference.arrayId.getDataIdHash()
        val oldArrayRefsObject = objectStore.getObject(arrayDataIdHash) ?: return@withLock

        val oldRefs =
            oldArrayRefsObject.fields.map { (it as ObjectStore.DbValue.LongValue).value }

        val targetHashes = dataIds.map { it.getDataIdHash() }.toSet()
        val remainingRefs = oldRefs.filter { it !in targetHashes }
        if (remainingRefs.size == oldRefs.size) return@withLock

        val hasListener = arrayListeners.containsKey(arrayDataIdHash)
        if (hasListener) {
            arraySnapshots[arrayDataIdHash] = remainingRefs.toMutableList()
        }

        objectStore.storeObject(
            ObjectStore.DbEntry(
                type = TList.ID,
                dataIdHash = arrayDataIdHash,
                fields = remainingRefs.map { ObjectStore.DbValue.LongValue(it) },
                properties = emptyMap()
            )
        )

        // Only ids that were actually members fire, mirroring removeFromArray.
        if (hasListener) {
            val removed = oldRefs.toSet() intersect targetHashes
            dataIds.forEach { dataId ->
                if (dataId.getDataIdHash() in removed) {
                    fireArrayRemoved(arrayDataIdHash, dataId, origin)
                }
            }
        }
    }

    override fun deleteData(dataId: DataId): Unit = lock.withLock {
        val hash = dataId.getDataIdHash()
        objectStore.removeObject(hash)
        hashToDataId.remove(hash)
    }

    override fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection?,
    ): Sequence<Data> = readArray(reference, projection, checkpoint = null)

    override fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        checkpoint: () -> Unit,
    ): Sequence<Data> = readArray(reference, projection, checkpoint)

    override fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
    ): Sequence<Data> = readArrayWindow(reference, projection, offset, limit, checkpoint = null)

    override fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
        checkpoint: () -> Unit,
    ): Sequence<Data> = readArrayWindow(reference, projection, offset, limit, checkpoint)

    /**
     * ⚠⚠ [checkpoint] (a cancellable read's, `core-background-processing.md` §6.2.1) runs before
     * each member is fetched, **inside the lock**: this read is eager, so a check anywhere after it
     * would come only once every row had been read. A throw is safe here — `DatabaseLock.withLock`
     * releases in `finally` on every platform, nothing below writes, and the half-built list is a
     * local that dies with the throw; the store keeps at most the whole rows it already read
     * through, exactly as a completed read would have.
     */
    private fun readArray(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        checkpoint: (() -> Unit)?,
    ): Sequence<Data> = lock.withLock {
        val arrayDataIdHash = reference.arrayId.getDataIdHash()

        // If the array doesn't exist, return an empty sequence instead of crashing
        if (objectStore.getObject(arrayDataIdHash) == null) {
            return@withLock emptySequence()
        }

        // Materialise the sequence inside the lock — the underlying
        // `deserializeArray` is lazy and its `getEntry`/`getDataId` callbacks
        // hit `objectStore` + `hashToDataId` on every iteration step. Without
        // eager materialisation, a caller iterating the returned `Sequence`
        // after the lock released would race concurrent writes — exactly
        // what the transaction model exists to prevent.
        var missing = 0
        val members = dbEntryMapper.deserializeArray(
            arrayDataIdHash = arrayDataIdHash,
            asEntity = reference.isEntity,
            projection = projection,
            getEntry = { dataIdHash ->
                objectStore.getObject(dataIdHash) ?: error(
                    "Entry not found: hash=$dataIdHash dataId=${hashToDataId[dataIdHash]} " +
                        "while reading array ${reference.arrayId} " +
                        "(elementSchema=${reference.elementSchemaId}, isEntity=${reference.isEntity})"
                )
            },
            getMemberEntry = { hash ->
                checkpoint?.invoke()
                objectStore.getObject(hash).also { if (it == null) missing++ }
            },
            getDataId = { hash -> hashToDataId[hash] }
        ).toList()
        reportMissingMembers(reference, missing)
        members.asSequence()
    }

    private fun readArrayWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
        checkpoint: (() -> Unit)?,
    ): Sequence<Data> = lock.withLock {
        val arrayDataIdHash = reference.arrayId.getDataIdHash()

        if (objectStore.getObject(arrayDataIdHash) == null) {
            return@withLock emptySequence()
        }

        // Same materialise-inside-the-lock contract as `getArrayData` — but the
        // window is sliced off the member-hash list *before* deserialisation,
        // so only `limit` entries pay the deserialise cost.
        var missing = 0
        val members = dbEntryMapper.deserializeArray(
            arrayDataIdHash = arrayDataIdHash,
            asEntity = reference.isEntity,
            projection = projection,
            offset = offset,
            limit = limit,
            getEntry = { dataIdHash ->
                objectStore.getObject(dataIdHash) ?: error(
                    "Entry not found: hash=$dataIdHash dataId=${hashToDataId[dataIdHash]} " +
                        "while reading array ${reference.arrayId} " +
                        "(elementSchema=${reference.elementSchemaId}, isEntity=${reference.isEntity})"
                )
            },
            getMemberEntry = { hash ->
                checkpoint?.invoke()
                objectStore.getObject(hash).also { if (it == null) missing++ }
            },
            getDataId = { hash -> hashToDataId[hash] }
        ).toList()
        reportMissingMembers(reference, missing)
        members.asSequence()
    }

    /**
     * A relation read that skipped members is **not** silent — it is the store telling you it has
     * lost rows something still points at, and the count is the only place that shows up.
     *
     * ⚠⚠ Skipping is the platform default, deliberately. A dangling member that throws
     * `IllegalStateException: Entry not found` out of the read kills whatever is reading — and when
     * that is `SyncScheduler.tickOnce`, **sync is dead on the device with no user-visible symptom**
     * for as long as the row stays missing. Hardening consumers one at a time (`TombstoneRegistry`,
     * `PendingBlockRestamper`, the drain) is the signal that the default, not the callers, is what
     * has to change. A shorter relation is a *recoverable* answer; a throw from the middle of a lazy
     * sequence is not.
     *
     * ⚠ The array *itself* being absent yields an empty sequence. A dangling member of a **nested
     * list field** is skipped and counted here too — thrown, one missing file inside a subfolder
     * would take out the whole root listing. A missing nested
     * *composite* child of a member still throws: that is a malformed entity, not a short list.
     * See `DbEntryMapper.deserializeArray`.
     */
    private fun reportMissingMembers(reference: ArrayDatabaseReference, missing: Int) {
        if (missing == 0) return
        println(
            "WARN DatabaseImpl: relation ${reference.arrayId} skipped $missing member(s) with no " +
                "row in the store. The read succeeded and is SHORT by that many. This is store " +
                "corruption — something deleted a row another relation still references."
        )
    }

    /**
     * See [Database.removeDanglingArrayMembers]. Rewrites the ref list to those whose objects
     * exist; touches nothing when they all do.
     *
     * ⚠ No listener is fired. A dangling reference was never a *member* any reader could see — a
     * read skipped it (see [reportMissingMembers]) and, before that, threw over it. Announcing a
     * removal for something no listener was ever handed would be inventing an event.
     */
    override fun removeDanglingArrayMembers(
        reference: ArrayDatabaseReference,
        origin: WriteOrigin,
    ): Int = lock.withLock {
        val arrayDataIdHash = reference.arrayId.getDataIdHash()
        val arrayObject = objectStore.getObject(arrayDataIdHash) ?: return@withLock 0

        val oldRefs = arrayObject.fields.map { (it as ObjectStore.DbValue.LongValue).value }
        val liveRefs = oldRefs.filter { objectStore.getObject(it) != null }
        val dropped = oldRefs.size - liveRefs.size
        if (dropped == 0) return@withLock 0

        objectStore.storeObject(
            ObjectStore.DbEntry(
                type = TList.ID,
                dataIdHash = arrayDataIdHash,
                fields = liveRefs.map { ObjectStore.DbValue.LongValue(it) },
                properties = emptyMap()
            )
        )
        // Keep the listener snapshot in step, or the next diff would replay these as removals.
        if (arrayListeners.containsKey(arrayDataIdHash)) {
            arraySnapshots[arrayDataIdHash] = liveRefs.toMutableList()
        }
        println(
            "DatabaseImpl: repaired relation ${reference.arrayId} — dropped $dropped dangling " +
                "reference(s); ${liveRefs.size} member(s) remain."
        )
        dropped
    }

    override fun getArrayMemberHashes(
        reference: ArrayDatabaseReference,
    ): List<Long>? = lock.withLock {
        objectStore.getObject(reference.arrayId.getDataIdHash())
            ?.fields
            ?.map { (it as ObjectStore.DbValue.LongValue).value }
    }

    override fun getComposite(reference: CompositeDatabaseReference): Data? = lock.withLock {
        val dbEntry = objectStore.getObject(reference.dataIdHash) ?: return@withLock null

        // Deserialise inside the lock — same race-avoidance rationale as
        // `getArrayData`. The `dataId` passed through is whatever `hashToDataId`
        // already knows for this hash (often null for a child resolved purely by
        // hash); `deSerialize` tolerates a null `dataId` — it simply won't defer
        // this child's own nested arrays/composites, which is the correct
        // one-level-at-a-time laziness.
        dbEntryMapper.deSerialize(
            dbEntry = dbEntry,
            asEntity = reference.isEntity,
            dataId = hashToDataId[reference.dataIdHash],
            getEntry = { dataIdHash ->
                objectStore.getObject(dataIdHash) ?: error("Entry not found")
            }
        )
    }

    override fun setArrayListener(
        reference: ArrayDatabaseReference,
        onAdded: (Data, WriteOrigin) -> Unit,
        onRemoved: (DataId, WriteOrigin) -> Unit
    ): Long = lock.withLock {
        val arrayReferenceDataIdHash = reference.arrayId.getDataIdHash()

        // Append a registration rather than replacing: a second session observing the same array
        // must not silence the first. Only the FIRST registration for this array installs the
        // snapshot + the objectStore diff fallback (both are per-array, shared by all listeners).
        val isFirstForArray = arrayListeners[arrayReferenceDataIdHash].isNullOrEmpty()
        val handle = nextArrayListenerHandle++
        arrayListeners.getOrPut(arrayReferenceDataIdHash) { mutableMapOf() }[handle] =
            ArrayListeners(onAdded, onRemoved)
        arrayListenerHashByHandle[handle] = arrayReferenceDataIdHash

        if (isFirstForArray) {
            arraySnapshots[arrayReferenceDataIdHash] = objectStore.getObject(arrayReferenceDataIdHash)
                ?.fields
                ?.map { value -> (value as ObjectStore.DbValue.LongValue).value }
                ?.toMutableList()
                ?: mutableListOf()

            // Diff fallback for array changes that bypass addToArray/removeFromArray
            // — a saveData(entity) that rewrites an embedded list field (e.g. a
            // semantic merger's only-grow output). Targeted mutations pre-update the
            // snapshot so this no-ops for them (no double-fire). O(n) via sets;
            // origin defaults to USER (the save-layer origin isn't visible here).
            objectStore.setListener(
                dataIdHash = arrayReferenceDataIdHash,
                listener = { update ->
                    val snapshot = arraySnapshots[arrayReferenceDataIdHash] ?: mutableListOf()
                    val newArrayEntries =
                        update.fields.map { (it as ObjectStore.DbValue.LongValue).value }

                    val oldSet = snapshot.toHashSet()
                    val newSet = newArrayEntries.toHashSet()
                    val removedEntries = snapshot.filter { it !in newSet }
                    val addedEntries = newArrayEntries.filter { it !in oldSet }

                    if (removedEntries.isEmpty() && addedEntries.isEmpty()) return@setListener

                    arraySnapshots[arrayReferenceDataIdHash] = newArrayEntries.toMutableList()

                    removedEntries.forEach { dataIdHash ->
                        val removedId = hashToDataId[dataIdHash] ?: run {
                            val dbEntry = objectStore.getObject(dataIdHash) ?: error("Entry not found")
                            getDataId(
                                dbEntryMapper.deSerialize(
                                    dbEntry = dbEntry,
                                    asEntity = reference.isEntity,
                                    getEntry = { h -> objectStore.getObject(h) ?: error("Entry not found") },
                                    dataId = null
                                )
                            )
                        }
                        fireArrayRemoved(arrayReferenceDataIdHash, removedId, WriteOrigin.USER)
                    }

                    addedEntries.forEach { dataIdHash ->
                        val dbEntry = objectStore.getObject(dataIdHash) ?: error("Entry not found")
                        val addedData = dbEntryMapper.deSerialize(
                            dbEntry = dbEntry,
                            asEntity = reference.isEntity,
                            getEntry = { h -> objectStore.getObject(h) ?: error("Entry not found") },
                            dataId = hashToDataId[dataIdHash]
                        )
                        fireArrayAdded(arrayReferenceDataIdHash, addedData, WriteOrigin.USER)
                    }
                }
            )
        }
        handle
    }

    override fun removeArrayListener(handle: Long): Unit = lock.withLock {
        val hash = arrayListenerHashByHandle.remove(handle) ?: return@withLock
        val listeners = arrayListeners[hash] ?: return@withLock
        listeners.remove(handle)
        // Only tear down the per-array state (snapshot + objectStore listener) once the LAST
        // registration for this array is gone — a lingering listener must keep firing.
        if (listeners.isEmpty()) {
            objectStore.removeListener(hash)
            arrayListeners.remove(hash)
            arraySnapshots.remove(hash)
        }
    }

    private fun getDataId(data: Data): DataId.EntityDataId {
        if (data is DataId.EntityDataId) return data

        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id

        val dataIdProvider = traitResolver.getTrait<DataIdProvider<out Data>>(
            typeId = typeId,
            traitId = DataIdProvider.ID
        )

        if (dataIdProvider != null) {
            val requirement = dataIdProvider.traitRequirements.toList().find {
                it.schema.isSubtypeOf(DataIdProvider.Requirements.DATA_ID)
            } ?: error("DATA_ID requirement not found in provider ${dataIdProvider.id} for $typeId")

            return context.pureOperationRunner().execute(requirement, data)
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

            else -> error("No DataIdProvider found for $typeId and it is not a simple value.")
        }
    }
}
