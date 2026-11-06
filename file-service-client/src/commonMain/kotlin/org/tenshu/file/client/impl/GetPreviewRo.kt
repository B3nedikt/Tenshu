package org.tenshu.file.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.toType

internal class GetPreviewRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetPreview {

    override fun getPreview(file: File, width: Int, height: Int, preferOriginal: Boolean): TByteArray {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = buildMap {
                    put(GetPreview.Params.FILE, file)
                    put(GetPreview.Params.WIDTH, width.toTInteger(context))
                    put(GetPreview.Params.HEIGHT, height.toTInteger(context))
                    // ⚠⚠ Only when set. The param is optional, and absence means today's ladder —
                    // so a `false` written out would be a wire change for every existing caller
                    // where sending nothing is both cheaper and exactly equivalent.
                    if (preferOriginal) put(GetPreview.Params.PREFER_ORIGINAL, true.toTBoolean(context))
                },
                schema = schema
            )
        )
        return context.roProvider()
            .createRequestObject(TByteArray.Schema.toType(), nextChain, context)
    }
}
