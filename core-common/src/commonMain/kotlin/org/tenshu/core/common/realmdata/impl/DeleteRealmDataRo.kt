package org.tenshu.core.common.realmdata.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.realmdata.DeleteRealmData
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class DeleteRealmDataRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DeleteRealmData {

    override fun deleteRealmData(
        typeId: String,
        createdBefore: Long?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(DeleteRealmData.Params.TYPE_ID, typeId.toTString(context))
            // Optional param: omitted, never sent as a sentinel. "No narrowing"
            // and "created before epoch 0" must not collapse onto the same wire.
            if (createdBefore != null) {
                put(DeleteRealmData.Params.CREATED_BEFORE, createdBefore.toTInteger(context))
            }
        }
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
                schema = schema
            )
        )
        // ⚠ Generic `Data` request object, the AnnounceSession / WriteSetting idiom:
        // typing it as `TInteger` would need a registered TInteger RO factory (missing,
        // it surfaces as an opaque `error/internal` on the very first call). The RESULT still
        // parses typed through the registered TInteger value.
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
