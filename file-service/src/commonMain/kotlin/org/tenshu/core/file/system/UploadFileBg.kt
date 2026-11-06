package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TaskHandle
import org.tenshu.core.asCancellableTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Background (cancellable Layer-2) counterpart of [uploadFile], used by the
 * client-orchestrated folder-upload loop (`docs/proposals/drive-folder-upload.md`).
 * Identical proxy behaviour — it reuses [UploadFile.Params] and the
 * shared `UploadFileImplementation` core verbatim — but carries
 * [BackgroundOperation] (no `IoOperation`) so it resolves to a
 * `CancellableTaskInteractor` and hands back a [TaskHandle]. The handle lets
 * the folder loop wire-cancel the *in-flight* per-file encryption in the proxy,
 * not merely stop the loop between files.
 *
 * The manual single-file upload path uses [uploadFile]/[UploadFile]
 * (an `IoOperation`) — this variant exists purely so the folder
 * loop's per-file write is cross-process cancellable.
 */
suspend fun FileSystem.uploadFileBackground(
    parentPath: String,
    fileName: String,
    data: ByteArray,
    sourceModifiedAt: Long? = null,
): TaskHandle<Data?> =
    findOperation<UploadFileBg>(UploadFileBg.ID)
        .uploadFile(
            parentPath = parentPath,
            fileName = fileName,
            data = data,
            sourceModifiedAt = sourceModifiedAt,
        )
        .asCancellableTaskInteractor<Data?>()
        .execute()

interface UploadFileBg : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun uploadFile(
        parentPath: String,
        fileName: String,
        data: ByteArray,
        sourceModifiedAt: Long? = null,
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/uploadFiles/bg"

        // Reuses UploadFile.Params so the shared UploadFileImplementation —
        // registered under this ID in the proxy factory — reads the same keys
        // unchanged. No new param vocabulary, no new impl.
        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = UploadFile.Params.PARENT_PATH, type = FilePath.Schema)
                string(id = UploadFile.Params.FILE_NAME, type = FilePath.Schema)
                byteArray(id = UploadFile.Params.DATA)
                int(id = UploadFile.Params.SOURCE_MODIFIED_AT, isOptional = true)
            },
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
        )
    }
}
