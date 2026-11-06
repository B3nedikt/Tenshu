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
 * Deregisters an app (`docs/proposals/control-app-registration.md` §5).
 * User-initiated from Control's Apps tab — *"Uninstall"* means deregister, not
 * binary removal (Control cannot delete the binary). The implementation:
 *
 *  - sets the targeted [AppRegistration] row(s) to
 *    [AppRegistration.State.REMOVED] (a tombstone, not a delete — §5.1);
 *  - purges every [AppGrant] for the app, so a later re-add starts from
 *    default-deny.
 *
 * [deviceId] selects the scope: non-null removes only `(appId, deviceId)` (the
 * per-device default, §5.2); `null` removes every `(appId, *)` row
 * (*"Remove from all devices"*).
 */
suspend fun AppService.removeApp(
    appId: String,
    deviceId: String? = null,
): TResult<Data?> =
    findOperation<RemoveApp>(RemoveApp.ID)
        .removeApp(appId, deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface RemoveApp : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun removeApp(
        appId: String,
        deviceId: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/app/service/removeApp"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                string(id = Params.APP_ID)
                string(id = Params.DEVICE_ID, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val APP_ID = "$ID/appId"
        const val DEVICE_ID = "$ID/deviceId"
    }
}
