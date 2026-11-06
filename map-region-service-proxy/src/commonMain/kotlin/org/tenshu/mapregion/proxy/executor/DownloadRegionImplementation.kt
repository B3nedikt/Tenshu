package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.DownloadRegion
import org.tenshu.core.mapregion.InstalledRegion
import org.tenshu.core.mapregion.MeteredNetworkRefusedError
import org.tenshu.mapregion.proxy.MeteredNetworkSignal
import org.tenshu.mapregion.proxy.RegionDownloader

/**
 * Downloads a region via [RegionDownloader] (fetch + sha256-gate + store) and returns the landed
 * [InstalledRegion]. Async (network); a transport/integrity/unknown-region failure throws through
 * the error-channel catch-all → typed `TResult.Failure`.
 */
internal class DownloadRegionImplementation(
    private val downloader: RegionDownloader,
    private val meteredNetwork: MeteredNetworkSignal,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val regionId: TString = params.read(DownloadRegion.Params.REGION_ID)
        // Optional param: absent means "no". Read defensively so an old client's call — which carries
        // no such field at all — is simply a non-override, not a dispatch failure.
        val allowMetered = (params?.get(DownloadRegion.Params.ALLOW_METERED) as? TBoolean)?.get() ?: false

        // ⚠ The gate is here rather than inside the downloader on purpose: `startOrJoin` means a second
        // call JOINS an in-flight job, and joining a download the user already consented to must not be
        // refused. This is about *starting* one.
        if (!allowMetered && meteredNetwork.isMetered()) {
            throw TenshuException(
                MeteredNetworkRefusedError.create(executionContext.context, regionId.get()),
            )
        }

        val landed = downloader.download(regionId.get())

        return InstalledRegion.create(
            context = executionContext.context,
            regionId = landed.regionId,
            name = landed.name,
            localPath = landed.localPath,
            byteSize = landed.byteSize,
            sha256 = landed.sha256,
            buildDate = landed.buildDate,
        ) as T
    }
}
