package org.tenshu.file.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

/**
 * Request-object binding for the [File.Thumbnail] / [File.ThumbnailLarge] on-demand
 * resolver — the file-service twin of `GetWorkoutStreamsRo`.
 *
 * ⚠⚠ **This class is not optional plumbing.** The dynamic-property template dispatches
 * through the CLIENT module's `roFactory` registration, so without it *every* select
 * projecting either thumbnail dies as an opaque `error/internal` — the failure mode
 * `fit-workout-streams-content-plane.md` already paid for once and
 * `sync-device-catch-up.md` §11.3 names as C7's first trap.
 */
internal class GetStoredThumbnailRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetStoredThumbnail {

    override fun getStoredThumbnail(file: File, thumbnailClass: String): TByteArray {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    GetStoredThumbnail.Params.FILE to file,
                    GetStoredThumbnail.Params.THUMBNAIL_CLASS to thumbnailClass.toTString(context),
                ),
                schema = schema
            )
        )
        return context.roProvider()
            .createRequestObject(TByteArray.Schema.toType(), nextChain, context)
    }
}
