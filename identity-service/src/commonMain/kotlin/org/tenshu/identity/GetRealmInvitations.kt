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
 * The **invitations** of shared realm `realmId` this owner is still waiting on: grants whose member has not
 * yet accepted (`shared-realm-membership-records.md` §3.4, the "invited" state; RO D7). Each row names the
 * member id and the contact it was granted to, so *Manage access* can show "Bob — Invited".
 *
 * ⚠ Owner-side only: the rows live in the owner's **personal** realm ([AwaitedAcceptance]), so a member's
 * device returns an empty list. A row leaves once the acceptance is processed or the member is removed.
 */
suspend fun IdentityService.getRealmInvitations(realmId: String): TResult<TList<AwaitedAcceptance>> =
    findOperation<GetRealmInvitations>(GetRealmInvitations.ID)
        .getRealmInvitations(realmId)
        .asTaskInteractor<TList<AwaitedAcceptance>>()
        .execute()

interface GetRealmInvitations : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getRealmInvitations(realmId: String): RequestObject

    companion object {
        const val ID = "${IdentityService.ID}/getRealmInvitations"

        val Schema = operation(
            id = ID,
            receiver = IdentityService.Schema.toType(),
            params = {
                string(Params.REALM_ID)
            },
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REALM_ID = "$ID/realmId"
    }
}
