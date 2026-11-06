package org.tenshu.sync

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
import org.tenshu.identity.PendingGrant

/**
 * GR-6 inbound poll: sweep every grantable contact's derivable grant-mailbox address
 * and pick up any sealed grant waiting there, storing each as a quarantined
 * `PendingGrant` (the mailbox `GET` is single-pickup, so discovery must persist the
 * blob — D4). Returns the full pending set, which backs the *Shared with me*
 * pending-grant cards. Accept (`acceptSharedRealmGrant`) consumes one; decline
 * (`declinePendingGrant`) drops one.
 *
 * Out-of-band-free discovery (the maintainer-deferred GR-4 inbound piece): grants
 * normally follow an in-person handshake, so this is a convenience over the
 * accept-from-contact path, not a requirement.
 */
suspend fun SyncService.pollPendingGrants(): TResult<TList<PendingGrant>> =
    findOperation<PollPendingGrants>(PollPendingGrants.ID)
        .pollPendingGrants()
        .asTaskInteractor<TList<PendingGrant>>()
        .execute()

interface PollPendingGrants : Operation {

    fun pollPendingGrants(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/pollPendingGrants"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = listType(PendingGrant.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
