package org.tenshu.home.device

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
 * Authors one idempotent [DeviceCommand] row on the caller's proxy; ordinary
 * sync delivers it to the hub owning the target device, which executes it at
 * most once (2-min staleness window) and reports the outcome on the device's
 * [DeviceState] row. Mirrors `IssueHubCommand`.
 *
 * The proxy-side implementation stamps `issuedAtEpochMs` (proxy clock) and
 * `issuedByDeviceId` (this device); the caller supplies the [commandId]
 * (a fresh UUID per gesture — the typed wrappers in
 * `:home-device-service-client` generate one and return it).
 */
suspend fun HomeDeviceService.issueDeviceCommand(
    commandId: String,
    targetDeviceId: String,
    verb: String,
    argValue: String = "",
): TResult<Data?> =
    findOperation<IssueDeviceCommand>(IssueDeviceCommand.ID)
        .issueDeviceCommand(commandId, targetDeviceId, verb, argValue)
        .asTaskInteractor<Data?>()
        .execute()

interface IssueDeviceCommand : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun issueDeviceCommand(
        commandId: String,
        targetDeviceId: String,
        verb: String,
        argValue: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/home/device/service/issueDeviceCommand"

        val Schema = operation(
            id = ID,
            receiver = HomeDeviceService.Schema.toType(),
            params = {
                string(id = Params.COMMAND_ID)
                string(id = Params.TARGET_DEVICE_ID)
                string(id = Params.VERB)
                string(id = Params.ARG_VALUE)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val COMMAND_ID = "$ID/commandId"
        const val TARGET_DEVICE_ID = "$ID/targetDeviceId"
        const val VERB = "$ID/verb"
        const val ARG_VALUE = "$ID/argValue"
    }
}
