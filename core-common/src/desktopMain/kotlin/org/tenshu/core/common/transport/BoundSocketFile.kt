package org.tenshu.core.common.transport

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * A socket file as the server that bound it left it, so that server can later remove **its own**
 * file and nobody else's.
 *
 * ⚠⚠ The path is not the identity. Both desktop servers unlink whatever is at their path before
 * binding, so a Control that slips past the single-instance guard (one from before it, or one
 * started while locking was unavailable) takes the path over while the first still runs. The first one's clean quit must then leave the file alone: unlinking by
 * path would remove the live listener's only name, and no new client could reach a Control that
 * is running.
 *
 * Two ways to tell "still ours", chosen by what the file system offers:
 *  - **A file key** (inode + device on macOS/Linux), captured right after the bind.
 *  - ⚠⚠ **An owner token on Windows**, where `fileKey()` is always `null`. The token goes into a
 *    sibling `<socket>.owner` file **before** the old socket is unlinked, so a successor has
 *    overwritten it before it touches the path. Creation time is *not* a substitute: NTFS "tunnels"
 *    a deleted file's creation time onto a file re-created under the same name shortly after, which
 *    is exactly the successor-rebinds case (`docs/proposals/desktop-windows-port.md` §3.3).
 *
 * ⚠⚠ **Check and delete are one step against a takeover.** Comparing and then deleting leaves a window: the old
 * server matches, is descheduled, the successor claims, unlinks and binds, and the old server then deletes the
 * live socket. So a takeover ([claim] … [Claim.bound]) and a removal ([removeIfStillOurs]) each hold an exclusive
 * lock on a sidecar `<socket>.lock` (never deleted, like any lock file). A removal that cannot get the lock in
 * time leaves the file — the safe direction; a claim that cannot proceeds unlocked rather than not start.
 *
 * Use: [claim] before unlinking the path, [Claim.bound] after the bind succeeds, [Claim.release] if it fails.
 */
internal class BoundSocketFile private constructor(
    private val path: Path,
    private val key: Any?,
    private val token: String?,
) {

    /**
     * Removes the file if it is still the one captured. Nothing to compare — no key and no token —
     * and the file stays: a leftover costs a client one refused connection, a wrong removal costs
     * every client the daemon.
     */
    fun removeIfStillOurs() {
        if (key == null && token == null) return
        val lock = takeoverLock(path) ?: return // contended: leave the file rather than race a successor
        try {
            when {
                key != null -> {
                    if (keyOf(path) != key) return
                    runCatching { Files.deleteIfExists(path) }
                }
                token != null -> {
                    if (readToken(ownerFile(path)) != token) return
                    runCatching { Files.deleteIfExists(path) }
                    runCatching { Files.deleteIfExists(ownerFile(path)) }
                }
            }
        } finally {
            lock.close()
        }
    }

    /**
     * A server's intent to take [path], recorded before it unlinks whatever is there. Holds the takeover lock until
     * [bound] or [release].
     */
    class Claim internal constructor(
        private val path: Path,
        private val token: String?,
        private val lock: AutoCloseable?,
    ) {
        /** Call once the bind succeeded. */
        fun bound(): BoundSocketFile {
            try {
                val key = keyOf(path)
                // A file system that does report keys needs no token; drop the sidecar again.
                if (key != null && token != null) runCatching { Files.deleteIfExists(ownerFile(path)) }
                return BoundSocketFile(path, key, if (key == null) token else null)
            } finally {
                release()
            }
        }

        /** Call if the bind failed. Idempotent. */
        fun release() {
            runCatching { lock?.close() }
        }
    }

    companion object {
        /**
         * Records the claim on [path]. Must run **before** the old socket is unlinked: a token
         * written after the bind would leave a window in which the previous owner still reads its
         * own token and removes the new socket.
         */
        fun claim(path: Path): Claim {
            runCatching { Files.createDirectories(path.parent) }
            val lock = takeoverLock(path)
            if (!needsToken(path)) return Claim(path, null, lock)
            val token = UUID.randomUUID().toString()
            val written = runCatching { Files.writeString(ownerFile(path), token) }.isSuccess
            // Unwritable sidecar ⇒ no token ⇒ the file is left on quit, the safe direction.
            return Claim(path, if (written) token else null, lock)
        }

        internal fun lockFile(path: Path): Path = path.resolveSibling("${path.fileName}.lock")

        /**
         * The exclusive takeover lock for [path], or null when it cannot be had within [timeoutMillis] (or locking is
         * unavailable). The OS releases it if the holder dies.
         */
        private fun takeoverLock(path: Path, timeoutMillis: Long = LOCK_TIMEOUT_MILLIS): AutoCloseable? {
            val channel = runCatching {
                FileChannel.open(lockFile(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }.getOrNull() ?: return null
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null // held by this same JVM (two servers on one path, e.g. in a test)
                } catch (_: Exception) {
                    runCatching { channel.close() }
                    return null
                }
                if (lock != null) {
                    return AutoCloseable {
                        runCatching { lock.release() }
                        runCatching { channel.close() }
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    runCatching { channel.close() }
                    return null
                }
                Thread.sleep(LOCK_RETRY_MILLIS)
            }
        }

        private const val LOCK_TIMEOUT_MILLIS = 2_000L
        private const val LOCK_RETRY_MILLIS = 20L

        /** Whether this file system gives no file key — probed on the directory, before the bind. */
        private fun needsToken(path: Path): Boolean = path.parent?.let { keyOf(it) } == null

        internal fun ownerFile(path: Path): Path = path.resolveSibling("${path.fileName}.owner")

        private fun readToken(file: Path): String? = runCatching { Files.readString(file).trim() }.getOrNull()

        private fun keyOf(path: Path): Any? = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull()
    }
}
