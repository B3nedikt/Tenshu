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
 * Extension for triggering the CreateRealm operation.
 *
 * First-device realm bootstrap per `docs/adr/0007-sync-device-management.md` and
 * `docs/proposals/sync-relay-api.md` §3.2. Called when this device is creating a brand-new
 * realm (no existing realm device to delegate from). The flow:
 *
 *   1. Derive the master identity Ed25519 keypair (asset A1) from [recoveryPhrase].
 *   2. Use the device's persistent signing keypair (asset A4) from `SyncManager`.
 *   3. Derive the realm `ownerId` deterministically from the master public key.
 *   4. POST `/sync/realm` with a master self-signature over the canonical bootstrap message.
 *
 * On success the relay has stored `(ownerId → masterPublicKey)` and registered the first
 * device's public key, so subsequent JWT-authenticated relay calls from this device verify.
 */
suspend fun SyncService.createRealm(
    recoveryPhrase: String,
    deviceName: String
): TResult<Data?> =
    findOperation<CreateRealm>(CreateRealm.ID)
        .createRealm(recoveryPhrase, deviceName)
        .asTaskInteractor<Data?>()
        .execute()

interface CreateRealm : Operation {

    fun createRealm(recoveryPhrase: String, deviceName: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/createRealm"

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
