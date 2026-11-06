package org.tenshu.home.device.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.IssueDeviceCommand

/**
 * Executes on the ISSUING device's proxy: builds the idempotent
 * [DeviceCommand] row (stamping `issuedAtEpochMs` from the proxy clock —
 * the hub's 2-minute staleness-window anchor — and `issuedByDeviceId` from
 * this device's identity) and saves it; `SyncObserver` emits the outbound
 * block and the relation maintainer (`HomeDeviceProxyInitialization`) adds
 * it to `HomeDeviceService.deviceCommands`. Ordinary sync then delivers it
 * to the hub owning the target device, which consumes it at most once.
 * Mirrors `IssueHubCommandImplementation`.
 */
internal class IssueDeviceCommandImplementation(
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
        val commandId: TString = params.read(IssueDeviceCommand.Params.COMMAND_ID)
        val targetDeviceId: TString = params.read(IssueDeviceCommand.Params.TARGET_DEVICE_ID)
        val verb: TString = params.read(IssueDeviceCommand.Params.VERB)
        val argValue: TString = params.read(IssueDeviceCommand.Params.ARG_VALUE)

        val entity = DeviceCommand.create(
            context = executionContext.context,
            commandId = commandId.get(),
            targetDeviceId = targetDeviceId.get(),
            verb = verb.get(),
            argValue = argValue.get(),
            issuedAtEpochMs = nowEpochMs(),
            issuedByDeviceId = issuerDeviceId() ?: "",
        )

        executionContext.saveData(entity)

        return null as T
    }
}
