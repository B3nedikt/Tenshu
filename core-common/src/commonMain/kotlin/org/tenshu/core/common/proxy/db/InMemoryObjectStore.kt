package org.tenshu.core.common.proxy.db

/**
 * ⚠ Not thread-safe, like every [ObjectStore]: the maps below are plain, and the contract is that
 * each access happens under the `DatabaseLock` of the `Database` built over this store. Open so a
 * test harness can assert that contract per access (`:system-test-utils`, `LockCheckedObjectStore`).
 */
open class InMemoryObjectStore : ObjectStore, WipeableObjectStore {

    private val store: MutableMap<Long, ObjectStore.DbEntry> = mutableMapOf()

    private val listeners: MutableMap<Long, MutableList<(ObjectStore.DbEntry) -> Unit>> =
        mutableMapOf()

    override fun isEmpty(): Boolean = store.isEmpty()

    /** Enumerable by construction — the whole map is resident (E4-3b, §8.3.4). */
    override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry> =
        if (typeIds.isEmpty()) emptyList() else store.values.filter { it.type in typeIds }

    /**
     * Test aid: drop every persisted object, modelling an object-store
     * wipe/reset (e.g. an iOS route-1 app-container wipe) that leaves the
     * separately-persisted sync cursor intact — the exact desync the sync
     * cursor/store self-heal ([org.tenshu.core.common.proxy.Database.isEmpty])
     * exists to recover from.
     *
     * ⚠ Also the RG-3 / D3 **discard path** in production — see [WipeableObjectStore]. A
     * silently-inert wipe is exactly the trap that interface warns about, so this is not a
     * test-only affordance despite the name.
     */
    override suspend fun wipeAllObjects() = clear()
    fun clear() {
        store.clear()
    }

    override fun storeObject(dbEntry: ObjectStore.DbEntry) {
        store[dbEntry.dataIdHash] = dbEntry

        notifyListeners(dbEntry.dataIdHash, dbEntry)
    }

    override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
        return store[dataIdHash]
    }

    override fun setListener(
        dataIdHash: Long,
        listener: (update: ObjectStore.DbEntry) -> Unit
    ) {
        val listenersForHash = listeners.getOrPut(dataIdHash) { mutableListOf() }
        listenersForHash.add(listener)

        val currentEntry = store[dataIdHash]

        if (currentEntry != null) {
            listener(currentEntry)
        }
    }

    override fun removeListener(dataIdHash: Long) {
        listeners.remove(dataIdHash)
    }

    override fun removeObject(dataIdHash: Long) {
        store.remove(dataIdHash)
    }

    private fun notifyListeners(dataIdHash: Long, updatedEntry: ObjectStore.DbEntry) {
        listeners[dataIdHash]?.toList()?.forEach { listener ->
            listener(updatedEntry)
        }
    }
}
