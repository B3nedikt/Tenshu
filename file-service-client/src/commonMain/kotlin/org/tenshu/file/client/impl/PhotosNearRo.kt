package org.tenshu.file.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.system.PhotosNear
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType

internal class PhotosNearRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PhotosNear {

    override fun photosNear(latE7: Long, lngE7: Long, radiusM: Long, limit: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    PhotosNear.Params.LAT_E7 to latE7.toTInteger(context),
                    PhotosNear.Params.LNG_E7 to lngE7.toTInteger(context),
                    PhotosNear.Params.RADIUS_M to radiusM.toTInteger(context),
                    PhotosNear.Params.LIMIT to limit.toLong().toTInteger(context),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TList<CapturedPhoto>>(
            type = listType(CapturedPhoto.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
