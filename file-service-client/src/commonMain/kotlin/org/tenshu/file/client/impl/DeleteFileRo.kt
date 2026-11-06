package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType

internal class DeleteFileRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), DeleteFile {

    override fun deleteFile(path: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(DeleteFile.Params.PATH to FilePath.create(context, path)),
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
