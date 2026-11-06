package org.tenshu.file.client

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.file.system.UploadSourceFileBg
import org.tenshu.core.fileUploader
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

/**
 * RO for [UploadSourceFileBg]. Mirrors [UploadSourceFileRo] — including the
 * `fileUploader().upload(contentUri)` FD-push that crosses the descendant
 * document URI to the proxy — but `schema` resolves to the
 * [org.tenshu.core.trait.BackgroundOperation] variant and the params are the
 * shared [UploadSourceFile.Params].
 */
internal class UploadSourceFileBgRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), UploadSourceFileBg {

    override fun uploadFile(
        parentPath: String,
        fileName: String,
        contentUri: String,
        sourceModifiedAt: Long?,
    ): RequestObject {
        context.fileUploader().upload(contentUri)

        val nextChain = requestChain.addArray(
            OperationDto(
                params = buildMap {
                    put(UploadSourceFile.Params.PARENT_PATH, FilePath.create(context, parentPath))
                    put(UploadSourceFile.Params.FILE_NAME, FilePath.create(context, fileName))
                    put(UploadSourceFile.Params.URI, contentUri.toTString(context))
                    sourceModifiedAt?.let {
                        put(UploadSourceFile.Params.SOURCE_MODIFIED_AT, it.toTInteger(context))
                    }
                },
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
