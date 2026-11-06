package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun FileSystem.deleteFile(path: String): TResult<Data?> =
    findOperation<DeleteFile>(DeleteFile.ID)
        .deleteFile(path)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun deleteFile(path: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/deleteFile"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PATH, type = FilePath.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PATH = "$ID/path"
    }
}
