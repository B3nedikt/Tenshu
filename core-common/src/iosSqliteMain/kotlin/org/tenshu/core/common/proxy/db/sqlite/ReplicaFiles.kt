@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.CLOCK_REALTIME
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.ENOTSUP
import platform.posix.ENOTTY
import platform.posix.F_FULLFSYNC
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.access
import platform.posix.arc4random
import platform.posix.clock_gettime
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.stat
import platform.posix.strerror
import platform.posix.timespec
import platform.posix.write
import kotlinx.cinterop.toKString

/**
 * A file-system call the store needed failed. Mapped to a retry by the caller, never to corruption.
 *
 * ⚠⚠ **A probe that fails throws this; it never answers.** "Does the marker exist", "how large is
 * the `-wal`" and "is there a file here" each have exactly one failure that is an answer, the
 * file not being there (`ENOENT`, `ENOTDIR`). Every other failure (`EACCES`, `EMFILE`, `EIO`, a
 * descriptor gone bad) is this exception, so no caller can read it as "absent", "empty" or
 * "small" and go on.
 */
internal class ReplicaFileException(message: String) : RuntimeException(message)

/** What `fstat` said of an open descriptor. [links] is 0 once the file was unlinked behind it. */
internal data class ReplicaFileStat(val size: Long, val links: Int, val modifiedSeconds: Long, val modifiedNanos: Long)

/**
 * The few file-system calls the store makes itself, beside SQLite's own. An interface so a test
 * can record the order of a marker's durability sequence and fail one step of it; production
 * binds [PosixReplicaFiles] and nothing else.
 *
 * ⚠⚠ None of it opens, renames, truncates or unlinks `objects.db`, `-wal` or `-shm` for writing
 * (S1). The two acts on those names are [createExclusive], which can only make an empty
 * `objects.db` where none exists, and a read-only descriptor on the `-wal` for [stat].
 */
internal interface ReplicaFiles {
    /** `mkdir -p`. */
    fun ensureDirectory(path: String)

    /**
     * A lookup by path. False only when nothing is there; a lookup that fails for another reason
     * throws. ⚠ 16 to 24 µs on the phone (Q0 results §12.2): not for a per-access path.
     */
    fun exists(path: String): Boolean

    /** `open(O_CREAT | O_EXCL)` of an empty file, closed at once. False when it already exists. */
    fun createExclusive(path: String): Boolean

    /**
     * A read-only descriptor on a file or a directory. -1 only when there is nothing at [path];
     * an open that fails for another reason throws.
     */
    fun openReadOnly(path: String): Int

    fun closeDescriptor(descriptor: Int)

    /** `fstat`: 0.2 µs on the phone against 16 to 24 µs for a lookup by path. Throws if it fails. */
    fun stat(descriptor: Int): ReplicaFileStat

    /**
     * Creates [path] (`O_EXCL`: it fails rather than truncate a file that is there), writes
     * [bytes] and returns the descriptor, still open.
     */
    fun createAndWrite(path: String, bytes: ByteArray): Int

    /** Unlinks a temporary file of the store's own. Best effort: a failure is not reported. */
    fun remove(path: String)

    /**
     * `fcntl(F_FULLFSYNC)`: the flush to the medium the sidecar rule asks for (§6). Throws if it
     * fails.
     *
     * ⚠⚠ **`fsync` stands in for it only where `F_FULLFSYNC` was refused as not supported**
     * (`ENOTSUP`, `ENOTTY`), and never after any other failure: an `EIO` from the flush answered
     * by an `fsync` that succeeds would report durable a marker a power loss can still take away.
     *
     * Which `errno` means "not supported" is not in Apple's `fcntl(2)`: it says only that
     * `F_FULLFSYNC` "is currently implemented on HFS, MS-DOS (FAT), Universal Disk Format (UDF)
     * and APFS file systems", and its ERRORS section has no entry for the others. `ENOTSUP` is
     * measured (a directory on devfs, macOS 26.6: `F_FULLFSYNC` fails with it and `fsync`
     * succeeds; pinned by a test). `ENOTTY` is not measured: the kernel hands `F_FULLFSYNC` to
     * the file system as an ioctl, and it is what an ioctl handler answers for a command it does
     * not know.
     *
     * ⚠ **The fallback is not expected to run in production at all.** The store is in the group
     * container, on APFS, where `F_FULLFSYNC` on a directory descriptor is accepted on the phone
     * and on the Mac (Q0 results §8.9 (q)). It is there for a volume that does not implement the
     * call, and it is logged when it runs.
     */
    fun fullSync(descriptor: Int)

    fun rename(from: String, to: String)

    /**
     * The whole of a small file of the store's own (the confirmation walk's position): null only
     * when nothing is at [path]. A file larger than [maxBytes], or one that cannot be read,
     * throws: a caller never takes part of a file for the whole of it.
     */
    fun readAll(path: String, maxBytes: Int): ByteArray?

    /**
     * The file's identity, `"<st_dev>:<st_ino>"`, by `stat` on the path (no descriptor is opened,
     * so no POSIX lock of SQLite's on that file is dropped); null only when nothing is at [path].
     */
    fun identity(path: String): String?

    /** Seconds and nanoseconds of the wall clock, the clock file timestamps are taken from. */
    fun now(): Pair<Long, Long>
}

/**
 * The two system calls behind [ReplicaFiles.fullSync], each answering 0 or the `errno` it failed
 * with. A seam of its own, below [ReplicaFiles], so a test can make `F_FULLFSYNC` fail with a
 * chosen `errno` and see which failures reach `fsync` at all.
 */
internal interface ReplicaSyncCalls {
    /** `fcntl(F_FULLFSYNC)`. */
    fun fullFsync(descriptor: Int): Int

    /** `fsync`. */
    fun fsync(descriptor: Int): Int
}

internal object PosixSyncCalls : ReplicaSyncCalls {
    override fun fullFsync(descriptor: Int): Int = if (fcntl(descriptor, F_FULLFSYNC) == 0) 0 else errno
    override fun fsync(descriptor: Int): Int = if (platform.posix.fsync(descriptor) == 0) 0 else errno
}

/** The production binding of [PosixFiles]. */
internal object PosixReplicaFiles : PosixFiles(PosixSyncCalls)

internal open class PosixFiles(private val sync: ReplicaSyncCalls) : ReplicaFiles {

    private fun failure(what: String, code: Int = errno): ReplicaFileException =
        ReplicaFileException("$what: ${strerror(code)?.toKString() ?: "?"} (errno=$code)")

    override fun ensureDirectory(path: String) {
        if (access(path, F_OK) == 0) return
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != path) ensureDirectory(parent)
        if (mkdir(path, 0x1C0u /* 0700 */) != 0 && errno != EEXIST) throw failure("mkdir $path")
    }

    private fun nothingThere(): Boolean = errno == ENOENT || errno == ENOTDIR

    override fun exists(path: String): Boolean {
        if (access(path, F_OK) == 0) return true
        if (nothingThere()) return false
        throw failure("look up $path")
    }

    override fun createExclusive(path: String): Boolean {
        val descriptor = open(path, O_CREAT or O_EXCL or O_WRONLY, 0x180 /* 0600 */)
        if (descriptor < 0) {
            if (errno == EEXIST) return false
            throw failure("create $path")
        }
        close(descriptor)
        return true
    }

    override fun openReadOnly(path: String): Int {
        val descriptor = open(path, O_RDONLY)
        if (descriptor >= 0) return descriptor
        if (nothingThere()) return -1
        throw failure("open $path")
    }

    override fun closeDescriptor(descriptor: Int) {
        if (descriptor >= 0) close(descriptor)
    }

    override fun stat(descriptor: Int): ReplicaFileStat = memScoped {
        if (descriptor < 0) throw ReplicaFileException("fstat of no descriptor")
        val out = alloc<stat>()
        if (fstat(descriptor, out.ptr) != 0) throw failure("fstat")
        ReplicaFileStat(
            size = out.st_size,
            links = out.st_nlink.toInt(),
            modifiedSeconds = out.st_mtimespec.tv_sec,
            modifiedNanos = out.st_mtimespec.tv_nsec,
        )
    }

    override fun createAndWrite(path: String, bytes: ByteArray): Int {
        val descriptor = open(path, O_CREAT or O_EXCL or O_WRONLY, 0x180 /* 0600 */)
        if (descriptor < 0) throw failure("create $path")
        var written = 0
        while (written < bytes.size) {
            val n = bytes.usePinned { write(descriptor, it.addressOf(written), (bytes.size - written).convert()) }
            if (n <= 0) {
                close(descriptor)
                throw failure("write $path")
            }
            written += n.toInt()
        }
        return descriptor
    }

    override fun remove(path: String) {
        platform.posix.unlink(path)
    }

    override fun fullSync(descriptor: Int) {
        val refused = sync.fullFsync(descriptor)
        if (refused == 0) return
        if (refused != ENOTSUP && refused != ENOTTY) throw failure("F_FULLFSYNC", refused)
        println("[replica] F_FULLFSYNC is not supported on this volume (errno=$refused): fsync instead")
        val failed = sync.fsync(descriptor)
        if (failed != 0) throw failure("fsync, where F_FULLFSYNC is not supported", failed)
    }

    override fun rename(from: String, to: String) {
        if (platform.posix.rename(from, to) != 0) throw failure("rename $from")
    }

    override fun readAll(path: String, maxBytes: Int): ByteArray? {
        val descriptor = openReadOnly(path)
        if (descriptor < 0) return null
        try {
            val size = stat(descriptor).size
            if (size > maxBytes) throw ReplicaFileException("$path is $size bytes, over $maxBytes")
            val bytes = ByteArray(size.toInt())
            var done = 0
            while (done < bytes.size) {
                val n = bytes.usePinned { platform.posix.read(descriptor, it.addressOf(done), (bytes.size - done).convert()) }
                if (n < 0) throw failure("read $path")
                // Shorter than its size said: it changed under the read, which is not a whole file.
                if (n == 0L) throw ReplicaFileException("$path ended after $done of ${bytes.size} bytes")
                done += n.toInt()
            }
            return bytes
        } finally {
            closeDescriptor(descriptor)
        }
    }

    override fun identity(path: String): String? = memScoped {
        val out = alloc<stat>()
        if (platform.posix.stat(path, out.ptr) != 0) {
            if (nothingThere()) return null
            throw failure("stat $path")
        }
        "${out.st_dev}:${out.st_ino}"
    }

    override fun now(): Pair<Long, Long> = memScoped {
        val out = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME.convert(), out.ptr)
        out.tv_sec to out.tv_nsec
    }
}

/**
 * `replica/CORRUPT`: the durable marker that fences every process (§6, S20).
 *
 * **Writing it** follows the sidecar rule, in this order and no other: a temporary file in the
 * same directory, `F_FULLFSYNC` on it, `rename` into place, `F_FULLFSYNC` on the directory. Only
 * when [write] has returned is the store durably fenced.
 *
 * ⚠ **Several processes can detect the same damage at once**, so the temporary file is each
 * writer's own (`CORRUPT.tmp.<pid>.<random>`, created `O_EXCL`): no writer truncates or renames
 * another's. And a writer whose own steps fail has still succeeded if the marker is there when it
 * looks, because a sibling wrote it: it syncs the directory itself and reports the store fenced.
 * A temporary file left by a process killed mid-write is **ignored**: the marker is looked up by
 * its exact name only, nothing reads or lists `CORRUPT.tmp.*`, and nothing removes another
 * writer's file (it may be a live writer's).
 *
 * **Checking it** is on every outermost access, inside the write lock, and a lookup by path costs
 * 16 to 24 µs on the phone against 2.3 µs for the point read it guards (Q0 results §12.1 (2)).
 * So [state] keeps the directory open and `fstat`s that descriptor (0.2 µs): creating the
 * marker renames a file into the directory, which moves the directory's modification time, so an
 * unchanged time means an unchanged answer and the lookup by path runs only when the time moved.
 *
 * ⚠ Two guards on that shortcut. A time is trusted only once it is [RACY_WINDOW_SECONDS] in the
 * past: a marker created in the same timestamp tick as the change last seen would otherwise be
 * missed (the "racy" case of every mtime cache). And a descriptor that cannot be opened or read,
 * or a directory unlinked behind it, falls back to the lookup by path every time. ⚠ The lookup
 * by path is then the answer, and if it fails too [state] throws: a marker that cannot be
 * looked for is never reported as not there.
 */
/**
 * What a look for `replica/CORRUPT` found. Three answers, because "it is there" and "it is
 * durable" are different facts and a caller must not be able to take one for the other.
 *
 * | Answer | At open | In an access, in a checkpoint |
 * |---|---|---|
 * | [ABSENT] | goes on | goes on |
 * | [DURABLE] | `Corrupt` | the handle is fenced `CORRUPT` |
 * | [NOT_DURABLE] | `StoreUnavailable`: refused, a retry | the handle is fenced `COMMIT_FAILED`, in memory |
 *
 * [NOT_DURABLE] refuses just as [DURABLE] does (the file exists now, and refusing is the safe
 * direction); it differs only in what is claimed. It is exactly how a marker this process could
 * not **write** is treated, since it is the same shortfall: no durable marker by this process.
 */
internal enum class ReplicaMarkerState {
    /** No marker. */
    ABSENT,

    /** The marker is there and **this process** has synced its directory entry. Cached. */
    DURABLE,

    /** The marker is there and this process could not sync its directory entry. Never cached. */
    NOT_DURABLE,
}

internal class ReplicaCorruptMarker(private val files: ReplicaFiles, private val directory: String) {

    val path: String = "$directory/$NAME"

    private var directoryDescriptor = -1
    private var trusted: ReplicaFileStat? = null
    private var found = false

    /** Test seam: how many checks went to the lookup by path. */
    internal var pathLookups: Int = 0
        private set

    /**
     * Looks for the marker ([ReplicaMarkerState] says what each answer means to a caller).
     *
     * ⚠⚠ **The invariant, for every path in this class: `found` is set only after a successful
     * directory sync by this process.** A marker found in place may be a rename no sync has
     * followed (its writer has not got there, or died), and a crash can still take that name
     * away. So a marker is remembered, and reported [ReplicaMarkerState.DURABLE], only once this
     * process has synced the directory itself. Until then every check looks again and tries the
     * sync again, and the answer is [ReplicaMarkerState.NOT_DURABLE]. Once durable it is never
     * looked for again: nothing in the design removes it.
     */
    fun state(): ReplicaMarkerState {
        if (found) return ReplicaMarkerState.DURABLE
        // The time is read BEFORE the lookup, so a marker created after the lookup also moves it
        // past the value remembered here. The shortcut failing is not an answer: it only means
        // the lookup by path decides.
        val seen = try {
            if (directoryDescriptor < 0) directoryDescriptor = files.openReadOnly(directory)
            if (directoryDescriptor >= 0) files.stat(directoryDescriptor) else null
        } catch (_: ReplicaFileException) {
            null
        }
        if (seen != null && seen.links > 0 && seen == trusted) return ReplicaMarkerState.ABSENT
        pathLookups++
        if (files.exists(path)) {
            // Nothing about the directory is trusted while a marker is in it and not durable.
            trusted = null
            return try {
                syncDirectory(files, directory)
                found = true
                ReplicaMarkerState.DURABLE
            } catch (e: ReplicaFileException) {
                println("[replica] the corruption marker is there, but its directory could not be synced: ${e.message}")
                ReplicaMarkerState.NOT_DURABLE
            }
        }
        trusted = seen?.takeIf { it.links > 0 && isSettled(it) }
        return ReplicaMarkerState.ABSENT
    }

    private fun isSettled(seen: ReplicaFileStat): Boolean {
        val (nowSeconds, _) = files.now()
        return nowSeconds - seen.modifiedSeconds >= RACY_WINDOW_SECONDS
    }

    /**
     * Writes the marker by the sidecar rule. [detail] is what the surface and the maintainer read:
     * the time, the detecting app and the SQLite error. Idempotent: a marker already there is kept.
     *
     * ⚠⚠ Whatever way it returns, the directory was synced **by this call** (the invariant of
     * [state]: `found` only after this process's own sync), also when the marker
     * was already there. A marker found in place may be a sibling's rename that no directory sync
     * has followed yet (or this process's own, from an attempt whose sync failed); reporting the
     * store fenced on that would be reporting durable what a crash can still undo.
     */
    fun write(detail: String) {
        if (files.exists(path)) {
            syncDirectory(files, directory)
            found = true
            return
        }
        val temporary = "$path.tmp.${getpid()}.${arc4random().toString(16)}"
        try {
            val descriptor = files.createAndWrite(temporary, detail.encodeToByteArray())
            try {
                files.fullSync(descriptor)
            } finally {
                files.closeDescriptor(descriptor)
            }
            files.rename(temporary, path)
        } catch (e: ReplicaFileException) {
            files.remove(temporary)
            // Corruption WAS detected. If a sibling's marker is there, the verdict stands, whatever
            // failed here; only with no marker at all is this a retry.
            if (!files.exists(path)) throw e
        }
        // This writer's own directory sync, also when the marker is a sibling's: its content was
        // synced before its rename, and the name is made durable here before anyone is told.
        syncDirectory(files, directory)
        found = true
    }

    fun close() {
        files.closeDescriptor(directoryDescriptor)
        directoryDescriptor = -1
        trusted = null
    }

    companion object {
        const val NAME = "CORRUPT"

        /** How old a directory time must be before an unchanged one is believed. */
        const val RACY_WINDOW_SECONDS = 2L
    }
}

/** Makes a create or a rename in [directory] durable: open it, `F_FULLFSYNC`, close. */
internal fun syncDirectory(files: ReplicaFiles, directory: String) {
    val descriptor = files.openReadOnly(directory)
    if (descriptor < 0) throw ReplicaFileException("open directory $directory")
    try {
        files.fullSync(descriptor)
    } finally {
        files.closeDescriptor(descriptor)
    }
}

/**
 * The physical size of the `-wal`, by `fstat` on a read-only descriptor the store keeps open
 * while its connection is (0.2 µs a call on the phone; the budget asks before every write
 * statement, §4.2.1).
 *
 * ⚠ A descriptor of the store's own on a SQLite file is safe **only** for the `-wal`: closing
 * any descriptor on a file drops every POSIX lock the process holds on it, and SQLite locks
 * `objects.db` and `-shm`, never the `-wal` (its WAL locks are byte ranges of `-shm`). Nothing is
 * read or written through it.
 */
internal class ReplicaWalProbe(
    private val files: ReplicaFiles,
    private val walPath: String,
    /**
     * Is the owner's connection open now? ⚠ The descriptor is kept **only while it is**: whoever
     * closes the connection closes the probe ([close]), and a measurement asked for after that
     * (a checkpoint's verification once its handle was released, a test seam) opens, measures
     * and closes again. Kept regardless, the descriptor would stay open with nothing left to
     * close it: across a suspension after a quiesce, for good after a release.
     */
    private val keepOpen: () -> Boolean,
) {

    private var descriptor = -1

    /**
     * The file's size in bytes; 0 **only** when there is no `-wal` at the path. ⚠⚠ A size that
     * cannot be read throws [ReplicaFileException]: read as 0 it would tell the budget and the
     * ceiling that the log is empty, and tell a checkpoint that its reset happened.
     */
    fun sizeBytes(): Long {
        try {
            repeat(2) {
                if (descriptor < 0) descriptor = files.openReadOnly(walPath)
                if (descriptor < 0) return 0
                val seen = files.stat(descriptor)
                if (seen.links > 0) {
                    if (!keepOpen()) close()
                    return seen.size
                }
                // Unlinked behind the descriptor (an outside hand; the store never removes its
                // -wal): look again by path, once.
                close()
            }
        } catch (e: ReplicaFileException) {
            // The descriptor is not kept: the next call starts from the path again.
            close()
            throw e
        }
        throw ReplicaFileException("the -wal at $walPath was unlinked twice while it was being measured")
    }

    fun close() {
        files.closeDescriptor(descriptor)
        descriptor = -1
    }
}
