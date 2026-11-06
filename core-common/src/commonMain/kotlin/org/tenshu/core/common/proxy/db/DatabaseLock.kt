package org.tenshu.core.common.proxy.db

/**
 * Reentrant mutual-exclusion lock guarding a [org.tenshu.core.common.proxy.Database]
 * against concurrent mutation. [DatabaseImpl] runs every public method inside
 * [withLock] so individual operations are atomic; multi-step caller-level
 * sequences compose via [org.tenshu.core.common.proxy.Database.transaction].
 *
 * Reentrancy matters: `transaction { saveData(); addToArray(...) }` acquires
 * the lock once at the `transaction` boundary, and each inner call
 * re-acquires the same thread's existing hold. A non-reentrant lock would
 * self-deadlock on the inner acquire.
 *
 * Implementations are platform-specific and bound via Koin (see
 * `cryptoBackendsModule` for the prior-art pattern):
 * - JVM / Android: [ReentrantLockDatabaseLock], a wrapper over
 *   `java.util.concurrent.locks.ReentrantLock`.
 * - JS: [NoOpDatabaseLock] — a pass-through. JS is single-threaded;
 *   non-suspending `withLock` bodies can't be interleaved by the dispatcher.
 * - Tests: `TestDatabaseLock` (`:core-test-utils`, the `TestProxy` default),
 *   because a test proxy's session work runs on `Dispatchers.Default`.
 * - iOS: the SQLite shared replica's own lock (`SharedReplicaStore.databaseLock`, every
 *   outermost access one SQLite transaction), because route-1 puts several app processes on
 *   one shared replica; `InProcessDatabaseLock` before that binding and in tests.
 */
interface DatabaseLock {
    fun <R> withLock(block: () -> R): R
}

/**
 * Pass-through [DatabaseLock]. ⚠ Safe only when every access to the store is on ONE thread: JS
 * production, or a test whose scope is single-threaded. A test with any `Dispatchers.Default` work
 * (every `init*Service` observer on a default `TestProxy`) takes `TestDatabaseLock` instead.
 * ⚠ Not iOS — that binds the shared replica's own lock.
 */
class NoOpDatabaseLock : DatabaseLock {
    override fun <R> withLock(block: () -> R): R = block()
}
