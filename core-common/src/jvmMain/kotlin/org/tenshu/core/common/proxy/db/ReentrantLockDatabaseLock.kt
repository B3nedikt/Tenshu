package org.tenshu.core.common.proxy.db

import java.util.concurrent.locks.ReentrantLock

/**
 * JVM/Android [DatabaseLock] backed by `java.util.concurrent.locks.ReentrantLock`.
 * Reentrant on the holding thread; blocks other threads until release.
 */
class ReentrantLockDatabaseLock : DatabaseLock {
    private val lock = ReentrantLock()

    /** Whether the calling thread is inside [withLock] — for asserting the "under the lock" contract. */
    val isHeldByCurrentThread: Boolean get() = lock.isHeldByCurrentThread

    override fun <R> withLock(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
