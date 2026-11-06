package org.tenshu.contact.client.impl

import org.tenshu.contact.ContactSource
import org.tenshu.contact.GetContactSourcePhotos
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

// The request-object binding for the ContactSource.Photos on-demand resolver — the contacts twin
// of GetWorkoutStreamsRo (`contacts-app.md` §9.10).
internal class GetContactSourcePhotosRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetContactSourcePhotos {

    override fun getPhotos(source: ContactSource): TByteArray {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(GetContactSourcePhotos.Params.SOURCE to source),
                schema = schema,
            )
        )
        return context.roProvider()
            .createRequestObject(TByteArray.Schema.toType(), nextChain, context)
    }
}
