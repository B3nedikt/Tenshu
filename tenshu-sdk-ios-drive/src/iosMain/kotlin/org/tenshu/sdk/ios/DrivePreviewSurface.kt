@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.drive.data.RepositoryResult
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * Preview support for Drive-on-iOS (ios-drive-parity.md P4) — the iOS counterpart of the
 * Compose `FilePreview` router (`drive-content-preview.md`). Three duties:
 *
 *  - **Routing**: [previewKindFor] classifies a tapped file by name through the SHARED
 *    `fileCategoryForName`/`isPdf` (one truth with the list-row glyphs and the other
 *    platforms' routers). PDF is singled out of `Document` because it is the one document
 *    type rendered in-app — on iOS natively via **PDFKit**, the seam
 *    `drive-content-preview.md` §4.1 reserved (the Compose `PdfDocument` actuals are
 *    deliberately android+desktop only).
 *  - **Bytes**: [loadBytes] serves the decrypted whole file over the installed repository
 *    (`getRemoteFile` — local plaintext when present, realm-routed fetch otherwise). Image +
 *    PDF render straight from memory; v1 inherits the whole-`ByteArray` ceiling exactly like
 *    D2a on the other platforms (the S7 streaming upgrade is the follow-up).
 *  - **Temp files**: video playback (AVPlayer needs a URL) and share/open-externally go
 *    through [writePreviewTempFile] — the D2a decrypt-to-temp-file shape, app-private tmp,
 *    deleted via [deletePreviewTempFile] when the screen goes away (threat-model A5:
 *    transient decrypted plaintext on app-private disk, same amendment as D2a/D3).
 */
object DrivePreviewSurface {

    /** `"image" | "pdf" | "video" | "other"` — which renderer a tapped file routes to. */
    fun previewKindFor(fileName: String): String = when {
        isPdf(fileName) -> "pdf"
        else -> when (fileCategoryForName(fileName)) {
            FileCategory.Image -> "image"
            FileCategory.Video -> "video"
            else -> "other"
        }
    }

    /**
     * S7 (ios-drive-parity.md): open a **seekable streaming session** for a video — range-decrypt
     * on demand, no whole-file plaintext temp. Returns an [IosContentStreamSession] the Swift
     * `AVAssetResourceLoaderDelegate` drives, or `null` when no stream is available (offline,
     * not-yet-synced, locked, or over the fake repository) — the caller then falls back to the D2a
     * [writePreviewTempFile] path. Reuses the shipped commonMain client op
     * (`FilesRepository.openStreamingContent`, S3); iOS adds only this presentation-edge binding.
     */
    suspend fun openVideoStream(path: String): IosContentStreamSession? =
        withContext(Dispatchers.Default) {
            val result = runCatching { DriveReadSurface.installedRepository().openStreamingContent(path) }
                .getOrNull()
            (result as? RepositoryResult.Success)?.value?.let { IosContentStreamSession(it) }
        }

    /** The file's decrypted bytes over the installed repository, or null on any failure. */
    suspend fun loadBytes(path: String): ByteArray? = withContext(Dispatchers.Default) {
        val result = runCatching { DriveReadSurface.installedRepository().getRemoteFile(path) }
            .getOrNull()
        (result as? RepositoryResult.Success)?.value
    }

    /**
     * P4e gap #2 — the small **list-row** thumbnail for the file at [path], as `NSData`
     * (bridged to Swift `Data`) so `UIImage(data:)` renders it directly. Unlike the
     * full-open [loadBytes]/[writePreviewTempFile] path (multi-MB → temp file, kept off
     * the SKIE byte bridge), a LIST thumbnail is a few KB, so it crosses as `NSData`
     * exactly like S7's ≤256 KiB ranges. Delegates to the shared per-row loader
     * (`FilesRepository.getPreview`, the same op Android/desktop rows use): on a byte-less
     * device the `GetPreview` resolver serves the eager-synced `File.Thumbnail`
     * (ios-drive-parity.md P4e gap #1 = its iOS generator); on the uploading device it
     * computes from local bytes. Null on any failure / no thumbnail (folder,
     * non-previewable, unsynced) — the caller then shows the category glyph.
     *
     * [maxPx] is the longest-edge request; pass 100 for a LIST-class row (the resolver's
     * `≤100 px` Entity branch serves the stored `File.Thumbnail` without a full-byte read).
     */
    suspend fun loadRowThumbnail(path: String, maxPx: Int): NSData? =
        withContext(Dispatchers.Default) {
            val bytes = runCatching {
                DriveReadSurface.installedRepository().getPreview(path, maxPx, maxPx)
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                memScoped {
                    NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.toULong())
                }
            }
        }

    /**
     * D2a: the file's decrypted bytes written to an app-private temp file (per-preview
     * subdirectory so the leaf name survives for the player/share sheet). Returns the
     * temp file's path, or null on any failure. Callers own deletion.
     */
    suspend fun writePreviewTempFile(path: String, fileName: String): String? =
        withContext(Dispatchers.Default) {
            val bytes = loadBytes(path) ?: return@withContext null
            val dir = NSTemporaryDirectory() + "preview-" + path.hashCode().toUInt().toString(16)
            NSFileManager.defaultManager.createDirectoryAtPath(
                dir, withIntermediateDirectories = true, attributes = null, error = null,
            )
            val tempPath = "$dir/$fileName"
            val written = memScoped {
                NSData.create(
                    bytes = allocArrayOf(bytes),
                    length = bytes.size.toULong(),
                ).writeToFile(tempPath, atomically = true)
            }
            tempPath.takeIf { written }
        }

    /** Remove a [writePreviewTempFile] product (and its per-preview directory). */
    fun deletePreviewTempFile(tempPath: String) {
        NSFileManager.defaultManager.removeItemAtPath(tempPath, error = null)
        NSFileManager.defaultManager.removeItemAtPath(
            tempPath.substringBeforeLast('/'),
            error = null,
        )
    }
}
