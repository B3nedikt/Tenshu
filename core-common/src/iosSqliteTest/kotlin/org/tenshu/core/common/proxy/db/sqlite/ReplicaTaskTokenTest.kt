@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.23: **a task's token.** Its assertion is the `BGTask` the platform already runs, so
 * nothing is begun or ended on the platform for it; while it is live, §4.2.1's admission checks
 * read the task's time (no deadline until its expiry) and not `backgroundTimeRemaining`; its
 * release quiesces as any last token's; and its expiry, from the task's `expirationHandler`, is
 * B.15.3's first row: every transaction interrupted, the gate drained, no lock left held.
 *
 * Real stores in temporary directories, fake assertions, the production timer, and a "main
 * thread" that is never the test's. The hold watchdog's own timers stay off (B.9 (131)).
 */
class ReplicaTaskTokenTest {

    private class Assertions : ProtectionAssertions {
        private val lock = NSRecursiveLock()
        private var next = 1L
        val begun = AtomicInt(0)
        val ended = AtomicInt(0)

        /** What `backgroundTimeRemaining` says: far less than any operation needs, unless a test says otherwise. */
        @kotlin.concurrent.Volatile
        var remainingMs = 100L

        override fun begin(reason: String, onExpired: () -> Unit): Long {
            begun.incrementAndGet()
            lock.lock()
            try {
                return next++
            } finally {
                lock.unlock()
            }
        }

        override fun end(id: Long) {
            ended.incrementAndGet()
        }

        override fun backgroundTimeRemainingMs(): Long = remainingMs
    }

    private class Setup(isForeground: Boolean = false) {
        val process = ReplicaProcess(tempReplica())
        val assertions = Assertions()
        val protection = ReplicaProtection(assertions, DispatchProtectionTimer, isMainThread = { false }, log = {})
        val store: SharedReplicaStore = process.store()

        init {
            protection.attach(process.registry)
            protection.install(isForeground)
        }

        val gate get() = process.registry.gate
        val watchdog get() = process.registry.holdWatchdog
        val runtimeConnection: SqliteConnection
            get() = assertNotNull(process.registry.handles().single { it.kind == ReplicaHandleKind.RUNTIME }.connection)

        fun count(): Long = ReplicaProcess(process.directory).sql { it.queryLong("SELECT count(*) FROM objects") }
    }

    private class OnAnotherThread<T>(block: () -> T) {
        private val condition = NSCondition()
        private var result: Result<T>? = null

        init {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                val r = runCatching(block)
                condition.lock()
                result = r
                condition.broadcast()
                condition.unlock()
            }
        }

        fun await(timeoutMs: Long = 30_000): T {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (result == null) {
                    if (!condition.waitUntilDate(deadline)) break
                }
                return (result ?: fail("still running after $timeoutMs ms")).getOrThrow()
            } finally {
                condition.unlock()
            }
        }
    }

    private fun within(timeoutMs: Long = 10_000, what: String, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            usleep(2_000u)
        }
    }

    /** An access whose INSERT runs a statement only an interrupt stops: the failure it ended with. */
    private fun Setup.accessStuckInAStatement(running: AtomicInt): OnAnotherThread<Throwable?> {
        store.statementFault = { sql ->
            if (sql.startsWith("INSERT INTO objects")) {
                val started = TimeSource.Monotonic.markNow()
                runtimeConnection.stoppableBy(everyOps = 10_000, shouldStop = {
                    running.value = 1
                    started.elapsedNow().inWholeMilliseconds > 8_000
                }) {
                    runtimeConnection.exec("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 2000000000) SELECT count(*) FROM c")
                }
            }
        }
        return OnAnotherThread {
            runCatching {
                store.databaseLock.withLock {
                    store.removeObject(1)
                    store.storeObject(row(2))
                }
            }.exceptionOrNull()
        }
    }

    /** ⚠⚠ The point of an expiry: a sibling process can take the write lock at once — nothing of ours holds one. */
    private fun Setup.assertNoWriteLockHeld() {
        ReplicaProcess(process.directory).sql { sibling ->
            sibling.exec("BEGIN IMMEDIATE")
            sibling.exec("ROLLBACK")
        }
    }

    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    // ── the lifecycle ──

    @Test
    fun aTaskTokenBeginsNoPlatformAssertionProtectsTheBackgroundAndItsReleaseQuiesces() {
        val s = Setup()
        assertFalse(s.gate.isOpen, "a background launch starts with its gate closed")
        val token = assertNotNull(s.protection.acquireTask("task:made-up"))
        assertEquals(0, s.assertions.begun.value, "the task is the assertion: no beginBackgroundTask")
        assertTrue(s.protection.isProtected)
        assertTrue(s.gate.isOpen)
        s.store.storeObject(row(1))
        assertEquals(1L, s.count())

        token.release()
        assertFalse(s.protection.isProtected)
        assertFalse(s.gate.isOpen, "the last token's release closes the gate")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertEquals(emptyList(), s.protection.expiryInterrupts, "a release interrupts nothing (200)")
        assertFalse(s.protection.isLatched)
        assertEquals(0, s.assertions.ended.value, "and ends none")
        s.assertNoWriteLockHeld()
    }

    @Test
    fun aSecondReleaseAndAnExpiryAfterTheReleaseChangeNothing() {
        val s = Setup()
        val token = assertNotNull(s.protection.acquireTask("task:made-up"))
        token.release()
        token.release()
        token.expire()
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes, "one quiesce")
        assertEquals(emptyList(), s.protection.expiryInterrupts)
        assertFalse(s.protection.isLatched, "an expiry after the release is not an expiry")
        assertEquals(0, s.protection.liveTokenCount)
    }

    @Test
    fun anExpiryMidWriteInterruptsItQuiescesTheStoreAndLeavesNoWriteLock() {
        val s = Setup()
        val token = assertNotNull(s.protection.acquireTask("task:made-up"))
        s.store.storeObject(row(1))
        val running = AtomicInt(0)
        val access = s.accessStuckInAStatement(running)
        within(what = "the statement runs") { running.value == 1 }

        val started = TimeSource.Monotonic.markNow()
        token.expire()
        val tookMs = started.elapsedNow().inWholeMilliseconds

        val expired = assertIs<ProtectionExpired>(access.await(), "the token-expiry interrupt's refusal")
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(expired.cause).primaryCode)
        assertTrue(tookMs < 1_500, "the drain waited for a rollback, not out its 2 s: $tookMs ms")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes, "no [quiesce] TIMED OUT")
        assertEquals(1, s.protection.expiryInterrupts.single().second, "one transaction reached")
        assertTrue(s.protection.isLatched, "the expiry that ended protection latches (§4.4)")
        assertFalse(s.gate.isOpen)
        assertEquals(0, s.gate.admittedCount)
        assertEquals(0, s.watchdog.armed)
        assertFalse(s.watchdog.isExpiring)
        assertEquals(1L, s.count(), "rolled back whole")
        s.assertNoWriteLockHeld()

        // Ended once: a release after the expiry, and a second expiry, are no-ops.
        token.release()
        token.expire()
        assertEquals(1, s.protection.quiesceOutcomes.size)
        assertEquals(1, s.protection.expiryInterrupts.size)
        assertEquals(0, s.assertions.begun.value + s.assertions.ended.value, "no platform assertion at any step")
    }

    @Test
    fun anExpiryWhileAnotherTokenProtectsStopsNoStatement() {
        val s = Setup()
        s.assertions.remainingMs = 60_000
        val task = assertNotNull(s.protection.acquireTask("task:made-up"))
        assertNotNull(s.protection.acquire("wake"))
        task.expire()
        assertEquals(60_000L, s.protection.remainingMs(), "with the task gone, the wake's own time again")
        assertEquals(emptyList(), s.protection.expiryInterrupts, "W7: another token still protects")
        assertTrue(s.gate.isOpen)
        s.store.storeObject(row(1))
        assertEquals(1L, s.count())
    }

    // ── admission: the task's time ──

    @Test
    fun admissionReadsTheTasksTimeNotBackgroundTimeRemaining() {
        val s = Setup()
        // backgroundTimeRemaining says 100 ms: under any operation's reserve.
        val task = assertNotNull(s.protection.acquireTask("task:made-up"))
        assertEquals(Long.MAX_VALUE, s.protection.remainingMs(), "the task's time: no deadline until its expiry")
        assertNull(s.gate.refusal(ReplicaOperation.WRITE, s.process.limits, opensConnection = true))
        assertNull(s.gate.refusal(ReplicaOperation.CHECKPOINT_RESTART, s.process.limits, opensConnection = true))
        s.store.storeObject(row(1))
        assertEquals(1L, s.count(), "a write under the task's token is admitted")
        task.release()

        // The same background under an entry's token reads backgroundTimeRemaining, and is refused.
        val wake = assertNotNull(s.protection.acquire("wake"))
        assertEquals(100L, s.protection.remainingMs())
        assertNotNull(s.gate.refusal(ReplicaOperation.WRITE, s.process.limits, opensConnection = true))
        val refused = assertNotNull(runCatching { s.store.storeObject(row(2)) }.exceptionOrNull(), "a write with 100 ms left")
        assertTrue(refused.chain().any { it is ReplicaAdmissionDeferred }, "deferred by §4.2.1: ${refused.chain()}")
        wake.release()
    }

    @Test
    fun inTheForegroundATaskTokenLeavesNoDeadlineAsBefore() {
        val s = Setup(isForeground = true)
        assertNotNull(s.protection.acquireTask("task:made-up"))
        assertNull(s.protection.remainingMs(), "the foreground's null stays the foreground's")
    }
}
