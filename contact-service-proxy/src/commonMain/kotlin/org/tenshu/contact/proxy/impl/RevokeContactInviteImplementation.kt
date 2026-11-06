package org.tenshu.contact.proxy.impl

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.RevokeContactInvite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.toTBoolean
import org.tenshu.contact.proxy.ContactInviteLocalStore
import org.tenshu.contact.proxy.ContactProxyComponents

/**
 * §3 revoke-before-redemption: delete the relay mailbox (idempotent — the
 * relay 404s an unknown id) and flip the local row to `REVOKED`. The link in
 * the wild becomes a dead capability (a redeeming invitee sees 410).
 */
internal class RevokeContactInviteImplementation(
    private val components: ContactProxyComponents,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val inviteId: TString = params.read(RevokeContactInvite.Params.INVITE_ID)

        components.relayClient.revokeInvite(inviteId.get())

        val store = ContactInviteLocalStore(executionContext)
        store.get(inviteId.get())?.let { row ->
            store.updateStatus(row, ContactInvite.Status.REVOKED)
        }
        components.responseStash.remove(inviteId.get())

        return true.toTBoolean(executionContext.context) as T
    }
}
