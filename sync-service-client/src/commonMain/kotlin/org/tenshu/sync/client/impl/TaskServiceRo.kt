package org.tenshu.sync.client.impl

import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.sync.TaskService

class TaskServiceRo(
    requestChain: RequestChain,
    context: TContext
) : ServiceRo(requestChain, context), TaskService {
    // Same override rationale as SyncServiceRo: without it, `operations`
    // resolves against the base service schema and never finds the
    // TaskService-registered ROs.
    override val schema: ServiceSchema
        get() = TaskService.Schema
}
