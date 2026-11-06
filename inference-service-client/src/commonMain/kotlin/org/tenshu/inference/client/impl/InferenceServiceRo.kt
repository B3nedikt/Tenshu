package org.tenshu.inference.client.impl

import org.tenshu.core.core.TComponent
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.inference.InferenceService
import org.tenshu.inference.MediaLabel

internal class InferenceServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : InferenceService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val labels: TList<MediaLabel>
        get() = serviceRelation(InferenceService.Relations.LABELS, MediaLabel.Schema)
}
