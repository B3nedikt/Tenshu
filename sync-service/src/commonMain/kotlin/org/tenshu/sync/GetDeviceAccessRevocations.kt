package org.tenshu.sync

import org.tenshu.core.Data

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

/**
 * What the relay has (not) done with each removal **this device** signed (`sync-entity-identity.md` §5b): one
 * `PendingDeviceAccessRevocation` row per removed device, with its `relayState` and `relayDetail`. Control's
 * Manage devices shows a removal the relay has not applied as such, never as plainly "removed"
 * (maintainer decision 2026-11-12).
 *
 * ⚠ Device-local: the outbox is not synced, so another of the user's devices does not see these states.
 */
suspend fun SyncService.getDeviceAccessRevocations(): TResult<TList<Data>> =
    findOperation<GetDeviceAccessRevocations>(GetDeviceAccessRevocations.ID)
        .getDeviceAccessRevocations()
        .asTaskInteractor<TList<Data>>()
        .execute()

interface GetDeviceAccessRevocations : Operation {

    override val schema get() = Schema

    fun getDeviceAccessRevocations(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getDeviceAccessRevocations"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
