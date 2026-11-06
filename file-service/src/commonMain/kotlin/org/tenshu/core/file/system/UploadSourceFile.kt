package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Upload a file the client identifies **by reference** rather than by bytes.
 *
 * [uri] is a platform source reference the *implementation* knows how to resolve — a `content://`
 * URI whose descriptor the client planted out of band (Android), a `phasset://` or `file://`
 * source (iOS), a filesystem path (desktop). The schema is deliberately platform-neutral; only
 * the `OperationImplementation` behind it is platform-specific, exactly as with `GetPreview`.
 *
 * ⭐ Prefer this over [uploadFile] whenever the client is holding a *reference*. Reading the bytes
 * client-side to pass them to [uploadFile] puts the read outside the operation, and therefore
 * outside `LocalTaskExecutor`'s catch — a source that cannot be read then throws where nothing
 * converts it into a `TResult`. See `docs/proposals/drive-upload-source-read.md` §1.
 */
suspend fun FileSystem.uploadSourceFile(
    parentPath: String,
    fileName: String,
    uri: String,
    sourceModifiedAt: Long? = null,
): TResult<Data?> =
    findOperation<UploadSourceFile>(UploadSourceFile.ID)
        .uploadFile(
            parentPath = parentPath,
            fileName = fileName,
            contentUri = uri,
            sourceModifiedAt = sourceModifiedAt,
        )
        .asTaskInteractor<Data?>()
        .execute()

interface UploadSourceFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun uploadFile(
        parentPath: String,
        fileName: String,
        contentUri: String,
        sourceModifiedAt: Long? = null,
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/sourceFileUpload"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                composite(id = Params.PARENT_PATH, type = FilePath.Schema)
                composite(id = Params.FILE_NAME, type = FilePath.Schema)
                composite(id = Params.URI, type = TString.Schema)
                // Optional: the client reads the picked file's own date where it holds the
                // grant to do so (on Android, ContentResolver — the content:// grant lives
                // client-side) and passes it here so the proxy can seed File.CreatedAt from it.
                // Absent when the client couldn't determine one, in which case the
                // implementation falls back to its own stat of the source, then to `now`.
                int(id = Params.SOURCE_MODIFIED_AT, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PARENT_PATH = "${ID}/path"
        const val FILE_NAME = "${ID}/fileName"
        const val URI = "${ID}/uri"
        const val SOURCE_MODIFIED_AT = "${ID}/sourceModifiedAt"
    }
}
