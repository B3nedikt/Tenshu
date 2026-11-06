package org.tenshu.contact.proxy.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.Person
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.common.INVITE_ACK_INFO
import org.tenshu.contact.common.InviteAckPayload
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.common.decodeInviteAckPayload
import org.tenshu.contact.common.identityFingerprint
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.PendingRedemptionStore
import org.tenshu.core.Data
import org.tenshu.core.common.util.deriveGrantSeed
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.deriveContactSas
import org.tenshu.core.common.util.generateId
import org.tenshu.core.time.clock

/**
 * §4 step 5 — the invitee's mutual close, as a cancellable Layer-2 task (the
 * `AwaitPairing` shape). Slow-polls `GET /sync/invite/ack`; on arrival,
 * unseals with the invitee's own A13 private key and runs the two end-to-end
 * checks: the echoed `K_link` must equal the redeemed link's, and the
 * echoed inviter key must equal the **link-pinned** one — together closing
 * relay-side ack forgery (T-CI-03).
 *
 * On `accepted` it also clears the [PendingRedemptionStore] entry; on a budget
 * elapse the redemption stays, so the next session resumes (see `AwaitInviteAck`).
 */
internal class AwaitInviteAckImplementation(
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
        val inviteId: TString = params.read(AwaitInviteAck.Params.INVITE_ID)
        val sasConfirmed =
            (params?.get(AwaitInviteAck.Params.SAS_CONFIRMED) as? TBoolean)?.get() ?: false

        fun result(value: InviteAckResult): T = value as T
        fun status(value: String): T =
            result(InviteAckResult.create(executionContext.context, status = value))

        val redemption = components.redemptionStore.get(inviteId.get())
            ?: return status(AwaitInviteAck.Result.GONE)

        var attempts = 0
        while (attempts < components.ackPollMaxAttempts) {
            when (val poll = components.relayClient.fetchAck(inviteId.get())) {
                is InviteRelayClient.AckPoll.Found ->
                    return result(
                        handleAck(executionContext, redemption, poll.sealedAck, sasConfirmed)
                    )

                InviteRelayClient.AckPoll.Gone -> {
                    components.redemptionStore.remove(inviteId.get())
                    return status(AwaitInviteAck.Result.GONE)
                }

                InviteRelayClient.AckPoll.NotYet -> Unit
            }
            delay(components.ackPollIntervalMs)
            attempts++
        }

        // Budget elapsed — the redemption stays for a next-session resume.
        return status(AwaitInviteAck.Result.PENDING)
    }

    private suspend fun handleAck(
        executionContext: ExecutionContext,
        redemption: PendingRedemptionStore.PendingRedemption,
        sealedAck: ByteArray,
        sasConfirmed: Boolean,
    ): InviteAckResult {
        fun status(value: String): InviteAckResult =
            InviteAckResult.create(executionContext.context, status = value)

        val keyPair = components.identityKeyPair()
            ?: return status(AwaitInviteAck.Result.PENDING) // locked — retry next session

        val payload = try {
            decodeInviteAckPayload(
                components.cryptoManager.unsealFromIdentity(
                    sealed = sealedAck,
                    myEncPrivateKey = keyPair.privateKey,
                    info = INVITE_ACK_INFO,
                    aad = redemption.inviteId.hexToByteArray(),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return status(AwaitInviteAck.Result.INVALID)
        }

        // The two §4-step-5 checks: correct K_link AND the echoed inviter key
        // equals the link-pinned one.
        val valid = payload.inviteSecret.contentEquals(redemption.inviteSecret) &&
            payload.identityPublicKey.contentEquals(redemption.peerA13PublicKey)
        if (!valid) return status(AwaitInviteAck.Result.INVALID)

        return when (payload.status) {
            InviteAckPayload.STATUS_ACCEPTED -> {
                createInviterRecords(executionContext, redemption, payload, sasConfirmed)
                closeOut(redemption.inviteId)
                InviteAckResult.create(
                    context = executionContext.context,
                    status = AwaitInviteAck.Result.ACCEPTED,
                    peerName = payload.selfAssertedName,
                    peerFingerprint = identityFingerprint(redemption.peerA13PublicKey),
                    intentKind = payload.intentKind,
                    intentLabel = payload.intentLabel,
                    sas = deriveContactSas(keyPair.publicKey, redemption.peerA13PublicKey),
                )
            }

            InviteAckPayload.STATUS_REJECTED -> {
                // Terminal declined — no records created (§4 step 5).
                closeOut(redemption.inviteId)
                status(AwaitInviteAck.Result.REJECTED)
            }

            else -> status(AwaitInviteAck.Result.INVALID)
        }
    }

    private fun createInviterRecords(
        executionContext: ExecutionContext,
        redemption: PendingRedemptionStore.PendingRedemption,
        payload: InviteAckPayload,
        sasConfirmed: Boolean,
    ) {
        val now = executionContext.context.clock().nowEpochMillis()
        // The proxy emits no display copy (ADR 0016): an empty self-asserted
        // name stays empty and the UI substitutes its localised "unnamed".
        val person = Person.create(
            context = executionContext.context,
            personId = generateId(),
            displayName = payload.selfAssertedName,
        )
        val identity = TenshuIdentity.create(
            context = executionContext.context,
            fingerprint = identityFingerprint(redemption.peerA13PublicKey),
            publicKey = redemption.peerA13PublicKey,
            personId = person.personId.get(),
            selfAssertedName = payload.selfAssertedName,
            verification = if (sasConfirmed) {
                TenshuIdentity.Verification.VERIFIED
            } else {
                TenshuIdentity.Verification.UNVERIFIED
            },
            verifiedAt = if (sasConfirmed) now else 0L,
            source = if (sasConfirmed) {
                TenshuIdentity.Source.QR_SAS
            } else {
                TenshuIdentity.Source.INVITE_LINK
            },
            // Persist the grant seed while the redeemed K_link is in hand (`shared-realm-membership-grant.md` D8).
            grantSeed = deriveGrantSeed(redemption.inviteSecret),
        )
        executionContext.saveData(person)
        executionContext.saveData(identity)
    }

    private suspend fun closeOut(inviteId: String) {
        // `done` frees relay state; TTL GC is the fallback if it fails.
        try {
            components.relayClient.done(inviteId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Best-effort — the relay's retention sweep cleans up.
        }
        components.redemptionStore.remove(inviteId)
    }
}
