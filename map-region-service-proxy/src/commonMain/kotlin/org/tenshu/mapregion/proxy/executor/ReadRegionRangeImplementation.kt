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
import org.tenshu.core.mapregion.ReadRegionRange
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTByteArray
import org.tenshu.mapregion.proxy.RegionStore

/**
 * Serves a byte range of an installed region bundle from the canonical store (D6). No decrypt /
 * realm routing (region bundles are public plaintext), so this is a thin positional read; an
 * unknown/not-installed region surfaces as a typed failure, bytes at/after EOF come back empty.
 */
internal class ReadRegionRangeImplementation(
    private val store: RegionStore,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val regionId: TString = params.read(ReadRegionRange.Params.REGION_ID)
        val offset: TInteger = params.read(ReadRegionRange.Params.OFFSET)
        val length: TInteger = params.read(ReadRegionRange.Params.LENGTH)

        val bytes = store.readRange(regionId.get(), offset.get(), length.get().toInt())
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Region '${regionId.get()}' is not installed.",
                )
            )

        return bytes.toTByteArray(executionContext.context) as T
    }
}
