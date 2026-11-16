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
 * say *which* copy is running. Only the holder writes. The content is for people, with one exception on
 * Windows: the recorded pid decides whether a refusal is worth retrying (below). A stale or missing text
 * only costs retries there, never a wrong verdict — the lock itself is the verdict.
 *
 * ⚠ `java.io`/`FileChannel` only, like the store lock, so this stays usable from any JVM target.
 *
 * ⚠⚠ **Windows differs twice** (`docs/proposals/desktop-windows-port.md` §3.3):
 *  - Its locks are *mandatory*: a refused process cannot read the locked file, so the holder text
 *    is also written to an unlocked sibling, `<lock>.holder`, and read from there when the lock
 *    file itself is unreadable.
 *  - A killed holder's lock is released *asynchronously* after the process is gone (measured: still
 *    held right after `waitFor`). So when the recorded holder pid is **no longer alive**, the attempt
 *    is retried for up to [DEAD_HOLDER_GRACE_MILLIS]. A live holder is answered at once — a second
 *    launch hands off to the running Control without waiting.
 *    ⚠ Windows only: elsewhere locks are released with the process, and a holder in another sandbox or
 *    pid namespace can be invisible to `ProcessHandle`, which would make a live holder look dead and
 *    cost a pointless wait.
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
        /** How long a lock whose recorded holder is dead may take to be released by the OS. */
        const val DEAD_HOLDER_GRACE_MILLIS = 5_000L

        private const val RETRY_INTERVAL_MILLIS = 50L

        private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        /**
         * Tries to take the lock on [file]. Creates the file and its parent directory if needed.
         *
         * Never waits on a live holder. Waits up to [deadHolderGraceMillis] only while the lock is
         * held **and** the pid the holder recorded is gone — the OS has not released a dead
         * process's lock yet.
         */
        fun tryAcquire(
            file: File,
            deadHolderGraceMillis: Long = if (isWindows) DEAD_HOLDER_GRACE_MILLIS else 0L,
        ): Outcome {
            val deadline = System.currentTimeMillis() + deadHolderGraceMillis
            while (true) {
                val outcome = tryAcquireOnce(file)
                if (outcome !is Outcome.HeldElsewhere) return outcome
                if (holderIsAlive(outcome.holder) || System.currentTimeMillis() >= deadline) return outcome
                Thread.sleep(RETRY_INTERVAL_MILLIS)
            }
        }

        /** `true` unless the holder text names a pid that is verifiably not running. */
        private fun holderIsAlive(holder: String?): Boolean {
            val pid = holder?.let { Regex("""^pid (\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                ?: return true
            return ProcessHandle.of(pid).orElse(null)?.isAlive == true
        }

        private fun holderFile(file: File): File = File(file.parentFile, "${file.name}.holder")

        private fun tryAcquireOnce(file: File): Outcome {
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
                val holder = readHolder(channel) ?: readHolderFile(file)
                runCatching { channel.close() }
                return Outcome.HeldElsewhere(holder)
            }
            writeHolder(channel)
            runCatching { holderFile(file).writeText(describeThisProcess()) }
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

        private fun readHolderFile(file: File): String? =
            runCatching { holderFile(file).readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

        private fun readHolder(channel: FileChannel): String? = runCatching {
            val size = channel.size().coerceAtMost(4096).toInt()
            if (size == 0) return null
            val buffer = ByteBuffer.allocate(size)
            channel.read(buffer, 0)
            String(buffer.array(), 0, buffer.position()).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
