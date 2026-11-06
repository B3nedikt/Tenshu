package app.tenshu.control.di

import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import kotlinx.coroutines.sync.Mutex
import org.tenshu.inference.proxy.catalog.BackgroundModelDownloader
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.inference.proxy.catalog.ModelTransferPort
import org.tenshu.inference.proxy.catalog.ModelTransferReport
import org.tenshu.inference.proxy.catalog.ModelTransferRequest
import platform.Foundation.NSError
import platform.Foundation.NSFileManager

/**
 * The background transfers of the owning app, as plain values: what the umbrella adapts its
 * Swift-facing bridge to. Here, and not in the umbrella, for [OwnedInferenceStores]' reason: the
 * umbrella does not see `:inference-service-proxy`.
 */
interface PhotosModelTransfers {
    /** One plain GET of [url], delivered whole through [PhotosModelDownloads.acceptDelivered]. */
    fun start(ref: String, url: String, allowsCellular: Boolean)

    /** The refs still running or waiting, handed to [completion], possibly on another thread. */
    fun liveRefs(completion: (List<String>) -> Unit)

    fun cancel(ref: String)
}

/**
 * The model download of the **one** app that owns inference on this phone — Photos
 * (`ios-on-device-inference.md` §14.11). The seam between the background `URLSession` in Swift
 * and [BackgroundModelDownloader].
 *
 * ⚠⚠ **Two halves, and only one needs a runtime.**
 * - [acceptDelivered] is a file move and nothing else. It runs wherever the session's delegate
 *   runs, including a process iOS relaunched in the background for the session's events, where
 *   no runtime is installed and none may be: installing one opens the shared replica, and a
 *   process suspended holding its lock is killed (`0xdead10cc`, §3.3).
 * - verifying, staging and committing are the installed graph's, at the next launch or return to
 *   the foreground ([resume]), from the journal.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object PhotosModelDownloads {

    /** ⚠ The process's, like the session in Swift it stands for: whatever graphs exist. */
    private val transfers = AtomicReference<PhotosModelTransfers?>(null)

    /** Installed once at app launch, on a phone the static gates admit. */
    fun installTransfers(installed: PhotosModelTransfers?) {
        transfers.value = installed
    }

    /** The installed transport as the downloader drives it, or `null` when there is none. */
    internal fun port(): ModelTransferPort? = transfers.value?.let(::Port)

    private class Port(private val transfers: PhotosModelTransfers) : ModelTransferPort {
        override fun start(request: ModelTransferRequest) =
            transfers.start(request.ref, request.url, request.allowsCellular)

        /** ⚠ A session that does not answer in time has not said *none*: `null`. */
        override suspend fun liveRefs(): Set<String>? = withTimeoutOrNull(LIVE_REFS_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                transfers.liveRefs { refs -> if (continuation.isActive) continuation.resume(refs.toSet()) }
            }
        }

        override fun cancel(ref: String) = transfers.cancel(ref)
    }

    /**
     * Takes the body of a finished transfer from the session's temporary file into the owner's
     * downloads directory, as `<ref>.delivered`. **Synchronous, and called from the session's
     * delegate**: the temporary file is gone when that callback returns.
     *
     * ⚠ Only a 2xx response is a file: an error page is dropped here, and the completion that
     * follows reports its status.
     * ⚠⚠ Nothing is put in a directory that is not excluded from backup: a delivered archive is
     * several hundred MB. The flag is set, and read back, before the move.
     * ⚠ Verifies nothing. The bytes are checked against the journal by the graph that settles them.
     *
     * @return whether the file is at its delivered path now.
     */
    fun acceptDelivered(
        ref: String,
        temporaryPath: String,
        statusCode: Int,
        root: String = OwnedInferenceStores.privateRoot(),
        excludeFromBackup: (String) -> String? = OwnedInferenceStores::excludeFromBackup,
    ): Boolean = autoreleasepool {
        if (!BackgroundModelDownloader.isRef(ref) || statusCode !in 200..299) return@autoreleasepool false
        val refused: String? = runCatching { excludeFromBackup(root) }.getOrElse { it.toString() }
        if (refused != null) {
            println("PhotosModelDownloads -> '$ref' dropped, $root cannot be excluded from backup: $refused")
            return@autoreleasepool false
        }
        val manager = NSFileManager.defaultManager
        val directory = "$root/${BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR}"
        manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val destination = "$root/${BackgroundModelDownloader.deliveredPath(BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR, ref)}"
        // An earlier delivery under the same ref is the same content-addressed artifact.
        manager.removeItemAtPath(destination, error = null)
        memScoped {
            val failure = alloc<ObjCObjectVar<NSError?>>()
            val moved: Boolean = manager.moveItemAtPath(temporaryPath, toPath = destination, error = failure.ptr)
            if (!moved) println("PhotosModelDownloads -> '$ref' not delivered: ${failure.value?.localizedDescription}")
            moved
        }
    }

    /**
     * The refs that have a delivered file under [root], or `null` when the directory is there
     * and could not be listed. ⚠ No directory is no delivered file, not an unknown.
     */
    fun deliveredRefs(root: String): Set<String>? = autoreleasepool {
        val manager = NSFileManager.defaultManager
        val directory = "$root/${BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR}"
        if (!manager.fileExistsAtPath(directory)) return@autoreleasepool emptySet()
        val names: List<*> = manager.contentsOfDirectoryAtPath(directory, error = null) ?: return@autoreleasepool null
        names.mapNotNull { it as? String }
            .filter { it.endsWith(BackgroundModelDownloader.DELIVERED_SUFFIX) }
            .mapTo(mutableSetOf()) { it.removeSuffix(BackgroundModelDownloader.DELIVERED_SUFFIX) }
    }

    /**
     * Whether an enabled build ever downloaded here: the downloads directory is there (the
     * journal is written into it before any transfer is started). Needs no runtime.
     * ⚠ What a build that offers **no** model asks at launch, before it attaches the session to
     * cancel what is left in it: a phone that never had the feature never creates the session.
     */
    fun leftBehind(root: String = OwnedInferenceStores.privateRoot()): Boolean = autoreleasepool {
        NSFileManager.defaultManager.fileExistsAtPath("$root/${BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR}")
    }

    /**
     * Deletes every delivered file under [root]: for a build that offers no model, where
     * nothing will ever verify or install them. Needs no runtime.
     *
     * ⚠⚠ **Only the delivered files.** The journal and the store's staging stay: a later build
     * that offers the model again reads the journal, finds its transfers gone and what was
     * staged still there, and fetches the rest. Until such a build they are reclaimed by
     * nothing (`tech-debt.md`).
     *
     * @return the refs whose file could not be deleted, or `null` when the directory could not be listed.
     */
    fun discardDelivered(root: String = OwnedInferenceStores.privateRoot()): Set<String>? = autoreleasepool {
        val refs: Set<String> = deliveredRefs(root) ?: return@autoreleasepool null
        val manager = NSFileManager.defaultManager
        refs.filterTo(mutableSetOf()) { ref ->
            val path = "$root/${BackgroundModelDownloader.deliveredPath(BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR, ref)}"
            !manager.removeItemAtPath(path, error = null)
        }
    }

    /**
     * The process's one lock for download steps: every graph's downloader runs its steps under
     * it, so a graph being torn down and its successor never interleave over the one journal
     * (`BackgroundModelDownloader`'s `steps`). Beside the transport, which is the process's too.
     */
    val steps: Mutex = Mutex()

    /**
     * Deletes [ref]'s delivered file: the transfer that delivered it lost its hold while the
     * file was being moved into place (`ModelTransferLedger.endDelivery`). Needs no runtime.
     */
    fun discardDelivered(ref: String, root: String = OwnedInferenceStores.privateRoot()) {
        if (!BackgroundModelDownloader.isRef(ref)) return
        autoreleasepool {
            NSFileManager.defaultManager.removeItemAtPath(
                "$root/${BackgroundModelDownloader.deliveredPath(BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR, ref)}",
                error = null,
            )
        }
    }

    /** This graph's downloader, or `null` in a graph that was not admitted. */
    fun downloader(koin: Koin): BackgroundModelDownloader? =
        runCatching { koin.get<ModelDeliveryComponents>().downloader as? BackgroundModelDownloader }.getOrNull()

    /**
     * A transfer ended. Returns at once: the work is the downloader's, on its own scope.
     *
     * @param report the ledger's: asked whether it still stands when it is acted on, and told
     *   when it has been. ⚠ Until then the ledger keeps the ended transfer as its ref's holder.
     * @return whether a downloader took the report, and so whether it will be settled.
     */
    fun transferFinished(
        koin: Koin,
        ref: String,
        statusCode: Int,
        errorMessage: String?,
        report: ModelTransferReport = ModelTransferReport.Unquestioned,
    ): Boolean {
        val downloader: BackgroundModelDownloader = downloader(koin) ?: return false
        downloader.transferFinished(ref, statusCode, errorMessage, report)
        return true
    }

    /** A transfer moved. For the progress bar. */
    fun transferProgressed(koin: Koin, ref: String, writtenBytes: Long) {
        downloader(koin)?.transferProgressed(ref, writtenBytes)
    }

    /**
     * At launch and on every return to the foreground: installs from the journal whatever was
     * delivered while nobody was listening, and starts again what the session no longer holds.
     * ⚠ Off the main thread: it hashes every delivered archive from disk.
     */
    suspend fun resume(koin: Koin) {
        downloader(koin)?.resume()
    }

    /**
     * ⚠ A choice, not a measurement: how long the session is given to say what it holds before
     * that is treated as unknown (nothing is then started again or cancelled). Drive's
     * `BackgroundUploadManager` waits as long for the same question.
     */
    private const val LIVE_REFS_TIMEOUT_MS = 5_000L
}
