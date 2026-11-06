package app.tenshu.timeline.data

import app.tenshu.takeout.PhotoSidecar
import app.tenshu.takeout.TakeoutArchive
import app.tenshu.takeout.TakeoutEntryKind
import app.tenshu.takeout.TakeoutRouter
import app.tenshu.takeout.readPhotoSidecar
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.sync.client.withBulkWriteTask

/** Where a Takeout import puts the photos it brings in. */
const val TAKEOUT_PHOTOS_FOLDER: String = "/Takeout Photos"

/** C4 lane id for the photo half of a Takeout import. */
private const val TAKEOUT_PHOTO_IMPORT_TASK_KIND = "takeout/photo-import"

/** What the photo half of an import produced. */
data class TakeoutPhotoResult(
    /** Photos uploaded (or already present — the upload is idempotent by path). */
    val imported: Int,
    /** Of those, how many carried a coordinate. ⚠ The number Track A's search actually depends on. */
    val located: Int,
    /** Photos seen in the archive. */
    val found: Int,
    /** Photos whose bytes could not be read (truncated, over the entry ceiling). */
    val unreadable: Int,
) {
    companion object {
        val None = TakeoutPhotoResult(imported = 0, located = 0, found = 0, unreadable = 0)
    }
}

/**
 * Brings a Takeout export's **photos** into Drive, with the coordinate its sidecar knows (B5,
 * `takeout-import-and-recall.md` TB-D4).
 *
 * ⭐⭐ **This is what makes Track A's "pictures taken in Munich" answer anything.** Photos backed up
 * from a Tenshu device carry EXIF; photos a user has in Google Photos are the corpus that is
 * actually years deep, and they arrive with their location in a JSON sidecar rather than in the
 * image. Without this, the place search works perfectly over a handful of recent photos.
 *
 * ⚠⚠ **Two passes over the archive, not one.** A sidecar can appear *after* the image it describes,
 * and the streaming reader cannot go back — so pass one reads only the sidecars (small, JSON) and
 * pass two uploads the images with what pass one learned. Buffering the images instead would mean
 * holding a photo export in memory, which is the one thing this whole design avoids.
 *
 * ⚠ Photos are uploaded **one at a time**, each dropped before the next is read. A 40 GB export is
 * a long import, not a large allocation.
 */
class TakeoutPhotoImporter(
    private val client: TenshuClient,
    private val folder: String = TAKEOUT_PHOTOS_FOLDER,
) {

    suspend fun import(archive: TakeoutArchive): TakeoutPhotoResult {
        // ── pass one: the sidecars ────────────────────────────────────────────
        val sidecars = mutableMapOf<String, PhotoSidecar>()
        archive.walk(
            wanted = { TakeoutRouter.kindOfName(it) == TakeoutEntryKind.PHOTO_SIDECAR },
        ) { name, bytes ->
            val parsed = bytes?.let(::readPhotoSidecar) ?: return@walk
            sidecars[photoNameOf(name)] = parsed
        }

        // ── pass two: the images ──────────────────────────────────────────────
        var found = 0
        var imported = 0
        var located = 0
        var unreadable = 0

        val fileSystem = client.getService<FileService>(FileService.ID).defaultFileSystem
        // ⚠ Created up front, and a failure here is not fatal: an existing folder answers with an
        // AlreadyExists that the upload below does not care about.
        fileSystem.createFolder(folder)

        // C4: one lane for the whole import, so a decade of photos rides the relay as batched
        // deltas rather than a `SyncBlock` each — the same treatment the timeline half gets.
        client.withBulkWriteTask(TAKEOUT_PHOTO_IMPORT_TASK_KIND) {
            archive.walk(
                wanted = { TakeoutRouter.kindOfName(it) == TakeoutEntryKind.PHOTO },
            ) { name, bytes ->
                found++
                if (bytes == null) {
                    unreadable++
                    return@walk
                }
                val leaf = name.substringAfterLast('/')
                val sidecar = sidecars[leaf]
                // ⚠⚠ Uploaded HERE, inside the walk, so this photo's bytes are collectable before
                // the next entry is read. Queueing them up to upload afterwards would hold the
                // whole export in memory.
                val result = fileSystem.uploadFile(
                    parentPath = folder,
                    fileName = leaf,
                    data = bytes,
                    sourceModifiedAt = sidecar?.takenAtMs,
                    captureLatE7 = sidecar?.latE7,
                    captureLngE7 = sidecar?.lngE7,
                )
                if (result is TResult.Success) {
                    imported++
                    if (sidecar?.hasCoordinate == true) located++
                }
            }
        }

        return TakeoutPhotoResult(
            imported = imported,
            located = located,
            found = found,
            unreadable = unreadable,
        )
    }

    /**
     * The image a sidecar describes — `IMG_1234.jpg.json` and
     * `IMG_1234.jpg.supplemental-metadata.json` both belong to `IMG_1234.jpg`.
     */
    private fun photoNameOf(sidecarPath: String): String {
        val leaf = sidecarPath.substringAfterLast('/').removeSuffix(".json")
        val supplemental = leaf.indexOf(".supplemental-meta")
        return if (supplemental > 0) leaf.substring(0, supplemental) else leaf
    }
}
