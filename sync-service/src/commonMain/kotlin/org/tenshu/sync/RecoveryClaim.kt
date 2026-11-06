package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the RecoveryClaim operation.
 *
 * Recovery flow per `docs/adr/0007-sync-device-management.md` and
 * `docs/proposals/sync-relay-api.md` §3.4. Called when this device is re-joining an
 * existing realm using only the recovery code (no other realm device available to
 * delegate). The flow:
 *
 *   1. Derive the master identity from [recoveryPhrase].
 *   2. Compute the realm `ownerId` deterministically from the master public key.
 *   3. Fetch a fresh challenge from the relay (`GET /sync/recovery-challenge`).
 *   4. Sign `(newDevicePublicKey || newDeviceId || challenge)` with the master identity.
 *   5. POST `/sync/recovery-claim`.
 *
 * On success, the relay has registered this device's public key and consumed the
 * challenge — subsequent JWT-authenticated requests verify normally.
 */
suspend fun SyncService.recoveryClaim(
    recoveryPhrase: String,
    deviceName: String
): TResult<Data?> =
    findOperation<RecoveryClaim>(RecoveryClaim.ID)
        .recoveryClaim(recoveryPhrase, deviceName)
        .asTaskInteractor<Data?>()
        .execute()

interface RecoveryClaim : Operation {

    fun recoveryClaim(recoveryPhrase: String, deviceName: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/recoveryClaim"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.RECOVERY_PHRASE)
                string(Params.DEVICE_NAME)
            },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val RECOVERY_PHRASE = "$ID/recoveryPhrase"
        const val DEVICE_NAME = "$ID/deviceName"
    }
}
