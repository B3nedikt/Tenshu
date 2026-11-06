package org.tenshu.core.common

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.tenshu.core.common.proxy.db.DatabaseLock

/**
 * [TestProxy]'s default [DatabaseLock]: a real reentrant lock, the shape production binds on every
 * multi-threaded platform (`ReentrantLockDatabaseLock` on JVM/Android, the shared replica's lock
 * on iOS). A pass-through on JS, which is single-threaded anyway.
 *
 * ⚠⚠ Not `NoOpDatabaseLock`. A [TestProxy] built without a `TestScope` runs its session work on
 * `Dispatchers.Default`, so `initSyncService` puts sync-health, content-transfer and SyncBlock writers
 * on worker threads beside the test thread. With no lock they all hit the plain `HashMap` inside
 * `InMemoryObjectStore` at once, and a read racing a resize answers "Entry not found … while reading
 * array" for a row that exists — the gate-only `ReapRemovalOutboxTest` red of 2026-10-16. That is a
 * harness defect, not a product race: production never runs a `Database` without a lock off JS.
 */
class TestDatabaseLock : DatabaseLock {
    private val lock = reentrantLock()

    override fun <R> withLock(block: () -> R): R = lock.withLock(block)
}
