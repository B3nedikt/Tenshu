package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.ListSharedFolder
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.core.toTString

internal class ListSharedFolderRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ListSharedFolder {

    override fun listSharedFolder(realmId: String, parentFileId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ListSharedFolder.Params.REALM_ID to realmId.toTString(context),
                    ListSharedFolder.Params.PARENT_FILE_ID to parentFileId.toTString(context),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<org.tenshu.core.collection.list.TList<SharedFile>>(
            type = listType(SharedFile.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
