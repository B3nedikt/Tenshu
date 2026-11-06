package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.ReverseGeocodeCandidates
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTString
import org.tenshu.mapregion.proxy.ProxyReverseGeocoderEgress

/**
 * Resolves a coordinate to the several nearest venue/place labels via the proxy's
 * [ProxyReverseGeocoderEgress] (Photon), for the "choose the right place" picker
 * (`timeline-recognition-and-place-naming.md` §4). Returns them joined by
 * [ReverseGeocodeCandidates.DELIMITER] (POI labels contain no newlines) — the app splits — or `""`
 * when the geocoder is unconfigured / finds nothing. Egress is Control's; the app has no `INTERNET`.
 */
internal class ReverseGeocodeCandidatesImplementation(
    private val geocoder: ProxyReverseGeocoderEgress?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val latE7: TInteger = params.read(ReverseGeocodeCandidates.Params.LAT_E7)
        val lngE7: TInteger = params.read(ReverseGeocodeCandidates.Params.LNG_E7)
        val labels = geocoder?.candidates(latE7.get() / 1e7, lngE7.get() / 1e7).orEmpty()
        return labels.joinToString(ReverseGeocodeCandidates.DELIMITER).toTString(executionContext.context) as T
    }
}
