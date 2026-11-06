package org.tenshu.hub.management.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.hub.management.HubManagementService
import org.tenshu.hub.management.IssueHubCommand
import org.tenshu.hub.management.client.impl.HubManagementServiceRo
import org.tenshu.hub.management.client.impl.IssueHubCommandRo

val hubManagementServiceClientModule = tModule {

    operation(
        interfaceClass = IssueHubCommand::class,
        protocolSchema = IssueHubCommand.Schema,
        roFactory = ::IssueHubCommandRo
    )

    service(
        interfaceClass = HubManagementService::class,
        serviceSchema = HubManagementService.Schema,
        roFactory = ::HubManagementServiceRo
    )
}
