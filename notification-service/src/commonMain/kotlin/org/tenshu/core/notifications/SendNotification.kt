package org.tenshu.core.notifications

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun NotificationService.sendNotification(notification: Notification): TResult<Data?> =
    findOperation<SendNotification>(SendNotification.ID)
        .sendNotification(notification)
        .asTaskInteractor<Data?>()
        .execute()

interface SendNotification : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun sendNotification(notification: Notification): RequestObject

    companion object {
        const val ID = "tenshu/notification/service/sendNotification"

        val Schema = operation(
            id = ID,
            receiver = NotificationService.Schema.toType(),
            params = {
                composite(id = Params.NOTIFICATION, type = Notification.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val NOTIFICATION = "$ID/notification"
    }
}
