package org.tenshu.photo.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.photo.SaveAlbum

internal class SaveAlbumRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveAlbum {

    override fun saveAlbum(albumId: String, name: String, coverContentId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveAlbum.Params.ALBUM_ID, albumId.toTString(context))
            put(SaveAlbum.Params.NAME, name.toTString(context))
            put(SaveAlbum.Params.COVER_CONTENT_ID, coverContentId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
