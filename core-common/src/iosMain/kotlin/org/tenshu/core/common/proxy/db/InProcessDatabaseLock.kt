package org.tenshu.core.common.proxy.db

import platform.Foundation.NSRecursiveLock

/**
 * The iOS [DatabaseLock] for a store that **one process** uses: an [NSRecursiveLock], the twin of the
 * JVM's `ReentrantLockDatabaseLock` (nested `withLock` on one thread must not deadlock — the DB
 * layer nests freely).
 *
 * ⚠ Not the shared replica's lock. The SQLite shared replica carries its own
 * (`SharedReplicaStore.databaseLock`: every outermost access is one SQLite transaction), and the
 * route-1 binding puts it in every production graph. This one is what a graph binds before that
 * override, and what a test's in-process store binds.
 */
class InProcessDatabaseLock : DatabaseLock {
    private val lock = NSRecursiveLock()

    override fun <R> withLock(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
