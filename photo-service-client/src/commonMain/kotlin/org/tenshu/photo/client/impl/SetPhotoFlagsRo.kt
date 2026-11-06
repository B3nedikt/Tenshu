package org.tenshu.photo.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.photo.SetPhotoFlags

internal class SetPhotoFlagsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetPhotoFlags {

    override fun setPhotoFlags(
        contentId: String,
        favourite: Boolean,
        hidden: Boolean,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SetPhotoFlags.Params.CONTENT_ID, contentId.toTString(context))
            put(SetPhotoFlags.Params.FAVOURITE, favourite.toTBoolean(context))
            put(SetPhotoFlags.Params.HIDDEN, hidden.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
