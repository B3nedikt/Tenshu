package org.tenshu.file.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.system.MountSharedRealm
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class MountSharedRealmRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), MountSharedRealm {

    override fun mountSharedRealm(
        sharedRealmId: String,
        parentPath: String,
        name: String,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    MountSharedRealm.Params.SHARED_REALM_ID to sharedRealmId.toTString(context),
                    MountSharedRealm.Params.PARENT_PATH to parentPath.toTString(context),
                    MountSharedRealm.Params.NAME to name.toTString(context),
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
