package org.tenshu.contact.proxy.impl

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.CreateContactInvite
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.time.clock
import org.tenshu.core.toTString
import org.tenshu.contact.proxy.ContactProxyComponents

/**
 * §4 step 1 — issue. Mints the §3 triple, registers the relay mailbox (JWT),
 * persists the [ContactInvite] (`secretHash` only — the raw `K_link` lives
 * solely in the returned link string), and returns the messaging link for
 * the OS share sheet. The relay's echoed (possibly clamped) expiry is what
 * gets persisted, so local and relay expiry never drift.
 */
internal class CreateContactInviteImplementation(
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
        val intentKind = (params?.get(CreateContactInvite.Params.INTENT_KIND) as? TString)?.get() ?: ""
        val intentLabel = (params?.get(CreateContactInvite.Params.INTENT_LABEL) as? TString)?.get() ?: ""

        val keyPair = components.identityKeyPair()
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Identity key (A13) unavailable — unlock this device to create an invite.",
                )
            )

        val link = InviteLinkPayload.mint(keyPair.publicKey)
        val now = executionContext.context.clock().nowEpochMillis()
        val expiresAt = components.relayClient.registerInvite(
            inviteId = link.inviteIdHex,
            expiresAtMillis = now + ContactInvite.DEFAULT_TTL_MILLIS,
        )

        executionContext.saveData(
            ContactInvite.create(
                context = executionContext.context,
                inviteId = link.inviteIdHex,
                secretHash = link.secretHashHex(),
                status = ContactInvite.Status.PENDING,
                createdAt = now,
                expiresAt = expiresAt,
                intentKind = intentKind,
                intentLabel = intentLabel,
            )
        )

        return link.toMessagingLink().toTString(executionContext.context) as T
    }
}
