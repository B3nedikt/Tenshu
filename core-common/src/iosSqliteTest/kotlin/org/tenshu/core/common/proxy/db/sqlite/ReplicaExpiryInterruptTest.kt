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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.15: **the token-expiry interrupt.** When the protection that covers the store's work
 * ends with nothing left to cover a lock (the last token's expiry, the background entered with no
 * time granted), every transaction of the process is stopped before the drain: a running statement
 * is interrupted, the next statement and the `COMMIT` are refused, the access rolls back and the
 * gate drains, so nothing holds a file lock into suspension (`0xdead10cc`). A `COMMIT` already
 * issued finishes. An expiry with the foreground or another token still protecting stops nothing
 * but its own work (W7). And a **job's own token** reaches its own run's statements, whatever else
 * protects.
 *
 * Real stores in temporary directories, fake assertions whose expiration handlers the test calls,
 * the production timer, and a "main thread" that is never the test's. The hold watchdog's own
 * timers stay off (B.9 (131)): only the expiry interrupts here. Every name is made up.
 */
class ReplicaExpiryInterruptTest {

    // ── fakes ──

    private class Assertions : ProtectionAssertions {
        private val lock = NSRecursiveLock()
        private var next = 1L
        private val reasons = HashMap<Long, String>()
        private val handlers = HashMap<Long, () -> Unit>()
        private val ended = HashSet<Long>()

        @kotlin.concurrent.Volatile
        var grantNone = false

        override fun begin(reason: String, onExpired: () -> Unit): Long? = locked {
            if (grantNone) return@locked null
            val id = next++
            reasons[id] = reason
            handlers[id] = onExpired
            id
        }

        override fun end(id: Long) {
            locked {
                check(ended.add(id)) { "assertion $id ended twice" }
            }
        }

        override fun backgroundTimeRemainingMs(): Long = 30_000

        private fun idOf(reason: String): Long = locked { reasons.entries.last { it.value == reason }.key }

        fun isEnded(reason: String): Boolean = locked { idOf(reason) in ended }

        /** The platform calls this assertion's expiration handler, on the calling thread. */
        fun expire(reason: String) = locked { handlers.getValue(idOf(reason)) }()

        private inline fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    private class Work : ProtectedWork {
        val starts = AtomicInt(0)
        val stops = AtomicInt(0)
        var onAwait: (() -> Unit)? = null
        override fun start() {
            starts.incrementAndGet()
        }

        override fun stop() {
            stops.incrementAndGet()
        }

        override fun awaitStopped(timeoutMs: Long) {
            onAwait?.invoke()
        }
    }

    /** A block on another thread, awaited with a deadline, so that one that never ends fails rather than hangs. */
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

    private class Setup(isForeground: Boolean) {
        val process = ReplicaProcess(tempReplica())
        val assertions = Assertions()
        val work = Work()
        val protection = ReplicaProtection(assertions, DispatchProtectionTimer, isMainThread = { false }, log = {})
        val store: SharedReplicaStore = process.store()

        init {
            protection.attach(process.registry)
            protection.install(isForeground, work)
        }

        val gate get() = process.registry.gate
        val watchdog get() = process.registry.holdWatchdog
        val runtimeConnection: SqliteConnection
            get() = assertNotNull(process.registry.handles().single { it.kind == ReplicaHandleKind.RUNTIME }.connection)

        fun count(sql: String = "SELECT count(*) FROM objects"): Long = ReplicaProcess(process.directory).sql { it.queryLong(sql) }
    }

    private fun within(timeoutMs: Long = 10_000, what: String, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            usleep(2_000u)
        }
    }

    /**
     * A statement that runs until something interrupts it: a recursive count to two billion.
     * [running] is set once SQLite is executing it (its progress handler has been asked), so an
     * interrupt sent after that can never be the lost one that lands before the start (B.9 (124)).
     * Its own stop after [selfStopMs] fails the test by time instead of hanging it.
     */
    private fun runUntilStopped(connection: SqliteConnection, running: AtomicInt, selfStopMs: Long = 8_000) {
        val started = TimeSource.Monotonic.markNow()
        connection.stoppableBy(everyOps = 10_000, shouldStop = {
            running.value = 1
            started.elapsedNow().inWholeMilliseconds > selfStopMs
        }) {
            connection.exec("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 2000000000) SELECT count(*) FROM c")
        }
    }

    /** An access whose INSERT runs a statement that only an interrupt stops; the failure it ended with. */
    private fun Setup.accessStuckInAStatement(running: AtomicInt): OnAnotherThread<Throwable?> {
        store.statementFault = { sql -> if (sql.startsWith("INSERT INTO objects")) runUntilStopped(runtimeConnection, running) }
        return OnAnotherThread {
            runCatching {
                store.databaseLock.withLock {
                    store.removeObject(1)
                    store.storeObject(row(2))
                }
            }.exceptionOrNull()
        }
    }

    private fun assertInterruptedByTheExpiry(failure: Throwable?) {
        val expired = assertIs<ProtectionExpired>(failure, "the token-expiry interrupt's refusal")
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(expired.cause).primaryCode, "SQLite's own interrupt")
    }

    // ── (200) the expiry that ends protection ──

    @Test
    fun anExpiryThatEndsProtectionInterruptsARunningStatementAndTheGateDrains() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.store.storeObject(row(1))
        val running = AtomicInt(0)
        val access = s.accessStuckInAStatement(running)
        within(what = "the statement runs") { running.value == 1 }
        val started = TimeSource.Monotonic.markNow()
        s.assertions.expire("wake")
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertInterruptedByTheExpiry(access.await())
        assertTrue(tookMs < 1_500, "the drain waited for a rollback, not out its 2 s: $tookMs ms")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes, "drained: no [quiesce] TIMED OUT")
        assertEquals(1, s.protection.expiryInterrupts.single().second, "one transaction reached")
        assertTrue(s.watchdog.interrupts >= 1)
        assertTrue(s.assertions.isEnded("wake"), "the assertion ended before the handler returned")
        assertEquals(ReplicaFence.COMMIT_FAILED, s.store.fencedBy, "rule (a), as for HOLD_MAX")
        assertFalse(s.gate.isOpen)
        assertEquals(0, s.gate.admittedCount)
        assertEquals(0, s.watchdog.armed)
        assertFalse(s.watchdog.isExpiring, "the expiry ended with its quiesce")
        assertEquals(1L, s.count("SELECT count(*) FROM objects WHERE hash = 1"), "rolled back whole: the delete before it as well")
        assertEquals(0L, s.count("SELECT count(*) FROM objects WHERE hash = 2"))
    }

    @Test
    fun anExpiryBetweenStatementsRefusesTheNextBeforeSqlite() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        val firstWritten = AtomicInt(0)
        val statements = ArrayList<String>()
        val lock = NSRecursiveLock()
        val access = OnAnotherThread {
            runCatching {
                s.store.databaseLock.withLock {
                    s.store.storeObject(row(1))
                    firstWritten.value = 1
                    // The caller's block computes; the expiry lands with no statement running.
                    within(what = "the expiry reaches this transaction") { s.watchdog.expiries == 1 }
                    s.store.statementFault = { sql ->
                        lock.lock()
                        statements += sql
                        lock.unlock()
                    }
                    s.store.storeObject(row(2))
                }
            }.exceptionOrNull()
        }
        within(what = "the first write") { firstWritten.value == 1 }
        s.assertions.expire("wake")
        val refused = assertIs<ProtectionExpired>(access.await())
        assertNull(refused.cause, "refused before SQLite")
        assertTrue(statements.none { it.startsWith("INSERT") }, "the refused statement never reached SQLite: $statements")
        assertEquals(0, s.watchdog.interrupts, "nothing was running to interrupt")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertEquals(ReplicaFence.COMMIT_FAILED, s.store.fencedBy)
        assertEquals(0L, s.count(), "rolled back: the first row too")
    }

    @Test
    fun aCommitAlreadyIssuedWhenTheExpiryLandsFinishes() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        var expiring: OnAnotherThread<Unit>? = null
        s.store.statementFault = { sql ->
            if (sql == "COMMIT" && expiring == null) {
                // The watch is disarmed as the COMMIT is issued (B.9 (124)): the expiry finds nothing.
                expiring = OnAnotherThread { s.assertions.expire("wake") }
                within(what = "the interrupt is issued") { s.protection.expiryInterrupts.isNotEmpty() }
            }
        }
        val failure = OnAnotherThread { runCatching { s.store.storeObject(row(1)) }.exceptionOrNull() }.await()
        assertNull(failure, "the COMMIT finished")
        assertNotNull(expiring).await()
        assertEquals(0, s.protection.expiryInterrupts.single().second, "no hold was armed any more")
        assertEquals(0, s.watchdog.expiries)
        assertNull(s.store.fencedBy)
        assertEquals(1L, s.count(), "committed")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
    }

    @Test
    fun theBackgroundEnteredWithNoTimeGrantedInterruptsAsAnExpiryDoes() {
        val s = Setup(isForeground = true)
        s.store.storeObject(row(1))
        val running = AtomicInt(0)
        val access = s.accessStuckInAStatement(running)
        within(what = "the statement runs") { running.value == 1 }
        s.assertions.grantNone = true
        s.protection.enteredBackground()
        assertInterruptedByTheExpiry(access.await())
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertTrue(s.protection.expiryInterrupts.single().first.startsWith("background with no time"))
        assertFalse(s.watchdog.isExpiring)
    }

    @Test
    fun anExpiryDuringItsOwnReleasesDrainInterruptsWhatTheDrainWaitsFor() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 10_000))
        s.store.storeObject(row(1))
        val running = AtomicInt(0)
        val access = s.accessStuckInAStatement(running)
        within(what = "the statement runs") { running.value == 1 }
        val releasing = OnAnotherThread { token.release() }
        within(what = "the release drains") { !s.gate.isOpen && s.gate.isQuiescing }
        val started = TimeSource.Monotonic.markNow()
        s.assertions.expire("wake")
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertInterruptedByTheExpiry(access.await())
        releasing.await()
        assertTrue(tookMs < 1_500, "not the 2 s cut, nor the 10 s drain: $tookMs ms")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertTrue(s.protection.expiryInterrupts.single().first.endsWith("during its release"))
        assertTrue(s.protection.isLatched)
        assertFalse(s.watchdog.isExpiring)
    }

    @Test
    fun anExpiryBeforeItsReleaseClosesTheGatesInterruptsWhenTheyClose() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 10_000))
        s.store.storeObject(row(1))
        val running = AtomicInt(0)
        val access = s.accessStuckInAStatement(running)
        within(what = "the statement runs") { running.value == 1 }
        var expiring: OnAnotherThread<Unit>? = null
        // Step 3, the gates still open: the expiry records the interrupt for its own quiesce's close.
        s.work.onAwait = {
            expiring = OnAnotherThread { s.assertions.expire("wake") }
            usleep(100_000u)
        }
        val started = TimeSource.Monotonic.markNow()
        token.release()
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertInterruptedByTheExpiry(access.await())
        assertNotNull(expiring).await()
        assertTrue(tookMs < 2_000, "the drain waited for the rollback: $tookMs ms")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertEquals(1, s.protection.expiryInterrupts.single().second)
    }

    // ── (202) what an expiry does not stop ──

    @Test
    fun anExpiryWhileTheForegroundProtectsStopsNothingAndWritesGoOn() {
        // §13's bench step: "simulate a task expiry while foregrounded → loops still running,
        // writes still succeed" (W7).
        val s = Setup(isForeground = true)
        assertNotNull(s.protection.acquire("wake"))
        val firstWritten = AtomicInt(0)
        val access = OnAnotherThread {
            runCatching {
                s.store.databaseLock.withLock {
                    s.store.storeObject(row(1))
                    firstWritten.value = 1
                    within(what = "the expiry") { s.assertions.isEnded("wake") }
                    s.store.storeObject(row(2))
                }
            }.exceptionOrNull()
        }
        within(what = "the first write") { firstWritten.value == 1 }
        s.assertions.expire("wake")
        assertNull(access.await(), "the transaction committed")
        s.store.storeObject(row(3))
        assertEquals(3L, s.count())
        assertNull(s.store.fencedBy)
        assertEquals(emptyList(), s.protection.expiryInterrupts)
        assertEquals(0, s.watchdog.expiries)
        assertEquals(0, s.work.stops.value, "the loops still run")
        assertTrue(s.gate.isOpen)
        assertFalse(s.protection.isLatched)
    }

    @Test
    fun anEntryTokenExpiringWhileAnotherProtectsStopsNoStatement() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        assertNotNull(s.protection.acquire("entry:made-up"))
        val firstWritten = AtomicInt(0)
        val access = OnAnotherThread {
            runCatching {
                s.store.databaseLock.withLock {
                    s.store.storeObject(row(1))
                    firstWritten.value = 1
                    within(what = "the expiry") { s.assertions.isEnded("entry:made-up") }
                    s.store.storeObject(row(2))
                }
            }.exceptionOrNull()
        }
        within(what = "the first write") { firstWritten.value == 1 }
        s.assertions.expire("entry:made-up")
        assertNull(access.await())
        assertEquals(2L, s.count())
        assertEquals(emptyList(), s.protection.expiryInterrupts)
        assertTrue(s.gate.isOpen, "the wake still protects")
    }

    @Test
    fun theExpiryEndsWithItsQuiesceSoTheNextForegroundWritesNormally() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.store.storeObject(row(1))
        s.assertions.expire("wake")
        assertEquals(1, s.protection.expiryInterrupts.size)
        assertFalse(s.watchdog.isExpiring)
        s.protection.enteredForeground()
        // A fresh store: the old handle served on through the expiry (nothing was mid-access).
        assertNull(s.store.fencedBy)
        s.store.storeObject(row(2))
        assertEquals(2L, s.count())
    }

    @Test
    fun anAccessAdmittedAsTheQuiesceReopensIsNotMetByItsExpiry() {
        // (201): the expiry ends BEFORE the gates reopen. Protection comes back (the foreground)
        // while the expiry's quiesce is ending; an access waiting at the gate is admitted by the
        // reopen and must commit, never meet the interrupt of a protection that is gone.
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.store.storeObject(row(1))
        var access: OnAnotherThread<Throwable?>? = null
        s.protection.betweenExpiryEndAndReopen = {
            s.protection.betweenExpiryEndAndReopen = null
            s.protection.enteredForeground()
            access = OnAnotherThread { runCatching { s.store.storeObject(row(2)) }.exceptionOrNull() }
            // Long enough for an access to arm its watch if the gate were already open here.
            usleep(300_000u)
        }
        s.assertions.expire("wake")
        assertNull(assertNotNull(access).await(), "the access admitted by the reopen committed")
        assertNull(s.store.fencedBy)
        assertEquals(0, s.watchdog.expiries, "nothing was reached: nothing was held at the expiry")
        assertEquals(2L, s.count())
    }

    // ── (201) the watchdog's half ──

    @Test
    fun aHoldArmedDuringTheExpiryIsExpiredAsItIsArmedAndNoneAfterIt() {
        val process = ReplicaProcess(tempReplica())
        process.store().close()
        val connection = rawConnection(process.databasePath)
        try {
            val watchdog = ReplicaHoldWatchdog().apply {
                after = { _, _ -> }
                clock = { 0L }
            }
            val before = watchdog.arm("before", connection, 1_000)
            assertEquals(1, watchdog.expireAll("made up"))
            assertFailsWith<ProtectionExpired> { before.enterStatement() }
            before.disarm()
            val during = watchdog.arm("during", connection, 1_000)
            assertFailsWith<ProtectionExpired> { during.beforeCommit() }
            during.disarm()
            watchdog.endExpiry()
            val after = watchdog.arm("after", connection, 1_000)
            after.enterStatement()
            after.exitStatement()
            after.beforeCommit()
            assertEquals(2, watchdog.expiries)
            assertEquals(0, watchdog.armed)
            assertEquals(0, watchdog.expireAll("again"), "disarmed holds are not reached")
        } finally {
            connection.close()
        }
    }

    @Test
    fun anExpiredHoldInsideAnExemptWindowOrDeferredIsRefusedAtItsNextStatement() {
        val process = ReplicaProcess(tempReplica())
        process.store().close()
        val connection = rawConnection(process.databasePath)
        try {
            val watchdog = ReplicaHoldWatchdog().apply {
                after = { _, _ -> }
                clock = { 0L }
            }
            // A checkpoint's CKPT_MAX in the foreground defers; the expiry does not.
            val deferred = watchdog.arm("checkpoint", connection, 1_650, deferWhile = { true })
            val exempt = watchdog.arm("open", connection, 1_000)
            exempt.exempt { watchdog.expireAll("made up") }
            assertFailsWith<ProtectionExpired> { deferred.enterStatement() }
            assertFailsWith<ProtectionExpired> { exempt.enterStatement() }
            deferred.disarm()
            exempt.disarm()
        } finally {
            connection.close()
        }
    }

    // ── (203) a job's own token ──

    @Test
    fun aJobsScopeReachesItsOwnHoldsAlonePastAndFuture() {
        val process = ReplicaProcess(tempReplica())
        process.store().close()
        val connection = rawConnection(process.databasePath)
        try {
            val watchdog = ReplicaHoldWatchdog().apply {
                after = { _, _ -> }
                clock = { 0L }
            }
            val scope = ReplicaExpiryScope("a made-up job token")
            val outside = watchdog.arm("access", connection, 1_000)
            val (chunk, checkpoint) = scope.around {
                watchdog.arm("chunk", connection, 1_000) to watchdog.arm("checkpoint", connection, 1_650, deferWhile = { true })
            }
            assertNull(ReplicaExpiryScope.current, "restored once the run returned")
            scope.expire()
            assertFailsWith<ProtectionExpired> { chunk.enterStatement() }
            assertFailsWith<ProtectionExpired> { checkpoint.enterStatement() }
            outside.enterStatement()
            outside.exitStatement()
            outside.beforeCommit()
            val late = scope.around { watchdog.arm("end-of-job checkpoint", connection, 1_650) }
            assertFailsWith<ProtectionExpired> { late.enterStatement() }
            listOf(chunk, checkpoint, late).forEach { it.disarm() }
        } finally {
            connection.close()
        }
    }

    @Test
    fun aJobsTokenExpiringInterruptsItsRunningChunkWhileTheForegroundStillProtects() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory, ReplicaLimits(chunkMaxRows = 5))
        val store = process.store()
        store.databaseLock.withLock { (1L..20L).forEach { store.storeObject(row(it)) } }
        store.close()
        process.sql { it.exec("INSERT INTO meta (k, v) VALUES ('wipe', ${wipeFrame()})") }
        val assertions = Assertions()
        val protection = ReplicaProtection(assertions, DispatchProtectionTimer, isMainThread = { false }, log = {})
        protection.attach(process.registry)
        protection.install(isForeground = true)
        val outcomes = ArrayList<ReplicaRunOutcome>()
        val told = NSCondition()
        val worker = ReplicaWorker(
            protection = protection,
            dispatch = { block -> dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), block) },
            onReplacementOutcome = { _, outcome ->
                told.lock()
                outcomes += outcome
                told.broadcast()
                told.unlock()
            },
        )
        val job = process.replacement()
        val running = AtomicInt(0)
        job.statementFault = {
            if (running.value == 0) {
                val connection = assertNotNull(process.registry.handles().first { it.kind == ReplicaHandleKind.REPLACEMENT }.connection)
                runUntilStopped(connection, running)
            }
        }
        val started = TimeSource.Monotonic.markNow()
        worker.scheduleReplacement(job)
        within(what = "the chunk's statement runs") { running.value == 1 }
        assertions.expire("replacement:test")
        told.lock()
        try {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(30.0)
            while (outcomes.isEmpty()) if (!told.waitUntilDate(deadline)) fail("no outcome")
        } finally {
            told.unlock()
        }
        val stopped = assertIs<ReplicaRunOutcome.Stopped>(outcomes.single())
        assertEquals("ProtectionExpired", stopped.reason)
        assertInterruptedByTheExpiry(stopped.cause)
        assertTrue(started.elapsedNow().inWholeMilliseconds < 6_000, "the expiry stopped it, not the statement's own stop")
        assertEquals(emptyList(), protection.expiryInterrupts, "nothing but the job's own run: the foreground still protects")
        assertTrue(process.registry.gate.isOpen)
        assertEquals(0, process.registry.holdWatchdog.armed)
        assertTrue(process.sql { it.queryLong("SELECT count(*) FROM meta WHERE k = 'wipe'") } == 1L, "the wipe is still owed")
    }

    @Test
    fun aJobsTokenExpiringStopsItsCheckpointUncountedAsACeilingStop() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        val scope = ReplicaExpiryScope("a made-up job token")
        // Inside the checkpoint's bracket: the interrupt reaches `sqlite3_wal_checkpoint_v2`, the
        // connection's very next call, which then returns SQLITE_INTERRUPT (it has frames to copy).
        store.checkpointer.fault = { scope.expire() }
        assertTrue(scope.around { store.foregroundCheckpoint() }, "the checkpoint ran")
        assertEquals(1, store.checkpointer.expiryStops)
        assertEquals(0, process.registry.checkpointCeilingStopsInARow, "not a CKPT_MAX stop")
        assertEquals(1, process.registry.holdWatchdog.expiries)
        assertNull(store.fencedBy, "a stopped checkpoint fences nothing")
        store.checkpointer.fault = null
        store.storeObject(row(2))
        assertEquals(2L, process.sql { it.queryLong("SELECT count(*) FROM objects") })
    }
}
