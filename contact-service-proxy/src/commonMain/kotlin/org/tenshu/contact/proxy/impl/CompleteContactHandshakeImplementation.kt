package org.tenshu.contact.proxy.impl

import kotlinx.coroutines.CancellationException
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.Person
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.common.INVITE_ACK_INFO
import org.tenshu.contact.common.InviteAckPayload
import org.tenshu.contact.common.InviteResponsePayload
import org.tenshu.contact.common.encode
import org.tenshu.contact.common.identityFingerprint
import org.tenshu.contact.proxy.ContactInviteLocalStore
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.ResponseVerification
import org.tenshu.core.common.util.deriveGrantSeed
import org.tenshu.contact.proxy.verifyInviteResponse
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.InvalidParameter
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.generateId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.clock
import org.tenshu.core.toTString

/**
 * Inviter-side completion (see `CompleteContactHandshake`). Acts on the stashed verified
 * response (sync-cycle pickup) or fetches + verifies on demand.
 */
internal class CompleteContactHandshakeImplementation(
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
        val inviteId: TString = params.read(CompleteContactHandshake.Params.INVITE_ID)
        val decision: TString = params.read(CompleteContactHandshake.Params.DECISION)
        // Required: the raw claimed fingerprint the prompt displayed.
        // "" is meaningful only on REJECT; the ACCEPT rule below refuses it (threat model T-CI-10).
        val clientShown: String =
            params.read<TString>(CompleteContactHandshake.Params.SHOWN_FINGERPRINT).get()
        val personId = (params?.get(CompleteContactHandshake.Params.PERSON_ID) as? TString)?.get() ?: ""
        val newPersonName =
            (params?.get(CompleteContactHandshake.Params.NEW_PERSON_NAME) as? TString)?.get() ?: ""
        val selfAssertedName =
            (params?.get(CompleteContactHandshake.Params.SELF_ASSERTED_NAME) as? TString)?.get() ?: ""
        val sasConfirmed =
            (params?.get(CompleteContactHandshake.Params.SAS_CONFIRMED) as? TBoolean)?.get() ?: false

        fun fail(message: String): Nothing =
            throw TenshuException(GenericError.create(executionContext.context, message))

        val inviteStore = ContactInviteLocalStore(executionContext)
        val row = inviteStore.get(inviteId.get())
            ?: fail("Unknown invite '${inviteId.get()}'.")
        if (row.status != ContactInvite.Status.PENDING && row.status != ContactInvite.Status.REDEEMED) {
            fail("Invite is ${row.status} — nothing to complete.")
        }
        val now = executionContext.context.clock().nowEpochMillis()
        if (now >= row.expiresAtMs) {
            inviteStore.updateStatus(row, ContactInvite.Status.EXPIRED)
            fail("Invite expired.")
        }

        val keyPair = components.identityKeyPair()
            ?: fail("Identity key (A13) unavailable — unlock this device to complete the handshake.")

        val payload = verifiedResponse(row, keyPair, inviteStore)
            ?: fail("Invite is COMPROMISED — the response failed the link-secret check.")

        return when (decision.get()) {
            CompleteContactHandshake.Decision.ACCEPT -> {
                // ⚠⚠ The key accepted is the key the prompt SHOWED (threat model T-CI-10).
                //
                // `payload` may come from a later relay fetch than the one the prompt's code was
                // derived from (the stash is process memory — empty after a restart and on every
                // other inviter device), and the row's claim is a synced last-writer-wins value
                // that a second inviter device's write can replace by merge under an open
                // prompt. Two bindings, both required:
                //  1. the response must be the one the ROW names — a later response under
                //     another key never becomes "the" response;
                //  2. it must be the one the PROMPT displayed — [clientShown], sent by the
                //     caller. This is what closes the merge flip: the row can
                //     move under the screen, the caller's statement of what it showed cannot.
                // A response failing either is refused before any row is written or any ack
                // sealed, and is dropped from the stash so the next attempt asks again.
                //
                // ⚠⚠ Binding 2 is UNCONDITIONAL: an ACCEPT that names no shown key ("") is
                // refused, never read as "nothing to compare". Skipping the comparison on empty
                // would let a prompt that had not loaded its claim yet — or any caller that
                // omitted it — store whatever response the relay served (T-CI-10).
                val answering = identityFingerprint(payload.identityPublicKey)
                val rowClaim = row.claimedFingerprint
                // A confirmation needs a code to have been on screen: the caller showed none, or
                // the row carries no claim a code could have been derived from — either way
                // `sasConfirmed` asserts a comparison nobody could have made. Checked first so
                // that refusal keeps its own, more precise type.
                if (sasConfirmed && (clientShown.isEmpty() || rowClaim.isEmpty())) {
                    throw TenshuException(
                        InvalidParameter.create(
                            context = executionContext.context,
                            name = CompleteContactHandshake.Params.SAS_CONFIRMED,
                            reason = InvalidParameter.Reasons.NOT_READY,
                        )
                    )
                }
                if ((rowClaim.isNotEmpty() && rowClaim != answering) || clientShown != answering) {
                    components.responseStash.remove(row.inviteId)
                    // Typed, not a sentence: the app owns the words ("decline this invite and
                    // send a new one"), and can only choose them if it can tell this refusal
                    // from any other. See `CompleteContactHandshake.Refusal`.
                    throw TenshuException(
                        InvalidParameter.create(
                            context = executionContext.context,
                            name = CompleteContactHandshake.Params.INVITE_ID,
                            reason = InvalidParameter.Reasons.ALREADY_CONSUMED,
                        )
                    )
                }
                val fingerprint = acceptHandshake(
                    executionContext = executionContext,
                    row = row,
                    payload = payload,
                    keyPair = keyPair,
                    existingPersonId = personId,
                    newPersonName = newPersonName,
                    selfAssertedName = selfAssertedName,
                    sasConfirmed = sasConfirmed,
                    inviteStore = inviteStore,
                )
                fingerprint.toTString(executionContext.context) as T
            }

            CompleteContactHandshake.Decision.REJECT -> {
                postAck(
                    row = row,
                    payload = payload,
                    status = InviteAckPayload.STATUS_REJECTED,
                    ownPublicKey = keyPair.publicKey,
                    selfAssertedName = "",
                    includeIntent = false,
                )
                inviteStore.updateStatus(row, ContactInvite.Status.REJECTED)
                components.responseStash.remove(row.inviteId)
                "".toTString(executionContext.context) as T
            }

            else -> fail("Unknown decision '${decision.get()}'.")
        }
    }

    /**
     * Stashed payload from the sync-cycle pickup, or an on-demand fetch +
     * verify. A failed check burns the invite here exactly as the pickup
     * would (`COMPROMISED`; the auto-reject is the pickup's job on its next
     * pass — completion only refuses).
     */
    private suspend fun verifiedResponse(
        row: ContactInviteLocalStore.InviteRow,
        keyPair: SyncCryptoManager.KeyPair,
        inviteStore: ContactInviteLocalStore,
    ): InviteResponsePayload? {
        components.responseStash.get(row.inviteId)?.let { return it }

        val sealed = components.relayClient.pendingResponses()
            .firstOrNull { it.inviteId == row.inviteId }
            ?.sealedResponse
            ?: return null
        return when (
            val verification = verifyInviteResponse(
                cryptoManager = components.cryptoManager,
                identityPrivateKey = keyPair.privateKey,
                inviteIdHex = row.inviteId,
                secretHash = row.secretHash,
                sealedResponse = sealed,
            )
        ) {
            is ResponseVerification.Ok -> {
                components.responseStash.put(row.inviteId, verification.payload)
                verification.payload
            }

            is ResponseVerification.BadSecret, ResponseVerification.Garbage -> {
                inviteStore.updateStatus(row, ContactInvite.Status.COMPROMISED)
                null
            }
        }
    }

    private suspend fun acceptHandshake(
        executionContext: ExecutionContext,
        row: ContactInviteLocalStore.InviteRow,
        payload: InviteResponsePayload,
        keyPair: SyncCryptoManager.KeyPair,
        existingPersonId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
        inviteStore: ContactInviteLocalStore,
    ): String {
        val now = executionContext.context.clock().nowEpochMillis()

        // Link-to-existing re-points personId only — trust never transfers by
        // linking (contact doc §6.3); a fresh thin Person otherwise.
        val targetPersonId = existingPersonId.ifEmpty {
            val person = Person.create(
                context = executionContext.context,
                personId = generateId(),
                // The proxy emits no display copy (ADR 0016): an empty name
                // stays empty and the UI substitutes its localised "unnamed".
                displayName = newPersonName.ifEmpty { payload.selfAssertedName },
            )
            executionContext.saveData(person)
            person.personId.get()
        }

        val fingerprint = identityFingerprint(payload.identityPublicKey)
        executionContext.saveData(
            TenshuIdentity.create(
                context = executionContext.context,
                fingerprint = fingerprint,
                publicKey = payload.identityPublicKey,
                personId = targetPersonId,
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
                // Persist the grant seed while K_link is in hand, so the grant mailbox address
                // stays secret-derivable post-handshake (`shared-realm-membership-grant.md` D8).
                grantSeed = deriveGrantSeed(payload.inviteSecret),
            )
        )

        postAck(
            row = row,
            payload = payload,
            status = InviteAckPayload.STATUS_ACCEPTED,
            ownPublicKey = keyPair.publicKey,
            selfAssertedName = selfAssertedName,
            includeIntent = true,
        )

        inviteStore.updateStatus(row, ContactInvite.Status.COMPLETED, resultFingerprint = fingerprint)
        components.responseStash.remove(row.inviteId)
        return fingerprint
    }

    private suspend fun postAck(
        row: ContactInviteLocalStore.InviteRow,
        payload: InviteResponsePayload,
        status: String,
        ownPublicKey: ByteArray,
        selfAssertedName: String,
        includeIntent: Boolean,
    ) {
        components.relayClient.postAck(
            inviteId = row.inviteId,
            sealedAck = components.cryptoManager.sealToIdentity(
                plaintext = InviteAckPayload(
                    status = status,
                    inviteSecret = payload.inviteSecret,
                    identityPublicKey = ownPublicKey,
                    selfAssertedName = selfAssertedName,
                    intentKind = if (includeIntent) row.intentKind else "",
                    intentLabel = if (includeIntent) row.intentLabel else "",
                ).encode(),
                recipientEncPublicKey = payload.identityPublicKey,
                info = INVITE_ACK_INFO,
                aad = row.inviteId.hexToByteArray(),
            ),
        )
    }
}
