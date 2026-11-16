package org.tenshu.identity

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * This **member's** acceptances still on their way to the owner ([OutboundAcceptance] rows), one per realm.
 * A row with [OutboundAcceptance.needsAccount] was refused by the relay for want of an entitlement: the app
 * says "Create a free account to join this folder" instead of leaving the member silently waiting
 * (`shared-realm-membership-records.md` §10, maintainer decision 2026-11-12).
 *
 * ⚠ Member-side: the rows live in the member's personal realm. A row leaves once the drop succeeds.
 */
suspend fun IdentityService.getPendingAcceptances(): TResult<TList<OutboundAcceptance>> =
    findOperation<GetPendingAcceptances>(GetPendingAcceptances.ID)
        .getPendingAcceptances()
        .asTaskInteractor<TList<OutboundAcceptance>>()
        .execute()

interface GetPendingAcceptances : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getPendingAcceptances(): RequestObject

    companion object {
        const val ID = "${IdentityService.ID}/getPendingAcceptances"

        val Schema = operation(
            id = ID,
            receiver = IdentityService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
