package org.tenshu.core.common.proxy.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store runs in WAL mode, so its on-disk log is only bounded if SQLite can periodically
 * **reset** it. Copying pages into the db is not enough: a checkpoint that copies everything but
 * cannot restart the log leaves the WAL growing forever, and — because the data is safely in the
 * db — nothing else goes wrong to give it away.
 *
 * ⚠⚠ Observed in production before the fix these tests pin: a desktop Control left running for two
 * days held a **124 MB WAL against a 3.6 MB database** (34x the entire file), growing ~1 MB/min
 * while otherwise idle. `PRAGMA wal_checkpoint(TRUNCATE)` reported `busy=1, log=849,
 * checkpointed=849` — every page copied, the log un-resettable. Cause: [SqliteObjectStore.readRow]
 * caches its point-read statement (re-preparing is the measured dominant cost of a cold read) and
 * used to `reset()` it only on the way *in*, so a stepped-but-unreset SELECT held an implicit read
 * transaction from the end of one point read until the start of the next — under lazy hydration,
 * essentially always.
 *
 * ⚠ Why the assertion is `busy`, not a WAL byte size: the file is a high-water mark that SQLite
 * reuses in place rather than shrinking, so its size is a lagging, timing-dependent proxy. `busy`
 * is the invariant itself — *can the log be reset right now* — and it is what actually diverges.
 */
class SqliteObjectStoreWalResetTest {

    private fun tempDbPath(): String =
        createTempDirectory("sqlite-objstore-wal").resolve("objects.db").toString()

    private fun walSizeBytes(dbPath: String): Long =
        Path.of("$dbPath-wal").let { if (Files.exists(it)) Files.size(it) else 0L }

    private companion object {
        /** The row a point read pins a snapshot on. */
        const val SEED_HASH = 1L

        /** A row written *after* that snapshot, so the log has frames the reader cannot see. */
        const val LATER_HASH = 2L
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun entry(hash: Long) = DbEntry(
        type = "row",
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("name-$hash")),
        properties = mapOf("v" to DbValue.LongValue(hash)),
    )

    /**
     * Result of `PRAGMA wal_checkpoint(TRUNCATE)` run on an independent connection: `busy` is 0
     * only when no reader held a snapshot, i.e. when the log could actually be restarted.
     */
    private data class CheckpointResult(val busy: Int, val logFrames: Int, val checkpointed: Int)

    private fun checkpointTruncate(path: String): CheckpointResult {
        val conn = BundledSQLiteDriver().open(path)
        try {
            // Without a timeout the pragma would return busy for ordinary write contention too,
            // which would make this assert something weaker than it claims.
            conn.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
            return conn.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { stmt ->
                assertTrue(stmt.step(), "wal_checkpoint returned no row")
                CheckpointResult(
                    busy = stmt.getLong(0).toInt(),
                    logFrames = stmt.getLong(1).toInt(),
                    checkpointed = stmt.getLong(2).toInt(),
                )
            }
        } finally {
            conn.close()
        }
    }

    /**
     * The control. Identical to the test below except that **no point read happens**, so if the
     * checkpoint cannot restart the log even here, the failure is the harness (a stray connection,
     * an uncommitted writer) and not the behaviour under test. Without it, a green
     * `busy == 0` in the real test would be indistinguishable from an environment where the
     * checkpoint trivially succeeds for reasons of its own.
     */
    @Test
    fun `control - with no point read, the log can be reset`() = runTest {
        val path = tempDbPath()
        val store = SqliteObjectStore.open(path, scope())
        try {
            store.storeObject(entry(SEED_HASH))
            store.flush()
            store.storeObject(entry(LATER_HASH))
            store.flush()

            assertEquals(0, checkpointTruncate(path).busy, "nothing is holding a snapshot here")
        } finally {
            store.close()
        }
    }

    @Test
    fun `point reads release their read transaction, so the WAL can still be reset`() = runTest {
        val path = tempDbPath()
        val store = SqliteObjectStore.open(path, scope())
        store.storeObject(entry(SEED_HASH))
        store.flush()
        store.close()

        // Reopen so the resident cache is cold: the read below must MISS and go through the
        // read-through point-SELECT, which is the path under test. Reading from a warm cache would
        // never touch `readConn` and the test would pass without exercising anything.
        val reopened = SqliteObjectStore.open(path, scope())
        try {
            // ⚠⚠ Exactly ONE read and ONE subsequent write, because that is exactly what the
            // mechanism needs — and saying so in code is the point. An earlier draft looped 200
            // reads and 400 writes, which tested nothing the first of each did not already test
            // and left the reader unable to tell which part mattered. Verified minimal: with the
            // bug present this fails at these counts, so neither is padding.
            //
            // ⚠⚠ The ORDER is the whole test, and the first draft got it wrong and passed against
            // the bug. A reader pinning the *current* end of the log blocks nothing — there is no
            // newer state for the checkpoint to be held back from. The failure needs the
            // production shape: a reader holding an OLD snapshot while the writer appends past it.
            assertNotNull(reopened.getObject(SEED_HASH), "seeded row should be on disk")

            reopened.storeObject(entry(LATER_HASH))
            reopened.flush()

            val result = checkpointTruncate(path)
            assertEquals(
                0,
                result.busy,
                "a reader is still holding a snapshot after a point read, so the WAL can never be " +
                    "reset and will grow without bound (copied ${result.checkpointed} of " +
                    "${result.logFrames} frames)",
            )
            // The checkpointer must also have got all the way through: a partial copy is the same
            // defect wearing a different number, and `busy` alone would not report it.
            assertEquals(
                result.logFrames,
                result.checkpointed,
                "checkpoint copied only ${result.checkpointed} of ${result.logFrames} frames",
            )
        } finally {
            reopened.close()
        }
    }

    /**
     * The fix stops the log *growing*; it cannot shrink one that is already huge, because SQLite
     * resets a checkpointed WAL but never truncates the file. Without the open-time reclaim, every
     * store that already grew a 124 MB log would keep that space allocated forever.
     */
    @Test
    fun `opening a store reclaims a log a previous process left oversized`() = runTest {
        val path = tempDbPath()

        // Build the oversized log directly rather than by reproducing the bug: `autocheckpoint=0`
        // means nothing ever resets it, which is the same end state by a deterministic route.
        // ⚠ The connection is deliberately left OPEN — SQLite checkpoints and removes the WAL when
        // the LAST connection closes, which would erase the precondition before the test began.
        val bloater = BundledSQLiteDriver().open(path)
        try {
            bloater.prepare("PRAGMA journal_mode=WAL").use { it.step() }
            bloater.prepare("PRAGMA wal_autocheckpoint=0").use { it.step() }
            bloater.prepare(
                "CREATE TABLE IF NOT EXISTS objects (" +
                    "hash INTEGER PRIMARY KEY NOT NULL, type TEXT NOT NULL, data BLOB NOT NULL)"
            ).use { it.step() }
            repeat(2_000) { i ->
                bloater.prepare("INSERT OR REPLACE INTO objects(hash, type, data) VALUES (?, ?, ?)")
                    .use { s ->
                        s.bindLong(1, i.toLong())
                        s.bindText(2, "row")
                        s.bindBlob(3, ByteArray(2_048) { i.toByte() })
                        s.step()
                    }
            }

            val bloated = walSizeBytes(path)
            assertTrue(bloated > 1_000_000, "precondition: wanted an oversized WAL, got $bloated B")

            val store = SqliteObjectStore.open(path, scope())
            try {
                val reclaimed = walSizeBytes(path)
                assertTrue(
                    reclaimed < bloated / 2,
                    "opening the store should have truncated the stale log, but it went from " +
                        "$bloated B to $reclaimed B",
                )
            } finally {
                store.close()
            }
        } finally {
            bloater.close()
        }
    }

    /**
     * The miss path returning *no* row already self-completed (stepping to DONE ends the implicit
     * transaction), so it was never the leak — pinned so a future refactor cannot regress the half
     * that always worked while fixing the half that did not.
     */
    @Test
    fun `a point read that finds nothing also leaves no reader holding the log`() = runTest {
        val path = tempDbPath()
        val store = SqliteObjectStore.open(path, scope())
        store.storeObject(entry(1L))
        store.flush()
        store.close()

        val reopened = SqliteObjectStore.open(path, scope())
        try {
            repeat(50) { assertEquals(null, reopened.getObject(9_000L + it), "absent row") }
            assertEquals(0, checkpointTruncate(path).busy, "a missing-row read must hold nothing")
        } finally {
            reopened.close()
        }
    }
}
