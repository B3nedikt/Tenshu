package app.tenshu.drive.data

import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.MediaItem
import org.tenshu.core.ingest.SecurityScopedFolderGrant
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.MediaType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Desktop [MediaSourceProvider] (`docs/proposals/drive-synced-folders.md` §4.1).
 * Desktop has no OS gallery, so a "bucket" is a real directory: the configured
 * [roots] (default `~/Pictures`) and their immediate sub-folders that contain
 * media. This is the Syncthing-style explicit model — §3.7's "image buckets
 * default-on" is an Android/iOS gallery affordance, not a desktop one.
 *
 * Change detection is a `java.nio.file.WatchService`; new-item detection is an
 * mtime watermark (the per-bucket cursor). Local candidate bucket ids are absolute paths;
 * selected MAS folders are opaque Control-owned grants and are listed for display only —
 * Control performs their runtime scan.
 *
 * [roots] is injectable so the desktop integration test points it at a temp dir.
 */
class DesktopMediaSourceProvider(
    private val roots: List<String> = defaultRoots(),
    private val preferences: BackupPreferences? = null,
) : MediaSourceProvider {

    override suspend fun listBuckets(includeVideo: Boolean): List<MediaBucket> {
        val dirs = candidateDirs()
        val localBuckets = dirs.mapNotNull { dir ->
            val media = mediaFilesIn(dir, includeVideo)
            if (media.isEmpty()) return@mapNotNull null
            MediaBucket(
                id = dir.absolutePath,
                displayName = dir.name.ifEmpty { dir.absolutePath },
                itemCount = media.size,
                sampleThumbnailUri = media.firstOrNull()?.absolutePath,
            )
        }
        // A sandboxed macOS grant is intentionally opaque to Drive. The grant's
        // display name is carried in the value, so the settings screen can render it without
        // resolving the bookmark or touching the host folder from this process. Grants are
        // persisted independently from selection state, so an unchecked folder remains available.
        val grantedBuckets = preferences?.readGrantedBuckets().orEmpty().mapNotNull { id ->
            SecurityScopedFolderGrant.decode(id)?.let { grant ->
                MediaBucket(
                    id = id,
                    displayName = grant.displayName,
                    itemCount = 0,
                    sampleThumbnailUri = null,
                )
            }
        }
        return (localBuckets + grantedBuckets).distinctBy { it.id }
    }

    override fun observeChanges(watched: Set<String>): Flow<Unit> = callbackFlow {
        val watchService: WatchService = FileSystems.getDefault().newWatchService()
        watched.forEach { path ->
            val dir = File(path)
            if (dir.isDirectory) {
                runCatching {
                    dir.toPath().register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                    )
                }
            }
        }
        val running = AtomicBoolean(true)
        val worker = thread(isDaemon = true, name = "synced-folders-watch") {
            while (running.get()) {
                val key = try {
                    watchService.take()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    break
                }
                if (key.pollEvents().isNotEmpty()) trySend(Unit)
                // A vanished watched dir returns false from reset(); keep the flow
                // alive watching whatever remains.
                key.reset()
            }
        }
        awaitClose {
            running.set(false)
            worker.interrupt()
            runCatching { watchService.close() }
        }
    }.conflate()

    override suspend fun listNewItems(
        bucketId: String,
        sinceCursor: String?,
        includeVideo: Boolean,
    ): List<MediaItem> {
        val watermark = sinceCursor?.toLongOrNull() ?: Long.MIN_VALUE
        return mediaFilesIn(File(bucketId), includeVideo)
            .filter { it.lastModified() > watermark }
            .sortedBy { it.lastModified() }
            .map { file ->
                MediaItem(
                    uri = file.absolutePath,
                    fileName = file.name,
                    addedAtMillis = file.lastModified(),
                    cursor = file.lastModified().toString(),
                    type = if (isVideo(file.name)) MediaType.VIDEO else MediaType.IMAGE,
                )
            }
    }

    /** The roots plus their immediate sub-directories (one level), de-duplicated. */
    private fun candidateDirs(): List<File> =
        roots.map(::File)
            .filter { it.isDirectory }
            .flatMap { root ->
                val children = root.listFiles()?.filter { it.isDirectory }.orEmpty()
                listOf(root) + children
            }
            .distinctBy { it.absolutePath }

    private fun mediaFilesIn(dir: File, includeVideo: Boolean): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && isMedia(it.name, includeVideo) }
            .orEmpty()

    private fun isMedia(name: String, includeVideo: Boolean): Boolean =
        isImage(name) || (includeVideo && isVideo(name))

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "avi", "m4v")

        fun defaultRoots(): List<String> {
            val home = System.getProperty("user.home") ?: return emptyList()
            return listOf("$home${File.separator}Pictures")
        }
    }
}
