package org.tenshu.core.common.proxy.file

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.number.TInteger
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.SyncBlockEmitterHolder
import java.io.FileInputStream

internal class AndroidFileUploadImplementation(
    private val fileDatabase: FileDatabase,
    private val fileDescriptorCache: ParcelFileDescriptorCache,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val deviceId: String? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
    /** M4: the `MOTION`-class mint, threaded to the shared upload core — see [UploadFileImplementation]. */
    private val motionThumbnailRenderer: MotionThumbnailRenderer? = null,
) : AsyncOperationImplementation {

    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val parentPath: TString = params.read(UploadSourceFile.Params.PARENT_PATH)
        val fileName: TString = params.read(UploadSourceFile.Params.FILE_NAME)
        val uri: TString = params.read(UploadSourceFile.Params.URI)
        // Optional — present only when the client (Drive) could read the source
        // file's date via its content:// grant (see AndroidFilesRepositoryPlatformDelegate).
        val clientSourceModifiedAt: Long? =
            (params?.get(UploadSourceFile.Params.SOURCE_MODIFIED_AT) as? TInteger)?.get()

        val result = uploadFile(
            executionContext = executionContext,
            parentPath = parentPath.get(),
            fileName = fileName.get(),
            contentUri = uri.get(),
            clientSourceModifiedAt = clientSourceModifiedAt,
        )
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        @Suppress("UNCHECKED_CAST")
        return (result as TResult.Success<*>).value as T
    }

    // suspend: the shared core now chunks + content-plane-PUTs file
    // bytes (docs/adr/0017-file-content-two-plane-channel.md P1), which is network IO.
    private suspend fun uploadFile(
        executionContext: ExecutionContext,
        parentPath: String,
        fileName: String,
        contentUri: String,
        clientSourceModifiedAt: Long?,
    ): TResult<Data?> {

        // Scoped to the client this session belongs to: the cache is one process-wide map
        // shared by every Binder client, and `contentUri` is a string the caller chose, so an
        // unscoped lookup could pick up a descriptor a *different* client had planted under
        // the same uri. The sender's identity is attested (uid → package), so this binds the
        // bytes we upload to the client that actually sent them.
        val clientId = executionContext.clientId
            ?: error("Upload has no client identity — refusing to read a descriptor for $contentUri")
        val parcelFileDescriptor = fileDescriptorCache.getFrom(clientId, contentUri)
            ?: error("File descriptor not found for $contentUri")

        val fileBytes = try {
            FileInputStream(parcelFileDescriptor.fileDescriptor)
                .use(FileInputStream::readBytes)
        } finally {
            // The descriptor is consumed here and never read again — dropping it closes the
            // dup'd fd. Nothing used to remove these, so every upload leaked one fd into the
            // proxy for the life of the process.
            fileDescriptorCache.removeFrom(clientId, contentUri)
        }

        // Prefer the client-supplied date: Drive holds the content:// grant and can
        // read the authoritative capture time (e.g. MediaStore DATE_TAKEN), which
        // the Photo Picker's redacted descriptor does NOT expose to a proxy-side
        // `fstat`. Fall back to `fstat` (then `now`) only when the client couldn't
        // determine one — e.g. an older Drive build or an unqueryable provider.
        val sourceModifiedAt = clientSourceModifiedAt ?: sourceLastModified(parcelFileDescriptor)

        return UploadFileImplementation(
            fileDatabase,
            syncBlockEmitterHolder,
            deviceId,
            storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
        ).uploadFile(
            executionContext,
            parentPath,
            fileName,
            fileBytes,
            sourceModifiedAt = sourceModifiedAt,
        )
    }

    /**
     * The source file's last-modified time (epoch millis) read straight from the
     * descriptor the client pushed across the Binder boundary. Fallback for when
     * the client didn't supply a date: `File.CreatedAt` still reflects the picked
     * file's own date rather than the import moment where the descriptor exposes a
     * real `st_mtime`. This runs proxy-side (Control has no permission on Drive's
     * `content://` URI, so the ContentResolver route is unavailable), but `fstat`
     * on the dup'd fd needs no grant.
     *
     * ⚠ Note this is *not* reliable for the Android Photo Picker: it serves bytes
     * through a redacted/on-access copy whose `st_mtime` is ~the access moment, so
     * the client-side ContentResolver value (preferred above) is what actually
     * fixes picker imports.
     *
     * Returns `null` — falling the caller back to `now` — for anything that isn't
     * a plain seekable file with a sane timestamp: a pipe/socket-backed provider
     * (cloud docs, generated content) reports a meaningless `st_mtime`, so it's
     * gated on `S_ISREG` and a positive value. Best-effort: any failure is
     * swallowed, an import never fails over a missing timestamp.
     */
    private fun sourceLastModified(pfd: ParcelFileDescriptor): Long? = try {
        val stat = Os.fstat(pfd.fileDescriptor)
        if (OsConstants.S_ISREG(stat.st_mode) && stat.st_mtime > 0L) {
            stat.st_mtime * 1000L
        } else {
            null
        }
    } catch (e: Throwable) {
        null
    }
}

