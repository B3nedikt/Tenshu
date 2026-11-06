package org.tenshu.home.device.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.home.device.IssueDeviceCommand

internal class IssueDeviceCommandRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), IssueDeviceCommand {

    override fun issueDeviceCommand(
        commandId: String,
        targetDeviceId: String,
        verb: String,
        argValue: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(IssueDeviceCommand.Params.COMMAND_ID, commandId.toTString(context))
            put(IssueDeviceCommand.Params.TARGET_DEVICE_ID, targetDeviceId.toTString(context))
            put(IssueDeviceCommand.Params.VERB, verb.toTString(context))
            put(IssueDeviceCommand.Params.ARG_VALUE, argValue.toTString(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
                schema = schema
            )
        )
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
