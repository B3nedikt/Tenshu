package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.security.syncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every cursor row [SyncManagerImpl] keeps in the object store is read and written **inside the
 * store's lock**.
 *
 * ⚠⚠ The cursor rows are the one store access that does not go through `DatabaseImpl`, and no
 * `ObjectStore` is thread-safe on its own. Unlocked, each accessor below raced every op on the
 * device, about once in a thousand runs and only under load — so this asserts the lock on each
 * access directly, where a system test could only wait for the race to come round.
 *
 * ⚠ One accessor per assertion, by name: a single "no unlocked access" over all of them would
 * still pass if an accessor stopped touching the store at all.
 */
class CursorRowsUnderStoreLockTest {

    private val lock = CountingLock()
    private val store = LockRecordingStore(lock)
    private val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
    private val syncManager = SyncManagerImpl(
        context = testProxy.tContext,
        cryptoManager = testProxy.tContext.syncCryptoManager(),
        storageDelegate = InMemorySecureStorageDelegate(),
        objectStore = store,
        storeLock = lock,
    )

    @Test fun theBlockCursor() = assertLocked("lastSyncTimestamp") {
        syncManager.lastSyncTimestamp = 7L
        assertEquals(7L, syncManager.lastSyncTimestamp)
    }

    @Test fun theTombstoneCursor() = assertLocked("lastTombstoneSyncTimestamp") {
        syncManager.lastTombstoneSyncTimestamp // absent: reads 0
        syncManager.lastTombstoneSyncTimestamp = 9L
        assertEquals(9L, syncManager.lastTombstoneSyncTimestamp)
    }

    @Test fun theBackfillFloor_andReopeningIt() = assertLocked("backfillCursor") {
        syncManager.backfillCursor = 11L
        assertEquals(11L, syncManager.backfillCursor)
        syncManager.reopenBackfill()
        assertEquals(null, syncManager.backfillCursor)
    }

    @Test fun theBackfillTarget() = assertLocked("backfillTarget") {
        syncManager.backfillTarget = 13L
        assertEquals(13L, syncManager.backfillTarget)
    }

    @Test fun theCursorResetOfADeviceForgettingItsRealm() = assertLocked("forgetThisDevice") {
        syncManager.forgetThisDevice()
    }

    @Test fun theCursorResetOfAJoinerDiscardingItsOwnRealm() = assertLocked("discardSupersededRealm") {
        syncManager.discardSupersededRealm()
    }

    private fun assertLocked(what: String, accesses: () -> Unit) {
        store.accesses.clear()
        accesses()
        // Anti-vacuity: an accessor that never reached the store proves nothing about its lock.
        assertTrue(store.accesses.isNotEmpty(), "$what never touched the object store")
        assertEquals(
            emptyList(),
            store.accesses.filterNot { it.locked }.map { it.what },
            "$what reached the object store outside the store lock",
        )
    }

    private class CountingLock : DatabaseLock {
        var depth = 0
            private set

        override fun <R> withLock(block: () -> R): R {
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }
    }

    private class Access(val what: String, val locked: Boolean)

    /** A map-backed store that notes, per access, whether [lock] was held when it arrived. */
    private class LockRecordingStore(private val lock: CountingLock) : ObjectStore {
        val accesses = mutableListOf<Access>()
        private val rows = mutableMapOf<Long, ObjectStore.DbEntry>()

        private fun note(what: String) {
            accesses += Access(what, locked = lock.depth > 0)
        }

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            note("storeObject")
            rows[dbEntry.dataIdHash] = dbEntry
        }

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            note("getObject")
            return rows[dataIdHash]
        }

        override fun removeObject(dataIdHash: Long) {
            note("removeObject")
            rows.remove(dataIdHash)
        }

        override fun setListener(dataIdHash: Long, listener: (update: ObjectStore.DbEntry) -> Unit) = Unit

        override fun removeListener(dataIdHash: Long) = Unit
    }
}
