package org.tenshu.core.common.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * An exclusive, non-blocking OS file lock that a process holds for its whole life, so a second
 * copy of the same daemon can tell it is the second one and step aside.
 *
 * ⚠⚠ This is the guard, not a diagnostic. `SqliteObjectStore`'s `objects.db.lock` uses the same
 * mechanism but only *reports* contention and opens anyway. On 2026-11-04 it did report it: two
 * Controls ran against one store (a development build and a sandboxed TestFlight build), and the
 * second wrote `store_lock_contended` and kept going. That log line is also the proof that
 * `FileChannel.tryLock` excludes across the App Sandbox boundary: the sandboxed copy saw the
 * unsandboxed copy's lock in the shared App Group container.
 *
 * The OS drops the lock when the process dies, however it dies, so a leftover file never blocks
 * the next start. The file is never deleted: deleting a lock file another process may hold is the
 * classic race. The holder writes its pid and launcher path into the file, so a refused copy can
 * say *which* copy is running. Only the holder writes; the content is for people, never for logic.
 *
 * ⚠ `java.io`/`FileChannel` only, like the store lock, so this stays usable from any JVM target.
 */
class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) {
    /** Releases the lock. Idempotent; the OS also releases it at process exit. */
    fun release() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    sealed interface Outcome {
        /** This process now holds the lock. Keep [lock] strongly reachable for the process's life. */
        data class Acquired(val lock: SingleInstanceLock) : Outcome

        /**
         * Another process (or another holder in this one) has it. [holder] is what the holder
         * wrote into the file, e.g. `"pid 21494 /Applications/TenshuControl.app/…"`; null if
         * unreadable or not written yet.
         */
        data class HeldElsewhere(val holder: String?) : Outcome

        /**
         * Locking itself is unavailable here (the file cannot be opened, or the filesystem has no
         * usable locks). Not the same as held: the caller decides whether to run unguarded.
         */
        data class Unavailable(val cause: Throwable) : Outcome
    }

    companion object {
        /**
         * Tries once to take the lock on [file], never waiting. Creates the file and its parent
         * directory if needed.
         */
        fun tryAcquire(file: File): Outcome {
            val channel = try {
                file.parentFile?.mkdirs()
                RandomAccessFile(file, "rw").channel
            } catch (t: Throwable) {
                return Outcome.Unavailable(t)
            }
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // A second holder in THIS process: the same answer as another process.
                null
            } catch (t: Throwable) {
                runCatching { channel.close() }
                return Outcome.Unavailable(t)
            }
            if (lock == null) {
                val holder = readHolder(channel)
                runCatching { channel.close() }
                return Outcome.HeldElsewhere(holder)
            }
            writeHolder(channel)
            return Outcome.Acquired(SingleInstanceLock(channel, lock))
        }

        private fun describeThisProcess(): String {
            val handle = ProcessHandle.current()
            val command = handle.info().command().orElse("?")
            return "pid ${handle.pid()} $command\n"
        }

        private fun writeHolder(channel: FileChannel) {
            // Best effort: the lock is what matters, the text only helps a person reading the file.
            runCatching {
                channel.truncate(0)
                channel.write(ByteBuffer.wrap(describeThisProcess().toByteArray()), 0)
                channel.force(false)
            }
        }

        private fun readHolder(channel: FileChannel): String? = runCatching {
            val size = channel.size().coerceAtMost(4096).toInt()
            if (size == 0) return null
            val buffer = ByteBuffer.allocate(size)
            channel.read(buffer, 0)
            String(buffer.array(), 0, buffer.position()).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
