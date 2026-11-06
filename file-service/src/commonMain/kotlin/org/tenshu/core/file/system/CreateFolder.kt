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

suspend fun FileSystem.createFolder(path: String): TResult<Data?> =
    findOperation<CreateFolder>(CreateFolder.ID)
        .createFolder(path)
        .asTaskInteractor<Data?>()
        .execute()

interface CreateFolder : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun createFolder(path: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/createFolder"

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
