package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.AbstractQueuedSynchronizer
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ A [TestProxy] built without a `TestScope` runs its session work on `Dispatchers.Default`, so a
 * test that calls `initSyncService` has store writers on worker threads beside its own. Its default
 * `Database` must therefore exclude them the way production's does, or the plain map inside
 * `InMemoryObjectStore` is read mid-resize and answers "Entry not found" for a row that exists —
 * `ReapRemovalOutboxTest`'s gate-only red of 2026-10-16 (34 in 5,000 runs under the old no-op lock,
 * 0 in 5,000 under a reentrant one).
 *
 * Deterministic: the other thread either runs to the end (no lock — red) or parks on the lock's
 * queue (green); the wait below ends on exactly one of the two, never on a timer.
 */
class TestProxyDefaultLockTest {

    @Test
    fun aSecondThreadWaitsOutTheTestThreadsTransaction() {
        val proxy = TestProxy()
        val database = proxy.database
        val id = DataId.StringDataId.create(proxy.tContext, "test/lockProbe", "row-1")
        val otherRan = AtomicBoolean(false)
        lateinit var other: Thread

        database.transaction {
            other = thread(name = "lock-probe") {
                database.getData(id)
                otherRan.set(true)
            }
            // ⚠ Assumes the only `ReentrantLock` `getData` can park on is the database lock (atomicfu's
            // JVM `ReentrantLock` is `java.util.concurrent.locks.ReentrantLock`). `TestDatabaseLock` keeps
            // its lock private, so the blocker is matched by kind, not instance.
            while (other.isAlive && !parkedOnAReentrantLock(other)) Thread.onSpinWait()
            assertFalse(otherRan.get(), "another thread read the store while this one held the database lock")
        }

        other.join()
        assertTrue(otherRan.get(), "released, so the waiting reader proceeds")
    }

    private fun parkedOnAReentrantLock(thread: Thread): Boolean =
        (LockSupport.getBlocker(thread) as? AbstractQueuedSynchronizer)?.javaClass?.enclosingClass == ReentrantLock::class.java
}
