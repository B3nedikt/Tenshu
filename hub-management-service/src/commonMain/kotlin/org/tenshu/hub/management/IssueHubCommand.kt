package org.tenshu.hub.management

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Authors one idempotent [HubCommand] row on the caller's proxy; ordinary
 * sync delivers it to the hub, which executes it at most once and reports
 * the outcome on its [HubStatus] row.
 *
 * The proxy-side implementation stamps `issuedAtEpochMs` (proxy clock) and
 * `issuedByDeviceId` (this device); the caller supplies the [commandId]
 * (a fresh UUID per gesture — the typed wrappers in
 * `:hub-management-service-client` generate one and return it).
 */
suspend fun HubManagementService.issueHubCommand(
    commandId: String,
    targetDeviceId: String,
    kind: String,
    mode: String = "",
): TResult<Data?> =
    findOperation<IssueHubCommand>(IssueHubCommand.ID)
        .issueHubCommand(commandId, targetDeviceId, kind, mode)
        .asTaskInteractor<Data?>()
        .execute()

interface IssueHubCommand : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun issueHubCommand(
        commandId: String,
        targetDeviceId: String,
        kind: String,
        mode: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/hub/management/service/issueHubCommand"

        val Schema = operation(
            id = ID,
            receiver = HubManagementService.Schema.toType(),
            params = {
                string(id = Params.COMMAND_ID)
                string(id = Params.TARGET_DEVICE_ID)
                string(id = Params.KIND)
                string(id = Params.MODE)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val COMMAND_ID = "$ID/commandId"
        const val TARGET_DEVICE_ID = "$ID/targetDeviceId"
        const val KIND = "$ID/kind"
        const val MODE = "$ID/mode"
    }
}
