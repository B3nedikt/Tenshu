package app.tenshu.drive.data

import app.tenshu.macos.MacDatalessProbe
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.replaceFileBackground
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.file.system.uploadFileBackground
import org.tenshu.file.client.fileService

class DesktopFilesRepositoryPlatformDelegate : FilesRepositoryPlatformDelegate {

    override fun getPlatformFileName(uriString: String): String {
        // For desktop/tests, we assume the uriString is just the file name or a path
        return uriString.substringAfterLast("/")
    }

    /** ⚠ On desktop a "uri" is a path — the same assumption [getPlatformFileName] makes. */
    override suspend fun contentHashOf(uriString: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            java.io.File(uriString).takeIf { it.isFile }?.inputStream()?.use { stream ->
                foldPlatformContentHash { buffer -> stream.read(buffer) }
            }
        }.getOrNull()
    }

    override suspend fun listFolderEntries(uriString: String): FolderListing =
        withContext(Dispatchers.IO) {
            val root = java.io.File(uriString)
            if (!root.exists() || !root.isDirectory) {
                error("Folder not found: $uriString")
            }
            val entries = mutableListOf<FolderEntry>()
            // walkTopDown visits a directory before its entries — directories
            // therefore precede the files they contain, as the contract requires.
            for (file in root.walkTopDown()) {
                if (file == root) continue
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory) {
                    entries += FolderEntry(relativePath, isDirectory = true, fileName = file.name, leafUriString = "")
                } else {
                    entries += FolderEntry(
                        relativePath = relativePath,
                        isDirectory = false,
                        fileName = file.name,
                        leafUriString = file.absolutePath,
                        sizeBytes = file.length(),
                        // Metadata-only probe (no fault-in): flags a cloud placeholder
                        // (OneDrive / iCloud / Dropbox) so the read budget + "Downloading
                        // from the cloud…" label know it materialises on read (§11 P2).
                        isOnlineOnly = MacDatalessProbe.isDataless(file.absolutePath),
                    )
                }
            }
            FolderListing(rootFolderName = root.name, entries = entries)
        }

    override suspend fun uploadPlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?> {
        return client.fileService.defaultFileSystem.uploadFile(
            parentPath = parentPath,
            fileName = fileName,
            data = readFileBytes(uriString),
            sourceModifiedAt = sourceModifiedAt(uriString),
        )
    }

    override suspend fun replacePlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?> {
        return client.fileService.defaultFileSystem.replaceFile(
            parentPath = parentPath,
            fileName = fileName,
            data = readFileBytes(uriString)
        )
    }

    override suspend fun uploadPlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?> {
        return client.fileService.defaultFileSystem.uploadFileBackground(
            parentPath = parentPath,
            fileName = fileName,
            data = readFileBytes(uriString),
            sourceModifiedAt = sourceModifiedAt(uriString),
        )
    }

    override suspend fun replacePlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?> {
        return client.fileService.defaultFileSystem.replaceFileBackground(
            parentPath = parentPath,
            fileName = fileName,
            data = readFileBytes(uriString)
        )
    }

    // `viewModelScope` collects on `Dispatchers.Main.immediate`; combined with
    // `rememberCoroutineScope().launch { upload(...) }` at the call site, the
    // coroutine starts executing inline on the EDT until its first real
    // suspension. Without the dispatch hop here, `file.readBytes()` for a
    // 50 MB JPEG blocks the EDT — the bottom sheet stays visible until the
    // read completes because `onHideAddFileActions()` only runs after return.
    private suspend fun readFileBytes(uriString: String): ByteArray {
        val file = java.io.File(uriString)
        if (!file.exists()) {
            error("File not found: $uriString")
        }
        // A file that lives in a cloud File Provider (OneDrive / iCloud Drive /
        // Dropbox / Google Drive) can be a *dataless placeholder*: its bytes are
        // not on disk, and the first read blocks on a synchronous, network-backed
        // on-demand fault-in with NO OS timeout. A folder upload is a serial walk,
        // so one stuck placeholder freezes the whole job at "0 of N"
        // (drive-folder-upload.md §11). P1 mitigation: bound the read so a stuck
        // cloud file fails THIS file (→ FileOutcome.Failed, isolated in the loop)
        // instead of the whole upload. `length()` reads metadata only (no fault-in).
        return readWithBudget(file.name, cloudReadTimeoutMs(file.length())) { file.readBytes() }
    }

    // The source file's own last-modified time (epoch millis) so the proxy seeds
    // File.CreatedAt from the file's real date, not the import moment. `null`
    // (non-positive / unreadable) falls back to `now` proxy-side.
    private fun sourceModifiedAt(uriString: String): Long? =
        java.io.File(uriString).lastModified().takeIf { it > 0L }
}

// Shared daemon pool for the potentially-blocking cloud-file fault-in reads. Daemon
// so an ABANDONED (timed-out) read's still-parked thread never keeps the JVM alive;
// cached so idle threads are reclaimed. One pool for the whole process.
private val cloudReadExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "drive-cloud-read").apply { isDaemon = true }
}

/**
 * Run [reader] (a potentially-blocking file read) under a hard [timeoutMs] budget on
 * an ABANDONABLE daemon worker. Coroutine cancellation cannot interrupt a blocking
 * `java.io` read, so on timeout we drop the Future and let its thread unpark whenever
 * the provider finally responds (drive-folder-upload.md §11 P1). `runInterruptible`
 * keeps user-cancel responsive — it interrupts the `Future.get` wait rather than
 * parking for the whole budget. On timeout throws [CloudFileReadTimeout]; a failure
 * inside [reader] is unwrapped and rethrown.
 */
internal suspend fun readWithBudget(
    fileName: String,
    timeoutMs: Long,
    reader: () -> ByteArray,
): ByteArray {
    val future = cloudReadExecutor.submit(Callable(reader))
    return try {
        // Map to the caller-facing exceptions *inside* the block: kotlinx
        // stacktrace-recovery COPIES an exception as it crosses the
        // runInterruptible/withContext boundary, and copying a `Future.get`
        // `ExecutionException` re-nests it (cause = the original ExecutionException,
        // not the reader's failure). Translating here means only the already-
        // unwrapped type crosses the boundary — a `CloudFileReadTimeout` (no copy
        // constructor, so it survives intact) or the reader's real cause.
        runInterruptible(Dispatchers.IO) {
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Budget elapsed → this file failed, not the job.
                throw CloudFileReadTimeout(fileName, timeoutMs)
            } catch (e: ExecutionException) {
                // `Future.get` wraps a reader failure — surface the real cause.
                throw (e.cause ?: e)
            }
        }
    } catch (e: Throwable) {
        // Abandon the (possibly still-blocked) read on any exit — timeout, reader
        // error, or coroutine cancellation (user-cancel) — then propagate.
        future.cancel(true)
        throw e
    }
}

// Per-file read budget for a possibly-cloud-backed file. A 30 s floor even for a tiny
// placeholder; above that, allow a very slow 128 KiB/s download so a legitimately large
// online-only file isn't false-failed while it materialises.
internal fun cloudReadTimeoutMs(sizeBytes: Long): Long {
    val floorMs = 30_000L
    val minBytesPerSec = 128L * 1024
    val scaledMs = if (sizeBytes <= 0) floorMs else sizeBytes * 1000L / minBytesPerSec
    return maxOf(floorMs, scaledMs)
}

/**
 * A cloud-backed file (OneDrive / iCloud Drive / Dropbox) whose on-demand
 * materialisation didn't finish within the per-file read budget — see
 * `drive-folder-upload.md` §11. Surfaced as an ordinary per-file upload failure
 * (`FileOutcome.Failed`) so the rest of the folder still uploads.
 */
class CloudFileReadTimeout(fileName: String, timeoutMs: Long) : Exception(
    "Timed out after ${timeoutMs}ms reading '$fileName' — an online-only cloud file that didn't download in time.",
)
