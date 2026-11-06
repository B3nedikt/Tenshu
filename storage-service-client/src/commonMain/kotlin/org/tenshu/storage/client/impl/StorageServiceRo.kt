package org.tenshu.storage.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.storage.StorageService
import org.tenshu.storage.StorageStatus

internal class StorageServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : StorageService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val statuses: TList<StorageStatus>
        get() = serviceRelation(StorageService.Relations.STATUSES, StorageStatus.Schema)
}
