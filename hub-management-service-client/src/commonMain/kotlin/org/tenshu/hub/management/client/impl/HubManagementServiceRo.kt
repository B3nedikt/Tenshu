package org.tenshu.hub.management.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.HubManagementService
import org.tenshu.hub.management.HubStatus

internal class HubManagementServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : HubManagementService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val hubStatuses: TList<HubStatus>
        get() = serviceRelation(HubManagementService.Relations.HUB_STATUSES, HubStatus.Schema)

    override val hubCommands: TList<HubCommand>
        get() = serviceRelation(HubManagementService.Relations.HUB_COMMANDS, HubCommand.Schema)
}
