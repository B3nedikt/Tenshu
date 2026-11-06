package org.tenshu.hub.management.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.IssueHubCommand

/**
 * Executes on the ISSUING device's proxy: builds the idempotent [HubCommand]
 * row (stamping `issuedAtEpochMs` from the proxy clock and
 * `issuedByDeviceId` from this device's identity) and saves it —
 * `SyncObserver` emits the outbound block and the relation maintainer
 * (`HubManagementProxyInitialization`) adds it to
 * `HubManagementService.hubCommands`. Ordinary sync then delivers it to the
 * hub, which consumes it at most once.
 */
internal class IssueHubCommandImplementation(
    private val nowEpochMs: () -> Long,
    private val issuerDeviceId: () -> String?,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val commandId: TString = params.read(IssueHubCommand.Params.COMMAND_ID)
        val targetDeviceId: TString = params.read(IssueHubCommand.Params.TARGET_DEVICE_ID)
        val kind: TString = params.read(IssueHubCommand.Params.KIND)
        val mode: TString = params.read(IssueHubCommand.Params.MODE)

        val entity = HubCommand.create(
            context = executionContext.context,
            commandId = commandId.get(),
            targetDeviceId = targetDeviceId.get(),
            kind = kind.get(),
            mode = mode.get(),
            issuedAtEpochMs = nowEpochMs(),
            issuedByDeviceId = issuerDeviceId() ?: "",
        )

        executionContext.saveData(entity)

        return null as T
    }
}
