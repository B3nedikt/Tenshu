package org.tenshu.core.common.proxy.db.sqlite

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Appendix B.11: the `REPLACEMENT` run on the worker. An open's `Updating` job handed to the
 * worker and run under one job token per run; a migration chunk refused outside the foreground,
 * asked by every chunk (B.3 (56)); a run that counted held until the next foreground; and the
 * end-of-job checkpoint before the job's token is released. Every store is made up.
 */
class ReplicaReplacementWorkerTest {

    /** Seven rows a chunk, so a 20-row migration takes several. */
    private val chunky = ReplicaLimits(chunkMaxRows = 7)

    // Wmax = 120 pages, so `Wmax/4` is 30 pages: a sibling's few dozen rows pass it.
    private val tight = ReplicaLimits(
        wmaxPages = 120,
        cachePages = 8,
        statementPages = 8,
        rowMaxBytes = 8_000,
        ceilingWaitMs = 150,
        chunkMaxRows = 1_000_000,
    )

    private val file = "tenshu/file"
    private val sync = "tenshu/sync"
    private val floors = mapOf(file to 1, sync to 1)
    private val oldBuild = testBuild(services = mapOf(file to 1, sync to 8), serviceFloors = floors)
    private val newBuild = testBuild(services = mapOf(file to 2, sync to 9), serviceFloors = floors)

    private class Step(override val typeIds: Set<String>, private val migration: (DbEntry) -> DbEntry?) : ReplicaMigrationStep {
        override fun migrate(row: DbEntry): DbEntry? = migration(row)
    }

    /** `tenshu/file` 1 → 2 appends a 2 (and throws on the rows in [bad]); `tenshu/sync` 8 → 9 moves no row. */
    private fun chain(bad: Set<Long> = emptySet()) = ReplicaMigrationSteps { service, from ->
        when (service to from) {
            file to 1 -> Step(setOf(file)) {
                if (it.dataIdHash in bad) throw IllegalStateException("a made-up failure")
                it.copy(fields = it.fields + DbValue.LongValue(2))
            }
            sync to 8 -> Step(emptySet()) { it }
            else -> null
        }
    }

    private fun anOldStore(limits: ReplicaLimits = chunky, rows: Int = 20): String {
        val directory = tempReplica()
        val store = ReplicaProcess(directory, limits).store(oldBuild)
        (1..rows).map { row(it.toLong(), file) }.chunked(10).forEach { some -> store.databaseLock.withLock { some.forEach(store::storeObject) } }
        store.databaseLock.withLock { store.storeObject(DbEntry(TList.ID, 9_001, listOf(DbValue.LongValue(1)))) }
        store.close()
        return directory
    }

    private fun ReplicaProcess.count(table: String): Long = sql { it.queryLong("SELECT count(*) FROM $table") }
    private fun ReplicaProcess.hasMeta(key: String): Boolean = sql { it.queryLong("SELECT count(*) FROM meta WHERE k = '$key'") } == 1L
    private fun ReplicaProcess.stamp(service: String): Long =
        sql { it.queryLong("SELECT version FROM schema_service_versions WHERE service_id = '$service'") }

    private fun ReplicaProcess.migrationMarker(): MigrationMarker? = sql { connection ->
        var blob: ByteArray? = null
        connection.query("SELECT v FROM meta WHERE k = 'migration'") { blob = it.getBlob(0) }
        blob
    }?.let { assertIs<DecodedMarker.Value<MigrationMarker>>(MigrationMarker.decode(it)).marker }

    /** The foreground open of the new build, which starts the chain: the job it answered with. */
    private fun ReplicaProcess.started(): ReplicaOpenResult.Updating {
        steps = chain()
        return assertIs<ReplicaOpenResult.Updating>(open(newBuild)).also { assertEquals(ReplicaJob.MIGRATION, it.job) }
    }

    private fun walBytes(process: ReplicaProcess): Long = bytesOf(process.databasePath + "-wal")?.size?.toLong() ?: 0L

    /** A sibling's commits that leave the `-wal` past `Wmax/4`; its connection is closed (no checkpoint on close). */
    private fun ReplicaProcess.siblingGrowsTheLog() {
        sql { connection ->
            connection.exec("PRAGMA wal_autocheckpoint = 0")
            // One transaction: past Wmax/4, and short of the ceiling (Wmax − δ), so no RESTART is due.
            connection.exec("BEGIN IMMEDIATE")
            for (i in 1..40) connection.exec("INSERT INTO objects (hash, type, data) VALUES (${100_000 + i}, 'tenshu/other', zeroblob(3000))")
            connection.exec("COMMIT")
        }
        assertTrue(walBytes(this) > limits.journalSizeLimitBytes, "the premise: the -wal is past Wmax/4 (${walBytes(this)})")
        assertTrue(walBytes(this) <= limits.beginCeilingBytes, "the premise: the -wal is short of the ceiling (${walBytes(this)})")
    }

    // ── fakes ──

    private class Assertions(var remainingMs: Long = 30_000) : ProtectionAssertions {
        private val lock = NSRecursiveLock()
        var begun = 0
        var ended = 0
        override fun begin(reason: String, onExpired: () -> Unit): Long? = locked { (++begun).toLong() }
        override fun end(id: Long) {
            locked { ended++ }
        }
        /** Every ask of the platform's `backgroundTimeRemaining`, and what [onRemaining] said of it (a throw here is swallowed as "0 left"). */
        var remainingAsked = 0
        var onRemaining: (() -> String?)? = null
        val violations = ArrayList<String>()

        override fun backgroundTimeRemainingMs(): Long {
            locked {
                remainingAsked++
                onRemaining?.invoke()?.let { violations += it }
            }
            return remainingMs
        }

        private inline fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Every block inline, on the caller's thread (which the protection is told is not the main one). */
    private object Inline : ProtectionTimer {
        override fun after(delayMs: Long, block: () -> Unit) = Unit
        override fun sleep(ms: Long) = Unit
        override fun dispatch(block: () -> Unit) = block()
    }

    private fun protection(assertions: Assertions, process: ReplicaProcess, foreground: Boolean): ReplicaProtection =
        ReplicaProtection(assertions, timer = Inline, isMainThread = { false }).also {
            it.attach(process.registry)
            it.install(isForeground = foreground)
        }

    /** A dispatch onto a real background queue, and a wait for it. */
    private class Background {
        private val condition = NSCondition()
        private var running = 0
        val dispatch: (() -> Unit) -> Unit = { block ->
            condition.lock()
            running++
            condition.unlock()
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                try {
                    block()
                } finally {
                    condition.lock()
                    running--
                    condition.broadcast()
                    condition.unlock()
                }
            }
        }

        fun await(timeoutMs: Long = 30_000) {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (running > 0) if (!condition.waitUntilDate(deadline)) fail("the worker still runs after $timeoutMs ms")
            } finally {
                condition.unlock()
            }
        }
    }

    // ── (146) a migration chunk outside the foreground ──

    @Test
    fun aMigrationRunOutsideTheForegroundIsRefusedAtItsFirstChunkWithNothingWritten() {
        val directory = anOldStore()
        val process = ReplicaProcess(directory, chunky)
        val job = process.started().replacement
        val before = process.migrationMarker()
        assertNotNull(before)
        val startCommits = job.commits // the open's Start

        // A run obtained in the foreground, run after the app was backgrounded (or a wake's run
        // meeting a migration a sibling started): refused under the lock, nothing committed.
        process.inBackground()
        assertEquals(ReplicaRunOutcome.NeedsForeground, job.run())
        assertEquals(startCommits, job.commits)
        assertEquals(before, process.migrationMarker())
        assertEquals(0, process.count("migration_done"))
        assertTrue(process.registry.handles().isEmpty())

        // Back in the foreground the same job goes on to its end.
        process.inForeground()
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertFalse(process.hasMeta("migration"))
        assertEquals(2, process.stamp(file))
    }

    @Test
    fun aRunBegunInTheForegroundStopsAtTheFirstChunkAfterTheAppIsBackgroundedAndKeepsWhatItCommitted() {
        val directory = anOldStore()
        val process = ReplicaProcess(directory, chunky)
        val job = process.started().replacement
        val startCommits = job.commits // the open's Start
        var flipped = false
        // The app is backgrounded as the first rows chunk commits.
        job.beforeCommit = {
            if (!flipped) {
                flipped = true
                process.inBackground()
            }
        }
        assertEquals(ReplicaRunOutcome.NeedsForeground, job.run())
        assertEquals(startCommits + 1, job.commits, "the chunk before the backgrounding stands")
        assertEquals(7, process.count("migration_done"))
        assertEquals(7L, process.migrationMarker()?.cursor)

        process.inForeground()
        job.beforeCommit = null
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertEquals(0, process.count("migration_done"))
        val store = assertIs<ReplicaOpenResult.Ready>(process.open(newBuild)).store
        for (hash in 1..20L) assertEquals(row(hash, file).let { it.copy(fields = it.fields + DbValue.LongValue(2)) }, store.getObject(hash), "row $hash migrated once")
        store.close()
    }

    @Test
    fun aWipeStillRunsOutsideTheForeground() {
        val directory = anOldStore(rows = 20)
        val process = ReplicaProcess(directory, chunky)
        process.inBackground()
        // A forget in a wake: the store-less wipe, whose later chunks run on a REPLACEMENT handle.
        assertTrue(process.opener(oldBuild, foreground = false).wipe("forget"))
        assertFalse(process.hasMeta("wipe"))
        assertEquals(0, process.count("objects"))
        process.inForeground()
    }

    // ── (149) the open hands its job to the worker ──

    @Test
    fun anOpensJobIsHandedToTheWorkerWhichRunsItUnderOneJobTokenAndTheNextOpenIsReady() {
        val directory = anOldStore()
        val process = ReplicaProcess(directory, chunky).also { it.steps = chain() }
        val assertions = Assertions()
        val protection = ReplicaProtection(assertions)
        protection.attach(process.registry)
        protection.install(isForeground = true)
        val background = Background()
        val lock = NSRecursiveLock()
        val outcomes = ArrayList<ReplicaRunOutcome>()
        val worker = ReplicaWorker(protection, background.dispatch, onReplacementOutcome = { _, outcome ->
            lock.lock()
            outcomes += outcome
            lock.unlock()
        })
        worker.attach(process.registry)

        val updating = assertIs<ReplicaOpenResult.Updating>(process.open(newBuild))
        assertTrue(updating.scheduled, "the host is told the worker has it")
        background.await()
        lock.lock()
        val ended = outcomes.toList()
        lock.unlock()
        assertEquals(listOf<ReplicaRunOutcome>(ReplicaRunOutcome.Finished), ended)
        assertEquals(1, assertions.begun, "one job token for the run")
        assertEquals(1, assertions.ended)
        assertTrue(process.registry.handles().isEmpty())
        assertIs<ReplicaOpenResult.Ready>(process.open(newBuild)).store.close()
        assertEquals(2, process.stamp(file))
    }

    @Test
    fun withNoWorkerAttachedTheHostIsToldToRunTheJobItself() {
        val process = ReplicaProcess(anOldStore(), chunky)
        val updating = process.started()
        assertFalse(updating.scheduled)
        assertEquals(ReplicaRunOutcome.Finished, updating.replacement.run())
    }

    // ── (150) one run of one object at a time ──

    @Test
    fun aSecondRunOfOneJobObjectWhileItRunsIsRefusedBeforeItTouchesAnything() {
        val process = ReplicaProcess(anOldStore(), chunky)
        val job = process.started().replacement
        var refused: IllegalStateException? = null
        job.beforeCommit = {
            if (refused == null) {
                val handles = process.registry.handles().size
                refused = assertFailsWith<IllegalStateException> { job.run() }
                assertEquals(handles, process.registry.handles().size, "the refused run registered nothing")
            }
        }
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertTrue("already running" in refused?.message.orEmpty(), "${refused?.message}")
        // Once it has returned, the object runs again (and finds nothing left).
        job.beforeCommit = null
        assertEquals(ReplicaRunOutcome.Finished, job.run())
    }

    // ── (147) a run that counted ──

    @Test
    fun aRunThatCountedAMigrationFailureIsHeldUntilTheNextForeground() {
        val directory = anOldStore()
        val process = ReplicaProcess(directory, chunky).also { it.steps = chain(bad = setOf(9)) }
        val assertions = Assertions()
        val protection = protection(assertions, process, foreground = true)
        val outcomes = ArrayList<ReplicaRunOutcome>()
        val worker = ReplicaWorker(protection, { it() }, onReplacementOutcome = { _, outcome -> outcomes += outcome })

        worker.scheduleReplacement(assertIs<ReplicaOpenResult.Updating>(process.open(newBuild)).replacement)
        assertEquals(1, assertIs<MigrationRowFailed>(assertIs<ReplicaRunOutcome.Stopped>(outcomes.last()).cause).attempts)
        val tokens = assertions.begun

        // The next open in the same foreground answers the same job; the worker does not run it.
        worker.scheduleReplacement(assertIs<ReplicaOpenResult.Updating>(process.open(newBuild)).replacement)
        val held = assertIs<ReplicaRunOutcome.Stopped>(outcomes.last())
        assertTrue("next foreground" in held.reason, held.reason)
        assertNull(held.cause)
        assertEquals(tokens, assertions.begun, "no token, no run")
        assertEquals(1, process.migrationMarker()?.failure?.count)
        worker.kick()
        assertIs<ReplicaRunOutcome.Stopped>(outcomes.last())
        assertEquals(1, process.migrationMarker()?.failure?.count, "a kick in the same foreground is held too")

        // A new foreground lifts it: the next run counts the second attempt.
        protection.enteredBackground()
        protection.enteredForeground()
        worker.kick()
        assertEquals(2, assertIs<MigrationRowFailed>(assertIs<ReplicaRunOutcome.Stopped>(outcomes.last()).cause).attempts)
        assertEquals(2, process.migrationMarker()?.failure?.count)
    }

    // ── a job that ended for good ──

    @Test
    fun aJobThatEndedForGoodIsNotRunAgainAtAKickAndOneThatStoppedIs() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        process.store().close()
        val queued = ArrayList<() -> Unit>()
        val outcomes = ArrayList<ReplicaRunOutcome>()
        val worker = ReplicaWorker(null, { queued += it }, onReplacementOutcome = { _, outcome -> outcomes += outcome })

        // Stopped (the gate closed): kept, and run again at the next kick.
        worker.scheduleReplacement(process.replacement())
        process.registry.gate.closeAndDrain(0)
        queued.removeAt(0).invoke()
        assertIs<ReplicaRunOutcome.Stopped>(outcomes.single())
        process.registry.gate.reopen()
        worker.kick()
        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()
        assertEquals(ReplicaRunOutcome.Finished, outcomes.last(), "nothing to do on a compatible store")

        // Finished: forgotten.
        worker.kick()
        assertTrue(queued.isEmpty(), "a finished job is not run again")
    }

    @Test
    fun aJobHandedOverWhileARunGoesOnIsRunOnceItEnds() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        process.store().close()
        val queued = ArrayList<() -> Unit>()
        val ran = ArrayList<ReplicaReplacement>()
        val worker = ReplicaWorker(null, { queued += it }, onReplacementOutcome = { job, _ -> ran += job })
        val first = process.replacement()
        val second = process.replacement()
        worker.scheduleReplacement(first)
        worker.scheduleReplacement(second)
        assertEquals(1, queued.size, "one dispatch per store")
        queued.removeAt(0).invoke()
        assertEquals(listOf(second), ran, "the latest job handed over is the one run")
        assertTrue(queued.isEmpty())
    }

    // ── (148) the end-of-job checkpoint ──

    @Test
    fun aBackgroundJobPastAQuarterOfWmaxCheckpointsBeforeItsTokenIsReleased() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory, tight)
        process.store().close()
        val assertions = Assertions()
        // Launched in the background: a wake's token protects.
        val protection = protection(assertions, process, foreground = false)
        val wake = assertNotNull(protection.acquire("a wake"))
        process.siblingGrowsTheLog()

        val job = process.replacement()
        var liveAtCheckpoint = -1
        var refusedDuringIt: IllegalStateException? = null
        var refusedBetween: IllegalStateException? = null
        // (154): a host's run between the worker's run and its end-of-job checkpoint.
        job.betweenRunAndEndOfJob = { refusedBetween = assertFailsWith<IllegalStateException> { job.run() } }
        job.checkpointer.fault = { mode ->
            assertEquals(SqliteCheckpointMode.PASSIVE, mode)
            liveAtCheckpoint = protection.liveTokenCount
            // (154): a host's run of the same object while the end-of-job checkpoint goes on.
            refusedDuringIt = assertFailsWith<IllegalStateException> { job.run() }
        }
        val outcomes = ArrayList<ReplicaRunOutcome>()
        val worker = ReplicaWorker(protection, { it() }, onReplacementOutcome = { _, outcome -> outcomes += outcome })
        worker.scheduleReplacement(job)

        assertEquals(listOf<ReplicaRunOutcome>(ReplicaRunOutcome.Finished), outcomes)
        assertEquals(1, job.endOfJobCheckpoints)
        assertEquals(2, liveAtCheckpoint, "the wake's token and the job's, still held")
        assertTrue("already running" in refusedDuringIt?.message.orEmpty(), "${refusedDuringIt?.message}")
        assertTrue("already running" in refusedBetween?.message.orEmpty(), "the worker's run and its end are one guarded section: ${refusedBetween?.message}")
        assertEquals(1, protection.liveTokenCount, "the job's token is released after it")
        assertTrue(walBytes(process) <= tight.journalSizeLimitBytes, "the log was reset: ${walBytes(process)}")
        assertTrue(process.registry.handles().isEmpty())

        // Under Wmax/4 now: the next job's end checkpoints nothing.
        val next = process.replacement()
        worker.scheduleReplacement(next)
        assertEquals(0, next.endOfJobCheckpoints)
        wake.release()
    }

    @Test
    fun theEndOfJobCheckpointIsNotTheForegroundsAndNotOutsideAnAdmission() {
        val directory = tempReplica()
        val process = ReplicaProcess(directory, tight)
        process.store().close()
        process.siblingGrowsTheLog()

        // The foreground (here: no protection attached, no deadline): the 30 s trigger's, not this one's.
        val inForeground = process.replacement()
        assertEquals(ReplicaRunOutcome.Finished, inForeground.endOfJob(ReplicaRunOutcome.Finished))
        assertEquals(0, inForeground.endOfJobCheckpoints)

        // In the background with the gate closed: no admission, no checkpoint.
        process.inBackground()
        process.registry.gate.closeAndDrain(0)
        val closed = process.replacement()
        assertEquals(ReplicaRunOutcome.Finished, closed.endOfJob(ReplicaRunOutcome.Finished))
        assertEquals(0, closed.endOfJobCheckpoints)
        assertTrue(walBytes(process) > tight.journalSizeLimitBytes)
        process.registry.gate.reopen()

        // A run that ended Stopped is a job's end too, and keeps its own outcome.
        val stopped = ReplicaRunOutcome.Stopped("made up")
        val open = process.replacement()
        assertEquals(stopped, open.endOfJob(stopped))
        assertEquals(1, open.endOfJobCheckpoints)
        assertTrue(walBytes(process) <= tight.journalSizeLimitBytes)
        process.inForeground()
    }

    // ── (153) "in the foreground" never asks the platform ──

    @Test
    fun aMigrationChunksForegroundQuestionDoesNotAskThePlatformUnderTheWriteLock() {
        val process = ReplicaProcess(anOldStore(), chunky)
        val assertions = Assertions()
        val protection = protection(assertions, process, foreground = true)
        val job = process.started().replacement
        // Backgrounded, then woken: a wake's token protects, and the job is run in it.
        protection.enteredBackground()
        val wake = assertNotNull(protection.acquire("a wake"))
        assertions.onRemaining = {
            if (process.registry.holdWatchdog.armed > 0) "backgroundTimeRemaining asked with the write lock held" else null
        }
        assertEquals(ReplicaRunOutcome.NeedsForeground, job.run())
        assertTrue(assertions.remainingAsked > 0, "the premise: the chunk's admission did ask, before its BEGIN")
        assertEquals(emptyList(), assertions.violations)
        wake.release()
    }

    @Test
    fun theEndOfJobCheckpointsForegroundQuestionDoesNotAskThePlatform() {
        val process = ReplicaProcess(tempReplica(), tight)
        process.store().close()
        val assertions = Assertions()
        val protection = protection(assertions, process, foreground = false)
        val wake = assertNotNull(protection.acquire("a wake"))
        process.siblingGrowsTheLog()
        val job = process.replacement()
        val asked = assertions.remainingAsked
        assertEquals(ReplicaRunOutcome.Finished, job.runToEndOfJob())
        assertEquals(1, job.endOfJobCheckpoints)
        // Every ask is an admission check, which needs the value: the chunk's and the checkpoint's.
        assertEquals(2, assertions.remainingAsked - asked, "only the two admission checks asked the platform")
        wake.release()
    }
}
