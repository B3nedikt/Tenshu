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
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Background (cancellable Layer-2) counterpart of [replaceFile] — the
 * "Overwrite" conflict choice inside the folder-upload loop. Same atomic
 * in-place semantics as [ReplaceFile] (reuses [ReplaceFile.Params] and
 * `ReplaceFileImplementation`); carries [BackgroundOperation] so an in-flight
 * overwrite is wire-cancellable along with the rest of the loop. See
 * `docs/proposals/drive-folder-upload.md` §3a / §9.
 */
suspend fun FileSystem.replaceFileBackground(
    parentPath: String,
    fileName: String,
    data: ByteArray
): TaskHandle<Data?> =
    findOperation<ReplaceFileBg>(ReplaceFileBg.ID)
        .replaceFile(
            parentPath = parentPath,
            fileName = fileName,
            data = data
        )
        .asCancellableTaskInteractor<Data?>()
        .execute()

interface ReplaceFileBg : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun replaceFile(parentPath: String, fileName: String, data: ByteArray): RequestObject

    companion object {
        const val ID = "tenshu/file/system/replaceFile/bg"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = ReplaceFile.Params.PARENT_PATH, type = FilePath.Schema)
                string(id = ReplaceFile.Params.FILE_NAME, type = FilePath.Schema)
                byteArray(id = ReplaceFile.Params.DATA)
            },
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
        )
    }
}
