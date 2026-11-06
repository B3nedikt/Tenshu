package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.StreamRegionRange
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTByteArray
import org.tenshu.mapregion.proxy.StreamingRegionSource
import org.tenshu.mapregion.proxy.UnknownRegionException

/**
 * Serves a byte range in **streamed** mode (D10): the [StreamingRegionSource] serves an installed
 * region from the local bundle, otherwise range-fetches the covering chunks from the tile host on a
 * cache miss. A region in neither the store nor the verified catalog surfaces as a typed failure;
 * bytes at/after EOF come back empty.
 */
internal class StreamRegionRangeImplementation(
    private val source: StreamingRegionSource,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val regionId: TString = params.read(StreamRegionRange.Params.REGION_ID)
        val offset: TInteger = params.read(StreamRegionRange.Params.OFFSET)
        val length: TInteger = params.read(StreamRegionRange.Params.LENGTH)

        val bytes = try {
            source.readRange(regionId.get(), offset.get(), length.get().toInt())
        } catch (e: UnknownRegionException) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Region '${regionId.get()}' is not available (neither installed nor in the catalog).",
                ),
            )
        }

        return bytes.toTByteArray(executionContext.context) as T
    }
}
