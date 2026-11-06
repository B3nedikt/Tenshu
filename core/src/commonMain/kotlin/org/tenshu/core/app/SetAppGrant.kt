package org.tenshu.core.app

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
 * Writes the user's tri-state permission decision for one app capability
 * (`docs/proposals/tenshu-rei.md` §3.3). User-initiated from Control's
 * permission widget. Upserts the [AppGrant] row keyed on
 * `(appId, serviceId, operationId)`; [grant] is one of
 * [AppGrant.Grant.GRANTED] / [AppGrant.Grant.DENIED] /
 * [AppGrant.Grant.ASK_EACH_TIME].
 *
 * The realm-wide `AppGrant` is `Syncable`, so the decision replicates to every
 * device like any other entity block.
 */
suspend fun AppService.setAppGrant(
    appId: String,
    serviceId: String,
    operationId: String,
    grant: String,
): TResult<Data?> =
    findOperation<SetAppGrant>(SetAppGrant.ID)
        .setAppGrant(appId, serviceId, operationId, grant)
        .asTaskInteractor<Data?>()
        .execute()

interface SetAppGrant : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun setAppGrant(
        appId: String,
        serviceId: String,
        operationId: String,
        grant: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/app/service/setAppGrant"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                string(id = Params.APP_ID)
                string(id = Params.SERVICE_ID)
                string(id = Params.OPERATION_ID)
                string(id = Params.GRANT)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val APP_ID = "$ID/appId"
        const val SERVICE_ID = "$ID/serviceId"
        const val OPERATION_ID = "$ID/operationId"
        const val GRANT = "$ID/grant"
    }
}
