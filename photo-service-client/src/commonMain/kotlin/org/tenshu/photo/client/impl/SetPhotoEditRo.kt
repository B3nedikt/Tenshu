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
import org.tenshu.photo.SetPhotoEdit

internal class SetPhotoEditRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetPhotoEdit {

    override fun setPhotoEdit(
        contentId: String,
        recipe: String,
        renditionPath: String,
        redacted: Boolean,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SetPhotoEdit.Params.CONTENT_ID, contentId.toTString(context))
            put(SetPhotoEdit.Params.RECIPE, recipe.toTString(context))
            put(SetPhotoEdit.Params.RENDITION_PATH, renditionPath.toTString(context))
            put(SetPhotoEdit.Params.REDACTED, redacted.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
