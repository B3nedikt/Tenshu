package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.asCancellableTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Background counterpart of [uploadSourceFile], used by the folder-upload loop
 * (`docs/proposals/drive-folder-upload.md` Phase 3a). Reuses [UploadSourceFile.Params] and the
 * same per-platform implementation verbatim; carries [BackgroundOperation] so the in-flight
 * per-file source read + encrypt in the proxy is wire-cancellable (Phase L2) rather than merely
 * stopping the loop between files. The manual single-file path keeps
 * [uploadSourceFile]/[UploadSourceFile].
 */
suspend fun FileSystem.uploadSourceFileBackground(
    parentPath: String,
    fileName: String,
    uri: String,
    sourceModifiedAt: Long? = null,
): TaskHandle<Data?> =
    findOperation<UploadSourceFileBg>(UploadSourceFileBg.ID)
        .uploadFile(
            parentPath = parentPath,
            fileName = fileName,
            contentUri = uri,
            sourceModifiedAt = sourceModifiedAt,
        )
        .asCancellableTaskInteractor<Data?>()
        .execute()

interface UploadSourceFileBg : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun uploadFile(
        parentPath: String,
        fileName: String,
        contentUri: String,
        sourceModifiedAt: Long? = null,
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/sourceFileUpload/bg"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                composite(id = UploadSourceFile.Params.PARENT_PATH, type = FilePath.Schema)
                composite(id = UploadSourceFile.Params.FILE_NAME, type = FilePath.Schema)
                composite(id = UploadSourceFile.Params.URI, type = TString.Schema)
                int(id = UploadSourceFile.Params.SOURCE_MODIFIED_AT, isOptional = true)
            },
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
        )
    }
}
