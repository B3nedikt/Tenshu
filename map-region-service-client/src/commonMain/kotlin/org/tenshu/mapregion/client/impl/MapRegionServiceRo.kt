package org.tenshu.mapregion.client.impl

import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.MapRegionService
import org.tenshu.core.request.RequestChain

/**
 * Client-side view of [MapRegionService]. The service exposes no data relations (operations
 * only), so the Ro adds nothing beyond the [ServiceRo] base — operations are reached via
 * `findOperation`.
 */
internal class MapRegionServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : MapRegionService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components
}
