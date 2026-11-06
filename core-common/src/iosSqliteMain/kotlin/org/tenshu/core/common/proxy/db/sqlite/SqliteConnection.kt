@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CHECKPOINT_FULL
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CHECKPOINT_PASSIVE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CHECKPOINT_RESTART
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CHECKPOINT_TRUNCATE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_DONE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NULL
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OK
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OPEN_CREATE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OPEN_READONLY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_OPEN_READWRITE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_ROW
import cnames.structs.sqlite3
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_backup_finish
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_backup_init
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_backup_pagecount
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_backup_step
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_extended_errcode
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_bind_int64
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_bind_double
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_bind_null
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_bind_zeroblob
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_close_v2
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_column_blob
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_column_bytes
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_column_double
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_column_int64
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_column_text
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_column_type
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_errmsg
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_errstr
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_extended_result_codes
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_finalize
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_get_autocommit
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_interrupt
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_open_v2
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_prepare_v2
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_progress_handler
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_reset
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_step
import cnames.structs.sqlite3_stmt
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_wal_checkpoint_v2
import org.tenshu.core.common.proxy.db.sqlite.c.tenshu_sqlite3_bind_blob_transient
import org.tenshu.core.common.proxy.db.sqlite.c.tenshu_sqlite3_bind_text_transient
import org.tenshu.core.common.proxy.db.sqlite.c.tenshu_sqlite3_no_ckpt_on_close
import kotlin.concurrent.AtomicInt

/**
 * A SQLite call failed. [resultCode] is the extended code (every connection turns extended result
 * codes on); [primaryCode] is its low byte, the one to compare with `SQLITE_BUSY`,
 * `SQLITE_INTERRUPT` and the like.
 */
class SqliteException(val resultCode: Int, message: String) : RuntimeException(message) {
    val primaryCode: Int get() = resultCode and 0xff
}

/** `sqlite3_wal_checkpoint_v2`'s mode. */
enum class SqliteCheckpointMode(internal val code: Int) {
    PASSIVE(SQLITE_CHECKPOINT_PASSIVE),
    FULL(SQLITE_CHECKPOINT_FULL),
    RESTART(SQLITE_CHECKPOINT_RESTART),
    TRUNCATE(SQLITE_CHECKPOINT_TRUNCATE),
}

/**
 * What a checkpoint did. [busy]: a reader or writer stopped it from finishing ([SqliteCheckpointMode.PASSIVE]
 * never reports it; it checkpoints what it can). [logFrames] and [checkpointedFrames] are `-1`
 * when the database is not in WAL mode.
 */
data class SqliteCheckpointResult(val busy: Boolean, val logFrames: Int, val checkpointedFrames: Int)

/**
 * What [SqliteConnection.backupTo] did. [completed] is false when the caller's `betweenSteps`
 * stopped it; the destination file is then partial and the caller deletes it. [pages] is the
 * source's size in pages as of the last step.
 */
data class SqliteBackupResult(val completed: Boolean, val pages: Int, val steps: Int)

/**
 * The iOS shared store's connection: it opens with `sqlite3_open_v2` and **owns the `sqlite3*`**
 * (`docs/proposals/ios-shared-store-sqlite.md` §2.1.1). Two ship requirements need that handle and
 * no published driver gives it out: [interrupt], and suppressing the checkpoint SQLite runs when
 * the last connection to a database closes, which [open] does before any statement.
 *
 * ⚠⚠ **Threading.** SQLite is built multi-thread (`THREADSAFE=2`): a connection and its statements
 * may be used from any thread but from **one at a time**, and this class adds no lock; the caller
 * serialises. The one exception is [interrupt], which may be called from another thread while a
 * statement runs.
 *
 * Per-connection settings (`journal_mode`, `synchronous`, `busy_timeout`, …; §2.3.1) are the
 * store's, issued through [exec].
 */
class SqliteConnection private constructor(
    private val db: CPointer<sqlite3>,
    val path: String,
    val readOnly: Boolean,
) {
    // Serialises [interrupt] against [close] only: an interrupt racing the close would call into a
    // freed handle. Both sides hold it for a few instructions, so it spins.
    private val closeGuard = AtomicInt(0)
    private var closed = false

    private inline fun <T> underCloseGuard(block: () -> T): T {
        while (!closeGuard.compareAndSet(0, 1)) { /* spin */ }
        try {
            return block()
        } finally {
            closeGuard.value = 0
        }
    }

    /** Prepares one statement. The caller closes it before closing the connection. */
    fun prepare(sql: String): SqliteStatement = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        check(sqlite3_prepare_v2(db, sql, -1, out.ptr, null), "prepare: $sql")
        SqliteStatement(this@SqliteConnection, out.value ?: error("no statement in: $sql"))
    }

    /** Prepares [sql], steps it to completion (draining any rows a PRAGMA returns), finalizes it. */
    fun exec(sql: String) {
        val statement = prepare(sql)
        try {
            while (statement.step()) { /* drain */ }
        } finally {
            statement.close()
        }
    }

    /**
     * `sqlite3_interrupt`: the statement running on this connection returns `SQLITE_INTERRUPT`
     * at its next check, and its transaction is rolled back if it was writing. Safe from another
     * thread while a statement runs; a no-op once the connection is closed.
     *
     * It also stops a running [checkpoint], per frame (Q0 (t)). ⚠ Two things it does **not** do:
     * - **With nothing running** it is lost before the next statement (SQLite clears the flag
     *   when a statement is prepared or stepped), but **not before a [checkpoint] that is the
     *   connection's very next call**: that checkpoint returns `SQLITE_INTERRUPT` if it has
     *   frames to copy.
     * - **It does not reach inside one B-tree operation.** A single-row write runs to its end, and
     *   so does an unconditional `DELETE FROM table` (SQLite's truncate optimisation is one
     *   opcode per tree, with no interrupt check): both return normally and the interrupt is lost.
     */
    fun interrupt() {
        underCloseGuard { if (!closed) sqlite3_interrupt(db) }
    }

    /**
     * `sqlite3_wal_checkpoint_v2` on the main database. A checkpoint stopped by a reader or a
     * writer comes back with [SqliteCheckpointResult.busy], not as an exception.
     */
    fun checkpoint(mode: SqliteCheckpointMode): SqliteCheckpointResult = memScoped {
        val log = alloc<IntVar>()
        val checkpointed = alloc<IntVar>()
        // "main", never null: a null name checkpoints every attached database too, and the two
        // frame counts are then not defined as the main database's.
        val rc = sqlite3_wal_checkpoint_v2(db, "main", mode.code, log.ptr, checkpointed.ptr)
        if ((rc and 0xff) != SQLITE_BUSY) check(rc, "checkpoint ${mode.name}")
        SqliteCheckpointResult(rc != SQLITE_OK, log.value, checkpointed.value)
    }

    /**
     * The online backup API (`sqlite3_backup_init` / `_step` / `_finish`): copies this database,
     * WAL content included, page by page from one read snapshot into a new file at
     * [destinationPath], [pagesPerStep] pages per step. It reads pages through the pager without
     * interpreting any B-tree. [betweenSteps] runs after every step that is not the last; returning
     * false stops the copy. For Control's snapshot of a corrupt store only (§6).
     *
     * ⚠ Not inside a transaction on this connection, and an [interrupt] does not reach it: the
     * place to stop it is [betweenSteps]. A failed step throws; the destination is then partial,
     * like a stopped one, and the caller deletes it.
     */
    fun backupTo(destinationPath: String, pagesPerStep: Int, betweenSteps: () -> Boolean = { true }): SqliteBackupResult {
        require(pagesPerStep > 0) { "pagesPerStep must be positive" }
        val destination = memScoped {
            val out = alloc<CPointerVar<sqlite3>>()
            val rc = sqlite3_open_v2(destinationPath, out.ptr, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE, null)
            val handle = out.value
            if (rc != SQLITE_OK || handle == null) {
                handle?.let { sqlite3_close_v2(it) }
                throw SqliteException(rc, "backup: open $destinationPath: ${sqlite3_errstr(rc)?.toKString()} (rc=$rc)")
            }
            handle
        }
        try {
            val backup = sqlite3_backup_init(destination, "main", db, "main")
                ?: throw SqliteException(
                    sqlite3_extended_errcode(destination),
                    "backup init: ${sqlite3_errmsg(destination)?.toKString() ?: "?"}",
                )
            var steps = 0
            var completed = false
            var failure = SQLITE_OK
            val pages: Int
            // The backup object is finished on every way out, a throwing [betweenSteps] included:
            // unfinished, it keeps the source's read transaction open and turns the destination's
            // close below into a deferred one that still holds its lock.
            try {
                while (true) {
                    val rc = sqlite3_backup_step(backup, pagesPerStep)
                    steps++
                    if (rc == SQLITE_DONE) {
                        completed = true
                        break
                    }
                    if (rc != SQLITE_OK) {
                        failure = rc
                        break
                    }
                    if (!betweenSteps()) break
                }
                pages = sqlite3_backup_pagecount(backup)
            } finally {
                // finish repeats the step's error, which is already in hand.
                sqlite3_backup_finish(backup)
            }
            if (failure != SQLITE_OK) {
                throw SqliteException(failure, "backup step: ${sqlite3_errstr(failure)?.toKString() ?: "?"} (rc=$failure)")
            }
            return SqliteBackupResult(completed, pages, steps)
        } finally {
            sqlite3_close_v2(destination)
        }
    }

    /**
     * Runs [block] with a progress handler on this connection: [shouldStop] is asked every
     * [everyOps] steps of SQLite's virtual machine, and by `quick_check` every [everyOps] pages
     * of its B-tree walk, on the calling thread; `true` ends the statement then running with
     * `SQLITE_INTERRUPT`. The handler is removed before this returns, whatever [block] did.
     *
     * For a statement that must stop at a deadline with no second thread to [interrupt] it. The
     * vendored build keeps the progress callback for this (`scripts/build-sqlite-apple.sh`).
     * ⚠ A statement shorter than [everyOps] steps is never asked.
     */
    fun <T> stoppableBy(everyOps: Int, shouldStop: () -> Boolean, block: () -> T): T {
        require(everyOps > 0) { "everyOps must be positive" }
        val asked = StableRef.create(shouldStop)
        sqlite3_progress_handler(db, everyOps, staticCFunction(::progressStop), asked.asCPointer())
        try {
            return block()
        } finally {
            // Not on a handle [block] closed: the pointer is no longer SQLite's.
            underCloseGuard { if (!closed) sqlite3_progress_handler(db, 0, null, null) }
            asked.dispose()
        }
    }

    /** `sqlite3_get_autocommit`: true when no transaction is open on this connection. */
    fun inAutocommit(): Boolean = sqlite3_get_autocommit(db) != 0

    /** Reads the no-checkpoint-on-close option back. [open] sets it; this is the proof it held. */
    fun noCheckpointOnClose(): Boolean = memScoped {
        val state = alloc<IntVar>()
        check(tenshu_sqlite3_no_ckpt_on_close(db, -1, state.ptr), "read NO_CKPT_ON_CLOSE")
        state.value == 1
    }

    /**
     * `sqlite3_close_v2`. Runs no checkpoint and leaves `-wal` as it is (see [open]). Idempotent.
     * Statements still open are finalized by SQLite when the last of them is closed.
     */
    fun close() {
        val first = underCloseGuard { !closed.also { closed = true } }
        if (first) sqlite3_close_v2(db)
    }

    internal fun check(rc: Int, what: String) {
        if (rc == SQLITE_OK) return
        throw SqliteException(rc, "$what: ${sqlite3_errmsg(db)?.toKString() ?: "?"} (rc=$rc)")
    }

    companion object {
        /**
         * Opens [path]: read/write, creating it if absent, or read-only if [readOnly].
         *
         * In order: the version gate ([SqliteLibrary.requireFixedBuild]), which refuses before any
         * file is touched; `sqlite3_open_v2`; then, **before any statement**,
         * the no-checkpoint-on-close db_config option, set and read back; then extended result codes.
         * A connection on which the option did not hold is closed and the open fails.
         */
        fun open(path: String, readOnly: Boolean = false, create: Boolean = true): SqliteConnection {
            SqliteLibrary.requireFixedBuild()
            // ⚠ [create] false: a read/write open that never makes the file (Control's SNAPSHOT
            // handle, which must write nothing to the originals, Appendix B.18.8 (310)).
            val flags = when {
                readOnly -> SQLITE_OPEN_READONLY
                create -> SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
                else -> SQLITE_OPEN_READWRITE
            }
            val db = memScoped {
                val out = alloc<CPointerVar<sqlite3>>()
                val rc = sqlite3_open_v2(path, out.ptr, flags, null)
                val handle = out.value
                if (rc != SQLITE_OK || handle == null) {
                    // sqlite3_open_v2 hands back a handle even when it fails; it must be closed.
                    val message = handle?.let { sqlite3_errmsg(it)?.toKString() }
                        ?: sqlite3_errstr(rc)?.toKString()
                    handle?.let { sqlite3_close_v2(it) }
                    throw SqliteException(rc, "open $path: $message (rc=$rc)")
                }
                handle
            }
            val connection = SqliteConnection(db, path, readOnly)
            try {
                memScoped {
                    val state = alloc<IntVar>()
                    connection.check(tenshu_sqlite3_no_ckpt_on_close(db, 1, state.ptr), "set NO_CKPT_ON_CLOSE")
                    if (state.value != 1 || !connection.noCheckpointOnClose()) {
                        throw SqliteException(SQLITE_OK, "open $path: NO_CKPT_ON_CLOSE did not take effect")
                    }
                }
                connection.check(sqlite3_extended_result_codes(db, 1), "extended result codes")
            } catch (t: Throwable) {
                connection.close()
                throw t
            }
            return connection
        }
    }
}

/**
 * [SqliteConnection.stoppableBy]'s callback. Nothing may be thrown through SQLite's frames, so a
 * predicate that throws stops the statement.
 */
private fun progressStop(asked: COpaquePointer?): Int =
    try {
        if (asked!!.asStableRef<() -> Boolean>().get().invoke()) 1 else 0
    } catch (_: Throwable) {
        1
    }

/**
 * One prepared statement of a [SqliteConnection]. Parameter indexes start at 1, column indexes at
 * 0, as in SQLite. Same threading rule as its connection.
 */
class SqliteStatement internal constructor(
    private val connection: SqliteConnection,
    private val statement: CPointer<sqlite3_stmt>,
) {
    private var closed = false

    fun bindLong(index: Int, value: Long) = connection.check(sqlite3_bind_int64(statement, index, value), "bind")

    fun bindNull(index: Int) = connection.check(sqlite3_bind_null(statement, index), "bind")

    fun bindDouble(index: Int, value: Double) = connection.check(sqlite3_bind_double(statement, index, value), "bind")

    fun bindText(index: Int, value: String) = bindBytes(index, value.encodeToByteArray(), text = true)

    fun bindBlob(index: Int, value: ByteArray) = bindBytes(index, value, text = false)

    /** Binds [bytes] as TEXT exactly as given, never re-encoded: a value read with [getBlob] goes back as it was stored, valid UTF-8 or not. */
    fun bindTextBytes(index: Int, bytes: ByteArray) = bindBytes(index, bytes, text = true)

    private fun bindBytes(index: Int, bytes: ByteArray, text: Boolean) {
        val rc = when {
            // An empty array has no element to pin. '' and x'' are distinct values in SQLite, and
            // a null pointer to sqlite3_bind_blob would bind NULL.
            bytes.isEmpty() && text -> tenshu_sqlite3_bind_text_transient(statement, index, null, 0)
            bytes.isEmpty() -> sqlite3_bind_zeroblob(statement, index, 0)
            else -> bytes.usePinned { pinned ->
                if (text) {
                    tenshu_sqlite3_bind_text_transient(statement, index, pinned.addressOf(0), bytes.size)
                } else {
                    tenshu_sqlite3_bind_blob_transient(statement, index, pinned.addressOf(0), bytes.size)
                }
            }
        }
        connection.check(rc, "bind")
    }

    /** True if a row is available, false when the statement is done. Anything else throws. */
    fun step(): Boolean = when (val rc = sqlite3_step(statement)) {
        SQLITE_ROW -> true
        SQLITE_DONE -> false
        else -> {
            connection.check(rc, "step")
            false
        }
    }

    fun isNull(column: Int): Boolean = sqlite3_column_type(statement, column) == SQLITE_NULL

    fun getLong(column: Int): Long = sqlite3_column_int64(statement, column)

    fun getDouble(column: Int): Double = sqlite3_column_double(statement, column)

    fun getBlob(column: Int): ByteArray {
        // The pointer first: it is what fixes the value's representation, and so its byte count.
        val bytes = sqlite3_column_blob(statement, column)
        val n = sqlite3_column_bytes(statement, column)
        return if (bytes == null || n == 0) ByteArray(0) else bytes.readBytes(n)
    }

    fun getText(column: Int): String {
        val bytes = sqlite3_column_text(statement, column)
        val n = sqlite3_column_bytes(statement, column)
        return if (bytes == null || n == 0) "" else bytes.readBytes(n).decodeToString()
    }

    /**
     * Ends the current run so the statement can be bound and stepped again, and releases the read
     * snapshot a `SELECT` holds. Bindings are kept.
     */
    fun reset() {
        // A statement finalized under its user (its connection was closed where it failed) has
        // nothing left to reset, and the pointer is no longer SQLite's.
        if (closed) return
        // sqlite3_reset repeats the error of the last step, which the caller has already had.
        sqlite3_reset(statement)
    }

    /** `sqlite3_finalize`. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        sqlite3_finalize(statement)
    }
}
