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
 * Extension for triggering the RevokeDevice operation.
 *
 * Per `docs/adr/0007-sync-device-management.md` (Revocation) and `docs/adr/0006-sync-tombstones.md`
 * §3.6, revocation is implemented by deleting the target's local `Device` entity and
 * emitting a `DeleteBlock` tombstone. Receivers short-circuit subsequent merges for the
 * revoked entity at HLC ≤ tombstone.hlc; the relay treats the tombstone as a GC marker.
 *
 * This operation supersedes the legacy `IdentityService.revokeDevice` relay-call —
 * the `/identity/revoke-device` endpoint and `deviceRegistry` map have been deleted
 * (Phase 3 cleanup).
 */
suspend fun SyncService.revokeDevice(deviceId: String): TResult<Data?> =
    findOperation<RevokeDevice>(RevokeDevice.ID)
        .revokeDevice(deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface RevokeDevice : Operation {

    fun revokeDevice(deviceId: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/revokeDevice"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.DEVICE_ID)
            },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val DEVICE_ID = "$ID/deviceId"
    }
}
