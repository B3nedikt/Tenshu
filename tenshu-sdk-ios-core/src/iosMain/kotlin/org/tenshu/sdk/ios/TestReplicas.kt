package org.tenshu.sdk.ios

import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.InProcessDatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ReplicaEpochSource
import org.tenshu.core.common.proxy.db.WipeableObjectStore
import platform.Foundation.NSRecursiveLock
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * ⚠ **A test's replica** — what [FirstPartyLocalRuntime.assembleBase] binds for its
 * `replicaDirectory`, never reached on the production path (which binds the SQLite shared replica,
 * [SharedReplicaBinding]).
 *
 * The rows are **one set per name in this process**, shared by every assembly over that name, as the
 * apps of one phone share the replica: an assembly over a name another one wrote (a co-resident app,
 * a relaunch) reads what it wrote. Each assembly gets **its own** store handle with its own
 * listeners, so — like the SQLite store across processes — a write in one graph reaches the others'
 * reads, never their listeners. One lock per name serialises every handle; it is also each graph's
 * `DatabaseLock`.
 *
 * ⚠ **The epoch, as the SQLite store's** ([ReplicaEpochSource]): one random value per name, moved
 * by a wipe; a handle answers it only while it is the one the handle was made on, and `null` once
 * it moved, as a fenced SQLite handle does (its own wipe included). So a graph built before a wipe
 * cannot vouch for the store, and one built after it sees the new epoch.
 *
 * Until 2026-11-05 this was the iOS file store opened over a temporary directory
 * (`ios-shared-store-sqlite.md` B.22.9). Nothing is written to disk.
 */
internal object TestReplicas {
    private class Shared {
        val lock = InProcessDatabaseLock()
        val rows = InMemoryObjectStore()

        @Volatile
        var epoch: Long = Random.nextLong()
    }

    private val registryLock = NSRecursiveLock()
    private val replicas = HashMap<String, Shared>()

    private fun shared(name: String): Shared {
        registryLock.lock()
        try {
            return replicas.getOrPut(name) { Shared() }
        } finally {
            registryLock.unlock()
        }
    }

    /** A new handle over [name]'s rows, with listeners of its own. */
    fun store(name: String): ObjectStore = Handle(shared(name))

    /** [name]'s one lock. */
    fun lock(name: String): DatabaseLock = shared(name).lock

    private class Handle(private val shared: Shared) : ObjectStore, WipeableObjectStore, ReplicaEpochSource {
        private val openedOn: Long = shared.epoch
        private val listeners = HashMap<Long, MutableList<(ObjectStore.DbEntry) -> Unit>>()

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            shared.lock.withLock {
                shared.rows.storeObject(dbEntry)
                listeners[dbEntry.dataIdHash]?.toList()?.forEach { it(dbEntry) }
            }
        }

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? = shared.lock.withLock { shared.rows.getObject(dataIdHash) }

        override fun setListener(dataIdHash: Long, listener: (update: ObjectStore.DbEntry) -> Unit) = shared.lock.withLock {
            listeners.getOrPut(dataIdHash) { mutableListOf() }.add(listener)
            shared.rows.getObject(dataIdHash)?.let(listener)
            Unit
        }

        override fun removeListener(dataIdHash: Long) = shared.lock.withLock {
            listeners.remove(dataIdHash)
            Unit
        }

        override fun removeObject(dataIdHash: Long) = shared.lock.withLock { shared.rows.removeObject(dataIdHash) }

        override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry> = shared.lock.withLock { shared.rows.rowsOfTypes(typeIds) }

        override fun isEmpty(): Boolean = shared.lock.withLock { shared.rows.isEmpty() }

        override suspend fun wipeAllObjects() = shared.lock.withLock {
            shared.rows.clear()
            shared.epoch = Random.nextLong()
        }

        override fun currentEpoch(): Long? = shared.lock.withLock { shared.epoch.takeIf { it == openedOn } }
    }
}
