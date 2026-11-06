package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.NearestTransitStop
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTString
import org.tenshu.mapregion.proxy.ProxyReverseGeocoderEgress
import org.tenshu.mapregion.proxy.TransitStopTiles

/**
 * Resolves the nearest **named transit stop** within the caller's radius. Returns `""` when no tier
 * can answer or none finds a stop in range — the caller reads blank as "no stop", never as an error.
 *
 * ### Two tiers, tiles first (§28.2)
 *
 * [TransitStopTiles] reads the stop out of the map tiles the proxy already streams; only if it
 * cannot answer does the question go to [ProxyReverseGeocoderEgress] and out to the network.
 *
 * ⚠⚠ **The order is the point, not an optimisation.** Asking the network first would send the
 * coordinate to a third party even when we hold the answer locally, and the whole reason geocoding is
 * routed through the proxy is to keep egress accountable. Tiles-first means a catalogued region is
 * answered with no third-party request at all.
 *
 * ⚠ A tile miss is *not* proof there is no stop — the region may be uncatalogued, or its POI coverage
 * thin — so the fallback is a genuine second opinion rather than dead code. Both tiers agree on what
 * counts as a stop: rail-keyed and named (see `StopTileReader.STOP_KINDS`).
 */
internal class NearestTransitStopImplementation(
    private val geocoder: ProxyReverseGeocoderEgress?,
    private val tileStops: TransitStopTiles?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val latE7: TInteger = params.read(NearestTransitStop.Params.LAT_E7)
        val lngE7: TInteger = params.read(NearestTransitStop.Params.LNG_E7)
        val withinM: TInteger = params.read(NearestTransitStop.Params.WITHIN_M)
        val lat = latE7.get() / 1e7
        val lng = lngE7.get() / 1e7
        val within = withinM.get().toDouble()

        val stop = tileStops?.nearestStop(lat, lng, within)?.takeIf { it.isNotBlank() }
            ?: geocoder?.transitStop(lat, lng, within).orEmpty()
        return stop.toTString(executionContext.context) as T
    }
}
