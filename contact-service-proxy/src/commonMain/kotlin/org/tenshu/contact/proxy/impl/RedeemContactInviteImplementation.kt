package org.tenshu.contact.proxy.impl

import org.tenshu.contact.RedeemContactInvite
import org.tenshu.contact.common.InviteConflictException
import org.tenshu.contact.common.InviteGoneException
import org.tenshu.contact.common.InviteResponsePayload
import org.tenshu.contact.common.InviteUnknownException
import org.tenshu.contact.common.INVITE_RESPONSE_INFO
import org.tenshu.contact.common.encode
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.InvalidParameter
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.time.clock
import org.tenshu.core.toTString
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.PendingRedemptionStore

/**
 * §4 step 2 — invitee-side redemption. Parses the link (either §3 encoding),
 * seals the `InviteResponsePayload` to the link-pinned inviter A13
 * (`inviteId` in the AAD), posts it anonymously, and persists the
 * device-local [PendingRedemptionStore.PendingRedemption] the ack wait
 * resumes from.
 */
internal class RedeemContactInviteImplementation(
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
        val linkPayload: TString = params.read(RedeemContactInvite.Params.LINK_PAYLOAD)
        val selfAssertedName: TString = params.read(RedeemContactInvite.Params.SELF_ASSERTED_NAME)

        val link = try {
            InviteLinkPayload.parse(linkPayload.get())
        } catch (t: IllegalArgumentException) {
            throw TenshuException(
                GenericError.create(executionContext.context, "Not a valid Tenshu invite link.")
            )
        } catch (t: RuntimeException) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    t.message ?: "Could not read the invite link.",
                )
            )
        }

        val keyPair = components.identityKeyPair()
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Identity key (A13) unavailable — unlock this device to accept an invite.",
                )
            )

        val sealed = components.cryptoManager.sealToIdentity(
            plaintext = InviteResponsePayload(
                inviteSecret = link.inviteSecret,
                identityPublicKey = keyPair.publicKey,
                selfAssertedName = selfAssertedName.get(),
            ).encode(),
            recipientEncPublicKey = link.inviterPublicKey,
            info = INVITE_RESPONSE_INFO,
            aad = link.inviteId,
        )

        // The relay's refusals map to the typed reasons `RedeemContactInvite` documents.
        try {
            components.relayClient.postResponse(link.inviteIdHex, sealed)
        } catch (e: InviteConflictException) {
            throw TenshuException(
                InvalidParameter.create(
                    context = executionContext.context,
                    name = RedeemContactInvite.Params.LINK_PAYLOAD,
                    reason = InvalidParameter.Reasons.ALREADY_CONSUMED,
                )
            )
        } catch (e: InviteGoneException) {
            throw TenshuException(
                InvalidParameter.create(
                    context = executionContext.context,
                    name = RedeemContactInvite.Params.LINK_PAYLOAD,
                    reason = InvalidParameter.Reasons.GONE,
                )
            )
        } catch (e: InviteUnknownException) {
            throw TenshuException(
                InvalidParameter.create(
                    context = executionContext.context,
                    name = RedeemContactInvite.Params.LINK_PAYLOAD,
                    reason = InvalidParameter.Reasons.GONE,
                )
            )
        }

        components.redemptionStore.save(
            PendingRedemptionStore.PendingRedemption(
                inviteId = link.inviteIdHex,
                inviteSecret = link.inviteSecret,
                peerA13PublicKey = link.inviterPublicKey,
                redeemedAtMs = executionContext.context.clock().nowEpochMillis(),
            )
        )

        return link.inviteIdHex.toTString(executionContext.context) as T
    }
}
