package org.tenshu.systemtest.di

import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import java.util.concurrent.ConcurrentHashMap

/**
 * The harness's object store: an [InMemoryObjectStore] that records every row access made
 * **without the `DatabaseLock`**, so [org.tenshu.systemtest.TestSystem.shutdown] can fail the test
 * that made one.
 *
 * ⚠⚠ No `ObjectStore` is thread-safe on its own; each relies on its callers holding the lock. One
 * caller that does not is a data race against every op on the device, and it does not look like
 * one: it shows up roughly once in a thousand runs, under load, as a row that exists reading as
 * absent, in whichever test happened to be reading. This turns that into a failure on every run,
 * naming the accessor.
 *
 * ⚠ Recorded, not thrown at the access: the callers are scheduler ticks whose catch-alls would
 * swallow the throw and leave a test that hangs or passes.
 *
 * ⚠ [clear] and [isEmpty] are unchecked here: `isEmpty` is a single size read that
 * `DatabaseImpl.isEmpty` itself makes without the lock, and `clear` is final in the store.
 * ⚠⚠ That does not make a bare `clear()` from a test thread safe — it raced the scheduler's next
 * tick. A test wipes through `TestClient.wipeStoreWhileNotRunning`, which holds the lock.
 */
internal class LockCheckedObjectStore(
    private val lockHeldByCurrentThread: () -> Boolean,
) : InMemoryObjectStore() {

    private val unlocked = ConcurrentHashMap<String, Int>()

    /** Each distinct unlocked access seen, as "what <- caller <- caller…", with how often. */
    val unlockedAccesses: Map<String, Int> get() = unlocked.toMap()

    private fun check(access: String, dataIdHash: Long) {
        if (lockHeldByCurrentThread()) return
        val callers = Throwable().stackTrace
            .filter { it.className.startsWith("org.tenshu") || it.className.startsWith("app.tenshu") }
            .filterNot { it.className == LockCheckedObjectStore::class.java.name }
            .take(CALLER_FRAMES)
            .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
        unlocked.merge("$access(${rowName(dataIdHash)}) <- $callers", 1, Int::plus)
    }

    /** The reserved rows sit at the bottom of the hash space; everything else is data. */
    private fun rowName(dataIdHash: Long): String =
        if (dataIdHash < Long.MIN_VALUE + RESERVED_ROWS) "reserved row ${dataIdHash - Long.MIN_VALUE}" else "data row"

    override fun storeObject(dbEntry: ObjectStore.DbEntry) {
        check("storeObject", dbEntry.dataIdHash)
        super.storeObject(dbEntry)
    }

    override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
        check("getObject", dataIdHash)
        return super.getObject(dataIdHash)
    }

    override fun removeObject(dataIdHash: Long) {
        check("removeObject", dataIdHash)
        super.removeObject(dataIdHash)
    }

    override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry> {
        check("rowsOfTypes", 0L)
        return super.rowsOfTypes(typeIds)
    }

    private companion object {
        const val CALLER_FRAMES = 6
        const val RESERVED_ROWS = 64
    }
}
