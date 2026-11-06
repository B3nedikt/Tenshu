package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.SetSourceAutoImport
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class SetSourceAutoImportRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), SetSourceAutoImport {

    override fun setSourceAutoImport(
        kind: String,
        deviceId: String,
        autoImport: Boolean,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    SetSourceAutoImport.Params.KIND to kind.toTString(context),
                    SetSourceAutoImport.Params.DEVICE_ID to deviceId.toTString(context),
                    SetSourceAutoImport.Params.AUTO_IMPORT to autoImport.toTBoolean(context),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
