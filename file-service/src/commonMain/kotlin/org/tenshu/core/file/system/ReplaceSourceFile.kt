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
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/** By-reference counterpart of [replaceFile] — see [UploadSourceFile] for what `uri` may be. */
suspend fun FileSystem.replaceSourceFile(
    parentPath: String,
    fileName: String,
    uri: String
): TResult<Data?> =
    findOperation<ReplaceSourceFile>(ReplaceSourceFile.ID)
        .replaceFile(
            parentPath = parentPath,
            fileName = fileName,
            uri
        )
        .asTaskInteractor<Data?>()
        .execute()

interface ReplaceSourceFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun replaceFile(
        parentPath: String,
        fileName: String,
        contentUri: String
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/sourceFileReplace"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                composite(id = Params.PARENT_PATH, type = FilePath.Schema)
                composite(id = Params.FILE_NAME, type = FilePath.Schema)
                composite(id = Params.URI, type = TString.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PARENT_PATH = "${ID}/path"
        const val FILE_NAME = "${ID}/fileName"
        const val URI = "${ID}/uri"
    }
}
