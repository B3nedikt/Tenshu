package app.tenshu.control.bridge

import org.tenshu.core.common.proxy.BridgeException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * The directory file contents cross between the Control daemon and the File Provider
 * extension in (docs/proposals/desktop-macos-integration.md §5b).
 *
 * Contents used to ride inside the bridge's JSON reply as base64: read whole, encoded (a third
 * larger), framed, sent, decoded — several copies of the file in memory on each side, and an
 * upload above ~190 MB refused outright by the 256 MiB frame limit. A video is exactly that
 * file. So only a **file name** crosses the bridge now, and the bytes sit in this directory
 * for the moment it takes the other side to pick them up.
 *
 * It lives in the App Group container because that is the one place both processes can reach:
 * the extension is sandboxed, and the daemon is not allowed into the extension's own container.
 *
 * ⚠⚠ **Plaintext on disk.** Decrypted bytes exist here between the write and the hand-off
 * (threat model T-C-16). The directory is `0700`, each file is removed as soon as it has been
 * consumed, and anything a crash left behind is swept when it is older than [MAX_AGE_MILLIS].
 *
 * ⚠ The names are chosen by the *caller*. They are checked against [NAME] and never used as
 * a path, so a name cannot walk out of the directory — and the *entry* behind a name is never
 * followed if it is a symlink ([openUpload], [createDownload]), so neither can a planted link.
 */
internal class FileProviderStaging(
    dir: File?,
    /** When an entry arrived, in epoch millis; a seam so a test can age an entry. */
    private val arrivedAt: (Path) -> Long? = ::inodeChangeMillis,
) {

    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastSweep = 0L

    /**
     * The staging directory and the identity (device + inode) it had when it was verified.
     * `null` — staging unavailable — when there is no directory or the path is a symlink.
     *
     * ⚠⚠ Checking once that the path is a real directory is not enough: swap the directory for
     * a symlink afterwards and every "file in staging" is a file somewhere else, whatever is
     * checked about the final entry. So every operation re-verifies, through [root], that the
     * path is still a real directory **and still the same one**.
     *
     * ⚠ That is a check before use, not a handle: the JDK offers no directory handles on macOS
     * (`SecureDirectoryStream` is unsupported there), so a process that swaps the directory in
     * the instant between this check and the open still wins. Closing that needs `openat`
     * through a native call. What this does stop is the swap made at leisure.
     */
    private val path: Path? = dir?.toPath()
    private val identity: Any? = path?.let {
        runCatching {
            it.toFile().mkdirs()
            val attributes = Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isDirectory) return@runCatching null
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
            attributes.fileKey()
        }.getOrNull()
    }

    init {
        sweep(System.currentTimeMillis())
    }

    /** The directory, if it is still the real directory that was verified at start. */
    private fun root(): Path? {
        val root = path ?: return null
        val expected = identity ?: return null
        val now = runCatching {
            Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        return root.takeIf { now.isDirectory && now.fileKey() == expected }
    }

    /** Checks that staging is available and [name] is a token; returns the entry's path. */
    private fun entry(name: String): Path {
        val root = root() ?: throw BridgeException("unavailable", "file staging is not available")
        if (!NAME.matches(name)) throw BridgeException("invalid_request", "'$name' is not a staging name")
        return root.resolve(name)
    }

    /**
     * Validates [name] before a transfer starts, and sweeps if a sweep is due.
     *
     * Swept on use, not only at start: the daemon stays up for days, and an upload the
     * extension staged and then crashed before announcing is known to nobody but this sweep.
     * ⚠ Never the entry being asked about — it may carry an old timestamp of its own.
     */
    fun validate(name: String) {
        entry(name)
        val now = System.currentTimeMillis()
        if (now - lastSweep > SWEEP_INTERVAL_MILLIS) sweep(now, except = name)
    }

    private fun attributes(entry: Path): BasicFileAttributes? = runCatching {
        Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    /**
     * Opens the staged upload [name] for reading.
     *
     * ⚠⚠ A regular file, **not followed**. The name is a token, but the directory entry behind
     * it is whatever a peer put there — and a symlink named like a token would have the daemon
     * read, and upload into the realm, any file it can open.
     */
    fun openUpload(name: String): InputStream {
        val entry = entry(name)
        if (attributes(entry)?.isRegularFile != true) {
            throw BridgeException("invalid_request", "nothing is staged as '$name'")
        }
        return Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)
    }

    /** The size of the staged upload [name], by the same not-followed rule as [openUpload]. */
    fun uploadSize(name: String): Long {
        val attributes = attributes(entry(name))
        if (attributes?.isRegularFile != true) {
            throw BridgeException("invalid_request", "nothing is staged as '$name'")
        }
        return attributes.size()
    }

    /**
     * Creates the staged download [name] for writing.
     *
     * ⚠⚠ Created exclusively. Whatever already sits under the name is removed first (the entry
     * itself — a symlink is unlinked, not followed) and the file is then made with
     * `CREATE_NEW`, which fails rather than write through anything that reappears there. A
     * plain "open for writing" would truncate the target of a planted link.
     */
    fun createDownload(name: String): OutputStream {
        val entry = entry(name)
        runCatching { Files.deleteIfExists(entry) }
        return Files.newOutputStream(
            entry, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        )
    }

    /** Asks the transfer writing [name] to stop; it notices at its next chunk. */
    fun cancel(name: String) {
        if (NAME.matches(name)) cancelled += name
    }

    fun isCancelled(name: String): Boolean = name in cancelled

    /** The transfer named [name] finished; its file stays for the other side to collect. */
    fun settled(name: String) {
        cancelled -= name
    }

    /** Forgets [name] and removes whatever it left on disk. */
    fun discard(name: String) {
        cancelled -= name
        if (!NAME.matches(name)) return
        // The entry itself: a symlink is unlinked, never what it points at.
        root()?.let { runCatching { Files.deleteIfExists(it.resolve(name)) } }
    }

    /**
     * Removes entries that arrived before `now - MAX_AGE_MILLIS`: what a crash left behind.
     *
     * ⚠⚠ Aged by the inode's **change time**, not its modification or creation time. A staged
     * upload is a clone of the user's file and inherits that file's dates — both of them: on
     * APFS a creation time cannot even stay later than the modification time — so a year-old
     * document would look a year stale the moment it landed and be swept before it was read.
     * The change time is set by the filesystem when the entry appears and cannot be backdated.
     */
    fun sweep(now: Long, except: String? = null) {
        lastSweep = now
        val root = root() ?: return
        val entries = runCatching { Files.newDirectoryStream(root).use { it.toList() } }.getOrNull() ?: return
        for (entry in entries) {
            if (entry.fileName.toString() == except) continue
            val arrived = arrivedAt(entry) ?: continue
            if (now - arrived > MAX_AGE_MILLIS) runCatching { Files.deleteIfExists(entry) }
        }
    }

    companion object {
        /** A UUID-shaped token: nothing that could be a path component with meaning. */
        val NAME = Regex("[A-Za-z0-9-]{8,64}")

        const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

        /** How often [file] re-runs the sweep while the daemon stays up. */
        const val SWEEP_INTERVAL_MILLIS = 60 * 60 * 1000L

        /**
         * One ranged read. Small on purpose: `readContentRange` returns its bytes inline, and
         * its own contract asks callers to stay at or under ~256 KiB.
         */
        const val CHUNK_BYTES = 256 * 1024
    }
}

/**
 * Copies [total] bytes into [sink] one ranged read at a time, so memory stays at one chunk
 * however large the file is. Returns the number of bytes written.
 *
 * [readRange] returns the bytes at an offset (at most the requested length), or `null` when the
 * read failed. An empty read before [total] is reached means the source is shorter than it
 * said — reported, not silently accepted as a complete file.
 */
internal suspend fun copyRanges(
    total: Long,
    sink: OutputStream,
    chunkBytes: Int = FileProviderStaging.CHUNK_BYTES,
    isCancelled: () -> Boolean = { false },
    readRange: suspend (offset: Long, length: Int) -> ByteArray?,
): Long {
    var offset = 0L
    while (offset < total) {
        if (isCancelled()) throw BridgeException("cancelled", "the transfer was cancelled")
        val want = minOf(chunkBytes.toLong(), total - offset).toInt()
        val bytes = readRange(offset, want)
            ?: throw BridgeException("unavailable", "content read failed at byte $offset of $total")
        if (bytes.isEmpty()) {
            throw BridgeException("unavailable", "content ended at byte $offset of $total")
        }
        sink.write(bytes)
        offset += bytes.size
    }
    return offset
}

/** The inode change time of [path] itself (a symlink is not followed), or `null` if unreadable. */
internal fun inodeChangeMillis(path: Path): Long? = runCatching {
    (Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS) as FileTime).toMillis()
}.getOrNull()
