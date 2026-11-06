package org.tenshu.file.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.ShareFolderIntoRealm
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class ShareFolderIntoRealmRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ShareFolderIntoRealm {

    override fun shareFolderIntoRealm(folderPath: String, realmId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ShareFolderIntoRealm.Params.FOLDER_PATH to FilePath.create(context, folderPath),
                    ShareFolderIntoRealm.Params.REALM_ID to realmId.toTString(context),
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
