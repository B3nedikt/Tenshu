package org.tenshu.mia.client.impl

import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.mia.MiaService

internal class MiaServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : MiaService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components
}
