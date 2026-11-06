package org.tenshu.core.common.transport

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * A socket file as the server that bound it left it, so that server can later remove **its own**
 * file and nobody else's.
 *
 * ⚠⚠ The path is not the identity. Both desktop servers unlink whatever is at their path before
 * binding, so a Control that slips past the single-instance guard (one from before it, or one
 * started while locking was unavailable) takes the path over while the first still runs. The first one's clean quit must then leave the file alone: unlinking by
 * path would remove the live listener's only name, and no new client could reach a Control that
 * is running.
 */
internal class BoundSocketFile private constructor(private val path: Path, private val key: Any?) {

    /**
     * Removes the file if it is still the one captured. A file system that reports no key gives
     * nothing to compare, and the file stays: a leftover costs a client one refused connection,
     * a wrong removal costs every client the daemon.
     */
    fun removeIfStillOurs() {
        if (key == null || keyOf(path) != key) return
        runCatching { Files.deleteIfExists(path) }
    }

    companion object {
        /** Capture [path] right after the bind that created it. */
        fun of(path: Path): BoundSocketFile = BoundSocketFile(path, keyOf(path))

        private fun keyOf(path: Path): Any? = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull()
    }
}
