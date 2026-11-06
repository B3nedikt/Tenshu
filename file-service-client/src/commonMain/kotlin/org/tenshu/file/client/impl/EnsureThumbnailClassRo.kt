package org.tenshu.file.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.EnsureThumbnailClass
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class EnsureThumbnailClassRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), EnsureThumbnailClass {

    override fun ensureThumbnailClass(
        path: String,
        thumbnailClass: String,
        userRequested: Boolean,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = buildMap {
                    put(EnsureThumbnailClass.Params.PATH, FilePath.create(context, path))
                    put(EnsureThumbnailClass.Params.THUMBNAIL_CLASS, thumbnailClass.toTString(context))
                    // ⚠ Only when set. Absence means "no", and an override that travelled as an
                    // explicit `false` would be a new field on every ordinary dwell mint.
                    if (userRequested) {
                        put(EnsureThumbnailClass.Params.USER_REQUESTED, true.toTBoolean(context))
                    }
                },
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TByteArray>(
            type = TByteArray.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
