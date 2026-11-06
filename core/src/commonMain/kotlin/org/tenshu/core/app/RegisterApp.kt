package org.tenshu.core.app

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Registers an app as installed on `(appId, deviceId)`
 * (`docs/proposals/control-app-registration.md` §3). **Proxy-only and
 * idempotent**: invoked from the proxy after the IPC handshake's code-signing
 * check, never by the connecting app. The implementation:
 *
 *  - is a no-op if an [AppRegistration] row already exists for the pair
 *    (whether [AppRegistration.State.ACTIVE] or [AppRegistration.State.REMOVED]);
 *  - a `Removed` row is *not* overwritten — the tombstone suppresses silent
 *    re-registration of a still-installed binary (§5.1);
 *  - otherwise stamps `createdAt = now`, `state = Active`, and the
 *    `verified` badge (false under the dev-mode handshake downgrade, §3.3).
 *
 * Because this write is reachable only behind the handshake's signature check,
 * a forged local client (threat-model T-C-11) cannot self-register (§6).
 */
suspend fun AppService.registerApp(
    appId: String,
    deviceId: String,
    verified: Boolean = false,
): TResult<Data?> =
    findOperation<RegisterApp>(RegisterApp.ID)
        .registerApp(appId, deviceId, verified)
        .asTaskInteractor<Data?>()
        .execute()

interface RegisterApp : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun registerApp(
        appId: String,
        deviceId: String,
        verified: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/app/service/registerApp"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                string(id = Params.APP_ID)
                string(id = Params.DEVICE_ID)
                boolean(id = Params.VERIFIED)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val APP_ID = "$ID/appId"
        const val DEVICE_ID = "$ID/deviceId"
        const val VERIFIED = "$ID/verified"
    }
}
