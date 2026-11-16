package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation
import org.tenshu.identity.DeviceRecord

/**
 * The devices this realm has **removed**, by its signed device records (`sync-entity-identity.md` §5b
 * R6c): for each, the removal record that decides it — its device id, who signed it (`master` = with the
 * recovery phrase, `self` = the device removed itself) and when. A device re-admitted since is absent.
 *
 * ⚠ The verification is the proxy's: records are not chain-readable, so a caller sees only rows that
 * verified against the realm's id. ⚠ Personal realm only.
 */
suspend fun SyncService.getRemovedDevices(): TResult<TList<DeviceRecord>> =
    findOperation<GetRemovedDevices>(GetRemovedDevices.ID)
        .getRemovedDevices()
        .asTaskInteractor<TList<DeviceRecord>>()
        .execute()

interface GetRemovedDevices : Operation {

    override val schema get() = Schema

    fun getRemovedDevices(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getRemovedDevices"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
