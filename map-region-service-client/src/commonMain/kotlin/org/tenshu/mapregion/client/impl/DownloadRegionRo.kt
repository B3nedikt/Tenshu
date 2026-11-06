package org.tenshu.mapregion.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.DownloadRegion
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class DownloadRegionRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DownloadRegion {

    override val schema: OperationSchema
        get() = DownloadRegion.Schema

    override fun downloadRegion(regionId: String, allowMetered: Boolean): RequestObject {
        val params = buildMap<String, Data> {
            put(DownloadRegion.Params.REGION_ID, regionId.toTString(context))
            // ⚠ Only when true. The parameter is optional and absence means "no", so an ordinary
            // download puts exactly the same bytes on the wire as before it existed — the new field
            // travels only on the deliberate override. Same shape as `AwaitInviteAckRo`.
            if (allowMetered) put(DownloadRegion.Params.ALLOW_METERED, allowMetered.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(params = params, schema = schema)
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
