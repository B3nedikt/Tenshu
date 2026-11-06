package org.tenshu.notifications.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.SendNotification
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType

internal class SendNotificationRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), SendNotification {

    override fun sendNotification(notification: Notification): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    SendNotification.Params.NOTIFICATION to notification
                ),
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
