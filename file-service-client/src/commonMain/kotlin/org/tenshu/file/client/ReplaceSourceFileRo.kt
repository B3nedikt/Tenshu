package org.tenshu.file.client

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.ReplaceSourceFile
import org.tenshu.core.fileUploader
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class ReplaceSourceFileRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), ReplaceSourceFile {

    override fun replaceFile(
        parentPath: String,
        fileName: String,
        contentUri: String
    ): RequestObject {
        context.fileUploader().upload(contentUri)

        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ReplaceSourceFile.Params.PARENT_PATH to FilePath.create(context, parentPath),
                    ReplaceSourceFile.Params.FILE_NAME to FilePath.create(context, fileName),
                    ReplaceSourceFile.Params.URI to contentUri.toTString(context)
                ),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
