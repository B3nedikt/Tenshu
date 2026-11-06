package org.tenshu.inference.client.impl

import org.tenshu.core.Data
import org.tenshu.core.number.TInteger
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.toTString
import org.tenshu.inference.NameFaceGroup

internal class NameFaceGroupRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), NameFaceGroup {

    override fun nameFaceGroup(filePaths: List<String>, name: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    NameFaceGroup.Params.FILE_PATHS to
                        context.dataFactory().createList<Data>(
                            filePaths.map { it.toTString(context) },
                        ),
                    NameFaceGroup.Params.NAME to name.toTString(context),
                ),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject<TInteger>(
            type = schema.returnType,
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
