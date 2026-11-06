@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The hold watchdog (§4.2.1, S8; Appendix B.9): `HOLD_MAX` on one transaction, enforced while a
 * statement runs (an interrupt) and between statements (the next statement or the `COMMIT`
 * refused), disarmed once `COMMIT` is issued. Every store here is made up. The watchdog's timer
 * is the test's: each deadline is fired by hand, on this thread or on another, at the point the
 * case is about. (Every other suite runs with the watchdog off: `ReplicaProcess`.)
 */
class ReplicaHoldWatchdogTest {

    /** The watchdog's timer, held: every armed deadline (and re-interrupt) waits here until a test fires it. */
    private class Timers {
        private val lock = NSLock()
        private val blocks = ArrayList<() -> Unit>()

        /** Every delay a deadline was armed with, in order (re-interrupts included). */
        val delays = ArrayList<Long>()
        val after: (Long, () -> Unit) -> Unit = { delay, block ->
            lock.lock()
            blocks += block
            delays += delay
            lock.unlock()
        }

        private fun take(): List<() -> Unit> {
            lock.lock()
            try {
                return blocks.toList().also { blocks.clear() }
            } finally {
                lock.unlock()
            }
        }

        /** Fires every deadline held so far, on this thread. */
        fun fireAll() = take().forEach { it() }

        /** Fires every deadline held so far on another thread, [ms] from now: while this one runs a statement. */
        fun fireAllSoon(ms: Long = 30) {
            val now = take()
            DispatchProtectionTimer.after(ms) { now.forEach { it() } }
        }
    }

    private fun ReplicaProcess.timers(): Timers = Timers().also { registry.holdWatchdog.after = it.after }

    private fun ReplicaProcess.connectionOf(kind: ReplicaHandleKind): SqliteConnection =
        assertNotNull(registry.handles().first { it.kind == kind }.connection)

    /**
     * A statement that runs until something stops it: a recursive count to two billion. If the
     * watchdog never interrupts it, its own progress handler does after 5 s, with the same code, so
     * a broken watchdog fails the test (by its count or its time) instead of hanging it.
     */
    private fun runUntilStopped(connection: SqliteConnection, selfStopMs: Long = 5_000) {
        val started = TimeSource.Monotonic.markNow()
        connection.stoppableBy(everyOps = 10_000, shouldStop = { started.elapsedNow().inWholeMilliseconds > selfStopMs }) {
            connection.exec("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 2000000000) SELECT count(*) FROM c")
        }
    }

    private fun countOf(directory: String, sql: String = "SELECT count(*) FROM objects"): Long = ReplicaProcess(directory).sql { it.queryLong(sql) }

    // ── an ordinary write transaction ──

    @Test
    fun aStatementRunningPastHoldMaxIsInterruptedAndTheHandleFencedCommitFailed() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        store.storeObject(row(1))
        val timers = process.timers()
        val started = TimeSource.Monotonic.markNow()
        store.statementFault = { sql ->
            if (sql.startsWith("INSERT INTO objects")) {
                timers.fireAllSoon()
                runUntilStopped(process.connectionOf(ReplicaHandleKind.RUNTIME))
            }
        }
        val refused = assertFailsWith<HoldCeilingExceeded> {
            store.databaseLock.withLock {
                store.removeObject(1)
                store.storeObject(row(2))
            }
        }
        assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000, "the watchdog stopped it, not the test's own 5 s stop")
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(refused.cause).primaryCode, "SQLite's own interrupt")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy, "rule (a)")
        assertTrue(process.registry.holdWatchdog.interrupts >= 1)
        assertEquals(0, process.registry.holdWatchdog.armed, "the rollback disarmed it")
        // Rolled back whole: the delete before it as well.
        assertEquals(1L, countOf(directory, "SELECT count(*) FROM objects WHERE hash = 1"))
        assertEquals(0L, countOf(directory, "SELECT count(*) FROM objects WHERE hash = 2"))
    }

    @Test
    fun aBlockIdlePastHoldMaxHasItsNextStatementRefusedBeforeSqlite() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        val timers = process.timers()
        val statements = ArrayList<String>()
        assertFailsWith<HoldCeilingExceeded> {
            store.databaseLock.withLock {
                store.storeObject(row(1))
                // The caller's block computes; the deadline passes with no statement running.
                timers.fireAll()
                store.statementFault = { statements += it }
                store.storeObject(row(2))
            }
        }
        assertEquals(0, process.registry.holdWatchdog.interrupts, "nothing was running to interrupt")
        assertTrue(statements.none { it.startsWith("INSERT") }, "the refused statement never reached SQLite: $statements")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
        assertEquals(0L, countOf(directory), "rolled back: the first row too")
    }

    @Test
    fun aBlockIdlePastHoldMaxHasItsCommitRefused() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        val timers = process.timers()
        var commits = 0
        store.statementFault = { if (it == "COMMIT") commits++ }
        assertFailsWith<HoldCeilingExceeded> {
            store.databaseLock.withLock {
                store.storeObject(row(1))
                timers.fireAll()
            }
        }
        assertEquals(0, commits, "the COMMIT was never issued")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
        assertEquals(0L, countOf(directory))
        assertEquals(0, process.registry.holdWatchdog.armed)
    }

    @Test
    fun theWatchdogIsDisarmedOnceCommitIsIssued() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        val timers = process.timers()
        // The deadline fires as the COMMIT is issued: it neither interrupts nor refuses it.
        store.statementFault = { if (it == "COMMIT") timers.fireAll() }
        store.storeObject(row(1))
        assertNull(store.fencedBy)
        assertEquals(1L, countOf(directory))
        assertEquals(0, process.registry.holdWatchdog.interrupts)
        assertEquals(0, process.registry.holdWatchdog.armed, "disarmed when the COMMIT was issued")
        // A deadline of a transaction long committed does nothing to the next one.
        store.statementFault = null
        val stale = Timers()
        process.registry.holdWatchdog.after = stale.after
        store.storeObject(row(2))
        process.registry.holdWatchdog.after = timers.after
        store.databaseLock.withLock {
            stale.fireAll()
            store.storeObject(row(3))
        }
        assertNull(store.fencedBy)
        assertEquals(3L, countOf(directory))
    }

    @Test
    fun anInterruptThatLandsBeforeTheStatementStartsIsIssuedAgain() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        val timers = process.timers()
        val started = TimeSource.Monotonic.markNow()
        store.statementFault = { sql ->
            if (sql.startsWith("INSERT INTO objects")) {
                // Fired once the statement is marked running and before SQLite starts it: SQLite
                // clears the flag as the statement starts (no statement active), so it is lost.
                timers.fireAll()
                // Only the re-interrupt the first one scheduled is left to stop it.
                timers.fireAllSoon()
                runUntilStopped(process.connectionOf(ReplicaHandleKind.RUNTIME))
            }
        }
        assertFailsWith<HoldCeilingExceeded> { store.storeObject(row(1)) }
        assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000, "stopped by the re-interrupt, not the test's own 5 s stop")
        assertTrue(process.registry.holdWatchdog.interrupts >= 2, "issued again: ${process.registry.holdWatchdog.interrupts}")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
    }

    @Test
    fun theRollbackOfAnInterruptedStatementIsNeverWatched() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        store.storeObject(row(1))
        val timers = process.timers()
        store.statementFault = { sql ->
            if (sql == SharedReplicaStore.POINT_READ_SQL) {
                timers.fireAllSoon()
                runUntilStopped(process.connectionOf(ReplicaHandleKind.RUNTIME))
            }
        }
        val watchdog = process.registry.holdWatchdog
        var seen: Triple<Int, Int, Int>? = null
        store.rollbackFault = {
            if (seen == null) {
                val before = watchdog.interrupts
                // The re-interrupts the first interrupt scheduled fire now, as the ROLLBACK is issued.
                timers.fireAll()
                seen = Triple(watchdog.armed, watchdog.running, watchdog.interrupts - before)
            }
        }
        assertFailsWith<HoldCeilingExceeded> {
            store.databaseLock.withLock {
                store.storeObject(row(2))
                store.getObject(1)
            }
        }
        // ⚠⚠ The statement was marked ended and the watch disarmed before the ROLLBACK: a deadline
        // firing then interrupts nothing, so the ROLLBACK cannot fail and keep the write lock (B.9 (128)).
        assertEquals(Triple(0, 0, 0), seen, "(armed, running, interrupts during the rollback)")
        store.rollbackFault = null
        assertEquals(0L, countOf(directory, "SELECT count(*) FROM objects WHERE hash = 2"), "rolled back")
        // The write lock is free: a sibling takes it at once.
        ReplicaProcess(directory).sql { connection ->
            connection.exec("BEGIN IMMEDIATE")
            connection.exec("ROLLBACK")
        }
    }

    @Test
    fun aLateTimerIsNotTrustedTheClockRefusesTheNextStatementAndTheCommit() {
        for (atCommit in listOf(false, true)) {
            val directory = tempReplica()
            val process = ReplicaProcess(directory)
            val store = process.store()
            var now = 0L
            process.registry.holdWatchdog.clock = { now }
            // Its timers are held and never fired: only the clock says the deadline passed.
            process.timers()
            assertFailsWith<HoldCeilingExceeded>(if (atCommit) "the COMMIT" else "the next statement") {
                store.databaseLock.withLock {
                    store.storeObject(row(1))
                    now += process.limits.holdMaxMs
                    if (!atCommit) store.storeObject(row(2))
                }
            }
            assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
            assertEquals(0L, countOf(directory))
            assertEquals(0, process.registry.holdWatchdog.interrupts)
        }
    }

    // ── a job's chunk ──

    private fun filled(rows: Int): String {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        (1L..rows.toLong()).chunked(20).forEach { some -> store.databaseLock.withLock { some.forEach { store.storeObject(row(it)) } } }
        store.close()
        return directory
    }

    @Test
    fun aChunkOnAReplacementHandleIsRolledBackAndItsRunStoppedWithNoRuntimeFenced() {
        val directory = filled(rows = 40)
        val process = ReplicaProcess(directory)
        // A live runtime in this process, as a wipe's caller would have.
        val runtime = process.store()
        val job = process.replacement()
        assertIs<ChunkResult.More>(job.startWipe("discard"))
        val timers = process.timers()
        val started = TimeSource.Monotonic.markNow()
        var once = true
        job.statementFault = { sql ->
            if (once && sql.startsWith("DELETE FROM objects")) {
                once = false
                timers.fireAllSoon()
                runUntilStopped(process.connectionOf(ReplicaHandleKind.REPLACEMENT))
            }
        }
        val stopped = assertIs<ReplicaRunOutcome.Stopped>(job.run())
        assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000)
        assertEquals("HoldCeilingExceeded", stopped.reason)
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(assertIs<HoldCeilingExceeded>(stopped.cause).cause).primaryCode)
        assertEquals(40L, countOf(directory), "nothing of the interrupted chunk stays")
        assertTrue(process.registry.handles().none { it.kind == ReplicaHandleKind.REPLACEMENT }, "its handle released")
        assertNull(runtime.fencedBy, "no runtime was fenced by it")
        assertEquals(0, process.registry.holdWatchdog.armed)

        // The deadline fires between two statements of the next run's chunk: the next statement is refused.
        var statements = 0
        job.statementFault = { if (++statements == 2) timers.fireAll() }
        val refused = assertIs<ReplicaRunOutcome.Stopped>(job.run())
        assertEquals("HoldCeilingExceeded", refused.reason)
        assertNull(assertIs<HoldCeilingExceeded>(refused.cause).cause, "refused before SQLite, not interrupted")
        assertEquals(40L, countOf(directory))

        // The next run, unwatched by the test, finishes the wipe.
        job.statementFault = null
        process.registry.holdWatchdog.after = ReplicaWatchdogQueue::after
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertEquals(0L, countOf(directory))
        runtime.close()
    }

    // ── the open (Q15, Appendix B.10) ──

    private fun schemaObjects(directory: String): Long = ReplicaProcess(directory).sql { it.queryLong("SELECT count(*) FROM sqlite_schema") }

    @Test
    fun aClassificationReadRunningPastHoldMaxIsInterruptedAndTheOpenIsARetryWithNothingWritten() {
        val directory = filled(rows = 5)
        val database = "$directory/${ReplicaOpener.DATABASE_NAME}"
        val before = bytesOf(database)
        val process = ReplicaProcess(directory)
        val timers = process.timers()
        val opener = process.opener()
        val started = TimeSource.Monotonic.markNow()
        opener.classifyFault = { connection ->
            timers.fireAllSoon()
            runUntilStopped(connection)
        }
        val refused = assertFailsWith<StoreUnavailable> { opener.open() }
        assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000, "the watchdog stopped it, not the test's own 5 s stop")
        val ceiling = assertIs<HoldCeilingExceeded>(refused.cause, "${refused.message}")
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(ceiling.cause).primaryCode, "SQLite's own interrupt")
        assertEquals(process.limits.holdMaxMs.toLong(), timers.delays.first(), "armed at HOLD_MAX")
        assertTrue(process.registry.handles().isEmpty(), "no connection outlives the refused open")
        assertEquals(0, process.registry.holdWatchdog.armed)
        assertEquals(0, process.registry.holdWatchdog.running)
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"), "an interrupt is not a corruption code")
        assertTrue(before.contentEquals(bytesOf(database)), "nothing written")
        assertEquals(1, process.registry.openHoldRefusalsInARow, "counted for the host (B.10 (140))")
        // §4.2.1's "classification retried": the next open, unhindered, is served.
        assertIs<ReplicaOpenResult.Ready>(process.open()).store.close()
        assertEquals(0, process.registry.openHoldRefusalsInARow, "a served open resets the count")
    }

    @Test
    fun theOpensWriteTransactionRunningPastHoldMaxIsInterruptedAndNothingIsCreated() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val timers = process.timers()
        val opener = process.opener()
        val started = TimeSource.Monotonic.markNow()
        opener.writeTransactionFault = {
            timers.fireAllSoon()
            runUntilStopped(process.connectionOf(ReplicaHandleKind.RUNTIME))
        }
        val refused = assertFailsWith<StoreUnavailable> { opener.open() }
        assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000, "the watchdog stopped it, not the test's own 5 s stop")
        val ceiling = assertIs<HoldCeilingExceeded>(refused.cause, "${refused.message}")
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(ceiling.cause).primaryCode)
        assertTrue(process.registry.holdWatchdog.interrupts >= 1)
        assertTrue(process.registry.handles().isEmpty(), "rule (a) for a store not handed out: its handle released")
        assertEquals(0, process.registry.holdWatchdog.armed)
        assertEquals(0L, schemaObjects(directory), "rolled back: no store was created")
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
        // A retry creates it.
        val ready = assertIs<ReplicaOpenResult.Ready>(process.open())
        assertTrue(ready.created)
        ready.store.close()
    }

    @Test
    fun theOpensWriteTransactionPastItsDeadlineHasItsNextStatementOrItsCommitRefusedBeforeSqlite() {
        for (atCommit in listOf(false, true)) {
            val directory = tempReplica()
            val process = ReplicaProcess(directory)
            val timers = process.timers()
            val opener = process.opener()
            if (atCommit) {
                // After the create, the directory sync and the epoch reads: only the COMMIT is left.
                opener.beforeCommitFault = { timers.fireAll() }
            } else {
                // The first statement takes HOLD_MAX by the clock (the timer late): the next one
                // (the marker's re-read) is refused by the clock check.
                var now = 0L
                process.registry.holdWatchdog.clock = { now }
                opener.writeTransactionFault = { now += process.limits.holdMaxMs }
            }
            val why = if (atCommit) "the COMMIT" else "the next statement"
            val refused = assertFailsWith<StoreUnavailable>(why) { opener.open() }
            assertNull(assertIs<HoldCeilingExceeded>(refused.cause, why).cause, "$why: refused before SQLite, not interrupted")
            assertEquals(0, process.registry.holdWatchdog.interrupts, why)
            assertTrue(process.registry.handles().isEmpty(), why)
            assertEquals(0L, schemaObjects(directory), "$why: rolled back, nothing created")
        }
    }

    // ── the production timer and clock ──

    @Test
    fun theProductionTimerAndClockInterruptARunningStatementInRealTime() {
        // ⚠ Real time on a shared host that can stall for minutes: HOLD_MAX is 200 ms, the
        // statement would stop itself only after 60 s, and the bound is 30 s.
        val limits = ReplicaLimits(holdMaxMs = 200, chunkTimeStopMs = 200, confirmMaxMs = 200)
        val directory = tempReplica()
        val process = ReplicaProcess(directory, limits)
        val store = process.store()
        process.watchdogInRealTime()
        store.statementFault = { sql ->
            if (sql.startsWith("INSERT INTO objects")) runUntilStopped(process.connectionOf(ReplicaHandleKind.RUNTIME), selfStopMs = 60_000)
        }
        val started = TimeSource.Monotonic.markNow()
        val refused = assertFailsWith<HoldCeilingExceeded> { store.storeObject(row(1)) }
        val tookMs = started.elapsedNow().inWholeMilliseconds
        // The deadline is armed after `started` and dispatch_after never fires early.
        assertTrue(tookMs >= limits.holdMaxMs - 10, "never before HOLD_MAX: $tookMs ms")
        assertTrue(tookMs < 30_000, "interrupted by the production timer, not the statement's own 60 s stop: $tookMs ms")
        assertEquals(SQLITE_INTERRUPT, assertIs<SqliteException>(refused.cause).primaryCode)
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
        assertTrue(process.registry.holdWatchdog.interrupts >= 1)
        assertEquals(0, process.registry.holdWatchdog.armed)
        assertEquals(0L, countOf(directory))
    }

    // ── a checkpoint's own deadline, CKPT_MAX (Appendix B.10) ──

    private fun checkpoint(process: ReplicaProcess, store: SharedReplicaStore, mode: SqliteCheckpointMode = SqliteCheckpointMode.PASSIVE): ReplicaCheckpointOutcome {
        assertTrue(process.registry.gate.enter("checkpoint"))
        try {
            return store.checkpointer.run(mode)
        } finally {
            process.registry.gate.leave()
        }
    }

    @Test
    fun aCheckpointRunningPastCkptMaxIsInterruptedAndSkippedWithNothingCounted() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        store.storeObject(row(1))
        process.inBackground()
        val timers = process.timers()
        val started = TimeSource.Monotonic.markNow()
        store.checkpointer.fault = {
            timers.fireAllSoon()
            runUntilStopped(process.connectionOf(ReplicaHandleKind.CHECKPOINT))
        }
        assertEquals(ReplicaCheckpointOutcome.NotReset, checkpoint(process, store), "an interrupted checkpoint is skipped")
        assertTrue(started.elapsedNow().inWholeMilliseconds < 4_000, "the watchdog stopped it, not the test's own 5 s stop")
        assertEquals(process.limits.checkpointMaxMs.toLong(), timers.delays.first(), "armed at CKPT_MAX, not HOLD_MAX")
        assertTrue(process.limits.checkpointMaxMs > process.limits.holdMaxMs)
        assertTrue(process.registry.holdWatchdog.interrupts >= 1)
        assertEquals(0, process.registry.holdWatchdog.armed)
        assertEquals(0, process.registry.checkpointCodesInARow, "an interrupt is no corruption code")
        assertEquals(1, process.registry.checkpointCeilingStopsInARow, "counted for the host")
        assertTrue(process.registry.handles().none { it.kind == ReplicaHandleKind.CHECKPOINT }, "its connection released")
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
        // Retried by the next trigger, unhindered.
        store.checkpointer.fault = null
        assertEquals(ReplicaCheckpointOutcome.Reset, checkpoint(process, store))
        assertEquals(0, process.registry.checkpointCeilingStopsInARow, "reset by one that ran to its end")
        store.close()
    }

    @Test
    fun aRewindWritePastCkptMaxIsRefusedBeforeSqliteAndNotCommitted() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        store.storeObject(row(1))
        fun rewinds(): Long = countOf(directory, "SELECT coalesce((SELECT v FROM meta WHERE k = '${ReplicaSchema.META_WAL_REWIND}'), 0)")
        val before = rewinds()
        process.inBackground()
        val timers = process.timers()
        // Every frame copied; the deadline passes before the rewind takes its lock.
        store.checkpointer.beforeRewind = { timers.fireAll() }
        assertEquals(ReplicaCheckpointOutcome.NotReset, checkpoint(process, store))
        assertEquals(0, process.registry.holdWatchdog.interrupts, "nothing was running to interrupt")
        assertEquals(0, process.registry.holdWatchdog.armed)
        assertEquals(before, rewinds(), "no rewind was written")
        assertTrue(process.registry.handles().none { it.kind == ReplicaHandleKind.CHECKPOINT })
        // The write lock is free.
        ReplicaProcess(directory).sql { connection ->
            connection.exec("BEGIN IMMEDIATE")
            connection.exec("ROLLBACK")
        }
        store.close()
    }

    /** `nBackfill` of the WAL-index (`-shm`): the frames a checkpoint has copied and kept (the u32 after the two 48-byte headers). */
    private fun backfilled(directory: String): Long {
        val shm = assertNotNull(bytesOf("$directory/${ReplicaOpener.DATABASE_NAME}-shm"))
        return (0 until 4).fold(0L) { acc, i -> acc or ((shm[96 + i].toLong() and 0xff) shl (8 * i)) }
    }

    @Test
    fun ckptMaxBoundsACheckpointInTheBackgroundOnlyAndAForegroundCopyRunsToItsEnd() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        (1L..20L).forEach { store.storeObject(row(it)) }
        assertEquals(0L, backfilled(directory), "the premise: nothing copied yet")
        val timers = process.timers()
        // CKPT_MAX passes as the copy is about to run, in either state.
        store.checkpointer.fault = { timers.fireAll() }

        // In protected background time it is bounded: stopped, and an interrupted copy keeps nothing.
        process.inBackground()
        assertEquals(ReplicaCheckpointOutcome.NotReset, checkpoint(process, store))
        assertEquals(0L, backfilled(directory), "none of the copy was kept")
        assertEquals(1, process.registry.checkpointCeilingStopsInARow)
        assertTrue(process.registry.holdWatchdog.interrupts >= 1)

        // ⚠⚠ In the foreground there is no deadline (B.7): CKPT_MAX passing is deferred, asked
        // again shortly, and the copy runs to its end, so a log too slow to copy inside CKPT_MAX
        // is still reset (B.10 (138), (143)).
        process.inForeground()
        val interruptsBefore = process.registry.holdWatchdog.interrupts
        // And by the clock: the copy has run past CKPT_MAX, and the rewind write after it is
        // still not refused (the clock checks defer too).
        var now = 0L
        process.registry.holdWatchdog.clock = { now }
        store.checkpointer.fault = {
            timers.fireAll()
            timers.fireAll()
            now += 2L * process.limits.checkpointMaxMs
        }
        assertEquals(ReplicaCheckpointOutcome.Reset, checkpoint(process, store), "completed, its rewind written")
        assertEquals(interruptsBefore, process.registry.holdWatchdog.interrupts, "never interrupted in the foreground")
        assertEquals(ReplicaHoldWatchdog.DEFERRED_RECHECK_MS, timers.delays.last(), "deferred, and asked again")
        assertEquals(0, process.registry.checkpointCeilingStopsInARow, "reset by a checkpoint that ran to its end")
        store.close()
    }

    @Test
    fun theOpensDirectorySyncIsExemptFromHoldMaxAndItsDeadlineWaitsForIt() {
        val directory = tempReplica()
        val files = RecordingFiles()
        val process = ReplicaProcess(directory, files = files)
        val timers = process.timers()
        var now = 0L
        process.registry.holdWatchdog.clock = { now }
        val opener = process.opener()
        var inside = false
        opener.writeTransactionFault = { inside = true }
        files.onCall = { call ->
            // The create's directory F_FULLFSYNC, under the write lock, takes twice HOLD_MAX, and
            // the timer fires while it runs.
            if (inside && call.startsWith("fullSync")) {
                inside = false
                now += 2L * process.limits.holdMaxMs
                timers.fireAll()
            }
        }
        val ready = assertIs<ReplicaOpenResult.Ready>(opener.open(), "the sync is not a statement: §4.2.1, exempt")
        assertTrue(ready.created)
        assertEquals(0, process.registry.holdWatchdog.interrupts)
        assertEquals(0, process.registry.openHoldRefusalsInARow)
        // The deadline that fell inside the window was armed again for the time the hold had left.
        assertEquals(process.limits.holdMaxMs.toLong(), timers.delays.last())
        ready.store.close()
    }

    @Test
    fun aCheckpointBegunInTheForegroundAndBackgroundedMidCopyIsStoppedAtCkptMax() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        (1L..20L).forEach { store.storeObject(row(it)) }
        val timers = process.timers()
        var background = false
        process.registry.gate.protection = object : ReplicaGateProtection {
            override fun awaitAdmission(gate: ReplicaGate, label: String) = Unit
            override fun remainingMs(): Long? = if (background) 60_000 else null
            override val isForeground: Boolean get() = !background
            override val isInstalled: Boolean = true
        }
        store.checkpointer.fault = {
            // CKPT_MAX passes while still in the foreground: deferred, no interrupt.
            timers.fireAll()
            assertEquals(0, process.registry.holdWatchdog.interrupts, "deferred in the foreground")
            // The app is backgrounded mid-copy; the next look at the deadline stops it.
            background = true
            timers.fireAll()
        }
        assertEquals(ReplicaCheckpointOutcome.NotReset, checkpoint(process, store))
        assertTrue(process.registry.holdWatchdog.interrupts >= 1, "interrupted once in the background")
        assertEquals(0L, backfilled(directory), "and kept none of its copy")
        assertEquals(1, process.registry.checkpointCeilingStopsInARow)
        assertEquals(0, process.registry.holdWatchdog.armed)
        store.close()
    }

    @Test
    fun aDeadlineThatFallsInsideASecondExemptWindowIsPushedBackOnceNotTwice() {
        val memory = SqliteConnection.open(":memory:")
        try {
            val watchdog = ReplicaHoldWatchdog()
            val timers = Timers()
            watchdog.after = timers.after
            var now = 0L
            watchdog.clock = { now }
            val hold = watchdog.arm("two windows", memory, limitMs = 1_000)
            now = 100; hold.beginExempt()
            now = 400; hold.endExempt()
            now = 900; hold.beginExempt()
            now = 1_000; timers.fireAll() // inside the second window: waits for its end
            now = 1_200; hold.endExempt()
            // 600 ms of windows: the deadline is 1,600, armed again for what the hold had left.
            assertEquals(400L, timers.delays.last())
            now = 1_599
            hold.enterStatement()
            now = 1_600
            val armedBefore = timers.delays.size
            timers.fireAll()
            // ⚠ Fired, not pushed back by the first window's 300 ms a second time (B.10 (144)).
            assertEquals(1, watchdog.interrupts, "the deadline at 1,600 interrupts the running statement")
            assertEquals(ReplicaHoldWatchdog.REINTERRUPT_MS, timers.delays.drop(armedBefore).single(), "only the re-interrupt is armed")
            hold.exitStatement()
            hold.disarm()
            assertFailsWith<IllegalStateException>("an exempt window never nests") {
                hold.beginExempt()
                hold.beginExempt()
            }
        } finally {
            memory.close()
        }
    }
}
