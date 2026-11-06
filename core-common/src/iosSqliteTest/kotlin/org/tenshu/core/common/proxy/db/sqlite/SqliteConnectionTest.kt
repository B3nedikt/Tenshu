@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OK
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OPEN_CREATE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OPEN_READWRITE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_READONLY
import cnames.structs.sqlite3
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_close_v2
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_exec
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_open_v2
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import platform.posix.usleep
import kotlin.concurrent.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The store's own SQLite driver (`docs/proposals/ios-shared-store-sqlite.md` §2.1.1, §2.1.2): the
 * four things Q0 gate zero needs of it, and the small statement surface.
 */
class SqliteConnectionTest {

    private fun tempDb(): String {
        val dir = NSTemporaryDirectory() + "sqlite-driver-test-" + Random.nextLong().toString(16)
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return "$dir/objects.db"
    }

    private fun bytesOf(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val n = data.length.toInt()
        if (n == 0) return ByteArray(0)
        return ByteArray(n).also { out ->
            out.usePinned { memcpy(it.addressOf(0), data.bytes, n.convert()) }
        }
    }

    private fun SqliteConnection.queryLong(sql: String): Long {
        val statement = prepare(sql)
        try {
            assertTrue(statement.step(), "no row for: $sql")
            return statement.getLong(0)
        } finally {
            statement.close()
        }
    }

    /** WAL mode, a table, and [transactions] committed single-row transactions. */
    private fun SqliteConnection.writeSomeWal(transactions: Int = 20) {
        exec("PRAGMA journal_mode=WAL")
        exec("CREATE TABLE IF NOT EXISTS t (k INTEGER PRIMARY KEY, v BLOB)")
        val insert = prepare("INSERT INTO t (k, v) VALUES (?, ?)")
        repeat(transactions) { i ->
            exec("BEGIN IMMEDIATE")
            insert.bindLong(1, i.toLong())
            insert.bindBlob(2, ByteArray(300) { (i + it).toByte() })
            insert.step()
            insert.reset()
            exec("COMMIT")
        }
        insert.close()
    }

    // ── the version gate ──

    @Test
    fun theLinkedLibraryIsTheVendoredFixedBuild() {
        assertEquals(SqliteLibrary.vendoredVersionNumber, SqliteLibrary.linkedVersionNumber)
        assertEquals(SqliteLibrary.vendoredSourceId, SqliteLibrary.linkedSourceId)
        assertTrue(SqliteLibrary.isFixedVersion(SqliteLibrary.linkedVersionNumber), SqliteLibrary.linkedVersion)
        assertTrue(SqliteLibrary.linkedVersionNumber >= 3_051_003, "pinned at or above 3.51.3")
        SqliteLibrary.requireFixedBuild()
    }

    @Test
    fun theGateKnowsWhichVersionsCarryTheWalResetFix() {
        for (fixed in listOf(3_051_003, 3_051_004, 3_052_000, 3_053_004, 3_050_007, 3_050_008, 3_044_006)) {
            assertTrue(SqliteLibrary.isFixedVersion(fixed), "$fixed is fixed")
        }
        for (broken in listOf(3_007_000, 3_044_005, 3_045_000, 3_049_002, 3_050_001, 3_050_006, 3_051_000, 3_051_002)) {
            assertFalse(SqliteLibrary.isFixedVersion(broken), "$broken has the bug")
        }
    }

    @Test
    fun theGateRefusesTheAndroidxBinaryAndAForeignBuildOfAFixedVersion() {
        // androidx.sqlite:sqlite-bundled 2.7.1 and 2.8.0-alpha01: SQLite 3.50.1.
        val androidx = "2025-06-06 14:52:32 b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95"
        assertNotNull(SqliteLibrary.refusal(3_050_001, androidx))
        // A fixed version that is not the vendored source: another SQLite won the link.
        assertNotNull(SqliteLibrary.refusal(SqliteLibrary.vendoredVersionNumber, "some other build"))
        assertNotNull(SqliteLibrary.refusal(3_052_000, SqliteLibrary.vendoredSourceId))
        assertNull(SqliteLibrary.refusal(SqliteLibrary.vendoredVersionNumber, SqliteLibrary.vendoredSourceId))
    }

    // ── NO_CKPT_ON_CLOSE ──

    @Test
    fun openSetsNoCheckpointOnCloseOnEveryConnectionKind() {
        val path = tempDb()
        val first = SqliteConnection.open(path)
        assertTrue(first.noCheckpointOnClose(), "read/write")
        first.writeSomeWal(transactions = 2)
        val second = SqliteConnection.open(path)
        assertTrue(second.noCheckpointOnClose(), "a second connection in the process")
        val readOnly = SqliteConnection.open(path, readOnly = true)
        assertTrue(readOnly.noCheckpointOnClose(), "read-only")
        readOnly.close(); second.close(); first.close()
    }

    @Test
    fun closingTheLastConnectionLeavesTheWalUntouched() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal()
        val walBefore = assertNotNull(bytesOf("$path-wal"), "frames are in the WAL before the close")
        val dbBefore = assertNotNull(bytesOf(path))
        assertTrue(walBefore.size > 20 * 300, "the WAL holds the 20 transactions: ${walBefore.size} bytes")

        connection.close()

        assertContentEquals(walBefore, bytesOf("$path-wal"), "the -wal is byte-identical after the last close")
        assertContentEquals(dbBefore, bytesOf(path), "no frame was checkpointed into the database file")

        // And nothing is lost: the next open recovers every committed row from that WAL.
        val reopened = SqliteConnection.open(path)
        assertEquals(20L, reopened.queryLong("SELECT count(*) FROM t"))
        reopened.close()
    }

    /**
     * The control for the test above: the same writes on a connection opened WITHOUT the option
     * (raw C API, not the driver). SQLite then checkpoints at the last close and deletes the WAL.
     * If this ever stops holding, the test above proves nothing.
     */
    @Test
    fun withoutTheOptionTheLastCloseCheckpointsAndRemovesTheWal() {
        val path = tempDb()
        memScoped {
            val out = alloc<CPointerVar<sqlite3>>()
            assertEquals(SQLITE_OK, sqlite3_open_v2(path, out.ptr, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE, null))
            val db = out.value
            val sql = "PRAGMA journal_mode=WAL; CREATE TABLE t (k INTEGER PRIMARY KEY, v BLOB);" +
                (0 until 20).joinToString("") { "INSERT INTO t VALUES ($it, zeroblob(300));" }
            assertEquals(SQLITE_OK, sqlite3_exec(db, sql, null, null, null))
            val dbBefore = assertNotNull(bytesOf(path))
            assertTrue(assertNotNull(bytesOf("$path-wal")).isNotEmpty())
            assertEquals(SQLITE_OK, sqlite3_close_v2(db))
            assertNull(bytesOf("$path-wal"), "an ordinary last close checkpoints and deletes the -wal")
            assertTrue(assertNotNull(bytesOf(path)).size > dbBefore.size, "the frames were checkpointed into the database")
        }
    }

    // ── interrupt ──

    @Test
    fun interruptFromAnotherThreadStopsARunningStatement() = runBlocking {
        val connection = SqliteConnection.open(tempDb())
        // Never finishes on its own.
        val endless = connection.prepare(
            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c",
        )
        val start = TimeSource.Monotonic.markNow()
        val interruptedAtNs = AtomicLong(0)
        val interrupter = launch(Dispatchers.Default) {
            usleep(200_000u)
            interruptedAtNs.value = start.elapsedNow().inWholeNanoseconds
            connection.interrupt()
        }
        val failure = assertFailsWith<SqliteException> { endless.step() }
        val returnedAtNs = start.elapsedNow().inWholeNanoseconds
        interrupter.join()

        assertEquals(SQLITE_INTERRUPT, failure.primaryCode)
        assertTrue(interruptedAtNs.value > 0, "the statement was still running when the interrupt was sent")
        val latencyMs = (returnedAtNs - interruptedAtNs.value) / 1e6
        println("interrupt-to-return: $latencyMs ms")
        assertTrue(latencyMs < 500.0, "the statement returned $latencyMs ms after the interrupt")

        // The connection is usable afterwards.
        endless.close()
        assertTrue(connection.inAutocommit())
        assertEquals(1L, connection.queryLong("SELECT 1"))
        connection.close()
    }

    @Test
    fun interruptRollsBackTheWriteTransactionItStops() = runBlocking {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal(transactions = 1)
        connection.exec("BEGIN IMMEDIATE")
        val endless = connection.prepare(
            "WITH RECURSIVE c(x) AS (SELECT 100 UNION ALL SELECT x + 1 FROM c) INSERT INTO t SELECT x, NULL FROM c",
        )
        val interrupter = launch(Dispatchers.Default) {
            usleep(100_000u)
            connection.interrupt()
        }
        val failure = assertFailsWith<SqliteException> { endless.step() }
        interrupter.join()
        assertEquals(SQLITE_INTERRUPT, failure.primaryCode)
        endless.close()
        if (!connection.inAutocommit()) connection.exec("ROLLBACK")
        assertEquals(1L, connection.queryLong("SELECT count(*) FROM t"), "nothing of the stopped statement stayed")
        connection.close()
    }

    @Test
    fun anInterruptWithNothingRunningAffectsNothingLater() {
        val connection = SqliteConnection.open(tempDb())
        connection.interrupt()
        connection.writeSomeWal(transactions = 3)
        assertEquals(3L, connection.queryLong("SELECT count(*) FROM t"))
        connection.close()
        connection.interrupt() // after close: a no-op, not a call into a freed handle
    }

    /**
     * ⚠ The one thing a stale interrupt does reach. SQLite clears the interrupt flag when the next
     * statement is prepared or stepped, but `sqlite3_wal_checkpoint_v2` checks the flag per frame
     * and clears it only on its way out. So an interrupt that arrives with nothing running stops a
     * checkpoint that is the connection's very next call, and only that one (Q0 (ac), (t)).
     */
    @Test
    fun aStaleInterruptStopsACheckpointThatIsTheVeryNextCall() {
        val connection = SqliteConnection.open(tempDb())
        connection.writeSomeWal()
        connection.interrupt()
        val stopped = assertFailsWith<SqliteException> { connection.checkpoint(SqliteCheckpointMode.PASSIVE) }
        assertEquals(SQLITE_INTERRUPT, stopped.primaryCode)
        // The failed call cleared the flag: the same checkpoint now runs to the end.
        val retried = connection.checkpoint(SqliteCheckpointMode.PASSIVE)
        assertFalse(retried.busy)
        assertEquals(retried.logFrames, retried.checkpointedFrames)

        // With any statement in between, the stale interrupt is gone before the checkpoint.
        connection.exec("INSERT INTO t VALUES (5000, NULL)")
        connection.interrupt()
        assertEquals(1L, connection.queryLong("SELECT 1"))
        assertFalse(connection.checkpoint(SqliteCheckpointMode.PASSIVE).busy)
        connection.close()
    }

    // ── backup ──

    @Test
    fun backupCopiesTheWalContentAndLeavesTheSourceUntouched() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal(transactions = 50)
        val dbBefore = assertNotNull(bytesOf(path))
        val walBefore = assertNotNull(bytesOf("$path-wal"))
        assertTrue(walBefore.size > 32, "the rows are in the WAL, not the database file")

        val copyPath = "$path.copy"
        val result = connection.backupTo(copyPath, pagesPerStep = 2)
        assertTrue(result.completed)
        assertTrue(result.steps > 1, "more than one step: ${result.steps}")
        assertTrue(result.pages > 2, "pages: ${result.pages}")
        assertContentEquals(dbBefore, bytesOf(path))
        assertContentEquals(walBefore, bytesOf("$path-wal"))
        assertTrue(connection.inAutocommit())
        connection.close()

        val copy = SqliteConnection.open(copyPath, readOnly = true)
        assertEquals(50L, copy.queryLong("SELECT count(*) FROM t"))
        assertEquals("ok", copy.prepare("PRAGMA integrity_check").let { s -> s.step(); s.getText(0).also { s.close() } })
        copy.close()
    }

    @Test
    fun aBackupStoppedBetweenStepsReportsItselfIncomplete() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal(transactions = 50)
        var calls = 0
        val result = connection.backupTo("$path.copy", pagesPerStep = 1) { ++calls < 2 }
        assertFalse(result.completed)
        assertEquals(2, result.steps)
        // The connection is still usable, and holds no read transaction open.
        assertTrue(connection.inAutocommit())
        assertEquals(50L, connection.queryLong("SELECT count(*) FROM t"))
        connection.close()
    }

    /**
     * A throwing `betweenSteps` must still finish the backup object: left unfinished it keeps the
     * source's read transaction open and defers the destination's close, lock and all.
     */
    @Test
    fun aBackupWhoseCallbackThrowsReleasesBothDatabases() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal(transactions = 50)
        val copyPath = "$path.copy"
        var calls = 0
        val thrown = assertFailsWith<IllegalStateException> {
            connection.backupTo(copyPath, pagesPerStep = 1) { if (++calls == 2) error("stop here") else true }
        }
        assertEquals("stop here", thrown.message)
        assertEquals(2, calls)

        // The source: no transaction left open, readable, and writable.
        assertTrue(connection.inAutocommit())
        assertEquals(50L, connection.queryLong("SELECT count(*) FROM t"))
        connection.exec("INSERT INTO t VALUES (9000, NULL)")
        // A reader the backup had left behind would keep RESTART busy.
        assertFalse(connection.checkpoint(SqliteCheckpointMode.RESTART).busy)

        // The destination: nobody holds it. Another connection can take its exclusive lock, and
        // the partial file can be deleted and the backup run again to the same path.
        val other = SqliteConnection.open(copyPath)
        other.exec("PRAGMA locking_mode=EXCLUSIVE")
        other.exec("BEGIN EXCLUSIVE")
        other.exec("ROLLBACK")
        other.close()
        assertTrue(NSFileManager.defaultManager.removeItemAtPath(copyPath, null))
        assertNull(bytesOf(copyPath))
        assertTrue(connection.backupTo(copyPath, pagesPerStep = 16).completed)
        connection.close()

        val copy = SqliteConnection.open(copyPath, readOnly = true)
        assertEquals(51L, copy.queryLong("SELECT count(*) FROM t"))
        copy.close()
    }

    @Test
    fun aBackupIntoADirectoryThatDoesNotExistFails() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal(transactions = 2)
        assertFailsWith<SqliteException> { connection.backupTo("$path.missing/copy.db", pagesPerStep = 8) }
        assertEquals(2L, connection.queryLong("SELECT count(*) FROM t"))
        connection.close()
    }

    // ── checkpoint, autocommit, read-only ──

    @Test
    fun checkpointReportsFramesAndAutocommitTracksTheTransaction() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal()
        assertTrue(connection.inAutocommit())
        connection.exec("BEGIN IMMEDIATE")
        assertFalse(connection.inAutocommit())
        connection.exec("COMMIT")
        assertTrue(connection.inAutocommit())

        val passive = connection.checkpoint(SqliteCheckpointMode.PASSIVE)
        assertFalse(passive.busy)
        assertTrue(passive.logFrames > 0, "frames in the log: ${passive.logFrames}")
        assertEquals(passive.logFrames, passive.checkpointedFrames, "no reader: every frame is backfilled")

        // A reader holding a snapshot on another connection stops RESTART, reported as busy.
        val reader = SqliteConnection.open(path)
        val select = reader.prepare("SELECT k FROM t")
        assertTrue(select.step())
        connection.exec("INSERT INTO t VALUES (1000, NULL)")
        assertTrue(connection.checkpoint(SqliteCheckpointMode.RESTART).busy)
        select.close()
        assertFalse(connection.checkpoint(SqliteCheckpointMode.RESTART).busy)
        reader.close(); connection.close()
    }

    /** Frames in a `-wal` file: a 32-byte header, then a 24-byte frame header plus one page each. */
    private fun framesIn(walPath: String, pageSize: Long): Int =
        ((assertNotNull(bytesOf(walPath)).size - 32) / (pageSize + 24)).toInt()

    @Test
    fun checkpointCountsAreTheMainDatabasesFrames() {
        val path = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal()
        val pageSize = connection.queryLong("PRAGMA page_size")
        val frames = framesIn("$path-wal", pageSize)
        assertTrue(frames >= 20, "at least one frame per committed transaction: $frames")

        val result = connection.checkpoint(SqliteCheckpointMode.PASSIVE)

        assertFalse(result.busy)
        assertEquals(frames, result.logFrames, "the count is the number of frames in main's -wal")
        assertEquals(frames, result.checkpointedFrames, "no reader: every one of them is backfilled")
        connection.close()
    }

    @Test
    fun checkpointLeavesAnAttachedDatabaseAlone() {
        val path = tempDb()
        val otherPath = tempDb()
        val connection = SqliteConnection.open(path)
        connection.writeSomeWal(transactions = 5)
        connection.exec("ATTACH DATABASE '$otherPath' AS other")
        connection.exec("PRAGMA other.journal_mode=WAL")
        connection.exec("CREATE TABLE other.u (k INTEGER PRIMARY KEY, v BLOB)")
        repeat(30) { connection.exec("INSERT INTO other.u VALUES ($it, zeroblob(300))") }
        val pageSize = connection.queryLong("PRAGMA page_size")
        val mainFrames = framesIn("$path-wal", pageSize)
        val otherFrames = framesIn("$otherPath-wal", pageSize)
        assertTrue(otherFrames > mainFrames, "the attached WAL is the longer one: $otherFrames > $mainFrames")
        val otherDbBefore = assertNotNull(bytesOf(otherPath))

        val result = connection.checkpoint(SqliteCheckpointMode.PASSIVE)

        assertEquals(mainFrames, result.logFrames, "main's frames, not the attached database's")
        assertEquals(mainFrames, result.checkpointedFrames)
        assertContentEquals(otherDbBefore, bytesOf(otherPath), "the attached database was not checkpointed")
        connection.close()
    }

    @Test
    fun aReadOnlyConnectionCannotWrite() {
        val path = tempDb()
        SqliteConnection.open(path).apply { writeSomeWal(transactions = 2); close() }
        val readOnly = SqliteConnection.open(path, readOnly = true)
        assertEquals(2L, readOnly.queryLong("SELECT count(*) FROM t"))
        val failure = assertFailsWith<SqliteException> { readOnly.exec("INSERT INTO t VALUES (99, NULL)") }
        assertEquals(SQLITE_READONLY, failure.primaryCode)
        readOnly.close()
    }

    @Test
    fun openingAMissingFileReadOnlyFailsAndCreatesNothing() {
        val path = tempDb()
        assertFailsWith<SqliteException> { SqliteConnection.open(path, readOnly = true) }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
    }

    // ── statements ──

    @Test
    fun valuesRoundTripThroughBindAndColumn() {
        val connection = SqliteConnection.open(tempDb())
        connection.exec("CREATE TABLE v (k INTEGER PRIMARY KEY, n INTEGER, s TEXT, b BLOB)")
        val insert = connection.prepare("INSERT INTO v (k, n, s, b) VALUES (?, ?, ?, ?)")
        val blob = ByteArray(40_000) { (it * 31).toByte() }
        val text = "héllo \u0000 wörld ✓"
        insert.bindLong(1, 1); insert.bindLong(2, Long.MIN_VALUE); insert.bindText(3, text); insert.bindBlob(4, blob)
        assertFalse(insert.step()); insert.reset()
        insert.bindLong(1, 2); insert.bindNull(2); insert.bindText(3, ""); insert.bindBlob(4, ByteArray(0))
        assertFalse(insert.step()); insert.reset()
        insert.close()

        val select = connection.prepare("SELECT n, s, b, typeof(s), typeof(b) FROM v WHERE k = ?")
        select.bindLong(1, 1)
        assertTrue(select.step())
        assertEquals(Long.MIN_VALUE, select.getLong(0))
        assertEquals(text, select.getText(1))
        assertContentEquals(blob, select.getBlob(2))
        assertFalse(select.step())
        select.reset()
        select.bindLong(1, 2)
        assertTrue(select.step())
        assertTrue(select.isNull(0))
        assertEquals("", select.getText(1))
        assertEquals(0, select.getBlob(2).size)
        assertEquals("text", select.getText(3), "an empty string is bound as text, not NULL")
        assertEquals("blob", select.getText(4), "an empty array is bound as a blob, not NULL")
        select.close()
        connection.close()
    }

    // ── a statement stopped from inside ──

    @Test
    fun aProgressHandlerStopsQuickCheckAndIsGoneAfterwards() {
        val connection = SqliteConnection.open(tempDb())
        connection.writeSomeWal(transactions = 200)
        val check = connection.prepare("PRAGMA quick_check(1)")

        // Never asked to stop: it runs to its answer, and it was asked on the way.
        var asked = 0
        val answer = connection.stoppableBy(everyOps = 1, shouldStop = { asked++; false }) {
            assertTrue(check.step())
            check.getText(0).also { check.reset() }
        }
        assertEquals("ok", answer)
        assertTrue(asked > 0, "the handler was asked $asked times")

        // Asked to stop, at each of its first asks in turn (the walk of the B-trees asks once a
        // page, the row loops after it once a step): the statement ends with SQLITE_INTERRUPT
        // every time. ⚠ Never with a row: a check that was cut short has no finding, and a row
        // from one would be read as damage.
        val total = asked
        for (stopAt in 1..minOf(total, 80)) {
            var n = 0
            val stopped = assertFailsWith<SqliteException>("stopped at ask $stopAt of $total") {
                connection.stoppableBy(everyOps = 1, shouldStop = { ++n >= stopAt }) { check.step() }
            }
            assertEquals(SQLITE_INTERRUPT, stopped.primaryCode, "stopped at ask $stopAt of $total")
            check.reset()
        }

        // A predicate that throws stops the statement too: nothing is thrown through SQLite.
        val thrown = assertFailsWith<SqliteException> {
            connection.stoppableBy(everyOps = 1, shouldStop = { error("no") }) { check.step() }
        }
        assertEquals(SQLITE_INTERRUPT, thrown.primaryCode)
        check.reset()

        // The handler does not outlive the call, whichever way it ended.
        asked = 0
        assertTrue(check.step())
        assertEquals("ok", check.getText(0))
        assertEquals(0, asked)
        check.close()
        assertEquals(200L, connection.queryLong("SELECT count(*) FROM t"))
        connection.close()
    }

    @Test
    fun aStatementResetAfterItsConnectionClosedItIsANoOp() {
        val connection = SqliteConnection.open(tempDb())
        connection.writeSomeWal(transactions = 2)
        val select = connection.prepare("SELECT count(*) FROM t")
        assertTrue(select.step())
        select.close()
        connection.close()
        // Its user's `finally` still runs: finalized, there is nothing of SQLite's left to touch.
        select.reset()
        select.close()
    }

    @Test
    fun aFailedStatementCarriesSqlitesMessage() {
        val connection = SqliteConnection.open(tempDb())
        val failure = assertFailsWith<SqliteException> { connection.exec("SELECT * FROM missing") }
        assertTrue("missing" in failure.message.orEmpty(), failure.message)
        connection.close()
        connection.close() // idempotent
    }
}
