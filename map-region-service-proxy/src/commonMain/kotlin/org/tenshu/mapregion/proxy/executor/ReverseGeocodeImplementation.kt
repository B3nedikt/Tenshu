package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.GeocodedPlace
import org.tenshu.core.mapregion.ReverseGeocodePlace
import org.tenshu.core.number.TInteger
import org.tenshu.mapregion.proxy.ProxyReverseGeocoderEgress

/**
 * Resolves a coordinate to a venue label **and its locality tiers** via the proxy's
 * [ProxyReverseGeocoderEgress] (Photon). Every field is `""` when the geocoder is unconfigured /
 * finds nothing — the app-side `ProxyReverseGeocoder` maps a blank label → `null`, folding cleanly
 * into its chain. The egress is Control's; the app never gains `INTERNET`
 * (P3, `timeline-movement-capture.md` §11.3).
 *
 * ⚠ `city` and `countryCode` used to be unreachable: the op returned a bare `TString`, so the app
 * defaulted them to blank on every lookup and `PlaceLabelEnricher`'s accuracy-coarsening — which
 * requires a non-blank city — could never fire. See [GeocodedPlace].
 */
internal class ReverseGeocodeImplementation(
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
        val latE7: TInteger = params.read(ReverseGeocodePlace.Params.LAT_E7)
        val lngE7: TInteger = params.read(ReverseGeocodePlace.Params.LNG_E7)
        val found = geocoder?.place(latE7.get() / 1e7, lngE7.get() / 1e7)
        return GeocodedPlace.create(
            context = executionContext.context,
            label = found?.label.orEmpty(),
            city = found?.city.orEmpty(),
            countryCode = found?.countryCode.orEmpty(),
        ) as T
    }
}
