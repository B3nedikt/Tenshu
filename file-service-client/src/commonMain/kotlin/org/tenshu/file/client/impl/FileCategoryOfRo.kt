package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.TConstant
import org.tenshu.core.file.FileCategoryOf
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

/**
 * Request-object binding for [File.Category]'s resolver.
 *
 * ⚠⚠ Not optional plumbing, for the same reason `GetStoredThumbnailRo` is not: a dynamic
 * property's template dispatches through the CLIENT module's `roFactory`, so without this every
 * chain that touches the category — a projection, a sort or the Photos feed's predicate — dies
 * with `Operation tenshu/file/categoryOf not found`.
 */
internal class FileCategoryOfRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), FileCategoryOf {

    override fun categoryOf(file: File): TConstant {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(FileCategoryOf.Params.FILE to file),
                schema = schema
            )
        )
        return context.roProvider()
            .createRequestObject(TConstant.Schema.toType(), nextChain, context)
    }
}
