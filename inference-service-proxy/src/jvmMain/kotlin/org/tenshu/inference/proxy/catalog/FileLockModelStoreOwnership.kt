package org.tenshu.inference.proxy.catalog

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Desktop's [ModelStoreOwnership]: an OS advisory lock on [lockFile], tried **once, in the
 * constructor**, and held until the process ends.
 *
 * Defence in depth since `ControlInstanceGuard` (`docs/tech-debt.md`, *"Desktop Control has no
 * single-instance guard"*, now resolved): a Control that slips past the guard opens the same data
 * directory. The first to get here
 * owns the model store; every later one is refused, reads what is installed, and neither writes
 * nor sweeps.
 *
 * ⚠⚠ Construct this **before the index is read from disk**. `DesktopSecureStorageDelegate` loads
 * its properties file once, in its constructor; a process that loaded it while another could still
 * write, and took the lock only after that one died, would own the store with a stale index.
 * ⚠ Three outcomes, not two: locking that is *unavailable* (the file cannot be created, the
 * filesystem has no locks) is refused too, with its own sentence — a store that cannot tell
 * whether it is alone must not behave as if it were.
 * ⚠ The file is empty and never deleted: deleting a lock file another process may hold is the
 * classic race, and the OS drops the lock with the process, so a stale file is inert.
 */
class FileLockModelStoreOwnership(private val lockFile: File) : ModelStoreOwnership {

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private val refusal: ModelStoreRefusal? = acquire()

    override fun refusal(): ModelStoreRefusal? = refusal

    private fun acquire(): ModelStoreRefusal? {
        val opened = try {
            lockFile.parentFile?.mkdirs()
            RandomAccessFile(lockFile, "rw").channel
        } catch (e: Exception) {
            return unavailable(e)
        }
        val taken: FileLock? = try {
            opened.tryLock()
        } catch (_: OverlappingFileLockException) {
            // A second holder in THIS JVM: the same hazard as another process.
            null
        } catch (e: Exception) {
            runCatching { opened.close() }
            return unavailable(e)
        }
        if (taken == null) {
            runCatching { opened.close() }
            return ModelStoreRefusal.OwnedElsewhere(
                "Another Control process owns the model store ($lockFile is locked). Models " +
                    "can be installed, updated and removed only from that one; quit the other " +
                    "Control and restart this one to change them here.",
            )
        }
        channel = opened
        lock = taken
        return null
    }

    private fun unavailable(cause: Exception): ModelStoreRefusal {
        val detail = "$lockFile: ${cause::class.simpleName}: ${cause.message}"
        return ModelStoreRefusal.LockUnavailable(
            message = "Could not lock the model store ($detail), so this Control cannot tell " +
                "whether another one is using it and will not change it.",
            detail = detail,
        )
    }

    /** Gives the lock up early. ⚠ For tests: production holds it for the life of the process. */
    internal fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
