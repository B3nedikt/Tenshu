package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.file.system.UploadFileBg
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType
import org.tenshu.core.toTByteArray

/**
 * RO for [UploadFileBg]. Byte-identical to [UploadFileRo] except `schema`
 * resolves to [UploadFileBg.Schema] (the [org.tenshu.core.trait.BackgroundOperation]
 * variant); the param keys are deliberately the shared [UploadFile.Params] so
 * the unchanged `UploadFileImplementation` reads them on the proxy side.
 */
internal class UploadFileBgRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), UploadFileBg {

    override fun uploadFile(
        parentPath: String,
        fileName: String,
        data: ByteArray,
        sourceModifiedAt: Long?,
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
