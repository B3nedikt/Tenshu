@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The chunked wipe (§5.1, D13) and the chunked migrator (§5.3, D11): jobs of bounded transactions
 * behind a marker, resumable after a kill at any commit. The limits here make a chunk a handful
 * of rows, so every job below is many chunks; the code under test is the production one.
 */
class ReplicaReplacementTest {

    /** Seven rows a chunk: a few dozen rows are several chunks of every phase. */
    private val chunky = ReplicaLimits(chunkMaxRows = 7)

    // C = 8, G = 8, δ = 20 frames, Wmax = 120 pages, journal_size_limit = 30, and no row count
    // that could end a chunk: only the budget does.
    private val tight = ReplicaLimits(
        wmaxPages = 120,
        cachePages = 8,
        statementPages = 8,
        rowMaxBytes = 8_000,
        ceilingWaitMs = 150,
        chunkMaxRows = 1_000_000,
    )

    private val file = "tenshu/file"
    private val other = "tenshu/other"

    private fun ReplicaProcess.count(table: String): Long = sql { it.queryLong("SELECT count(*) FROM $table") }
    private fun ReplicaProcess.metaInt(key: String): Long = sql { it.queryLong("SELECT v FROM meta WHERE k = '$key'") }
    private fun ReplicaProcess.hasMeta(key: String): Boolean = sql { it.queryLong("SELECT count(*) FROM meta WHERE k = '$key'") } == 1L
    private fun ReplicaProcess.stamp(service: String): Long =
        sql { it.queryLong("SELECT version FROM schema_service_versions WHERE service_id = '$service'") }

    private fun ReplicaProcess.metaBlob(key: String): ByteArray? = sql { connection ->
        var blob: ByteArray? = null
        connection.query("SELECT v FROM meta WHERE k = '$key'") { blob = it.getBlob(0) }
        blob
    }

    private fun SharedReplicaStore.fill(rows: List<DbEntry>, batch: Int = 20) {
        rows.chunked(batch).forEach { some -> databaseLock.withLock { some.forEach(::storeObject) } }
    }

    /** A store with [rows] `tenshu/file` rows and a few in the other tables a wipe empties. Closed. */
    private fun aFilledStore(limits: ReplicaLimits, rows: Int, build: ReplicaBuild = testBuild(), payloadBytes: Int = 40): String {
        val directory = tempReplica()
        val process = ReplicaProcess(directory, limits)
        val store = process.store(build)
        store.fill((1..rows).map { row(it.toLong(), payloadBytes = payloadBytes) })
        store.close()
        process.sql { connection ->
            repeat(5) { connection.exec("INSERT INTO schedule_lease (key, record) VALUES ('lease-$it', x'0102')") }
            repeat(5) { connection.exec("INSERT INTO migration_done (hash) VALUES ($it)") }
            repeat(3) { connection.exec("INSERT INTO migration_dropped (hash) VALUES ($it)") }
        }
        return directory
    }

    private fun assertWiped(directory: String, limits: ReplicaLimits, build: ReplicaBuild = testBuild()) {
        val process = ReplicaProcess(directory, limits)
        // Derived from the layout: a table is emptied or named as kept, and there is no third kind.
        assertEquals(ReplicaSchema.tables.map { it.name }.toSet(), (ReplicaSchema.tablesEmptiedByWipe + ReplicaSchema.tablesKeptByWipe).toSet())
        for (table in ReplicaSchema.tablesEmptiedByWipe) assertEquals(0, process.count(table), table)
        assertFalse(process.hasMeta("wipe"), "the marker is gone")
        assertFalse(process.hasMeta("migration"))
        val store = process.store(build)
        assertTrue(store.isEmpty())
        store.close()
    }

    /** Kills a job at its [commitsAllowed]-th commit: that transaction is rolled back, as a kill would. */
    private class Kill(var commitsAllowed: Int) {
        var fired = false
        fun arm(replacement: ReplicaReplacement) {
            replacement.beforeCommit = {
                if (commitsAllowed-- == 0) {
                    fired = true
                    throw RuntimeException("killed")
                }
            }
        }
    }

    // ── the wipe ──

    @Test
    fun aWipeEmptiesEveryTableInSeveralChunksAndReturnsOnlyAfterTheLast() {
        val directory = aFilledStore(chunky, rows = 100)
        val process = ReplicaProcess(directory, chunky)
        val store = process.store()
        val epoch = store.epoch
        val seq = process.metaInt("seq")

        runBlocking { store.wipeAllObjects() }

        // Returned: so the last chunk has committed.
        for (table in ReplicaSchema.tablesEmptiedByWipe) assertEquals(0, process.count(table), table)
        assertFalse(process.hasMeta("wipe"))
        assertNotEquals(epoch, process.metaInt("epoch"))
        // 100 rows at 7 a chunk: `seq` moves once per chunk that deletes rows of `objects`.
        assertTrue(process.metaInt("seq") - seq >= 100 / 7, "several chunks: seq moved by ${process.metaInt("seq") - seq}")
        assertEquals("wipe test", process.sql { it.queryText("SELECT v FROM meta WHERE k = 'replaced_by'") })
        // The stamps are kept.
        assertEquals(3, process.stamp(file))
        assertEquals(22, process.sql { it.queryLong("PRAGMA user_version") })

        // The wiping handle fenced itself and never serves again.
        assertEquals(ReplicaFence.REPLACED, store.fencedBy)
        assertNull(store.getObject(1))
        assertFailsWith<ReplicaReplaced> { store.storeObject(row(1)) }
        assertEquals(0, process.registry.handles().size, "every handle of the wipe was released")
        assertWiped(directory, chunky)
    }

    @Test
    fun aWipeKilledAtEveryCommitIsNeverReadableAndTheNextOpenFinishesIt() {
        var commitsAllowed = 0
        while (true) {
            val directory = aFilledStore(chunky, rows = 40)
            // A sibling with the store open since before the wipe.
            val sibling = ReplicaProcess(directory, chunky).store()
            val before = ReplicaProcess(directory, chunky).metaInt("epoch")

            val kill = Kill(commitsAllowed)
            val wiping = ReplicaProcess(directory, chunky)
            val job = wiping.replacement().also(kill::arm)
            val started = job.startWipe("forget")
            val outcome = if (started is ChunkResult.More) job.run() else (started as ChunkResult.Ended).outcome

            val after = ReplicaProcess(directory, chunky)
            if (!kill.fired) {
                assertEquals(ReplicaRunOutcome.Finished, outcome)
                assertWiped(directory, chunky)
                assertTrue(commitsAllowed >= 8, "the wipe was several chunks: $commitsAllowed commits")
                return
            }
            assertIs<ReplicaRunOutcome.Stopped>(outcome, "killed at commit $commitsAllowed")
            if (commitsAllowed == 0) {
                // The first chunk never committed: nothing changed, for anyone.
                assertFalse(after.hasMeta("wipe"))
                assertEquals(before, after.metaInt("epoch"))
                assertEquals(row(1), sibling.getObject(1))
                assertEquals(40, after.count("objects"))
            } else {
                // Marked: the epoch moved once, at the start, and not since.
                assertTrue(after.hasMeta("wipe"), "killed at commit $commitsAllowed")
                val marked = after.metaInt("epoch")
                assertNotEquals(before, marked)
                // ⚠ Never a readable half-state: the sibling is fenced at its next access, and no
                // open hands out a store.
                assertNull(sibling.getObject(40), "killed at commit $commitsAllowed")
                assertEquals(ReplicaFence.REPLACED, sibling.fencedBy)
                val resumed = assertIs<ReplicaOpenResult.Updating>(after.open(), "killed at commit $commitsAllowed")
                assertEquals(ReplicaJob.WIPE, resumed.job)
                assertEquals(marked, after.metaInt("epoch"), "a resume does not move the epoch")
                // A wake may resume a wipe too.
                assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).open(foreground = false))
                assertEquals(ReplicaRunOutcome.Finished, resumed.replacement.run())
                assertNotEquals(marked, after.metaInt("epoch"), "the finish moves the epoch again")
                assertWiped(directory, chunky)
            }
            sibling.close()
            commitsAllowed++
        }
    }

    @Test
    fun aWipeCallThatCannotFinishThrowsWipeNotFinishedAndLeavesTheStoreMarked() {
        val directory = aFilledStore(chunky, rows = 40)
        val process = ReplicaProcess(directory, chunky)
        val store = process.store()
        store.onReplacement = Kill(commitsAllowed = 2)::arm

        val failure = assertFailsWith<WipeNotFinished> { runBlocking { store.wipeAllObjects() } }
        assertIs<ReplicaRunOutcome.Stopped>(failure.outcome)
        // Started and not finished: the handle is fenced, the marker and the committed chunks stay.
        assertEquals(ReplicaFence.REPLACED, store.fencedBy)
        assertTrue(process.hasMeta("wipe"))
        assertEquals(40L - 2 * 7, process.count("objects"))
        assertEquals(0, process.registry.handles().size)

        val resumed = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).open())
        assertEquals(ReplicaRunOutcome.Finished, resumed.replacement.run())
        assertWiped(directory, chunky)
    }

    @Test
    fun aWipeWhoseFirstChunkDoesNotCommitChangesNothing() {
        val directory = aFilledStore(chunky, rows = 10)
        val process = ReplicaProcess(directory, chunky)
        val store = process.store()
        assertTrue(process.registry.gate.closeAndDrain(100))
        assertFailsWith<ReplicaGateClosed> { store.wipe("forget") }
        process.registry.gate.reopen()

        assertNull(store.fencedBy)
        assertFalse(process.hasMeta("wipe"))
        assertEquals(row(3), store.getObject(3))
    }

    @Test
    fun theStoreLessWipeRefusesANewerStoreCreatesNothingAndJoinsAWipeInProgress() {
        // Nothing there: nothing is created.
        val empty = tempReplica()
        assertFalse(ReplicaProcess(empty).opener().wipe("discard"))
        assertFalse(fileExists("$empty/objects.db"))

        // ⚠ A sidecar with no objects.db is not "nothing there": the wipe answers what an open of
        // the same directory answers (step 0's orphan rule), or it would report a benign no-op
        // over a replica the very next open fences for good.
        for (sidecar in listOf("-wal", "-shm")) {
            val orphaned = tempReplica()
            PosixReplicaFiles.createExclusive("$orphaned/objects.db$sidecar")
            assertIs<ReplicaRunOutcome.Corrupt>(assertFailsWith<WipeRefused>(sidecar) { ReplicaProcess(orphaned).opener().wipe("discard") }.outcome)
            assertTrue(fileExists("$orphaned/${ReplicaCorruptMarker.NAME}"), sidecar)
            assertFalse(fileExists("$orphaned/objects.db"), sidecar)
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(orphaned).open(), sidecar)
        }

        // A newer build's store: refused before any read/write connection, byte for byte unchanged.
        val newer = aFilledStore(chunky, rows = 10, build = testBuild(core = 23))
        val bytes = bytesOf("$newer/objects.db")
        val wal = bytesOf("$newer/objects.db-wal")
        assertIs<ReplicaRunOutcome.Skew>(assertFailsWith<WipeRefused> { ReplicaProcess(newer, chunky).opener().wipe("discard") }.outcome)
        assertContentEquals(bytes, bytesOf("$newer/objects.db"))
        assertContentEquals(wal, bytesOf("$newer/objects.db-wal"))

        // A store: wiped to the last chunk before the call returns.
        val directory = aFilledStore(chunky, rows = 40)
        assertTrue(ReplicaProcess(directory, chunky).opener().wipe("discard"))
        assertWiped(directory, chunky)

        // A wipe in progress is joined: its marker (and end stamps) kept, the epoch not moved again by a start.
        val marked = aFilledStore(chunky, rows = 40)
        val process = ReplicaProcess(marked, chunky)
        val job = process.replacement().also(Kill(commitsAllowed = 2)::arm)
        assertIs<ChunkResult.More>(job.startWipe("first"))
        job.run()
        val frame = process.metaBlob("wipe")
        val joiner = process.replacement()
        assertEquals(ChunkResult.More(ReplicaJob.WIPE), joiner.startWipe("second"))
        assertContentEquals(frame, process.metaBlob("wipe"))
        assertTrue(ReplicaProcess(marked, chunky).opener().wipe("second"))
        assertWiped(marked, chunky)
    }

    @Test
    fun aStoreOnAnOlderLayoutIsRefusedRetryablyWhateverElseIsTrueOfIt() {
        // A build of the next layout. What a layout step is has not been designed (B.3 (33)), and
        // this build knows the schema of its own layout only: it may not validate an older
        // store against that (a healthy one would read as damaged), nor start, resume or wipe.
        val next = testBuild(layout = ReplicaSchema.LAYOUT + 1)
        val directory = aFilledStore(chunky, rows = 10)
        val process = ReplicaProcess(directory, chunky)
        fun refused(what: String) {
            assertFailsWith<StoreUnavailable>(what) { ReplicaProcess(directory, chunky).open(next) }
            assertFailsWith<StoreUnavailable>(what) { ReplicaProcess(directory, chunky).opener(next).wipe("discard") }
            assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"), what)
            assertEquals(10, process.count("objects"), what)
        }
        refused("behind on the layout alone")
        process.sql { it.exec("INSERT INTO meta (k, v) VALUES ('wipe', ${wipeFrame()})") }
        refused("a wipe in progress")
        process.sql { it.exec("UPDATE meta SET k = 'migration', v = ${migrationFrame()} WHERE k = 'wipe'") }
        refused("a migration in progress")
        assertTrue(process.hasMeta("migration"))
    }

    @Test
    fun aWipeWhoseDamageVerdictIsNotRepeatedLeavesNoHandleBehind() {
        val directory = aFilledStore(chunky, rows = 10)
        val process = ReplicaProcess(directory, chunky)
        process.sql { it.exec("DELETE FROM meta WHERE k = 'layout'") }
        val opener = process.opener()
        // The first read says damaged; by the second the store is whole again.
        opener.beforeConfirm = { process.sql { it.exec("INSERT INTO meta (k, v) VALUES ('layout', ${ReplicaSchema.LAYOUT})") } }
        assertFailsWith<StoreUnavailable> { opener.wipe("discard") }

        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
        assertEquals(10, process.count("objects"), "nothing was wiped on a verdict that did not hold")
        // ⚠ The re-read must not have opened a store and dropped it: a runtime handle nobody can
        // release would refuse every later open in this process.
        assertEquals(0, process.registry.handles().size)
        process.store().close()
    }

    @Test
    fun aStoreBelowTheFloorIsWipedInChunksAndStampedOnlyOnceItIsEmpty() {
        val directory = aFilledStore(chunky, rows = 40, build = testBuild(core = 20))
        val build = testBuild(coreFloor = 21)

        // The open writes the first chunk and returns: it deletes nothing and stamps nothing.
        val opened = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).open(build))
        assertEquals(ReplicaJob.WIPE, opened.job)
        val process = ReplicaProcess(directory, chunky)
        assertEquals(40, process.count("objects"))
        assertEquals(20, process.sql { it.queryLong("PRAGMA user_version") })

        // The resumer rule: a build below the stamps the wipe will write may not resume it.
        val between = testBuild(core = 21, coreFloor = 19)
        val refused = assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).open(between))
        assertTrue("v22" in refused.detail, refused.detail)
        assertEquals(40, process.count("objects"), "nothing was written by the build that may not resume")

        // Killed part-way: still not stamped.
        opened.replacement.also(Kill(commitsAllowed = 3)::arm).run()
        assertEquals(20, process.sql { it.queryLong("PRAGMA user_version") })
        assertTrue(process.count("objects") in 1..39)

        val resumed = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).open(build, foreground = false))
        assertEquals(ReplicaRunOutcome.Finished, resumed.replacement.run())
        assertEquals(22, process.sql { it.queryLong("PRAGMA user_version") })
        assertWiped(directory, chunky, build)
        assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(directory, chunky).open(build)).store.close()
    }

    @Test
    fun everyWipeChunkStaysInsideTheWalCeilingWhenTheBudgetNotTheRowCountEndsIt() {
        // Wmax = 68 pages: about twenty-five pages of growth a chunk. A delete dirties little (a
        // freed leaf is not written), so it takes thousands of small rows to get there.
        val tighter = tight.copy(wmaxPages = 68)
        val directory = tempReplica()
        val process = ReplicaProcess(directory, tighter)
        val store = process.store()
        store.fill((1..6_000L).map { row(it) })
        store.close()

        val job = process.replacement()
        assertIs<ChunkResult.More>(job.startWipe("discard"))
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        // The start, the finish, and with no budget one chunk between them.
        assertTrue(job.commits >= 5, "the budget cut the wipe into chunks: ${job.commits}")
        assertEquals(0, job.ceilingBreaches)
        assertTrue(job.largestWalBytes <= tighter.wmaxBytes, "the -wal stayed under Wmax: ${job.largestWalBytes}")
        assertWiped(directory, tighter)
    }

    @Test
    fun aChunkCommitsAtItsTimeStopAndOneThatHeldTheLockPastHoldMaxIsRolledBackWhole() {
        val directory = aFilledStore(ReplicaLimits(), rows = 40)
        val process = ReplicaProcess(directory)
        val job = process.replacement()
        assertIs<ChunkResult.More>(job.startWipe("discard"))

        // Every look at the clock is 400 ms later: the third statement of the chunk is past HOLD_MAX.
        var now = 0L
        job.clock = { now.also { now += 400 } }
        val stopped = assertIs<ReplicaRunOutcome.Stopped>(job.run())
        assertIs<HoldCeilingExceeded>(stopped.cause)
        assertEquals(40, process.count("objects"), "nothing of the refused chunk stays")
        assertTrue(process.hasMeta("wipe"))

        // 100 ms a look: a chunk reaches its 500 ms stop after a row or two, and commits.
        job.clock = { now.also { now += 100 } }
        val commits = job.commits
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertTrue(job.commits - commits >= 20, "chunks ended by the time stop: ${job.commits - commits}")
        assertWiped(directory, ReplicaLimits())
    }

    @Test
    fun aBeginThatFailsClosesItsConnectionBeforeItGivesTheAdmissionBackForEveryKindOfHandle() {
        // BEGIN IMMEDIATE fails with something that is not "busy": on a job's handle, on the
        // runtime handle of a store, and on the open's own. ⚠ "Drained" tells quiesce that nothing
        // of this process touches SQLite any more, and a close is a touch: asked at the moment
        // the connection is being closed, the gate must still count the access that failed.
        val directory = aFilledStore(chunky, rows = 20)
        val process = ReplicaProcess(directory, chunky)
        val store = process.store()
        val observed = ArrayList<String>()
        fun failing(what: String, attempt: () -> Unit) {
            process.registry.beginFault = { throw SqliteException(SQLITE_IOERR, "disk I/O error") }
            process.registry.closeFault = { handle ->
                val drained = process.registry.gate.closeAndDrain(0)
                process.registry.gate.reopen()
                observed += "$what: ${handle.kind} closed, in a transaction=${handle.connection?.inAutocommit() == false}, drained=$drained"
            }
            try {
                attempt()
            } finally {
                process.registry.beginFault = null
                process.registry.closeFault = null
            }
        }

        failing("an access") { assertFailsWith<StoreUnavailable> { store.getObject(1) } }
        // Retryable: the handle is neither fenced nor gone, and its next access reopens.
        assertNull(store.fencedBy)
        assertEquals(row(1), store.getObject(1))
        store.close()

        failing("an open") { assertFailsWith<StoreUnavailable> { process.open() } }

        val job = process.replacement()
        assertIs<ChunkResult.More>(job.startWipe("discard"))
        failing("a job") { assertIs<SqliteException>(assertIs<ReplicaRunOutcome.Stopped>(job.run()).cause) }

        assertEquals(
            listOf(
                "an access: RUNTIME closed, in a transaction=false, drained=false",
                "an open: CLASSIFY closed, in a transaction=false, drained=false",
                "an open: RUNTIME closed, in a transaction=false, drained=false",
                "a job: REPLACEMENT closed, in a transaction=false, drained=false",
            ),
            observed,
        )
        assertEquals(0, process.registry.handles().size)
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
        assertEquals(20, process.count("objects"))
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertWiped(directory, chunky)
    }

    @Test
    fun aHandleIsClosedInsideAnAdmissionWheneverTheGateIsOpen() {
        // The closes that follow no failure at all: a store's own close, and the release at the
        // end of a job's run. With the gate open each is counted while it runs.
        val directory = aFilledStore(chunky, rows = 20)
        val process = ReplicaProcess(directory, chunky)
        val drainedAtClose = ArrayList<Boolean>()
        process.registry.closeFault = {
            drainedAtClose += process.registry.gate.closeAndDrain(0)
            process.registry.gate.reopen()
        }
        val store = process.store()
        store.storeObject(row(99))
        store.close()
        val job = process.replacement()
        assertIs<ChunkResult.More>(job.startWipe("discard"))
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        process.registry.closeFault = null
        assertTrue(drainedAtClose.size >= 3, "closes seen: ${drainedAtClose.size}")
        assertEquals(emptyList<Boolean>(), drainedAtClose.filter { it }, "a close ran with the gate reading drained")
    }

    @Test
    fun aRunStopsBetweenChunksWhenAskedOrWhenTheGateCloses() {
        val directory = aFilledStore(chunky, rows = 40)
        val process = ReplicaProcess(directory, chunky)
        val job = process.replacement()
        assertIs<ChunkResult.More>(job.startWipe("discard"))

        var asked = 0
        assertIs<ReplicaRunOutcome.Stopped>(job.run { ++asked > 2 })
        assertEquals(40L - 2 * 7, process.count("objects"))

        assertTrue(process.registry.gate.closeAndDrain(100))
        assertIs<ReplicaRunOutcome.Stopped>(job.run())
        assertEquals(40L - 2 * 7, process.count("objects"))
        assertEquals(0, process.registry.handles().size)
        process.registry.gate.reopen()

        assertEquals(ReplicaRunOutcome.Finished, job.run())
        assertWiped(directory, chunky)
    }

    private fun ReplicaProcess.rewinds(): Long = sql { it.queryLong("SELECT coalesce((SELECT v FROM meta WHERE k = 'wal_rewind'), 0)") }

    /** A sibling that knows none of the store's rules grows the log past the ceiling, or just past [bytes]. */
    private fun ReplicaProcess.growLogPastTheCeiling(bytes: Long = limits.beginCeilingBytes) = sql { connection ->
        var next = 100_000L
        while (bytesOf("$databasePath-wal")!!.size <= bytes) {
            connection.exec("INSERT INTO objects (hash, type, data) VALUES (${next++}, 'x', zeroblob(3000))")
        }
    }

    @Test
    fun aLogResetIsNotStartedOnceTheGateHasDrained() {
        val directory = aFilledStore(tight, rows = 10)
        val process = ReplicaProcess(directory, tight)
        process.growLogPastTheCeiling()
        val rewinds = process.rewinds()

        // The chunk has rolled back to reset the log, and a quiesce drains the gate right there.
        // ⚠ "Drained" is what lets quiesce close every connection: nothing of the job may touch
        // the database after it, the ceiling checkpoint and its rewind write included.
        val job = process.replacement()
        var drained: Boolean? = null
        job.beforeLogReset = { drained = process.registry.gate.closeAndDrain(100) }
        val stopped = assertIs<ChunkResult.Ended>(job.startWipe("discard"))
        assertEquals(true, drained)
        assertIs<ReplicaRunOutcome.Stopped>(stopped.outcome)
        assertEquals(rewinds, process.rewinds(), "no checkpoint and no rewind write after the drain")
        assertFalse(process.hasMeta("wipe"))
        assertEquals(0, process.registry.handles().size)

        // With the gate open the same call resets the log and starts the wipe.
        job.beforeLogReset = null
        process.registry.gate.reopen()
        assertIs<ChunkResult.More>(job.startWipe("discard"))
        assertEquals(rewinds + 1, process.rewinds())
    }

    /** A store whose log is past `Wmax/4` and under the ceiling: a job's next commit is followed by a PASSIVE checkpoint. */
    private fun aStoreDueAnOpportunisticCheckpoint(): ReplicaProcess {
        val process = ReplicaProcess(aFilledStore(tight, rows = 10), tight)
        process.growLogPastTheCeiling(tight.journalSizeLimitBytes)
        assertTrue(bytesOf("${process.databasePath}-wal")!!.size <= tight.beginCeilingBytes)
        return process
    }

    @Test
    fun aCorruptionCodeFromTheCheckpointAfterAChunkEndsTheRunAndMarksTheStoreOnlyWhenTheNextCheckpointRepeatsIt() {
        val modes = ArrayList<SqliteCheckpointMode>()
        fun aJobWhoseCheckpointMeetsACorruptionCode(process: ReplicaProcess) = process.replacement().also { job ->
            job.checkpointer.fault = { mode ->
                modes += mode
                throw SqliteException(SQLITE_CORRUPT, "database disk image is malformed")
            }
        }

        // The chunk itself committed; what must not happen is a next chunk on a store SQLite has
        // just called corrupt. One answer ends the run, and marks nothing: the file is healthy.
        val healthy = aStoreDueAnOpportunisticCheckpoint()
        val stopped = assertIs<ChunkResult.Ended>(aJobWhoseCheckpointMeetsACorruptionCode(healthy).startWipe("discard"))
        assertEquals(listOf(SqliteCheckpointMode.PASSIVE), modes)
        assertIs<ReplicaRunOutcome.Stopped>(stopped.outcome)
        assertFalse(fileExists("${healthy.directory}/${ReplicaCorruptMarker.NAME}"))
        assertTrue(healthy.hasMeta("wipe"), "the committed chunk stays")
        assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(healthy.directory, tight).open())

        // A checkpoint's code is never handed to the walk (B.8.3 (113)): the next checkpoint code in
        // a row of this process, on another connection, is the confirmation (B.3 (52)).
        assertFalse(healthy.walk().isPending())
        val damaged = aStoreDueAnOpportunisticCheckpoint()
        damaged.registry.confirmFault = { throw SqliteException(SQLITE_CORRUPT, "database disk image is malformed") }
        assertIs<ReplicaRunOutcome.Stopped>(assertIs<ChunkResult.Ended>(aJobWhoseCheckpointMeetsACorruptionCode(damaged).startWipe("discard")).outcome)
        assertFalse(fileExists("${damaged.directory}/${ReplicaCorruptMarker.NAME}"))
        assertFalse(damaged.walk().isPending())
        // The second code in a row and its marker are ReplicaCorruptionConfirmTest's matrix (B.3 (52)).
        assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(damaged.directory, tight).open())
    }

    @Test
    fun aMarkerThatAppearsWhileTheRewindWaitsForItsLockStopsTheRewindWrite() {
        val process = aStoreDueAnOpportunisticCheckpoint()
        val rewinds = process.rewinds()
        val job = process.replacement()
        // A sibling fences the store after the checkpoint's last look for the marker and before
        // its rewind write holds the write lock. The marker is a file: SQLite's lock does not
        // order the two, so it is looked for again once the lock is held.
        job.checkpointer.beforeRewind = {
            ReplicaCorruptMarker(PosixReplicaFiles, process.directory).apply {
                write("a sibling's finding")
                close()
            }
        }
        val ended = assertIs<ChunkResult.Ended>(job.startWipe("discard"))
        assertIs<ReplicaRunOutcome.Corrupt>(ended.outcome)
        assertEquals(rewinds, process.rewinds(), "nothing is committed to a store that is fenced for good")
    }

    @Test
    fun aRewindThatFoundTheMarkerAnswersTheMarkerEvenWhenItsRollbackFails() {
        val process = aStoreDueAnOpportunisticCheckpoint()
        val rewinds = process.rewinds()
        val job = process.replacement()
        job.checkpointer.beforeRewind = {
            ReplicaCorruptMarker(PosixReplicaFiles, process.directory).apply {
                write("a sibling's finding")
                close()
            }
        }
        // ⚠ The marker was found. Whatever becomes of the ROLLBACK, the answer is the marker:
        // never "reset" off the size of the log, which a caller would take as leave to go on.
        job.checkpointer.rollbackFault = { throw SqliteException(SQLITE_IOERR, "disk I/O error") }
        val ended = assertIs<ChunkResult.Ended>(job.startWipe("discard"))
        assertIs<ReplicaRunOutcome.Corrupt>(ended.outcome)
        assertEquals(rewinds, process.rewinds())
        assertEquals(0, process.registry.handles().size, "the checkpoint's connection was closed, which rolls back")
    }

    @Test
    fun theHoldIsMeasuredFromTheWriteLockSoAStalledProbeCountsAgainstIt() {
        val directory = aFilledStore(ReplicaLimits(), rows = 10)
        var now = 0L
        var stalls = 2
        // The two probes a transaction makes before its body, the marker's and the -wal's, each
        // take 600 ms: with the write lock already held.
        val files = object : ReplicaFiles by PosixReplicaFiles {
            override fun stat(descriptor: Int): ReplicaFileStat {
                if (stalls-- > 0) now += 600
                return PosixReplicaFiles.stat(descriptor)
            }
        }
        val process = ReplicaProcess(directory, files = files)
        val job = process.replacement()
        job.clock = { now }

        val stopped = assertIs<ReplicaRunOutcome.Stopped>(assertIs<ChunkResult.Ended>(job.startWipe("discard")).outcome)
        assertIs<HoldCeilingExceeded>(stopped.cause)
        assertFalse(process.hasMeta("wipe"), "the transaction that held the lock past HOLD_MAX wrote nothing")
        assertIs<ChunkResult.More>(job.startWipe("discard"))
    }

    /** Where [text]'s bytes begin in this frame. */
    private fun ByteArray.indexOf(text: String): Int {
        val wanted = text.encodeToByteArray()
        return (0..size - wanted.size).first { at -> wanted.indices.all { this[at + it] == wanted[it] } }
    }

    // ── the markers ──

    @Test
    fun aMarkerFrameReadsBackAndAFrameOfAnotherFormatIsNotThisBuildsToJudge() {
        val wipe = WipeMarker("forget", "Control", 1_700_000_000L, mapOf("tenshu/core" to 22, file to 3))
        assertEquals(DecodedMarker.Value(wipe), WipeMarker.decode(wipe.encode()))
        for (cursor in listOf<Long?>(null, Long.MIN_VALUE, 0L, Long.MAX_VALUE)) {
            val migration = MigrationMarker("Drive", 5L, mapOf(file to 3, "tenshu/sync" to 9), file, 1, MigrationPhase.SWEEP, cursor)
            assertEquals(DecodedMarker.Value(migration), MigrationMarker.decode(migration.encode()))
        }
        val frame = wipe.encode()
        assertEquals(DecodedMarker.Malformed, WipeMarker.decode(frame.copyOf(frame.size - 1)))
        assertEquals(DecodedMarker.Malformed, WipeMarker.decode(frame + 0.toByte()))
        assertEquals(DecodedMarker.OtherFormat(2), WipeMarker.decode(byteArrayOf(2, 9, 9)))
        // ⚠ A frame no writer produces is damage even when every field parses. An unstamped
        // service is stamped with no row read, so it is only ever "ROWS, before the first row":
        // the same step with a phase or a cursor is a damaged stamped step, and taking it for
        // unstamped would stamp the service over rows nobody migrated.
        val stamped = MigrationMarker("Drive", 5L, mapOf(file to 3), file, 1, MigrationPhase.VERIFY, 9L)
        val unstamped = stamped.copy(stepFrom = MigrationMarker.UNSTAMPED, phase = MigrationPhase.ROWS, cursor = null)
        assertEquals(DecodedMarker.Value(unstamped), MigrationMarker.decode(unstamped.encode()))
        for (impossible in listOf(
            unstamped.copy(phase = MigrationPhase.SWEEP),
            unstamped.copy(cursor = 4L),
            // The cleanup has no cursor: deleting is idempotent.
            stamped.copy(phase = MigrationPhase.CLEANUP),
        )) {
            assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(impossible.encode()), "$impossible")
        }
        // "No cursor" has one encoding.
        val noCursor = stamped.copy(cursor = null).encode()
        assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(noCursor.copyOf().also { it[it.size - 1] = 1 }))
        // ⚠ Text is decoded strictly. Bytes that are not UTF-8, replaced by U+FFFD, would read as a
        // well-formed frame naming a service nobody declares: SKEW, "update the app", for good.
        for (text in listOf("Control", "tenshu/core")) {
            val damaged = frame.copyOf().also { it[it.indexOf(text)] = 0xFF.toByte() }
            assertEquals(DecodedMarker.Malformed, WipeMarker.decode(damaged), text)
        }
        val migrating = MigrationMarker("Drive", 5L, mapOf(file to 3), file, 1, MigrationPhase.ROWS, null).encode()
        val badTarget = migrating.copyOf().also { it[it.indexOf(file)] = 0xC0.toByte() }
        assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(badTarget))

        // A newer format on the store: SKEW, nothing written. This format, and not a frame: damage.
        val newer = aFilledStore(chunky, rows = 5)
        ReplicaProcess(newer).sql { it.exec("INSERT INTO meta (k, v) VALUES ('wipe', x'020909')") }
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(newer, chunky).open())
        assertEquals(5, ReplicaProcess(newer).count("objects"))

        // ⚠ And it is SKEW in the background too, and for a migration marker: decided by the
        // read-only classification, before the foreground is asked for and before any read/write
        // connection is opened on a store a newer build is working on.
        val otherFormat = aFilledStore(chunky, rows = 5)
        ReplicaProcess(otherFormat).sql { it.exec("INSERT INTO meta (k, v) VALUES ('migration', x'0709')") }
        val bytesBefore = bytesOf("$otherFormat/objects.db")
        val walBefore = bytesOf("$otherFormat/objects.db-wal")
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(otherFormat, chunky).open(foreground = false))
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(otherFormat, chunky).open())
        assertIs<ReplicaRunOutcome.Skew>(assertFailsWith<WipeRefused> { ReplicaProcess(otherFormat, chunky).opener().wipe("discard") }.outcome)
        assertContentEquals(bytesBefore, bytesOf("$otherFormat/objects.db"))
        assertContentEquals(walBefore, bytesOf("$otherFormat/objects.db-wal"))

        // ⚠ And it stays SKEW when another `meta` row of that newer store reads as damaged to this
        // build: nothing permanent is written on a store a newer build is working on.
        ReplicaProcess(otherFormat).sql { it.exec("UPDATE meta SET v = 'by someone' WHERE k = 'wal_rewind'") }
        ReplicaProcess(otherFormat).sql { it.exec("INSERT OR REPLACE INTO meta (k, v) VALUES ('wal_rewind', 'by someone')") }
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(otherFormat, chunky).open())
        assertFalse(fileExists("$otherFormat/${ReplicaCorruptMarker.NAME}"))

        val damaged = aFilledStore(chunky, rows = 5)
        ReplicaProcess(damaged).sql { it.exec("INSERT INTO meta (k, v) VALUES ('migration', x'01')") }
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(damaged, chunky).open())
        assertTrue(fileExists("$damaged/${ReplicaCorruptMarker.NAME}"))
    }

    // ── the migration ──

    private val floors = mapOf(file to 1, "tenshu/sync" to 1)
    private val oldBuild = testBuild(services = mapOf(file to 1, "tenshu/sync" to 8), serviceFloors = floors)
    private val newBuild = testBuild(services = mapOf(file to 3, "tenshu/sync" to 9), serviceFloors = floors)

    private class Step(override val typeIds: Set<String>, private val migration: (DbEntry) -> DbEntry?) : ReplicaMigrationStep {
        override fun migrate(row: DbEntry): DbEntry? = migration(row)
    }

    /**
     * `tenshu/file` 1 → 2 appends a 2 to every row; 2 → 3 drops every fifth row and appends a 3
     * to the rest; `tenshu/sync` 8 → 9 moves no row. ⚠ Appending is not idempotent, which is the
     * point: a row migrated twice by any step shows it.
     */
    private val chain = ReplicaMigrationSteps { service, from ->
        when (service to from) {
            file to 1 -> Step(setOf(file)) { it.copy(fields = it.fields + DbValue.LongValue(2)) }
            file to 2 -> Step(setOf(file)) { if (it.dataIdHash % 5 == 0L) null else it.copy(fields = it.fields + DbValue.LongValue(3)) }
            "tenshu/sync" to 8 -> Step(emptySet()) { it }
            else -> null
        }
    }

    private val arrays = listOf(9_001L, 9_002L, 9_003L)

    /** A store of the old build: [rows] file rows, rows of a type no step reads, and arrays naming the file rows. */
    private fun anOldStore(limits: ReplicaLimits, rows: Int, payloadBytes: Int = 40): String {
        val directory = tempReplica()
        val store = ReplicaProcess(directory, limits).store(oldBuild)
        // Large rows one a transaction: the fill has a budget too.
        store.fill((1..rows).map { row(it.toLong(), file, payloadBytes) }, batch = if (payloadBytes > 1_000) 1 else 20)
        store.fill((1..10).map { row(5_000L + it, other) })
        store.fill(arrays.map { hash -> DbEntry(TList.ID, hash, (1..rows).filter { it % (hash - 9_000) == 0L }.map { DbValue.LongValue(it.toLong()) }) })
        store.close()
        return directory
    }

    private fun migrating(directory: String, limits: ReplicaLimits, build: ReplicaBuild = newBuild) =
        ReplicaProcess(directory, limits).also { it.steps = chain }.let { it to it.replacement(build) }

    private fun assertMigrated(directory: String, limits: ReplicaLimits, rows: Int, payloadBytes: Int = 40) {
        val process = ReplicaProcess(directory, limits).also { it.steps = chain }
        assertFalse(process.hasMeta("migration"), "the marker is gone")
        assertEquals(0, process.count("migration_done"))
        assertEquals(0, process.count("migration_dropped"))
        assertEquals(3, process.stamp(file))
        assertEquals(9, process.stamp("tenshu/sync"))
        val store = assertIs<ReplicaOpenResult.Ready>(process.open(newBuild)).store
        for (hash in 1..rows.toLong()) {
            val original = row(hash, file, payloadBytes)
            val expected = if (hash % 5 == 0L) null else original.copy(fields = original.fields + DbValue.LongValue(2) + DbValue.LongValue(3))
            assertEquals(expected, store.getObject(hash), "row $hash: migrated exactly once by each step")
        }
        for (hash in 1..10L) assertEquals(row(5_000 + hash, other), store.getObject(5_000 + hash))
        for (hash in arrays) {
            val members = (1..rows).filter { it % (hash - 9_000) == 0L && it % 5 != 0 }.map { DbValue.LongValue(it.toLong()) }
            assertEquals(members, store.getObject(hash)?.fields, "array $hash names no dropped row")
        }
        store.close()
    }

    @Test
    fun aChainOfStepsOverSeveralChunksMigratesEveryRowExactlyOnce() {
        val directory = anOldStore(chunky, rows = 60)
        // A sibling of the old build with the store open.
        val sibling = ReplicaProcess(directory, chunky).store(oldBuild)
        val before = sibling.epoch

        val process = ReplicaProcess(directory, chunky).also { it.steps = chain }
        val opened = assertIs<ReplicaOpenResult.Updating>(process.open(newBuild))
        assertEquals(ReplicaJob.MIGRATION, opened.job)
        // The Start: the marker and a new epoch, no row touched, no stamp moved.
        val started = process.metaInt("epoch")
        assertNotEquals(before, started)
        assertEquals(1, process.stamp(file))
        assertEquals("migrating test", process.sql { it.queryText("SELECT v FROM meta WHERE k = 'replaced_by'") })
        assertNull(sibling.getObject(1))
        assertEquals(ReplicaFence.REPLACED, sibling.fencedBy)

        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())
        assertTrue(opened.replacement.commits > 30, "many chunks: ${opened.replacement.commits}")
        assertEquals(0, opened.replacement.ceilingBreaches)
        assertNotEquals(started, process.metaInt("epoch"), "the chain's Finish moves the epoch")
        assertMigrated(directory, chunky, rows = 60)
    }

    @Test
    fun aStepOverManyTypesTakesItsRowsInHashOrderAndHoldsOneRowAtATimeNotAPagePerType() {
        val types = (1..6).map { "tenshu/type-$it" }
        val directory = tempReplica()
        val store = ReplicaProcess(directory, chunky).store(oldBuild)
        // Hashes interleave the types, so every chunk's page is a merge of all six.
        val rows = (1..120L).map { row(it, types[(it % 6).toInt()], payloadBytes = 3_000) }
        store.fill(rows, batch = 5)
        store.close()

        val process = ReplicaProcess(directory, chunky)
        process.steps = ReplicaMigrationSteps { service, from ->
            if (service == file && from == 1) Step(types.toSet()) { it.copy(fields = it.fields + DbValue.LongValue(2)) } else null
        }
        val build = testBuild(services = mapOf(file to 2, "tenshu/sync" to 8), serviceFloors = floors)
        val job = process.replacement(build)
        assertIs<ChunkResult.More>(job.admit(foreground = true))
        assertEquals(ReplicaRunOutcome.Finished, job.run())

        // ⚠ A page of blobs per type is `types × page` of memory, on a phone: a step over a broad
        // schema would be killed for it on every retry. The rows are read one at a time.
        assertTrue(job.largestRowBytesHeld in 3_000..4_000, "one row held at a time: ${job.largestRowBytesHeld} bytes")
        val migrated = assertIs<ReplicaOpenResult.Ready>(process.open(build)).store
        for (row in rows) assertEquals(row.copy(fields = row.fields + DbValue.LongValue(2)), migrated.getObject(row.dataIdHash))
        migrated.close()
    }

    @Test
    fun aChainKilledAtEveryCommitResumesWhereItStoppedAndNeverServesAMixedStore() {
        var commitsAllowed = 0
        while (true) {
            val directory = anOldStore(chunky, rows = 20)
            val kill = Kill(commitsAllowed)
            val (process, job) = migrating(directory, chunky)
            kill.arm(job)
            val admitted = job.admit(foreground = true)
            val outcome = if (admitted is ChunkResult.More) job.run() else (admitted as ChunkResult.Ended).outcome

            if (!kill.fired) {
                assertEquals(ReplicaRunOutcome.Finished, outcome)
                assertMigrated(directory, chunky, rows = 20)
                assertTrue(commitsAllowed > 20, "the chain was many chunks: $commitsAllowed commits")
                return
            }
            val what = "killed at commit $commitsAllowed"
            assertIs<ReplicaRunOutcome.Stopped>(outcome, what)
            if (commitsAllowed > 0) {
                // The stamp moves only at a step finish, with the marker: a store stamped at a
                // version is never part-way to it.
                val marker = assertIs<DecodedMarker.Value<MigrationMarker>>(MigrationMarker.decode(process.metaBlob("migration")!!), what).marker
                assertEquals(marker.stepFrom.toLong(), process.stamp(marker.stepService), what)
                // No build is handed a store: not the old one, not the new one.
                assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).open(oldBuild), what)
                assertFailsWith<StoreNeedsForeground>(what) { ReplicaProcess(directory, chunky).open(newBuild, foreground = false) }
            }
            val epoch = process.metaInt("epoch")
            val resumer = ReplicaProcess(directory, chunky).also { it.steps = chain }
            val resumed = assertIs<ReplicaOpenResult.Updating>(resumer.open(newBuild), what)
            if (commitsAllowed > 0) assertEquals(epoch, process.metaInt("epoch"), "a resume does not move the epoch")
            assertEquals(ReplicaRunOutcome.Finished, resumed.replacement.run(), what)
            assertMigrated(directory, chunky, rows = 20)
            commitsAllowed++
        }
    }

    @Test
    fun twoResumersTakingTurnsNeverFenceEachOtherAndNoRowIsMigratedTwice() {
        val directory = anOldStore(chunky, rows = 40)
        val (process, first) = migrating(directory, chunky)
        val (_, second) = migrating(directory, chunky)
        assertEquals(ChunkResult.More(ReplicaJob.MIGRATION), first.admit(foreground = true))
        assertEquals(ChunkResult.More(ReplicaJob.MIGRATION), second.admit(foreground = true))
        val epoch = process.metaInt("epoch")

        var turns = 0
        var done = 0
        while (done < 2) {
            done = 0
            for (job in listOf(first, second)) {
                when (val result = job.runOneChunk()) {
                    is ChunkResult.More -> assertEquals(epoch, process.metaInt("epoch"), "no chunk between start and finish moves the epoch")
                    is ChunkResult.Ended -> {
                        assertEquals(ReplicaRunOutcome.Finished, result.outcome)
                        done++
                    }
                }
            }
            if (++turns > 500) fail("the chain did not finish")
        }
        assertTrue(first.commits > 5 && second.commits > 5, "both worked: ${first.commits}, ${second.commits}")
        assertMigrated(directory, chunky, rows = 40)
    }

    @Test
    fun aBuildWhoseChainGoesFurtherExtendsTheTargetsAndTheOlderResumerThenWritesNothing() {
        val directory = anOldStore(chunky, rows = 20)
        val middleBuild = testBuild(services = mapOf(file to 2, "tenshu/sync" to 8), serviceFloors = floors)
        val (process, older) = migrating(directory, chunky, middleBuild)
        assertEquals(ChunkResult.More(ReplicaJob.MIGRATION), older.admit(foreground = true))
        fun targets() = assertIs<DecodedMarker.Value<MigrationMarker>>(MigrationMarker.decode(process.metaBlob("migration")!!)).marker.targets
        assertEquals(mapOf(file to 2), targets())

        // The newer build resumes the same marker, and at the first step finish carries it on.
        val (_, newer) = migrating(directory, chunky)
        assertEquals(ChunkResult.More(ReplicaJob.MIGRATION), newer.admit(foreground = true))
        var turns = 0
        while (process.stamp(file) == 1L) {
            assertIs<ChunkResult.More>(newer.runOneChunk())
            if (++turns > 200) fail("the first step never finished")
        }
        assertEquals(mapOf(file to 3, "tenshu/sync" to 9), targets())

        // The older build's next chunk reads targets it is below: SKEW, and it writes nothing.
        val frame = process.metaBlob("migration")
        val seq = process.metaInt("seq")
        assertIs<ReplicaRunOutcome.Skew>(assertIs<ChunkResult.Ended>(older.runOneChunk()).outcome)
        assertContentEquals(frame, process.metaBlob("migration"))
        assertEquals(seq, process.metaInt("seq"))
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).also { it.steps = chain }.open(middleBuild))

        assertEquals(ReplicaRunOutcome.Finished, newer.run())
        assertMigrated(directory, chunky, rows = 20)
    }

    @Test
    fun aServiceWithNoStampIsStampedAndAChainWithNoRegisteredStepIsWiped() {
        // No stamp: no earlier build wrote rows of it. Stamped at its target, no row touched.
        val unstamped = aFilledStore(chunky, rows = 10)
        ReplicaProcess(unstamped).sql { it.exec("DELETE FROM schema_service_versions WHERE service_id = 'tenshu/sync'") }
        ReplicaProcess(unstamped).sql { it.exec("DELETE FROM migration_done") }
        ReplicaProcess(unstamped).sql { it.exec("DELETE FROM migration_dropped") }
        val opened = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(unstamped, chunky).open())
        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())
        val process = ReplicaProcess(unstamped, chunky)
        assertEquals(9, process.stamp("tenshu/sync"))
        val store = process.store()
        assertEquals(row(7), store.getObject(7))
        store.close()

        // Behind, above the floor, and this build registers no step from there: nothing can read
        // the rows, so the job becomes a wipe, stamped with this build's versions at its end.
        val cut = aFilledStore(chunky, rows = 30, build = testBuild(core = 20))
        val started = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(cut, chunky).open())
        assertEquals(ReplicaJob.MIGRATION, started.job)
        assertEquals(ReplicaRunOutcome.Finished, started.replacement.run())
        assertEquals(22, ReplicaProcess(cut).sql { it.queryLong("PRAGMA user_version") })
        assertWiped(cut, chunky)
    }

    @Test
    fun aWipeDuringAMigrationReplacesItAndLeavesNothingOfIt() {
        val directory = anOldStore(chunky, rows = 40)
        val (process, job) = migrating(directory, chunky)
        Kill(commitsAllowed = 9).arm(job)
        assertIs<ChunkResult.More>(job.admit(foreground = true))
        assertIs<ReplicaRunOutcome.Stopped>(job.run())
        assertTrue(process.count("migration_done") > 0, "part-way through a step")

        // Only a build that could have resumed the migration may wipe over it.
        assertIs<ReplicaRunOutcome.Skew>(assertFailsWith<WipeRefused> { ReplicaProcess(directory, chunky).opener(oldBuild).wipe("discard") }.outcome)
        assertTrue(process.hasMeta("migration"))

        assertTrue(process.opener(newBuild).wipe("discard"))
        assertFalse(process.hasMeta("migration"))
        for (table in ReplicaSchema.tablesEmptiedByWipe) assertEquals(0, process.count(table), table)
        // Empty, so stamped at the wiping build's versions: nothing is left to migrate.
        assertEquals(3, process.stamp(file))
        assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(directory, chunky).also { it.steps = chain }.open(newBuild)).store.close()
    }

    @Test
    fun everyMigrationChunkStaysInsideTheWalCeilingWhenTheBudgetEndsIt() {
        val directory = anOldStore(tight, rows = 400, payloadBytes = 3_000)
        val (_, job) = migrating(directory, tight)
        assertIs<ChunkResult.More>(job.admit(foreground = true))
        assertEquals(ReplicaRunOutcome.Finished, job.run())
        // With no budget the whole chain is fifteen transactions (one per phase and change
        // of phase); a rewritten row is a page, and a chunk has room for a few dozen.
        assertTrue(job.commits > 20, "the budget cut the chain into chunks: ${job.commits}")
        assertEquals(0, job.ceilingBreaches)
        assertTrue(job.largestWalBytes <= tight.wmaxBytes, "the -wal stayed under Wmax: ${job.largestWalBytes}")
        assertMigrated(directory, tight, rows = 400, payloadBytes = 3_000)
    }

    // ── three things slice 1 left ──

    @Test
    fun theWalDescriptorIsNotLeftOpenByAMeasurementAfterTheConnectionClosed() {
        val files = RecordingFiles()
        val process = ReplicaProcess(tempReplica(), files = files)
        val store = process.store()
        store.storeObject(row(1))
        fun walDescriptors() = files.openDescriptors.values.count { it == "objects.db-wal" }
        assertEquals(1, walDescriptors(), "kept while the connection is open")

        // A quiesce closes the connection, and with it the probe. A measurement after that
        // (the test seam; a checkpoint's verification) must not leave one behind.
        assertTrue(process.registry.gate.closeAndDrain(100))
        process.registry.closeAllForQuiesce()
        assertEquals(0, walDescriptors())
        assertTrue(store.walBytes > 0)
        assertEquals(0, walDescriptors(), "measured with the connection closed: opened, read, closed")
        process.registry.gate.reopen()

        store.close()
        assertTrue(store.walBytes > 0)
        assertEquals(0, walDescriptors(), "and after the handle was released")
    }

    @Test
    fun anAccessThatFailsAtItsEpochReadCleansUpBeforeItLeavesTheGate() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        store.epochFault = { throw SqliteException(SQLITE_IOERR, "disk I/O error") }
        // ⚠ "Drained" tells quiesce that nothing of this process touches the database any more.
        // The rollback and the close of a failed access are such touches: asked at the moment
        // the connection is being closed, the gate must still count the access.
        var drainedWhileClosing: Boolean? = null
        process.registry.closeFault = {
            drainedWhileClosing = process.registry.gate.closeAndDrain(0)
            process.registry.gate.reopen()
        }
        assertFailsWith<ReplicaAccessFailed> { store.getObject(1) }
        assertEquals(false, drainedWhileClosing)
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
        assertTrue(process.registry.gate.closeAndDrain(0), "and it has left the gate once it is done")
    }

    @Test
    fun theCorruptionMarkerIsWrittenInsideAnAdmissionOrNotNow() {
        // A sidecar with no objects.db: damage found on the file system, with no access open.
        val directory = tempReplica()
        PosixReplicaFiles.createExclusive("$directory/objects.db-wal")
        val process = ReplicaProcess(directory)
        // ⚠ Quiesce waits for a marker write as for any operation (§4.2.1), which it can only
        // do for one inside an admission. With the gate closed and drained none is started:
        // the verdict is a retry, and the next open, admitted, writes it.
        assertTrue(process.registry.gate.closeAndDrain(0))
        assertFailsWith<StoreUnavailable> { process.open() }
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
        process.registry.gate.reopen()
        assertIs<ReplicaOpenResult.Corrupt>(process.open())
        assertTrue(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
    }

    @Test
    fun aDamageVerdictUnderTheWriteLockWritesItsMarkerBeforeGivingTheLockUp() {
        for (damage in listOf("CREATE INDEX extra ON objects(data)", "DELETE FROM meta WHERE k = 'seq'")) {
            val directory = tempReplica()
            ReplicaProcess(directory).store().close()
            ReplicaProcess(directory).sql { it.exec(damage) }

            val files = RecordingFiles()
            var lockHeld: Boolean? = null
            files.onCall = { call ->
                if (call.startsWith("rename")) {
                    // A sibling that begins now would pass its own marker check and commit to a
                    // store already judged damaged.
                    val sibling = rawConnection("$directory/objects.db")
                    lockHeld = try {
                        sibling.exec("BEGIN IMMEDIATE")
                        sibling.exec("ROLLBACK")
                        false
                    } catch (e: SqliteException) {
                        e.primaryCode == SQLITE_BUSY
                    } finally {
                        sibling.close()
                    }
                }
            }
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory, files = files).open(), damage)
            assertEquals(true, lockHeld, damage)
        }
    }
}
