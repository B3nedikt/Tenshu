package org.tenshu.file.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.schema.propertyRef

/**
 * A photograph's preview, for an index pass that cannot read the original (2026-09-27).
 *
 * ⚠⚠ The bytes are a thumbnail rung, and a rung is stored **upright** ([ExifUpright]) — so no
 * orientation comes with them. Applying `File.Orientation` on top turns the face a second time.
 */
class IndexPreview(val bytes: ByteArray)

/**
 * The best preview of [filePath] this device can give the index pass **without the original**:
 * a stored `xlarge` (1024 px) rung, then `xxlarge` (2048 px), then — only when [mayPull] — an
 * `xlarge` pulled from the relay.
 *
 * ⚠⚠ **Why it exists.** On the maintainer's phone 1,290 of 1,345 photographs were stored only on
 * the relay; the pass read their originals, found nothing, and People covered 55 photos. A 1024 px
 * preview is far smaller to fetch than an original. ⚠ Whether it is enough for the face pipeline —
 * and whether 0.44 still holds on it — is a measurement owed on the verified library
 * (`photos-people-and-pets.md` §12.13), not an assumption this code may make.
 *
 * ⚠ Never `large` or `list`: at 256 px and below a face in a group photo is a handful of pixels.
 * ⚠ Reads the rung directly rather than through `ensureThumbnailClass`: that op can download a whole
 * original to mint a missing rung, and stamps every rung it serves as recently viewed — an index
 * pass going through it would make the entire library look warm to the thumbnail keeper.
 * ⚠ A pull is a network fetch: [mayPull] must be false on a metered connection. It is best-effort —
 * a relay that does not have the rung simply leaves it absent.
 */
suspend fun ExecutionContext.previewForIndex(
    fileDatabase: FileDatabase,
    filePath: String,
    mayPull: Boolean,
): IndexPreview? {
    val dataId = DataId.StringDataId.create(context = context, type = File.ID, keyValue = filePath)
    val row = database.getData(dataId) ?: return null
    val contentId = runCatching {
        (resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    fun stored(thumbnailClass: String): ByteArray? {
        val path = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)
        // ⚠ Stat first: a `readBytes` miss logs a warning on Android, and a miss is common here.
        if (fileDatabase.fileSize(path) == null) return null
        return fileDatabase.readBytes(path)?.let { (it as? org.tenshu.core.TByteArray)?.get() }
    }

    val local = stored(GetStoredThumbnail.CLASS_XLARGE) ?: stored(GetStoredThumbnail.CLASS_XXLARGE)
    if (local != null) return IndexPreview(local)
    if (!mayPull) return null

    val resolver = context.onDemandResolution() ?: return null
    try {
        resolver.resolve(
            executionContext = this,
            entityId = dataId,
            propertyId = File.ThumbnailXLarge.id,
            params = null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Best-effort: the rung lands on disk or it does not.
    }
    return stored(GetStoredThumbnail.CLASS_XLARGE)?.let { IndexPreview(it) }
}
