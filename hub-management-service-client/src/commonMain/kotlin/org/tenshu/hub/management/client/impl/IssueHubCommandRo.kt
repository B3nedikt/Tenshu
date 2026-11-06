package org.tenshu.hub.management.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.hub.management.IssueHubCommand

internal class IssueHubCommandRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), IssueHubCommand {

    override fun issueHubCommand(
        commandId: String,
        targetDeviceId: String,
        kind: String,
        mode: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(IssueHubCommand.Params.COMMAND_ID, commandId.toTString(context))
            put(IssueHubCommand.Params.TARGET_DEVICE_ID, targetDeviceId.toTString(context))
            put(IssueHubCommand.Params.KIND, kind.toTString(context))
            put(IssueHubCommand.Params.MODE, mode.toTString(context))
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
