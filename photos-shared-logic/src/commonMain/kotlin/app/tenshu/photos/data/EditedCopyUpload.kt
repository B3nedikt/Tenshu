package app.tenshu.photos.data

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService

/**
 * How an edited copy's bytes reach the proxy (`photos-editing.md` §9.2) — one upload of a NEW
 * file; ⚠ never a replace.
 *
 * ⚠⚠ A seam because the inline upload cannot work on Android: an app → Control **request** is one
 * Binder transaction (only replies move bytes out of band), so a multi-megabyte JPEG in it fails
 * with `TransactionTooLargeException`, reported as "not connected" (`docs/tech-debt.md`). Android
 * binds `AndroidEditedCopyUpload` (`:photos-shared-ui`) — a file in the app's cache, handed over
 * as a descriptor. Desktop (a socket) and iOS (in process) carry the bytes inline.
 */
fun interface EditedCopyUpload {
    suspend fun upload(
        client: TenshuClient,
        folder: String,
        fileName: String,
        bytes: ByteArray,
        capturedAtMs: Long,
        latE7: Long?,
        lngE7: Long?,
    ): TResult<Data?>
}

/** The inline upload: `UploadFile` with the bytes, the capture time and the place as params. */
val InlineEditedCopyUpload = EditedCopyUpload { client, folder, fileName, bytes, capturedAtMs, latE7, lngE7 ->
    client.fileService.defaultFileSystem.uploadFile(
        parentPath = folder,
        fileName = fileName,
        data = bytes,
        sourceModifiedAt = capturedAtMs,
        captureLatE7 = latE7,
        captureLngE7 = lngE7,
    )
}
