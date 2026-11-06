package org.tenshu.file.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.system.ReadSharedFile
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class ReadSharedFileRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ReadSharedFile {

    override fun readSharedFile(realmId: String, fileId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ReadSharedFile.Params.REALM_ID to realmId.toTString(context),
                    ReadSharedFile.Params.FILE_ID to fileId.toTString(context),
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
