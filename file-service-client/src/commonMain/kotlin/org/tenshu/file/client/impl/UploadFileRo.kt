package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType
import org.tenshu.core.toTByteArray

internal class UploadFileRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), UploadFile {

    override fun uploadFile(
        parentPath: String,
        fileName: String,
        data: ByteArray,
        sourceModifiedAt: Long?,
        captureLatE7: Long?,
        captureLngE7: Long?,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = buildMap {
                    put(UploadFile.Params.PARENT_PATH, FilePath.create(context, parentPath))
                    put(UploadFile.Params.FILE_NAME, FilePath.create(context, fileName))
                    put(UploadFile.Params.DATA, data.toTByteArray(context))
                    sourceModifiedAt?.let {
                        put(UploadFile.Params.SOURCE_MODIFIED_AT, it.toTInteger(context))
                    }
                    // ⚠ Both or neither: a latitude without a longitude is not half a coordinate,
                    // it is a wrong one, and the proxy would have to invent the other half.
                    if (captureLatE7 != null && captureLngE7 != null) {
                        put(UploadFile.Params.CAPTURE_LAT_E7, captureLatE7.toTInteger(context))
                        put(UploadFile.Params.CAPTURE_LNG_E7, captureLngE7.toTInteger(context))
                    }
                },
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<org.tenshu.core.Data>(
            type = org.tenshu.core.Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
