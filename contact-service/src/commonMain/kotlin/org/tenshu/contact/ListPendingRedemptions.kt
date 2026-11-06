package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Enumerate this device's live invitee-side redemptions
 * (`drive-manage-access-ux.md` §4.2) so the UI can resume `awaitInviteAck`
 * after an app restart. The durable `PendingRedemption` records live in
 * proxy-side secure storage only; this is the client's view of them, as
 * [PendingRedemptionInfo] rows (fingerprint, never key material).
 */
suspend fun ContactService.listPendingRedemptions(): TResult<TList<PendingRedemptionInfo>> =
    findOperation<ListPendingRedemptions>(ListPendingRedemptions.ID)
        .listPendingRedemptions()
        .asTaskInteractor<TList<PendingRedemptionInfo>>()
        .execute()

interface ListPendingRedemptions : Operation {

    override val schema get() = Schema

    fun listPendingRedemptions(): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/listPendingRedemptions"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {},
            returnType = listType(PendingRedemptionInfo.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
