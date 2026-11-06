@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.drive.data.FilesRepositoryPlatformDelegate
import app.tenshu.drive.data.FolderListing
import app.tenshu.drive.data.foldPlatformContentHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.replaceSourceFile
import org.tenshu.core.file.system.replaceSourceFileBackground
import org.tenshu.core.file.system.uploadSourceFile
import org.tenshu.core.file.system.uploadSourceFileBackground
import org.tenshu.file.client.fileService
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS [FilesRepositoryPlatformDelegate] — the ingest half of the route-1 client
 * (ios-drive-parity.md P3a).
 *
 * ⭐ **This no longer reads bytes.** Since Ph1b of `drive-upload-source-read.md` it hands the
 * *source reference* to `UploadSourceFile` / `ReplaceSourceFile` and the read happens inside the
 * operation, where `LocalTaskExecutor`'s catch-all converts a failure into a `TResult`. Reading
 * here meant the read ran while evaluating the op's arguments — before any `TResult` existed — so
 * an unreadable source threw past every handler and `abort()`ed the app. `IosPhotoUploadSource` is the
 * one reader now, and it runs proxy-side.
 *
 * `sourceModifiedAt` stays client-side deliberately: it follows the settled cross-platform
 * decision (tech-debt "`File.CreatedAt` from the source file's timestamp") and, for a PhotoKit
 * asset, the *creation* date is closer to the truth than the import-moment mtime a picker copy
 * carries. The proxy cannot see either.
 *
 * Folder enumeration stays unimplemented: iOS has no SAF-tree grant — bulk/folder ingest is
 * the P6 Synced-Folders reframe (PhotoKit + background `URLSession`), not a picker concern.
 */
class IosFilesRepositoryPlatformDelegate : FilesRepositoryPlatformDelegate {

    override fun getPlatformFileName(uriString: String): String =
        (IosFileUploadSource.pathOf(uriString) ?: uriString).substringAfterLast('/')

    override suspend fun listFolderEntries(uriString: String): FolderListing =
        throw NotImplementedError("iOS folder ingest is the P6 Synced-Folders reframe — no SAF tree to enumerate.")

    override suspend fun uploadPlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TResult<Data?> = client.fileService.defaultFileSystem.uploadSourceFile(
        parentPath = parentPath,
        fileName = fileName,
        uri = uriString,
        sourceModifiedAt = sourceModifiedAt(uriString),
    )

    override suspend fun replacePlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TResult<Data?> = client.fileService.defaultFileSystem.replaceSourceFile(
        parentPath = parentPath,
        fileName = fileName,
        uri = uriString,
    )

    override suspend fun uploadPlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TaskHandle<Data?> = client.fileService.defaultFileSystem.uploadSourceFileBackground(
        parentPath = parentPath,
        fileName = fileName,
        uri = uriString,
        sourceModifiedAt = sourceModifiedAt(uriString),
    )

    override suspend fun replacePlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String,
    ): TaskHandle<Data?> = client.fileService.defaultFileSystem.replaceSourceFileBackground(
        parentPath = parentPath,
        fileName = fileName,
        uri = uriString,
    )

    /**
     * ⚠ This is the one path that still reads bytes client-side, and it has to: the question it
     * answers — "do we already have these bytes?" — is asked *before* committing to an upload it
     * may avoid. It reads through [IosPhotoUploadSource], the same reader the op uses, so the two can
     * never disagree about what a source reference means.
     *
     * ⚠ The fold still cuts at the shared chunk boundary, so the value matches what the proxy
     * stamps. `null` means "could not read", never "no match" — a caller treating an unreadable
     * candidate as a duplicate would drop it silently.
     */
    override suspend fun contentHashOf(uriString: String): String? = try {
        val bytes = IosPhotoUploadSource.readBytes(uriString)
        var offset = 0
        foldPlatformContentHash { buffer ->
            if (offset >= bytes.size) {
                -1
            } else {
                val n = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + n)
                offset += n
                n
            }
        }
    } catch (_: Throwable) {
        // A lapsed PhotoKit identifier, a document package, or a file the user removed mid-drain.
        null
    }

    /**
     * The source's own timestamp (epoch millis) for the `File.CreatedAt` seed. For a PhotoKit
     * asset this is the photo's **creation date**; for a file, the filesystem mtime. Null falls
     * back to `now` proxy-side.
     */
    internal fun sourceModifiedAt(uriString: String): Long? {
        if (IosPhotoLibrary.localIdentifierOf(uriString) != null) {
            val asset = IosPhotoLibrary.assetFor(uriString) ?: return null
            return asset.creationDate?.let { (it.timeIntervalSince1970 * 1000.0).toLong() }?.takeIf { it > 0 }
        }
        val path = IosFileUploadSource.pathOf(uriString) ?: return null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
        val date = attributes[NSFileModificationDate] as? NSDate ?: return null
        return (date.timeIntervalSince1970 * 1000.0).toLong().takeIf { it > 0 }
    }
}
