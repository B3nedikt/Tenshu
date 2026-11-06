package org.tenshu.ingest.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.IngestService
import org.tenshu.core.request.RequestChain

internal class IngestServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : IngestService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val sources: TList<IngestSource>
        get() = serviceRelation(IngestService.Relations.SOURCES, IngestSource.Schema)
}
