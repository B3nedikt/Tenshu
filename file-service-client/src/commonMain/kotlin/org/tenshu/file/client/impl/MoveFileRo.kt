package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType

internal class MoveFileRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), MoveFile {

    override fun moveFile(sourcePath: String, targetPath: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    MoveFile.Params.SOURCE_PATH to FilePath.create(context, sourcePath),
                    MoveFile.Params.TARGET_PATH to FilePath.create(context, targetPath)
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<org.tenshu.core.Data>(
            type = org.tenshu.core.Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
