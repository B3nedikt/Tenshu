package org.tenshu.core.common.proxy

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.value
import platform.Foundation.NSError
import kotlinx.cinterop.usePinned
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.db.sharedReplicaDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForUpdatingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Durable iOS [FileDatabase] — the plaintext content cache on disk, replacing the
 * `InMemoryFileDatabase` that made every cached byte die with the process
 * (`ios-fit-timeline-maps-parity.md` P4 / B-2).
 *
 * ## What it unblocks, and why it was blocking
 *
 * Three surfaces silently forgot their work on iOS because this was in memory:
 * Timeline's **keep-offline**, Drive's **plaintext byte cache** (a file opened
 * offline had to be re-fetched after any app restart), and Maps' **downloaded
 * regions** (a multi-hundred-MB region evaporated on the next launch). ⚠ The
 * keep-offline case is the sharpest: the *button* worked, so the failure looked
 * like a sync bug rather than a missing store.
 *
 * ## Where the bytes live
 *
 * The same [sharedReplicaDirectory] the route-1 object-store replica uses — the
 * shared App-Group container (app-local Documents only on the simulator / test
 * host; a device build without the container throws) — under a `files/` subdirectory beside
 * `objects/`. ⚠ That means co-resident first-party apps can read the cache, which
 * is the trust extension route-1 already made deliberately (threat-model A5, C4e
 * amendment: the apps are realm co-owners); this slice extends it from metadata
 * rows to **content bytes** and the amendment says so.
 *
 * ## Bounded memory is the whole point
 *
 * ⚠⚠ [readBytesRange] / [writeBytesRange] are true positional IO over
 * `NSFileHandle`, not read-modify-write over the whole file. The default
 * implementations on the interface are *correct but unbounded*, and inheriting
 * them here would reintroduce the OOM the two-plane content path exists to avoid
 * — on the platform where the byte bridge is already the tightest constraint
 * (`ios-drive-parity.md` P4: multi-MB must never cross SKIE).
 *
 * ⚠ [readBytes] is deliberately left whole-file: it is the convenience path the
 * interface defines, every other impl behaves the same way, and the bounded
 * callers use the ranged pair. A lazy file-backed `TByteArray` (Android's
 * `FileByteArrayDto`) would be the next improvement if a caller needs it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileDatabase(
    private val root: String = sharedReplicaDirectory(CONTENT_SUBDIRECTORY),
) : FileDatabase {

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
        require(filePath.isNotBlank()) { "File path cannot be blank." }
        val target = absolutePathFor(filePath)
        ensureParentDirectory(target)
        // `atomically = true` writes a temp file and renames, so a crash mid-write
        // leaves the previous bytes rather than a truncated file. The reassembly
        // path already promotes through `moveFile`, but the direct writers
        // (thumbnails, small blobs) get the same guarantee for free.
        //
        // ⚠⚠⚠ **The Boolean is read, not discarded.** Every caller that keeps durable state — the
        // append-log stores especially — decides what it may now believe from whether this returned.
        // Dropping the result made a refused write indistinguishable from a successful one, so a
        // store would clear its memory, advance its offset or mark a wipe done while the file was
        // untouched; the next start then replays everything it thought it had removed. iOS was the
        // only implementation that did this: the JVM raises `IOException` and Android propagates.
        val written = memScoped {
            val failure = alloc<ObjCObjectVar<NSError?>>()
            // ⚠ `NSDataWritingAtomic` is what `atomically = true` meant — a temp file and a rename, so a
            // crash mid-write leaves the previous bytes rather than a truncated file. The
            // error-reporting overload takes it as an option rather than a flag, and passing 0
            // here would silently drop that guarantee while still compiling.
            if (bytes.toNSData().writeToFile(target, options = NSDataWritingAtomic, error = failure.ptr)) {
                null
            } else {
                failure.value?.localizedDescription ?: "no NSError reported"
            }
        }
        if (written != null) {
            error("writeRawByteArray($filePath, ${bytes.size} bytes) failed: $written")
        }
    }

    override fun writeByteArray(filePath: String, bytes: TByteArray) {
        writeRawByteArray(filePath, bytes.get())
    }

    override fun readBytes(filePath: String): TByteArray? {
        if (filePath.isBlank()) return null
        val data = NSData.dataWithContentsOfFile(absolutePathFor(filePath)) ?: return null
        return TByteArrayDto(data.toByteArray())
    }

    /** O(1) — reads the directory entry, never the file (interface note). */
    override fun fileSize(filePath: String): Long? {
        if (filePath.isBlank()) return null
        val attributes = NSFileManager.defaultManager
            .attributesOfItemAtPath(absolutePathFor(filePath), error = null)
            ?: return null
        return (attributes[NSFileSize] as? Number)?.toLong()
    }

    /**
     * True positional read — one `CHUNK`-sized window, never the whole file.
     * Returns an empty array past EOF and a short array for the final chunk,
     * matching every other implementation.
     */
    override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
        if (filePath.isBlank()) return null
        val path = absolutePathFor(filePath)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        // ⚠⚠ The pool is what makes this bounded. `readDataOfLength` returns an AUTORELEASED
        // `NSData`, and a worker thread with no run loop drains nothing until it ends — so a
        // caller reading a 555 MB file in 1 MiB windows held all 555 MB (measured on a phone,
        // `ios-on-device-inference.md` §9). The bytes are copied out before the pool pops.
        return autoreleasepool {
            val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return@autoreleasepool null
            try {
                handle.seekToFileOffset(offset.convert())
                handle.readDataOfLength(length.convert()).toByteArray()
            } finally {
                handle.closeFile()
            }
        }
    }

    /**
     * True positional write, extending the file when needed. ⚠ An *updating*
     * handle refuses to open a path that does not exist, so an absent file is
     * created empty first — the reassembly path always starts from nothing.
     */
    override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
        require(filePath.isNotBlank()) { "File path cannot be blank." }
        val path = absolutePathFor(filePath)
        ensureParentDirectory(path)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        }
        // ⚠⚠⚠ **An unavailable handle is a FAILURE, not a quiet no-op.** This used to `return`, so a
        // read-only volume, a protected file or an unreachable path looked exactly like a completed
        // write — and every append-log store advanced its offset and mutated its in-memory map over
        // bytes that were never persisted, losing an accepted write or resurrecting a removed row on
        // the next open. The durable-write-before-memory ordering those stores rely on is only worth
        // anything if the durable write says when it did not happen.
        val handle = NSFileHandle.fileHandleForUpdatingAtPath(path)
            ?: error("writeBytesRange($filePath @ $offset) failed: cannot open the path for updating")
        try {
            handle.seekToFileOffset(offset.convert())
            handle.writeData(bytes.toNSData())
        } finally {
            handle.closeFile()
        }
    }

    /**
     * O(1) rename, so the manifest applier's promote step stays `O(CHUNK)`.
     * ⚠ The destination is removed first: `moveItemAtPath` fails rather than
     * replacing, unlike `File.renameTo`.
     */
    override fun moveFile(fromPath: String, toPath: String) {
        val from = absolutePathFor(fromPath)
        val to = absolutePathFor(toPath)
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(from)) return
        ensureParentDirectory(to)
        if (fm.fileExistsAtPath(to)) fm.removeItemAtPath(to, error = null)
        // ⚠⚠ The failure is NOT swallowed, unlike every other `error = null` in this file.
        // `moveFile` is how the P2 manifest applier promotes a temp reassembly onto the real path
        // *after* the whole-file integrity gate passes, so a silent failure leaves the caller
        // believing a verified file landed while the old contents are still there. And on a file
        // move it leaves the bytes at the old path while the row names the new one — a device that
        // then answers "not me" to every content repair, because repair reads the row's path
        // (`content-plane-refcount.md` §6d D3).
        //
        // ⚠ The `error = null` calls above stay: an absent source is a documented no-op, and
        // failing to remove a destination that is about to be replaced is not a failure of the
        // move — if it matters, the move itself fails next and says so.
        val failureReason = memScoped {
            val failure = alloc<ObjCObjectVar<NSError?>>()
            if (fm.moveItemAtPath(from, toPath = to, error = failure.ptr)) {
                null
            } else {
                // ⚠ Carry the reason, not just the fact. The common cause is a destination whose
                // parent is occupied by a FILE — `ensureParentDirectory` could not create it —
                // and that is invisible from the two paths alone.
                failure.value?.localizedDescription ?: "no NSError reported"
            }
        }
        if (failureReason != null) {
            error("moveFile($fromPath -> $toPath) failed: $failureReason")
        }
    }

    override fun deleteFile(filePath: String) {
        if (filePath.isBlank()) return
        NSFileManager.defaultManager.removeItemAtPath(absolutePathFor(filePath), error = null)
    }

    /** Reads the directory entry — no file content is touched. */
    override fun lastModifiedMs(filePath: String): Long? {
        if (filePath.isBlank()) return null
        val attributes = NSFileManager.defaultManager
            .attributesOfItemAtPath(absolutePathFor(filePath), error = null)
            ?: return null
        val modified = attributes[NSFileModificationDate] as? NSDate ?: return null
        // ⚠ NSDate is seconds-since-2001 as a Double; the SPI contract is epoch millis.
        return (modified.timeIntervalSince1970 * 1000.0).toLong()
    }

    override fun availableSpaceBytes(): Long? {
        val attributes = NSFileManager.defaultManager
            .attributesOfFileSystemForPath(root, error = null)
            ?: return null
        return (attributes[NSFileSystemFreeSize] as? Number)?.toLong()?.takeIf { it > 0L }
    }

    /**
     * ⚠ Callers pass paths that may be absolute-looking (`/.health/streams/x`) —
     * they are keys in a virtual tree, not host paths, so a leading separator is
     * stripped rather than treated as the filesystem root. The JVM impls get this
     * for free from `File(dir, path)`; here it has to be explicit.
     */
    private fun absolutePathFor(filePath: String): String =
        "$root/${filePath.trimStart('/')}"

    private fun ensureParentDirectory(absolutePath: String) {
        val parent = absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isEmpty()) return
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = parent,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    companion object {
        /** Sibling of the object store's `objects/` in the same replica root. */
        const val CONTENT_SUBDIRECTORY: String = "files"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        memScoped { NSData.create(bytes = allocArrayOf(this@toNSData), length = size.convert()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}
