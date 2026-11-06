package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.LinesServing
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTString
import org.tenshu.mapregion.proxy.TransitLineOverlays

/**
 * Resolves the transit lines serving **both** ends of a leg (§33 L4), as `"U5"` or `"U3,U6"`.
 *
 * ### ⚠⚠ One tier only, and no network fallback — deliberately
 *
 * [NearestTransitStopImplementation] has two tiers because a third-party geocoder genuinely can name
 * a station. Nothing comparable exists here: no public reverse-geocode answers "which lines serve
 * these two coordinates", and §24.2 measured that the shipped basemap carries no line identity at
 * all. So there is exactly one source, and when it cannot answer the honest result is `""`.
 *
 * ⚠ `""` is *ignorance*, never "this leg has no line". The caller must render the leg as it would
 * without an overlay rather than asserting anything about it.
 */
internal class LinesServingImplementation(
    private val overlays: TransitLineOverlays?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val fromLat: TInteger = params.read(LinesServing.Params.FROM_LAT_E7)
        val fromLng: TInteger = params.read(LinesServing.Params.FROM_LNG_E7)
        val toLat: TInteger = params.read(LinesServing.Params.TO_LAT_E7)
        val toLng: TInteger = params.read(LinesServing.Params.TO_LNG_E7)
        val withinM: TInteger = params.read(LinesServing.Params.WITHIN_M)

        val lines = overlays?.linesServing(
            fromLatE7 = fromLat.get(),
            fromLngE7 = fromLng.get(),
            toLatE7 = toLat.get(),
            toLngE7 = toLng.get(),
            withinM = withinM.get().toDouble(),
        ).orEmpty()

        // ⚠ Sorted, so the same pair renders identically on every device — the same reason
        // `TransitLineIndex.label` sorts.
        return lines.sorted().joinToString(",").toTString(executionContext.context) as T
    }
}
