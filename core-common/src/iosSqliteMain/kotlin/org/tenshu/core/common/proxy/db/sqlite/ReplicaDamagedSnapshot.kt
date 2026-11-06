@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalObjCRefinement::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.DT_DIR
import platform.posix.DT_REG
import platform.posix.ENOENT
import platform.posix.ENOSPC
import platform.posix.ENOTDIR
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.closedir
import platform.posix.errno
import platform.posix.opendir
import platform.posix.pread
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.usleep
import platform.posix.write
import kotlin.native.HiddenFromObjC
import kotlin.time.TimeSource

/**
 * How Control's copy of a corrupt store was made (§6 step 4's `CORRUPT` column, paths (1)–(3)).
 */
@HiddenFromObjC
enum class DamagedCopyMethod {
    /**
     * Tried first (⭐ decided by the maintainer 2026-10-30, B.18.8 (319)): the backup API, a fixed
     * number of pages per step, a gate check between steps. Page for page: the damage is kept.
     */
    BACKUP,

    /** Second: the byte copy of `objects.db` and `-wal` in 1 MiB chunks, verified by fingerprint. */
    BYTE_COPY,

    /**
     * The last resort only: `VACUUM INTO`, for a file small enough to finish inside the expiry
     * drain. ⚠ A logical rebuild: it drops what it cannot read and so hides the damage the copy is
     * for. ⚠ Entries are in the order tried: `entries` is the production order.
     */
    VACUUM_INTO,
}

/**
 * A finished copy of a corrupt store (`replica-damaged/<UTC timestamp>/`, with its `COMPLETE`):
 * what "Export damaged data" hands the user. [files] are absolute paths, every regular file of
 * the directory, `COMPLETE` included.
 */
@HiddenFromObjC
class DamagedCopy(val directory: String, val files: List<String>, val method: DamagedCopyMethod) {
    override fun toString(): String = "DamagedCopy($method, $directory, ${files.size} files)"
}

/**
 * How one attempt at Control's snapshot ended (B.18). ⚠ Only [Completed] and [AlreadyComplete]
 * carry a copy that may be offered; every other answer offers nothing.
 */
@HiddenFromObjC
sealed class DamagedSnapshotOutcome {
    /** No `replica/CORRUPT`: there is nothing to preserve. */
    data object NotCorrupt : DamagedSnapshotOutcome()

    /** The marker is there and could not be made durable here: §6 snapshots only after it is. A retry. */
    data object MarkerNotDurable : DamagedSnapshotOutcome()

    /** ⭐ B.18.8 (320): the user deleted this marker's copy in Control: nothing is copied again for it. */
    data object Deleted : DamagedSnapshotOutcome()

    /** A copy of this marker and of the originals as they are now is already complete: taken once per marker. */
    data class AlreadyComplete(val copy: DamagedCopy) : DamagedSnapshotOutcome()

    /** A copy was made, synced and marked `COMPLETE` by this call. */
    data class Completed(val copy: DamagedCopy) : DamagedSnapshotOutcome()

    /** Protection or the foreground ended (F6k), or the caller asked to stop: the `.partial` is left, never offered. */
    data class Stopped(val reason: String) : DamagedSnapshotOutcome()

    /** (4): the writer drain or the writer hold could not take the lock (a peer holds a transaction). */
    data class Busy(val reason: String) : DamagedSnapshotOutcome()

    /** (3): the byte copy's two fingerprints differed in every attempt: "could not be copied consistently". */
    data class Inconsistent(val attempts: Int) : DamagedSnapshotOutcome()

    /** Not enough free space for a whole copy and the floor it must leave: refused before anything is written, or the disk filled during the copy. */
    data class NoRoom(val neededBytes: Long, val freeBytes: Long) : DamagedSnapshotOutcome()

    /** Every path failed, or a file-system step of the copy failed: nothing is offered. */
    data class Failed(val reason: String) : DamagedSnapshotOutcome()
}

/** What the copy needs of the file system beside [ReplicaFiles]: a seam so a test can fill the disk or fail one step. */
internal interface DamagedCopyFiles {
    /** `stat` by path (no descriptor is opened, so no POSIX lock of SQLite's is dropped); null when nothing is there. */
    fun statPath(path: String): ReplicaFileStat?

    /** The names in [directory] that are regular files ([directories] false) or directories ([directories] true); empty when it is not there. */
    fun list(directory: String, directories: Boolean): List<String>

    /** Bytes free for this app on [path]'s volume. Throws when it cannot be read. */
    fun freeBytes(path: String): Long

    /** `pread`: up to [count] bytes at [offset] into [into]; 0 at the end of the file. Throws on failure. */
    fun readAt(descriptor: Int, into: ByteArray, count: Int, offset: Long): Int

    /** A new file for writing (`O_CREAT | O_EXCL`, 0600). Throws on failure. */
    fun createForWrite(path: String): Int

    /** Writes the first [count] bytes of [bytes] whole. Throws [ReplicaNoSpace] on `ENOSPC`, [ReplicaFileException] otherwise. */
    fun writeAll(descriptor: Int, bytes: ByteArray, count: Int)

    /** Removes [directory] and the regular files in it (one level: a copy has no subdirectory). Best effort. */
    fun removeTree(directory: String)

    /** Sets §2.4's protection class and backup exclusion on [path]. Throws when either cannot be set. */
    fun protect(path: String, directory: Boolean)
}

/** A write found the volume full (`ENOSPC`). */
internal class ReplicaNoSpace(message: String) : RuntimeException(message)

internal object PosixDamagedCopyFiles : DamagedCopyFiles {
    private fun failure(what: String, code: Int = errno): ReplicaFileException =
        ReplicaFileException("$what: ${strerror(code)?.toKString() ?: "?"} (errno=$code)")

    override fun statPath(path: String): ReplicaFileStat? = memScoped {
        val out = alloc<stat>()
        if (stat(path, out.ptr) != 0) {
            if (errno == ENOENT || errno == ENOTDIR) return null
            throw failure("stat $path")
        }
        ReplicaFileStat(out.st_size, out.st_nlink.toInt(), out.st_mtimespec.tv_sec, out.st_mtimespec.tv_nsec)
    }

    override fun list(directory: String, directories: Boolean): List<String> {
        val dir = opendir(directory)
        if (dir == null) {
            if (errno == ENOENT || errno == ENOTDIR) return emptyList()
            throw failure("opendir $directory")
        }
        try {
            val names = ArrayList<String>()
            while (true) {
                val entry = readdir(dir)?.pointed ?: break
                val name = entry.d_name.toKString()
                if (name == "." || name == "..") continue
                val isDirectory = when (entry.d_type.toInt()) {
                    DT_DIR -> true
                    DT_REG -> false
                    else -> isDirectoryAt("$directory/$name")
                }
                if (isDirectory == directories) names += name
            }
            return names.sorted()
        } finally {
            closedir(dir)
        }
    }

    private fun isDirectoryAt(path: String): Boolean = memScoped {
        val out = alloc<stat>()
        stat(path, out.ptr) == 0 && (out.st_mode.toInt() and 0xF000) == 0x4000
    }

    override fun freeBytes(path: String): Long {
        // ⚠ "Important usage" counts what iOS would purge to make room for it, as a user-asked
        // copy is; the plain free size is the fallback where the key is not answered.
        val url = NSURL.fileURLWithPath(path)
        val important = (url.resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error = null)
            ?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
        if (important != null && important > 0) return important
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, error = null)
            ?: throw ReplicaFileException("the free space of $path could not be read")
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue
            ?: throw ReplicaFileException("the free space of $path could not be read")
    }

    override fun readAt(descriptor: Int, into: ByteArray, count: Int, offset: Long): Int {
        val n = into.usePinned { pread(descriptor, it.addressOf(0), count.convert(), offset) }
        if (n < 0) throw failure("pread")
        return n.toInt()
    }

    override fun createForWrite(path: String): Int {
        val descriptor = platform.posix.open(path, O_CREAT or O_EXCL or O_WRONLY, 0x180 /* 0600 */)
        if (descriptor < 0) throw failure("create $path")
        return descriptor
    }

    override fun writeAll(descriptor: Int, bytes: ByteArray, count: Int) {
        var written = 0
        while (written < count) {
            val n = bytes.usePinned { write(descriptor, it.addressOf(written), (count - written).convert()) }
            if (n < 0) {
                val code = errno
                if (code == ENOSPC) throw ReplicaNoSpace("the volume is full")
                throw failure("write", code)
            }
            if (n == 0L) throw ReplicaFileException("write wrote nothing")
            written += n.toInt()
        }
    }

    override fun removeTree(directory: String) {
        for (name in list(directory, directories = false)) unlink("$directory/$name")
        rmdir(directory)
    }

    override fun protect(path: String, directory: Boolean) {
        // §2.4 (D3): the replica's class, explicit; a default is not a guarantee.
        val set = NSFileManager.defaultManager.setAttributes(
            mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            ofItemAtPath = path,
            error = null,
        )
        if (!set) throw ReplicaFileException("the protection class could not be set on $path")
        if (directory) {
            val excluded = NSURL.fileURLWithPath(path, isDirectory = true)
                .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
            if (!excluded) throw ReplicaFileException("the backup exclusion could not be set on $path")
            // ⚠ Read back from a fresh URL (an NSURL caches its resource values), as
            // `OwnedInferenceStores` does: a flag that did not hold is a failure, not a hope.
            val held = memScoped {
                val read = alloc<ObjCObjectVar<Any?>>()
                NSURL.fileURLWithPath(path, isDirectory = true).getResourceValue(read.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null)
                (read.value as? NSNumber)?.boolValue == true
            }
            if (!held) throw ReplicaFileException("the backup exclusion did not read back on $path")
        }
    }
}

/**
 * ⚠⚠ **Control's snapshot of a corrupt store** (§6 step 4's `CORRUPT` column, F6k, F12, D8;
 * Appendix B.18, Q47). One consistent copy of `replica/objects.db` (and, for a byte copy, its
 * `-wal`) into `replica-damaged/<UTC timestamp>/`, offered for export only once its `COMPLETE`
 * is on disk. It reads the originals and writes nothing to them (S1's exception).
 *
 * In order, every step stated in §6:
 * 1. **The marker first**: `replica/CORRUPT` must exist **and be durable** in this process. No
 *    marker, nothing to do; one that cannot be made durable, a retry.
 * 2. **Once per marker**: a complete copy whose `COMPLETE` names this marker and the originals'
 *    sizes and times as they are now is offered again, not retaken.
 * 3. **The checkpoint drain**: wait until `CKPT_MAX` has passed since the marker's recorded
 *    time (B.18 (300): before the writer drain, so no lock is held while waiting).
 * 4. **Room** (B.18 (303)): a copy is refused, with nothing written, unless the volume has the
 *    originals' size plus [FREE_FLOOR_BYTES] free; a disk that fills during the copy discards it.
 * 5. **The writer drain** on a registered `SNAPSHOT` handle: `BEGIN IMMEDIATE`, `ROLLBACK`.
 * 6. **The copy**, into `<dir>.partial/`: `VACUUM INTO` (≤ [VACUUM_INTO_MAX_BYTES]), else the
 *    backup API, else the byte copy under a writer hold, verified by fingerprint, at most
 *    [BYTE_COPY_ATTEMPTS] times. A gate check between every step, chunk and page batch.
 * 7. **Durable, then final**: every file `F_FULLFSYNC`ed, the directory too, `COMPLETE` by the
 *    sidecar rule inside `.partial/`, then the rename to `<dir>/` and the parent's sync.
 * 8. **Superseded copies** are removed only after the new one is final (B.18 (305)).
 *
 * ⚠ The `SNAPSHOT` handle is the one handle that skips §3.1 step 0 and §5.2's classification,
 * and it runs exactly `BEGIN IMMEDIATE`, `ROLLBACK`, `VACUUM INTO` and the backup: nothing in this
 * class issues any other statement on it.
 */
internal class ReplicaDamagedSnapshot(
    private val registry: ReplicaHandles,
    private val replicaDirectory: String,
    private val files: ReplicaFiles = PosixReplicaFiles,
    private val copyFiles: DamagedCopyFiles = PosixDamagedCopyFiles,
    private val limits: ReplicaLimits = registry.limits,
    /** Wall-clock milliseconds, for the marker's `CKPT_MAX` (the clock the marker's time was taken from). */
    private val wallClockMs: () -> Long = { files.now().let { (s, ns) -> s * 1_000 + ns / 1_000_000 } },
    /** Sleeps the calling thread. A test seam. */
    private val sleepMs: (Long) -> Unit = { usleep((it * 1_000).toUInt()) },
    private val log: (String) -> Unit = ::println,
) {
    private val databasePath = "$replicaDirectory/${ReplicaOpener.DATABASE_NAME}"
    private val walPath = "$databasePath-wal"
    private val shmPath = "$databasePath-shm"
    private val markerPath = "$replicaDirectory/${ReplicaCorruptMarker.NAME}"

    /** `replica-damaged/`, beside `replica/`. */
    val damagedRoot: String = damagedRootOf(replicaDirectory)

    /** Test seam: the paths tried, in order, in place of [DamagedCopyMethod.entries]. Null in production. */
    internal var methods: List<DamagedCopyMethod>? = null

    /** Test seam: paths that fail as if SQLite or the file system had refused them. Empty in production. */
    internal var failing: Set<DamagedCopyMethod> = emptySet()

    /** Test seam: called between two chunks of a byte copy (attempt, file, offset). Null in production. */
    internal var betweenChunks: ((attempt: Int, file: String, offset: Long) -> Unit)? = null

    /** Test seam: called after the copy and before its files are synced. Null in production. */
    internal var beforeSync: (() -> Unit)? = null

    /**
     * One attempt, on the calling thread (never the main thread: it copies the whole file).
     * [shouldStop] is the caller's own stop (an expired token); the gate and the foreground are
     * asked as well, between every step.
     */
    fun take(shouldStop: () -> Boolean = { false }): DamagedSnapshotOutcome {
        val stop: () -> Boolean = { shouldStop() || !registry.gate.isOpen || !registry.gate.inForeground() }
        val state = ReplicaCorruptMarker(files, replicaDirectory).let { marker ->
            try {
                marker.state()
            } catch (e: ReplicaFileException) {
                return DamagedSnapshotOutcome.Failed("the marker could not be looked for: ${e.message}")
            } finally {
                marker.close()
            }
        }
        when (state) {
            ReplicaMarkerState.ABSENT -> return DamagedSnapshotOutcome.NotCorrupt
            ReplicaMarkerState.NOT_DURABLE -> return DamagedSnapshotOutcome.MarkerNotDurable
            ReplicaMarkerState.DURABLE -> Unit
        }
        return try {
            takeMarked(stop)
        } catch (e: ReplicaFileException) {
            DamagedSnapshotOutcome.Failed(e.message.orEmpty())
        } catch (e: SqliteException) {
            // A drain's ROLLBACK, say: the handle was released on the way out, nothing is offered.
            DamagedSnapshotOutcome.Failed("SQLite: ${e.message}")
        }
    }

    private fun takeMarked(stop: () -> Boolean): DamagedSnapshotOutcome {
        val marker = markerNow() ?: return DamagedSnapshotOutcome.NotCorrupt
        val originals = originalsNow()
        if (deletedFor(files, damagedRoot, marker)) {
            // ⚠ B.18.9 (322): whatever a delete left behind (a crash after its tombstone, an unlink
            // that failed) goes now: the user deleted this data, so none of it may stay.
            removeAllCopies(copyFiles, damagedRoot)
            return DamagedSnapshotOutcome.Deleted
        }
        current(marker, originals)?.let { return DamagedSnapshotOutcome.AlreadyComplete(it) }
        if (stop()) return DamagedSnapshotOutcome.Stopped("not in the foreground")

        // Step 3, the checkpoint drain: once CKPT_MAX has passed since the marker's time, no
        // checkpoint that began before the marker can still be running (§4.2.1).
        // ⚠ B.18.8 (313): capped at CKPT_MAX from now, so a marker time in the future (a wall
        // clock moved back, or a sibling's clock ahead) can never hold "copying" for good.
        val drainedAtMs = minOf(marker.timeSeconds * 1_000 + 1_000 + limits.checkpointMaxMs, wallClockMs() + limits.checkpointMaxMs)
        while (true) {
            val left = drainedAtMs - wallClockMs()
            if (left <= 0) break
            if (stop()) return DamagedSnapshotOutcome.Stopped("stopped during the checkpoint drain")
            sleepMs(minOf(left, DRAIN_SLICE_MS))
        }

        // A `.partial` of an aborted attempt is Control's own temporary copy, never the originals.
        for (name in copyFiles.list(damagedRoot, directories = true)) {
            if (name.endsWith(PARTIAL_SUFFIX)) copyFiles.removeTree("$damagedRoot/$name")
        }

        // Step 4: a whole copy and the floor, or nothing written at all: the room is asked of the
        // container, before `replica-damaged/` is made (B.18.8 (316)).
        val needed = originals.copyBytes + FREE_FLOOR_BYTES
        val free = copyFiles.freeBytes(containerOf(damagedRoot))
        if (free < needed) {
            log("[replica-damaged] not enough room for a copy: $needed bytes needed, $free free")
            return DamagedSnapshotOutcome.NoRoom(needed, free)
        }
        files.ensureDirectory(damagedRoot)
        copyFiles.protect(damagedRoot, directory = true)

        val name = uniqueName()
        val partial = "$damagedRoot/$name$PARTIAL_SUFFIX"
        files.ensureDirectory(partial)
        copyFiles.protect(partial, directory = false)
        val done = try {
            when (val copied = copyUnderSnapshotHandle(partial, originals, stop)) {
                is Copied.Done -> copied
                is Copied.Ended -> {
                    // ⚠ F6k: a stopped copy leaves its `.partial` (never offered, deleted at the
                    // next attempt); any other ending frees the space now.
                    if (copied.outcome !is DamagedSnapshotOutcome.Stopped) copyFiles.removeTree(partial)
                    return copied.outcome
                }
            }
        } catch (e: ReplicaNoSpace) {
            copyFiles.removeTree(partial)
            return DamagedSnapshotOutcome.NoRoom(needed, copyFiles.freeBytes(containerOf(damagedRoot)))
        }
        if (stop()) return DamagedSnapshotOutcome.Stopped("stopped before the copy was made durable")
        return try {
            // ⚠ B.18.8 (311): COMPLETE records the originals as the copy saw them, not as they were
            // before the drains, or `current()` would refuse the very copy just made.
            finish(partial, name, marker, done.originals, done.method)
        } catch (e: ReplicaNoSpace) {
            copyFiles.removeTree(partial)
            DamagedSnapshotOutcome.NoRoom(needed, copyFiles.freeBytes(containerOf(damagedRoot)))
        } catch (e: ReplicaFileException) {
            // A sync, the COMPLETE or a rename failed: nothing is offered, and no disk is held.
            copyFiles.removeTree(partial)
            DamagedSnapshotOutcome.Failed("the copy could not be made durable: ${e.message}")
        }
    }

    // ── step 7: durable, then final ──

    private fun finish(partial: String, name: String, marker: MarkerSeen, originals: Originals, method: DamagedCopyMethod): DamagedSnapshotOutcome {
        // The marker's own text goes with the copy: the time, the app and SQLite's error.
        files.readAll(markerPath, MARKER_MAX_BYTES)?.let { text ->
            val descriptor = files.createAndWrite("$partial/${ReplicaCorruptMarker.NAME}", text)
            files.closeDescriptor(descriptor)
        }
        beforeSync?.invoke()
        // ⚠⚠ Every copied file is F_FULLFSYNCed before COMPLETE exists, so a copy is offered only
        // when it is all on the medium (§6's sidecar table).
        for (file in copyFiles.list(partial, directories = false)) {
            copyFiles.protect("$partial/$file", directory = false)
            syncFile("$partial/$file")
        }
        syncDirectory(files, partial)
        // COMPLETE by the sidecar rule, inside the `.partial`: a crash before the rename below
        // leaves a `.partial`, which is never offered and is deleted at the next attempt.
        val complete = "$partial/$COMPLETE_NAME"
        val temporary = "$complete.tmp"
        val descriptor = files.createAndWrite(temporary, completeText(marker, originals, method).encodeToByteArray())
        try {
            files.fullSync(descriptor)
        } finally {
            files.closeDescriptor(descriptor)
        }
        files.rename(temporary, complete)
        syncDirectory(files, partial)
        val final = "$damagedRoot/$name"
        files.rename(partial, final)
        syncDirectory(files, damagedRoot)
        val copy = DamagedCopy(final, copyFiles.list(final, directories = false).map { "$final/$it" }, method)
        log("[replica-damaged] the damaged store was copied ($method) into $final")
        // Step 8: only now, with the new copy final, are the older ones removed (B.18 (305)).
        for (older in copyFiles.list(damagedRoot, directories = true)) {
            if (older != name && !older.endsWith(PARTIAL_SUFFIX)) copyFiles.removeTree("$damagedRoot/$older")
        }
        return DamagedSnapshotOutcome.Completed(copy)
    }

    private fun syncFile(path: String) {
        val descriptor = files.openReadOnly(path)
        if (descriptor < 0) throw ReplicaFileException("$path went away before it was synced")
        try {
            files.fullSync(descriptor)
        } finally {
            files.closeDescriptor(descriptor)
        }
    }

    // ── steps 5 and 6: on the SNAPSHOT handle ──

    private sealed class Copied {
        class Done(val method: DamagedCopyMethod, val originals: Originals) : Copied()
        class Ended(val outcome: DamagedSnapshotOutcome) : Copied()
    }

    /** How the writer drain ended: drained, a header SQLite cannot read, or the attempt is over. */
    private sealed class Drain {
        data object Drained : Drain()
        data object Unreadable : Drain()
        class Ended(val outcome: DamagedSnapshotOutcome) : Drain()
    }

    private fun copyUnderSnapshotHandle(partial: String, originals: Originals, stop: () -> Boolean): Copied {
        val opened = ArrayList<Int>()
        // Under the registry's lock, like every close of the connection.
        fun closeOpened() {
            opened.forEach(files::closeDescriptor)
            opened.clear()
        }
        registry.lock.lock()
        try {
            val handle = try {
                registry.register(ReplicaHandleKind.SNAPSHOT, "snapshot")
            } catch (e: StoreUnavailable) {
                return Copied.Ended(DamagedSnapshotOutcome.Busy(e.message.orEmpty()))
            }
            // ⚠⚠ B.18.8 (312): the byte copy's descriptors close with the connection, whoever
            // closes it and whenever: a release the gate defers, a quiesce, a latched close's retry.
            handle.onClosed = ::closeOpened
            try {
                // ⚠⚠ B.18.8 (310): no SQLite open of a file it could change. With no `objects.db`
                // there is nothing to copy (the open would create one); with one shorter than a
                // page, the first shared lock would delete a non-empty `-wal` as a stale log. So
                // both are judged by `stat` first, and the short one byte-copied unheld.
                val database = copyFiles.statPath(databasePath)
                    ?: return Copied.Ended(DamagedSnapshotOutcome.Failed("objects.db is not there: nothing to copy, nothing created"))
                val drain = if (database.size < ReplicaLimits.PAGE_BYTES) {
                    log("[replica-damaged] objects.db is ${database.size} bytes: byte-copied, SQLite never opens it")
                    if (registry.handles().any { it !== handle }) return Copied.Ended(DamagedSnapshotOutcome.Busy("another handle of this process is open on the store"))
                    Drain.Unreadable
                } else {
                    writerDrain(handle, stop)
                }
                if (drain is Drain.Ended) return Copied.Ended(drain.outcome)
                // ⚠ A header SQLite cannot read stops (1) and (2) as surely as the drain: only the
                // byte copy can preserve it, without a hold, verified by fingerprint (B.18 (301)).
                val order = when {
                    drain == Drain.Unreadable -> listOf(DamagedCopyMethod.BYTE_COPY)
                    else -> methods ?: DamagedCopyMethod.entries
                }
                var lastFailure = "nothing was tried"
                for (method in order) {
                    if (stop()) return Copied.Ended(DamagedSnapshotOutcome.Stopped("stopped before $method"))
                    val result = when {
                        method in failing -> Attempt.Failed("injected by the test")
                        else -> when (method) {
                        DamagedCopyMethod.VACUUM_INTO ->
                            if (originals.databaseBytes > VACUUM_INTO_MAX_BYTES) {
                                Attempt.Failed("the file is ${originals.databaseBytes} bytes, over VACUUM INTO's bound")
                            } else {
                                vacuumInto(handle, partial, stop)
                            }
                        DamagedCopyMethod.BACKUP -> backup(handle, partial, stop)
                        DamagedCopyMethod.BYTE_COPY -> byteCopy(handle, partial, held = drain == Drain.Drained, stop, opened)
                        }
                    }
                    when (result) {
                        is Attempt.Done -> return Copied.Done(method, result.originals)
                        is Attempt.Ended -> return Copied.Ended(result.outcome)
                        is Attempt.Failed -> {
                            log("[replica-damaged] $method failed: ${result.reason}")
                            lastFailure = "$method: ${result.reason}"
                            for (file in copyFiles.list(partial, directories = false)) files.remove("$partial/$file")
                        }
                    }
                }
                return Copied.Ended(DamagedSnapshotOutcome.Failed("every path failed (last: $lastFailure)"))
            } finally {
                try {
                    registry.release(handle)
                } catch (_: StoreUnavailable) {
                    // Latched and logged by the registry.
                }
                // ⚠⚠ Only with the SNAPSHOT connection closed: closing a descriptor drops every
                // POSIX lock this process holds on that file, SQLite's on `objects.db` and `-shm`
                // included (B.18 (302)). A connection still open (a deferred or latched close)
                // closes them itself, through `onClosed` (312).
                if (!handle.hasConnection) closeOpened() else log("[replica-damaged] the snapshot's close is owed: its descriptors close with it")
            }
        } finally {
            registry.lock.unlock()
        }
    }

    private sealed class Attempt {
        /** Done, with the originals' sizes and times as the copy read them. */
        class Done(val originals: Originals) : Attempt()
        class Failed(val reason: String) : Attempt()
        class Ended(val outcome: DamagedSnapshotOutcome) : Attempt()
    }

    /** (i) `BEGIN IMMEDIATE`, then `ROLLBACK`, with no statement in between. */
    private fun writerDrain(handle: ReplicaHandle, stop: () -> Boolean): Drain {
        // ⚠ A second handle of this process on the file would have its locks dropped by the byte
        // copy's descriptors, and is not expected beside a corrupt store: refused (B.18 (302)).
        if (registry.handles().any { it !== handle }) {
            return Drain.Ended(DamagedSnapshotOutcome.Busy("another handle of this process is open on the store"))
        }
        val held = try {
            hold(handle, "snapshot:drain", stop) ?: return Drain.Ended(busyOrStopped(stop, "the writer drain"))
        } catch (e: ReplicaGateClosed) {
            return Drain.Ended(DamagedSnapshotOutcome.Stopped(e.message.orEmpty()))
        } catch (e: ReplicaAdmissionDeferred) {
            return Drain.Ended(DamagedSnapshotOutcome.Stopped(e.message.orEmpty()))
        } catch (e: SqliteException) {
            log("[replica-damaged] the writer drain could not begin (${e.message}): the header is not readable, so only a byte copy can preserve it")
            return Drain.Unreadable
        } catch (e: StoreUnavailable) {
            return Drain.Ended(DamagedSnapshotOutcome.Busy(e.message.orEmpty()))
        }
        try {
            held.exec("ROLLBACK")
        } finally {
            registry.gate.leave()
        }
        return Drain.Drained
    }

    private fun busyOrStopped(stop: () -> Boolean, what: String): DamagedSnapshotOutcome =
        if (stop()) DamagedSnapshotOutcome.Stopped("stopped while $what waited") else DamagedSnapshotOutcome.Busy("$what could not take the lock: another process holds the store in a transaction")

    /** `BEGIN IMMEDIATE` on the SNAPSHOT handle, waiting at most [LOCK_WAIT_MS]; the connection with the admission held, or null. */
    private fun hold(handle: ReplicaHandle, label: String, stop: () -> Boolean): SqliteConnection? {
        val started = TimeSource.Monotonic.markNow()
        return registry.acquire(handle, label) { !stop() && started.elapsedNow().inWholeMilliseconds < LOCK_WAIT_MS }
    }

    /** (1) `VACUUM INTO`, in autocommit, stoppable from inside by the progress handler. */
    private fun vacuumInto(handle: ReplicaHandle, partial: String, stop: () -> Boolean): Attempt {
        if (!registry.gate.enter("snapshot:vacuum")) return Attempt.Ended(DamagedSnapshotOutcome.Stopped("the gate is closed"))
        try {
            val connection = registry.connectionFor(handle)
            check(connection.inAutocommit()) { "[replica-damaged] VACUUM INTO inside a transaction" }
            val destination = "$partial/${ReplicaOpener.DATABASE_NAME}"
            connection.stoppableBy(VACUUM_ASK_EVERY, stop) {
                connection.exec("VACUUM INTO '${destination.replace("'", "''")}'")
            }
            // After both drains nothing changes them, so the originals now are the copy's.
            return Attempt.Done(originalsNow())
        } catch (e: SqliteException) {
            if (e.primaryCode == SQLITE_INTERRUPT && stop()) return Attempt.Ended(DamagedSnapshotOutcome.Stopped("VACUUM INTO was stopped"))
            return Attempt.Failed(e.message.orEmpty())
        } catch (e: StoreUnavailable) {
            return Attempt.Ended(DamagedSnapshotOutcome.Busy(e.message.orEmpty()))
        } finally {
            registry.gate.leave()
        }
    }

    /** (2) The backup API, [BACKUP_PAGES_PER_STEP] pages a step, a gate check between steps. */
    private fun backup(handle: ReplicaHandle, partial: String, stop: () -> Boolean): Attempt {
        if (!registry.gate.enter("snapshot:backup")) return Attempt.Ended(DamagedSnapshotOutcome.Stopped("the gate is closed"))
        try {
            val connection = registry.connectionFor(handle)
            val result = connection.backupTo("$partial/${ReplicaOpener.DATABASE_NAME}", BACKUP_PAGES_PER_STEP) { !stop() }
            if (!result.completed) return Attempt.Ended(DamagedSnapshotOutcome.Stopped("the backup was stopped after ${result.steps} steps"))
            return Attempt.Done(originalsNow())
        } catch (e: SqliteException) {
            return Attempt.Failed(e.message.orEmpty())
        } catch (e: StoreUnavailable) {
            return Attempt.Ended(DamagedSnapshotOutcome.Busy(e.message.orEmpty()))
        } finally {
            registry.gate.leave()
        }
    }

    /**
     * (3) The byte copy: under a writer hold ([held]), the originals' fingerprint, `objects.db` and
     * `-wal` in 1 MiB chunks with a gate check between chunks, the fingerprint again, `ROLLBACK`.
     * Equal fingerprints or the copy is discarded and retried, at most [BYTE_COPY_ATTEMPTS] times.
     * Its descriptors go into [opened], closed by the caller after the handle's release.
     */
    private fun byteCopy(handle: ReplicaHandle, partial: String, held: Boolean, stop: () -> Boolean, opened: MutableList<Int>): Attempt {
        val database = files.openReadOnly(databasePath).also { if (it >= 0) opened += it }
        if (database < 0) return Attempt.Failed("objects.db is not there")
        val wal = files.openReadOnly(walPath).also { if (it >= 0) opened += it }
        val shm = files.openReadOnly(shmPath).also { if (it >= 0) opened += it }
        for (attempt in 1..BYTE_COPY_ATTEMPTS) {
            val connection = if (held) {
                try {
                    hold(handle, "snapshot:hold", stop) ?: return Attempt.Ended(busyOrStopped(stop, "the writer hold"))
                } catch (e: ReplicaGateClosed) {
                    return Attempt.Ended(DamagedSnapshotOutcome.Stopped(e.message.orEmpty()))
                } catch (e: ReplicaAdmissionDeferred) {
                    return Attempt.Ended(DamagedSnapshotOutcome.Stopped(e.message.orEmpty()))
                } catch (e: SqliteException) {
                    return Attempt.Failed("the writer hold: ${e.message}")
                } catch (e: StoreUnavailable) {
                    return Attempt.Ended(DamagedSnapshotOutcome.Busy(e.message.orEmpty()))
                }
            } else {
                if (!registry.gate.enter("snapshot:bytes")) return Attempt.Ended(DamagedSnapshotOutcome.Stopped("the gate is closed"))
                null
            }
            var same: Boolean
            val before: Fingerprint
            try {
                before = fingerprint(database, wal, shm)
                copyBytes(database, before.databaseSize, "$partial/${ReplicaOpener.DATABASE_NAME}", attempt, stop)
                    ?.let { return Attempt.Ended(it) }
                if (wal >= 0) {
                    copyBytes(wal, before.walSize, "$partial/${ReplicaOpener.DATABASE_NAME}-wal", attempt, stop)
                        ?.let { return Attempt.Ended(it) }
                }
                same = fingerprint(database, wal, shm) == before
            } finally {
                try {
                    connection?.exec("ROLLBACK")
                } finally {
                    registry.gate.leave()
                }
            }
            // The originals as this consistent attempt read them, under its hold (311).
            if (same) {
                return Attempt.Done(
                    Originals(
                        before.databaseSize, "${before.databaseTime.first}.${before.databaseTime.second}",
                        before.walSize, if (before.walSize < 0) "-" else "${before.walTime.first}.${before.walTime.second}",
                    ),
                )
            }
            log("[replica-damaged] the originals changed during byte copy $attempt of $BYTE_COPY_ATTEMPTS: discarded")
            for (file in copyFiles.list(partial, directories = false)) files.remove("$partial/$file")
        }
        return Attempt.Ended(DamagedSnapshotOutcome.Inconsistent(BYTE_COPY_ATTEMPTS))
    }

    /** Copies [size] bytes of [source] into a new [destination]; a stop between chunks, or null when done. */
    private fun copyBytes(source: Int, size: Long, destination: String, attempt: Int, stop: () -> Boolean): DamagedSnapshotOutcome? {
        val out = copyFiles.createForWrite(destination)
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            var offset = 0L
            while (offset < size) {
                if (stop()) return DamagedSnapshotOutcome.Stopped("stopped in the byte copy at $offset of $size bytes")
                val want = minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
                val n = copyFiles.readAt(source, buffer, want, offset)
                if (n <= 0) throw ReplicaFileException("$destination: the original ended at $offset of $size bytes")
                copyFiles.writeAll(out, buffer, n)
                offset += n
                betweenChunks?.invoke(attempt, destination.substringAfterLast('/'), offset)
            }
            return null
        } finally {
            files.closeDescriptor(out)
        }
    }

    /** §6's fingerprint: sizes and times of the three files, `objects.db`'s first page, the `-wal`'s header, the whole `-shm`. */
    private data class Fingerprint(
        val databaseSize: Long,
        val databaseTime: Pair<Long, Long>,
        val walSize: Long,
        val walTime: Pair<Long, Long>,
        val shmSize: Long,
        val shmTime: Pair<Long, Long>,
        // ⚠ The bytes themselves, compared, in place of their SHA-256 (B.18 (301)): no weaker, and no digest to build.
        val firstPage: List<Byte>,
        val walHeader: List<Byte>,
        val shm: List<Byte>,
    )

    private fun fingerprint(database: Int, wal: Int, shm: Int): Fingerprint {
        val db = files.stat(database)
        val w = if (wal >= 0) files.stat(wal) else null
        val s = if (shm >= 0) files.stat(shm) else null
        if ((s?.size ?: 0) > SHM_MAX_BYTES) throw ReplicaFileException("the -shm is ${s?.size} bytes, over $SHM_MAX_BYTES")
        return Fingerprint(
            db.size, db.modifiedSeconds to db.modifiedNanos,
            w?.size ?: -1, w?.let { it.modifiedSeconds to it.modifiedNanos } ?: (0L to 0L),
            s?.size ?: -1, s?.let { it.modifiedSeconds to it.modifiedNanos } ?: (0L to 0L),
            read(database, ReplicaLimits.PAGE_BYTES), if (wal >= 0) read(wal, WAL_HEADER_BYTES) else emptyList(),
            if (shm >= 0) read(shm, (s?.size ?: 0).toInt()) else emptyList(),
        )
    }

    private fun read(descriptor: Int, count: Int): List<Byte> {
        if (count <= 0) return emptyList()
        val bytes = ByteArray(count)
        var done = 0
        while (done < count) {
            val chunk = ByteArray(count - done)
            val n = copyFiles.readAt(descriptor, chunk, count - done, done.toLong())
            if (n <= 0) break
            chunk.copyInto(bytes, done, 0, n)
            done += n
        }
        return bytes.copyOf(done).toList()
    }

    // ── the marker, the originals, the copies on disk ──

    internal class MarkerSeen(val identity: String, val timeSeconds: Long)

    /** The marker as it is now: its identity and its recorded time (the file's own time when none can be read). */
    private fun markerNow(): MarkerSeen? = markerOf(files, copyFiles, markerPath)

    internal class Originals(val databaseBytes: Long, val databaseTime: String, val walBytes: Long, val walTime: String) {
        val copyBytes: Long get() = databaseBytes + maxOf(walBytes, 0L)
    }

    private fun originalsNow(): Originals = originalsOf(copyFiles, databasePath, walPath)

    private fun current(marker: MarkerSeen, originals: Originals): DamagedCopy? =
        currentCopy(copyFiles, files, damagedRoot, marker, originals)

    private fun uniqueName(): String {
        val base = utcStamp(files.now().first)
        var name = base
        var n = 2
        while (copyFiles.statPath("$damagedRoot/$name") != null || copyFiles.statPath("$damagedRoot/$name$PARTIAL_SUFFIX") != null) {
            name = "$base-${n++}"
        }
        return name
    }

    private fun completeText(marker: MarkerSeen, originals: Originals, method: DamagedCopyMethod): String = buildString {
        append("format=1\n")
        append("method=").append(method.name).append('\n')
        append("taken=").append(files.now().first).append('\n')
        append("marker.identity=").append(marker.identity).append('\n')
        append("marker.time=").append(marker.timeSeconds).append('\n')
        append("objects.db.size=").append(originals.databaseBytes).append('\n')
        append("objects.db.mtime=").append(originals.databaseTime).append('\n')
        append("objects.db-wal.size=").append(originals.walBytes).append('\n')
        append("objects.db-wal.mtime=").append(originals.walTime).append('\n')
    }

    companion object {
        const val DAMAGED_DIRECTORY = "replica-damaged"
        const val PARTIAL_SUFFIX = ".partial"
        const val COMPLETE_NAME = "COMPLETE"

        /** §6: `VACUUM INTO` only for a file small enough to finish well inside the 2 s expiry drain, until Q0 sets the bound. */
        const val VACUUM_INTO_MAX_BYTES = 32L * 1024 * 1024

        /** The backup's pages per step: 4 MiB, a few ms of copy between gate checks. */
        const val BACKUP_PAGES_PER_STEP = 1_024

        /** How often `VACUUM INTO`'s progress handler asks whether to stop, in virtual-machine steps. */
        const val VACUUM_ASK_EVERY = 1_000

        /** §6: the byte copy's chunk. */
        const val CHUNK_BYTES = 1024 * 1024

        /** §6: a byte copy whose fingerprints differ is retried, at most this many times in one foreground. */
        const val BYTE_COPY_ATTEMPTS = 3

        /** How long the writer drain and the writer hold wait for a sibling's transaction before (4) applies. */
        const val LOCK_WAIT_MS = 2_000L

        /** B.18 (303): a copy never leaves the volume with less than this free. */
        const val FREE_FLOOR_BYTES = 256L * 1024 * 1024

        private const val DRAIN_SLICE_MS = 100L
        private const val WAL_HEADER_BYTES = 32
        private const val SHM_MAX_BYTES = 16L * 1024 * 1024
        private const val MARKER_MAX_BYTES = 64 * 1024
        private const val COMPLETE_MAX_BYTES = 16 * 1024

        /** B.18.8 (320): `replica-damaged/DELETED`, the marker whose copy the user deleted. */
        const val DELETED_NAME = "DELETED"

        private fun deletedText(marker: MarkerSeen) = "marker.identity=${marker.identity}\nmarker.time=${marker.timeSeconds}\n"

        /** Every copy and `.partial` under [damagedRoot] (the tombstone is a file and stays). Returns how many. */
        internal fun removeAllCopies(copyFiles: DamagedCopyFiles, damagedRoot: String): Int {
            val names = copyFiles.list(damagedRoot, directories = true)
            for (name in names) copyFiles.removeTree("$damagedRoot/$name")
            if (names.isNotEmpty()) println("[replica-damaged] removed ${names.size} cop${if (names.size == 1) "y" else "ies"} the user deleted")
            return names.size
        }

        /** True when the user deleted the copy of exactly this [marker] (a new marker copies again). */
        internal fun deletedFor(files: ReplicaFiles, damagedRoot: String, marker: MarkerSeen): Boolean = try {
            files.readAll("$damagedRoot/$DELETED_NAME", COMPLETE_MAX_BYTES)?.decodeToString() == deletedText(marker)
        } catch (_: ReplicaFileException) {
            false
        }

        /** Writes the tombstone by the sidecar rule, so a delete outlives a crash. */
        internal fun writeDeleted(files: ReplicaFiles, damagedRoot: String, marker: MarkerSeen) {
            files.ensureDirectory(damagedRoot)
            val path = "$damagedRoot/$DELETED_NAME"
            val temporary = "$path.tmp"
            files.remove(temporary)
            val descriptor = files.createAndWrite(temporary, deletedText(marker).encodeToByteArray())
            try {
                files.fullSync(descriptor)
            } finally {
                files.closeDescriptor(descriptor)
            }
            files.rename(temporary, path)
            syncDirectory(files, damagedRoot)
        }

        /** The App Group container `replica-damaged/` sits in: where the room is asked. */
        internal fun containerOf(damagedRoot: String): String = damagedRoot.trimEnd('/').substringBeforeLast('/')

        fun damagedRootOf(replicaDirectory: String): String =
            replicaDirectory.trimEnd('/').substringBeforeLast('/') + "/" + DAMAGED_DIRECTORY

        internal fun markerOf(files: ReplicaFiles, copyFiles: DamagedCopyFiles, markerPath: String): MarkerSeen? {
            val identity = files.identity(markerPath) ?: return null
            val recorded = files.readAll(markerPath, MARKER_MAX_BYTES)?.decodeToString()
                ?.lineSequence()?.firstOrNull { it.startsWith("time=") }?.removePrefix("time=")?.trim()?.toLongOrNull()
            val time = recorded ?: copyFiles.statPath(markerPath)?.modifiedSeconds ?: return null
            return MarkerSeen(identity, time)
        }

        internal fun originalsOf(copyFiles: DamagedCopyFiles, databasePath: String, walPath: String): Originals {
            val db = copyFiles.statPath(databasePath)
            val wal = copyFiles.statPath(walPath)
            return Originals(
                db?.size ?: -1, db?.let { "${it.modifiedSeconds}.${it.modifiedNanos}" } ?: "-",
                wal?.size ?: -1, wal?.let { "${it.modifiedSeconds}.${it.modifiedNanos}" } ?: "-",
            )
        }

        /**
         * ⚠⚠ **The only copy that may be offered**: the newest final directory (not `.partial`)
         * whose `COMPLETE` names this [marker] and these [originals]. Any other answer is null:
         * a copy of an earlier marker, one taken before the originals changed, a directory with
         * no `COMPLETE`, a `COMPLETE` that does not parse.
         */
        internal fun currentCopy(copyFiles: DamagedCopyFiles, files: ReplicaFiles, damagedRoot: String, marker: MarkerSeen, originals: Originals): DamagedCopy? {
            for (name in copyFiles.list(damagedRoot, directories = true).sortedDescending()) {
                if (name.endsWith(PARTIAL_SUFFIX)) continue
                val directory = "$damagedRoot/$name"
                val text = try {
                    files.readAll("$directory/$COMPLETE_NAME", COMPLETE_MAX_BYTES)?.decodeToString()
                } catch (_: ReplicaFileException) {
                    null
                } ?: continue
                val fields = text.lineSequence().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()
                val method = fields["method"]?.let { m -> DamagedCopyMethod.entries.firstOrNull { it.name == m } } ?: continue
                val matches = fields["marker.identity"] == marker.identity &&
                    fields["marker.time"] == marker.timeSeconds.toString() &&
                    fields["objects.db.size"] == originals.databaseBytes.toString() &&
                    fields["objects.db.mtime"] == originals.databaseTime &&
                    fields["objects.db-wal.size"] == originals.walBytes.toString() &&
                    fields["objects.db-wal.mtime"] == originals.walTime
                if (!matches) continue
                return DamagedCopy(directory, copyFiles.list(directory, directories = false).map { "$directory/$it" }, method)
            }
            return null
        }

        /** `20261030T101112Z`: sortable, and safe as a file name everywhere the export may land. */
        internal fun utcStamp(epochSeconds: Long): String {
            val days = epochSeconds.floorDiv(86_400L)
            val secondsOfDay = epochSeconds.mod(86_400L)
            // Civil from days (Howard Hinnant's algorithm).
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = doy - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = yoe + era * 400 + if (month <= 2) 1 else 0
            fun two(v: Long) = v.toString().padStart(2, '0')
            return "${year.toString().padStart(4, '0')}${two(month)}${two(day)}T${two(secondsOfDay / 3600)}${two(secondsOfDay % 3600 / 60)}${two(secondsOfDay % 60)}Z"
        }
    }
}

/**
 * ⚠⚠ **The damaged store's copies, for the platform** (Appendix B.18, Q47): Control's snapshot
 * ([take]) and, for every process, the copy that may be offered now ([current]). Behind the
 * switch only: nothing calls it in an unswitched process.
 */
@SharedReplicaPlatformApi
@HiddenFromObjC
object SharedReplicaDamagedCopies {
    private val taking = platform.Foundation.NSLock()

    /**
     * ⚠⚠ **Control's snapshot, and only Control's** (§6: "Control alone does this"): one attempt,
     * on the calling thread, never the main thread. Refused, like the open, with no installed
     * protection ([SharedReplicaUnprotected]). One at a time per process.
     */
    fun take(replicaDirectory: String, shouldStop: () -> Boolean = { false }, limits: ReplicaLimits = ReplicaLimits()): DamagedSnapshotOutcome {
        val path = "$replicaDirectory/${ReplicaOpener.DATABASE_NAME}"
        val registry = ReplicaHandles.forPath(path, limits)
        SharedReplicaStore.requireProtected(registry, path)
        // ⚠ Waits for a clean-up, a delete or another attempt in progress, so none interleave.
        // ⚠⚠ It BLOCKS, for as long as another attempt copies: every caller ([take], [delete])
        // must stay off the main thread (B.18.9).
        taking.lock()
        try {
            return ReplicaDamagedSnapshot(registry, replicaDirectory).take(shouldStop)
        } finally {
            taking.unlock()
        }
    }

    /**
     * ⚠ B.18.8 (316): **the leftovers, outside an attempt.** Every `.partial` (a stopped attempt's)
     * is removed; and when there is **no marker** (the store was since deleted by D14's forget, or
     * was never damaged here), every copy too: nothing could offer it again. Control runs it at its
     * launch and after its forget. A copy of the marker there now is kept (an attempt replaces it).
     * Skipped while an attempt runs in this process. File work only; never the main thread.
     * Returns how many directories were removed.
     */
    fun cleanUp(replicaDirectory: String): Int = cleanUpIn(replicaDirectory, PosixReplicaFiles, PosixDamagedCopyFiles)

    internal fun cleanUpIn(replicaDirectory: String, files: ReplicaFiles, copyFiles: DamagedCopyFiles): Int {
        if (!taking.tryLock()) return 0
        try {
            val root = ReplicaDamagedSnapshot.damagedRootOf(replicaDirectory)
            val marked = files.exists("$replicaDirectory/${ReplicaCorruptMarker.NAME}")
            // B.18.9 (322): a delete's leftovers, swept here too.
            if (marked) {
                val marker = ReplicaDamagedSnapshot.markerOf(files, copyFiles, "$replicaDirectory/${ReplicaCorruptMarker.NAME}")
                if (marker != null && ReplicaDamagedSnapshot.deletedFor(files, root, marker)) return ReplicaDamagedSnapshot.removeAllCopies(copyFiles, root)
            }
            var removed = 0
            for (name in copyFiles.list(root, directories = true)) {
                if (name.endsWith(ReplicaDamagedSnapshot.PARTIAL_SUFFIX) || !marked) {
                    copyFiles.removeTree("$root/$name")
                    removed++
                }
            }
            // The tombstone of a marker that is gone means nothing any more.
            if (!marked) files.remove("$root/${ReplicaDamagedSnapshot.DELETED_NAME}")
            if (removed > 0) println("[replica-damaged] removed $removed leftover cop${if (removed == 1) "y" else "ies"} (marker ${if (marked) "there" else "gone"})")
            return removed
        } catch (e: ReplicaFileException) {
            println("[replica-damaged] the leftovers could not be removed: ${e.message}")
            return 0
        } finally {
            taking.unlock()
        }
    }

    /**
     * ⭐ **The user's "Delete the copy"** (decided by the maintainer 2026-10-30, Q51; B.18.8 (320)):
     * every copy and every `.partial` removed, and a tombstone naming the marker there now, so that
     * Control does not copy the same damage again at its next foreground. A new marker (the store
     * damaged anew) copies again. Waits for an attempt in progress. False when nothing could be
     * done (no marker, or the tombstone could not be written; the copies are then kept).
     */
    fun delete(replicaDirectory: String): Boolean = deleteIn(replicaDirectory, PosixReplicaFiles, PosixDamagedCopyFiles)

    internal fun deleteIn(replicaDirectory: String, files: ReplicaFiles, copyFiles: DamagedCopyFiles): Boolean {
        // ⚠⚠ Blocks while an attempt copies: never on the main thread (B.18.9).
        taking.lock()
        try {
            val marker = ReplicaDamagedSnapshot.markerOf(files, copyFiles, "$replicaDirectory/${ReplicaCorruptMarker.NAME}") ?: return false
            val root = ReplicaDamagedSnapshot.damagedRootOf(replicaDirectory)
            // ⚠ B.18.9 (322): everything that can refuse comes BEFORE the tombstone, so a tombstone
            // is never written without the removals right after it. The tombstone first: a crash
            // between it and the removals leaves copies nothing offers again, and the next attempt
            // or clean-up removes them; never a re-copy of deleted data.
            files.ensureDirectory(root)
            copyFiles.protect(root, directory = true)
            ReplicaDamagedSnapshot.writeDeleted(files, root, marker)
            ReplicaDamagedSnapshot.removeAllCopies(copyFiles, root)
            println("[replica-damaged] the user deleted the copy of the damaged data")
            return true
        } catch (e: ReplicaFileException) {
            println("[replica-damaged] the copy could not be deleted: ${e.message}")
            return false
        } finally {
            taking.unlock()
        }
    }

    /**
     * ⭐ Q50 (decided 2026-10-30): **a complete copy is on disk**, whatever its marker and whether
     * or not it was ever shared (a share cannot be confirmed). What Control's forget warns about,
     * since the forget removes it (B.18.8 (316)). File looks only; false on a doubt.
     */
    fun anyComplete(replicaDirectory: String): Boolean = try {
        val root = ReplicaDamagedSnapshot.damagedRootOf(replicaDirectory)
        PosixDamagedCopyFiles.list(root, directories = true).any { name ->
            !name.endsWith(ReplicaDamagedSnapshot.PARTIAL_SUFFIX) && PosixReplicaFiles.exists("$root/$name/${ReplicaDamagedSnapshot.COMPLETE_NAME}")
        }
    } catch (e: ReplicaFileException) {
        false
    }

    /** True when the user deleted the copy of the marker there now (any process may ask; file looks only). */
    fun isDeleted(replicaDirectory: String): Boolean = try {
        ReplicaDamagedSnapshot.markerOf(PosixReplicaFiles, PosixDamagedCopyFiles, "$replicaDirectory/${ReplicaCorruptMarker.NAME}")
            ?.let { ReplicaDamagedSnapshot.deletedFor(PosixReplicaFiles, ReplicaDamagedSnapshot.damagedRootOf(replicaDirectory), it) } == true
    } catch (e: ReplicaFileException) {
        false
    }

    /**
     * The copy "Export damaged data" may offer now: the newest complete one of the marker there
     * now, taken from the originals as they are now; null for anything else (no marker, no
     * complete copy, a copy of an earlier marker, originals changed since). Read-only file looks,
     * no SQLite and no descriptor on the store's files, so any process may ask. A look that fails
     * is null: nothing is offered on a doubt.
     */
    fun current(replicaDirectory: String): DamagedCopy? = currentIn(replicaDirectory, PosixReplicaFiles, PosixDamagedCopyFiles)

    internal fun currentIn(replicaDirectory: String, files: ReplicaFiles, copyFiles: DamagedCopyFiles): DamagedCopy? = try {
        val databasePath = "$replicaDirectory/${ReplicaOpener.DATABASE_NAME}"
        ReplicaDamagedSnapshot.markerOf(files, copyFiles, "$replicaDirectory/${ReplicaCorruptMarker.NAME}")?.let { marker ->
            ReplicaDamagedSnapshot.currentCopy(
                copyFiles,
                files,
                ReplicaDamagedSnapshot.damagedRootOf(replicaDirectory),
                marker,
                ReplicaDamagedSnapshot.originalsOf(copyFiles, databasePath, "$databasePath-wal"),
            )
        }
    } catch (e: ReplicaFileException) {
        println("[replica-damaged] the copies could not be looked at: ${e.message}")
        null
    }
}
