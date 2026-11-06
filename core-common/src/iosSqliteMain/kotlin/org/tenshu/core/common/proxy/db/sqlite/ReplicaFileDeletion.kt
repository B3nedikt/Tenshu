@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NOTADB
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSFileManager
import platform.posix.F_SETLK
import platform.posix.F_WRLCK
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.open
import platform.posix.pread
import platform.posix.unlink

/**
 * ⚠⚠ **Deleting the files of a store that can never be wiped** (Appendix B.13 (179), decision
 * D14, "delete the files, then purge"): a deprovision whose wipe is refused because the store is
 * **corrupt** or was written by a **newer build** removes the replica directory's files outright,
 * so custody can be purged without leaving a store behind (D3) and without keeping custody at rest
 * after Control cleared the bridge (A4).
 *
 * ⚠⚠ §5's rule is "never unlink the database file under open connections": a connection on an
 * unlinked file goes on reading and writing a ghost while a new store is made at the path. So the
 * deletion first proves that **no connection is open anywhere**, and **every doubt answers
 * [SharedReplicaDeletion.BUSY]** with nothing touched (custody kept, tried again next time):
 * 1. this process has no registered handle on the path (its runtime handle is released first by
 *    the caller), under the registry's lock, held to the end;
 * 2. ⚠⚠ **no other process holds a SQLite lock on the files** (B.13 (184)), by
 *    taking, without waiting, POSIX write locks on exactly the bytes SQLite's unix VFS locks:
 *    `objects.db`'s `[PENDING_BYTE, SHARED_FIRST + SHARED_SIZE)` (`0x40000000` for 512 bytes:
 *    PENDING, RESERVED and the SHARED range, `sqlite3.c` 16727-16733), which every reader and
 *    writer holds while it reads or writes, and `-shm`'s dead-man switch, byte 128
 *    (`UNIX_SHM_DMS`, `sqlite3.c` 44799-44800, asserted at 48762), which **every connection that
 *    has the WAL index open holds for its life, idle or not**. A lock another process holds makes
 *    the take fail; it is never waited for. Held until the unlinks are done, they also make an
 *    open that starts now fail its first lock. ⚠ POSIX locks do not see this process's own, which
 *    step 1 covers. This runs before this process opens any descriptor on the files, since
 *    closing one drops every lock the process holds on that file;
 * 3. a connection of its own leaves WAL mode (`PRAGMA journal_mode = DELETE`), which SQLite does
 *    only as the one connection (`SQLITE_BUSY` otherwise, measured), then takes `BEGIN EXCLUSIVE`.
 *    **Any failure of the open or of the pragma is `BUSY`**: a `CANTOPEN` while protected data
 *    is unavailable, `EMFILE` or a sandbox refusal says nothing about the store.
 * A file that is **not a SQLite database at all** (its 16-byte magic, read with `pread` through
 * the probe's own descriptor) never reaches SQLite: it is unlinked with step 2's locks still held
 * (B.13 (186)).
 */
internal fun deleteReplicaFiles(
    registry: ReplicaHandles,
    directory: String,
    /** Test seam: the open of the deletion's own connection. */
    openConnection: (String) -> SqliteConnection = { SqliteConnection.open(it) },
    /** Test seam: called with the probe's locks held, just before the unlinks. Null in production. */
    beforeUnlink: (() -> Unit)? = null,
    /** Test seam: called after SQLite's connection is closed and before the probe's locks are taken again (188). */
    beforeRelock: (() -> Unit)? = null,
): SharedReplicaDeletion {
    registry.lock.lock()
    try {
        if (registry.handles().isNotEmpty()) return SharedReplicaDeletion.BUSY
        val database = "$directory/${ReplicaOpener.DATABASE_NAME}"
        val locks = ArrayList<Int>()
        // The probe's ranges by descriptor, to take them again once SQLite's connection is closed (188).
        val probes = ArrayList<Triple<Int, Long, Long>>()
        try {
            // Step 2, before any descriptor of SQLite's on the files.
            if (!lockAgainstOtherProcesses(database, SQLITE_LOCK_FIRST, SQLITE_LOCK_BYTES, locks)) return SharedReplicaDeletion.BUSY
            val databaseDescriptor = locks.firstOrNull()
            databaseDescriptor?.let { probes += Triple(it, SQLITE_LOCK_FIRST, SQLITE_LOCK_BYTES) }
            val before = locks.size
            if (!lockAgainstOtherProcesses("$database-shm", SHM_DMS_BYTE, 1, locks)) return SharedReplicaDeletion.BUSY
            if (locks.size > before) probes += Triple(locks.last(), SHM_DMS_BYTE, 1L)
            if (databaseDescriptor == null) {
                beforeUnlink?.invoke()
                unlinkEverything(directory)
                return SharedReplicaDeletion.DELETED
            }
            // ⚠⚠ (186): "unreadable" is decided WITHOUT SQLite, by the 16-byte magic read through
            // the probe's own descriptor. A connection of SQLite's would replace the probe's write
            // locks with its own (POSIX locks are the process's, not the descriptor's), and its
            // release on the failed read would leave nothing held at the unlink. So a file that is
            // not a SQLite database is unlinked under the probe's locks, SQLite never touching it.
            // A failed read (EIO) proves nothing about the store: kept.
            val sqliteFile = looksLikeSqlite(databaseDescriptor) ?: return SharedReplicaDeletion.BUSY
            if (!sqliteFile) {
                beforeUnlink?.invoke()
                unlinkEverything(directory)
                return SharedReplicaDeletion.DELETED
            }
            val connection = try {
                openConnection(database)
            } catch (_: Throwable) {
                // ⚠⚠ Never "unreadable": an open that fails says nothing about the store.
                return SharedReplicaDeletion.BUSY
            }
            var open = true
            try {
                connection.exec("PRAGMA busy_timeout = 0")
                try {
                    val mode = connection.queryText("PRAGMA journal_mode = DELETE")
                    // ⚠ Still `wal`: another connection (SQLite's documented answer). Layered behind
                    // the BUSY below and step 2 (M248, M264): kept for a SQLite that answers so.
                    if (!mode.equals("delete", ignoreCase = true)) return SharedReplicaDeletion.BUSY
                    connection.exec("PRAGMA locking_mode = EXCLUSIVE")
                    connection.exec("BEGIN EXCLUSIVE")
                } catch (e: SqliteException) {
                    // ⚠⚠ (188): a valid magic over a header or schema SQLite cannot read (`CORRUPT`,
                    // `NOTADB`) is the store D14 deletes. SQLite's connection has replaced the
                    // probe's locks with its own and dropped them on the failed read, so it is closed
                    // (dropping whatever it still held) and the probe's locks are TAKEN AGAIN on the
                    // probe's own descriptors: all taken, the unlinks run under them; any refused (a
                    // process that opened meanwhile), kept. Every other code (BUSY, LOCKED,
                    // CANTOPEN, IOERR) is kept as it is.
                    if (e.primaryCode != SQLITE_CORRUPT && e.primaryCode != SQLITE_NOTADB) return SharedReplicaDeletion.BUSY
                    open = false
                    connection.close()
                    beforeRelock?.invoke()
                    if (!probes.all { (descriptor, start, length) -> relock(descriptor, start, length) }) return SharedReplicaDeletion.BUSY
                }
                beforeUnlink?.invoke()
                unlinkEverything(directory)
                return SharedReplicaDeletion.DELETED
            } finally {
                if (open) connection.close()
            }
        } finally {
            locks.forEach { close(it) }
        }
    } finally {
        registry.lock.unlock()
    }
}

/** SQLite's file magic, "SQLite format 3" and its terminating zero (the header's first 16 bytes). */
private val SQLITE_MAGIC: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

/**
 * Whether the file on [descriptor] starts with SQLite's magic, read by `pread` (the descriptor's
 * position and locks untouched). ⚠ **An empty file is "SQLite"**: SQLite reads a zero-length
 * file as an empty database, and it goes the readable path. A short read is not. **A failed read
 * (`-1`, an `EIO`) is null**: it proves nothing, and the deletion keeps the files.
 */
internal fun looksLikeSqlite(descriptor: Int): Boolean? {
    val header = ByteArray(SQLITE_MAGIC.size)
    val read = header.usePinned { pread(descriptor, it.addressOf(0), header.size.convert(), 0) }
    if (read < 0L) return null
    if (read == 0L) return true
    return read == header.size.toLong() && header.contentEquals(SQLITE_MAGIC)
}

/** `PENDING_BYTE`: SQLite's lock bytes start here (`sqlite3.c`, `#define PENDING_BYTE (0x40000000)`). */
internal const val SQLITE_LOCK_FIRST: Long = 0x40000000L

/** PENDING (1) + RESERVED (1) + the SHARED range (`SHARED_SIZE` 510). */
internal const val SQLITE_LOCK_BYTES: Long = 512L

/** `UNIX_SHM_DMS`: `-shm`'s dead-man-switch byte. */
internal const val SHM_DMS_BYTE: Long = 128L

/**
 * A write lock on `[start, start + length)` of [path], taken without waiting, its descriptor kept
 * in [held]. True when taken, or when there is no such file (nothing can hold a lock on it);
 * **false when any other process holds a lock that overlaps**, or the file cannot be opened.
 */
internal fun lockAgainstOtherProcesses(path: String, start: Long, length: Long, held: MutableList<Int>): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    val descriptor = open(path, O_RDWR)
    if (descriptor < 0) return false
    held += descriptor
    return relock(descriptor, start, length)
}

/** A write lock on `[start, start + length)` through [descriptor], without waiting: false when another process holds an overlapping one. */
internal fun relock(descriptor: Int, start: Long, length: Long): Boolean =
    memScoped {
        val request = alloc<flock>()
        request.l_type = F_WRLCK.toShort()
        request.l_whence = SEEK_SET.toShort()
        request.l_start = start
        request.l_len = length
        fcntl(descriptor, F_SETLK, request.ptr) == 0
    }

/** Unlinks every file of [directory] (the store, its sidecars, the marker, the walk's position). The directory stays. */
private fun unlinkEverything(directory: String) {
    @Suppress("UNCHECKED_CAST")
    val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) as? List<String> ?: return
    for (name in names) unlink("$directory/$name")
    runCatching { syncDirectory(PosixReplicaFiles, directory) }
}
