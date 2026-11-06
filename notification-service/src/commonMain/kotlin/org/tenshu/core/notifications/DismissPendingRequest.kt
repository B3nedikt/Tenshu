package org.tenshu.core.notifications

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
 * Mark the inbound request with this **content-derived** id ([requestId] =
 * `PendingRequest.id`) as seen/dismissed. The proxy stamps a [DismissedRequest]
 * `Syncable(Eager)` record so the dismissal converges to every paired device
 * (`control-central-notifications.md` Phase 4). Idempotent — re-dismissing the
 * same id folds to one record (keyed by the id).
 */
suspend fun NotificationService.dismissPendingRequest(requestId: String): TResult<Data?> =
    findOperation<DismissPendingRequest>(DismissPendingRequest.ID)
        .dismissPendingRequest(requestId)
        .asTaskInteractor<Data?>()
        .execute()

interface DismissPendingRequest : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun dismissPendingRequest(requestId: String): RequestObject

    companion object {
        const val ID = "tenshu/notification/service/dismissPendingRequest"

        val Schema = operation(
            id = ID,
            receiver = NotificationService.Schema.toType(),
            params = {
                string(id = Params.REQUEST_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val REQUEST_ID = "$ID/requestId"
    }
}
