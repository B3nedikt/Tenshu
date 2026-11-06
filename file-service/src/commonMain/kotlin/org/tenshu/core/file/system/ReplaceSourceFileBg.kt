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
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Background counterpart of [replaceSourceFile] — the "Overwrite" conflict choice inside the
 * folder-upload loop. Same atomic in-place semantics as [ReplaceSourceFile] (reuses its params
 * and the same per-platform implementation); carries [BackgroundOperation] so an in-flight
 * overwrite is wire-cancellable. See
 * `docs/proposals/drive-folder-upload.md` §3a / §9.
 */
suspend fun FileSystem.replaceSourceFileBackground(
    parentPath: String,
    fileName: String,
    uri: String
): TaskHandle<Data?> =
    findOperation<ReplaceSourceFileBg>(ReplaceSourceFileBg.ID)
        .replaceFile(
            parentPath = parentPath,
            fileName = fileName,
            uri
        )
        .asCancellableTaskInteractor<Data?>()
        .execute()

interface ReplaceSourceFileBg : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun replaceFile(
        parentPath: String,
        fileName: String,
        contentUri: String
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/sourceFileReplace/bg"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                composite(id = ReplaceSourceFile.Params.PARENT_PATH, type = FilePath.Schema)
                composite(id = ReplaceSourceFile.Params.FILE_NAME, type = FilePath.Schema)
                composite(id = ReplaceSourceFile.Params.URI, type = TString.Schema)
            },
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
        )
    }
}
