package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType
import org.tenshu.core.toTByteArray

internal class ReplaceFileRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), ReplaceFile {

    override fun replaceFile(
        parentPath: String,
        fileName: String,
        data: ByteArray
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ReplaceFile.Params.PARENT_PATH to FilePath.create(context, parentPath),
                    ReplaceFile.Params.FILE_NAME to FilePath.create(context, fileName),
                    ReplaceFile.Params.DATA to data.toTByteArray(context)
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
