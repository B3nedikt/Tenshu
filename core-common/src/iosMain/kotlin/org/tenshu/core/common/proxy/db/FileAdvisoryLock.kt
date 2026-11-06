@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.flock

/**
 * A cross-process advisory lock over a file, via BSD `flock(2)` (Control-on-iOS C4d). The primitive
 * the in-process-collapse data plane (route 1) needs for **single-writer discipline**: Control and
 * Drive, two processes sharing one device's replica, serialise their writes through one lock so they
 * never fork the HLC. Built + proven here on the simulator (`CrossProcessHlcCoordinationSpikeTest`)
 * to *resolve the route-1/route-2 fork* before committing to the App-Group plumbing (§C4); productionising
 * it over a shared App-Group container is the paid/on-device step (C4e).
 *
 * **Why `flock`, not `fcntl`/`lockf`:** `flock` locks are bound to the *open file description*, so two
 * independent `open()`s of the same path conflict — even within one process — which is exactly the
 * cross-writer contention we model. POSIX `fcntl` record locks are per-*process* and would NOT contend
 * within one process (a classic footgun); the spike's same-process test would be vacuous with them.
 *
 * Not re-entrant; one outstanding `lock()` per handle. The lock is *advisory* — it only excludes other
 * `flock` users of the same file, which is all that touches it. Call [close] when done.
 */
class FileAdvisoryLock private constructor(private val fd: Int) {

    /** Blocks until the exclusive lock is held. */
    fun lock() {
        flock(fd, LOCK_EX)
    }

    /** Tries to take the exclusive lock without blocking; true if acquired. */
    fun tryLock(): Boolean = flock(fd, LOCK_EX or LOCK_NB) == 0

    /** Releases the lock. */
    fun unlock() {
        flock(fd, LOCK_UN)
    }

    /** Closes the underlying descriptor (also drops any held lock). */
    fun close() {
        close(fd)
    }

    /** Runs [block] holding the exclusive lock, releasing it even on failure. */
    fun <T> withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    companion object {
        /** `0644`. Passed through C's variadic `open`, so as an `Int`. */
        private const val LOCK_FILE_MODE: Int = 0x1A4

        /**
         * Opens (creating the empty lock file if needed) a handle on the lock at [path]. The parent
         * directory must already exist. Each call opens its own file description, so two handles on
         * the same path contend — that's the point (it models two processes).
         */
        fun open(path: String): FileAdvisoryLock {
            // ⚠⚠ One `open(O_CREAT)`, never "create if missing, then open" (`ios-shared-store-sqlite.md`
            // B.20.3 (364), fixed in B.22): `createFileAtPath` replaces a file another opener made a
            // moment before, and the two then `flock` different inodes and exclude nothing. The
            // launch reset of B.22 can meet a missing lock file with two apps launching at once.
            val fd = platform.posix.open(path, O_RDWR or O_CREAT, LOCK_FILE_MODE)
            check(fd >= 0) { "FileAdvisoryLock: cannot open lock file at $path" }
            return FileAdvisoryLock(fd)
        }
    }
}
