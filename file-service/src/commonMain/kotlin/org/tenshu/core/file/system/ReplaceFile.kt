package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Atomic in-place overwrite of an existing file (the "Overwrite" folder-upload
 * conflict choice). Same shape as [uploadFile], but the target path
 * pre-existing is not an error — the entity is updated in place (same
 * `dataId`, new bytes, bumped metadata). It is **not** delete-then-recreate:
 * a delete would tombstone the entity and the recreate would race the
 * tombstone retention window across paired devices. See
 * `docs/proposals/drive-folder-upload.md`.
 */
suspend fun FileSystem.replaceFile(
    parentPath: String,
    fileName: String,
    data: ByteArray
): TResult<Data?> {
    return findOperation<ReplaceFile>(ReplaceFile.ID)
        .replaceFile(
            parentPath = parentPath,
            fileName = fileName,
            data = data
        )
        .asTaskInteractor<Data?>()
        .execute()
}

interface ReplaceFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun replaceFile(parentPath: String, fileName: String, data: ByteArray): RequestObject

    companion object {
        const val ID = "tenshu/file/system/replaceFile"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PARENT_PATH, type = FilePath.Schema)
                string(id = Params.FILE_NAME, type = FilePath.Schema)
                byteArray(id = Params.DATA)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PARENT_PATH = "${ID}/path"
        const val FILE_NAME = "${ID}/fileName"
        const val DATA = "${ID}/data"
    }
}
