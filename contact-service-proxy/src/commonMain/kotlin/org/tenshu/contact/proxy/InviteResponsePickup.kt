package org.tenshu.contact.proxy

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.common.INVITE_ACK_INFO
import org.tenshu.contact.common.InviteAckPayload
import org.tenshu.contact.common.encode
import org.tenshu.contact.common.identityFingerprint
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.util.deriveContactSas
import org.tenshu.core.time.clock

/**
 * The inviter-side response-arrival check (`contact-invitation-handshake.md`
 * §4 step 4 / §12 "Modified"): rides the proxy sync cycle via
 * `SyncScheduler.addSyncCycleHook`, gated on locally-`PENDING`/`REDEEMED`
 * [ContactInvite] rows so steady-state sync traffic is unchanged — no live
 * invites, no relay call.
 *
 * For each pending sealed response:
 *  - **verified** → stash the payload for `CompleteContactHandshake` and flip
 *    the row to `REDEEMED` (syncs to all devices; the completion prompt is
 *    the UI's reaction to that state),
 *  - **bad secret** → auto-reject: sealed `rejected` ack to the responder's
 *    claimed key + burn the invite (`COMPROMISED`),
 *  - **garbage** → burn (`COMPROMISED`); nothing readable to reject to,
 *  - locally expired rows are flipped to `EXPIRED` and skipped.
 *
 * This check is the one headless-safe piece of the flow (I9) — it only moves
 * state; every user decision stays in the interactive ops.
 */
class InviteResponsePickup(
    private val executionContext: ExecutionContext,
    private val components: ContactProxyComponents,
) {

    private val inviteStore = ContactInviteLocalStore(executionContext)

    suspend fun checkOnce() {
        val live = inviteStore.all().filter {
            it.status == ContactInvite.Status.PENDING || it.status == ContactInvite.Status.REDEEMED
        }
        if (live.isEmpty()) return
        val keyPair = components.identityKeyPair() ?: return

        val now = executionContext.context.clock().nowEpochMillis()
        val unexpired = live.filter { row ->
            val expired = now >= row.expiresAtMs
            if (expired && row.status == ContactInvite.Status.PENDING) {
                inviteStore.updateStatus(row, ContactInvite.Status.EXPIRED)
            }
            !expired
        }
        if (unexpired.isEmpty()) return

        // Transport failures are non-events: the next cycle retries.
        val pending = try {
            components.relayClient.pendingResponses()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return
        }

        // ⚠⚠ `unexpired` is a snapshot taken BEFORE this loop, and nothing below refreshes it. A
        // rule of the form "the first response for an invite wins" therefore cannot be read off
        // `row` alone: two responses for one invite in a single relay answer would both see the
        // pre-loop row (no claim yet, not burned yet), and the second would overwrite what the
        // first settled. What this pass has already decided per invite is carried here instead.
        val claimedThisPass = mutableMapOf<String, String>()
        val burnedThisPass = mutableSetOf<String>()

        for (response in pending) {
            val row = unexpired.firstOrNull { it.inviteId == response.inviteId } ?: continue
            // A burned invite stays burned: a later entry in the same answer must not revive it
            // by writing REDEEMED over the stale row.
            if (row.inviteId in burnedThisPass) continue
            when (
                val verification = verifyInviteResponse(
                    cryptoManager = components.cryptoManager,
                    identityPrivateKey = keyPair.privateKey,
                    inviteIdHex = row.inviteId,
                    secretHash = row.secretHash,
                    sealedResponse = response.sealedResponse,
                )
            ) {
                is ResponseVerification.Ok -> {
                    val claimedFingerprint =
                        identityFingerprint(verification.payload.identityPublicKey)
                    // ⚠⚠ First claim wins (threat model T-CI-10). The completion prompt
                    // shows this row's claimed fingerprint + code, and the user's "codes
                    // match" is a statement about THAT key. The relay promises a single
                    // response slot, so a second response under a different key means it
                    // broke the promise — and a pickup that re-pointed the claim (and the
                    // stash completion reads) would leave a tick made against code X
                    // standing over key Y. The lock must not depend on the thing it guards
                    // against: the later response is neither stashed nor shown.
                    //
                    // The standing claim is the one an EARLIER entry of this same relay answer
                    // set, if any — only then the row's (see `claimedThisPass`).
                    val standingClaim = claimedThisPass[row.inviteId] ?: row.claimedFingerprint
                    if (standingClaim.isNotEmpty() && standingClaim != claimedFingerprint) {
                        println(
                            "InviteResponsePickup -> ignoring a second response for invite " +
                                "${row.inviteId}: it claims a different key than the one " +
                                "already shown for this invite"
                        )
                        continue
                    }
                    claimedThisPass[row.inviteId] = claimedFingerprint
                    components.responseStash.put(row.inviteId, verification.payload)
                    // The claim triple rides the (synced) REDEEMED flip so any
                    // inviter device renders the completion prompt's header +
                    // SAS panel without a relay round trip
                    // (drive-manage-access-ux.md §2.3/§5.2); strictly a claim
                    // — quarantine rules apply. The SAS is derived here
                    // because the raw keys never cross the client boundary.
                    if (row.status == ContactInvite.Status.PENDING ||
                        row.claimedFingerprint != claimedFingerprint
                    ) {
                        inviteStore.updateStatus(
                            row = row,
                            status = ContactInvite.Status.REDEEMED,
                            claimedName = verification.payload.selfAssertedName,
                            claimedFingerprint = claimedFingerprint,
                            claimedSas = deriveContactSas(
                                keyPair.publicKey,
                                verification.payload.identityPublicKey,
                            ),
                        )
                    }
                }

                is ResponseVerification.BadSecret -> {
                    // Burn + auto-reject (§4 step 3): a wrong-secret response is
                    // evidence the channel leaked; the sealed reject tells the
                    // (possibly attacking) responder nothing it can use.
                    rejectQuietly(
                        row = row,
                        responderPublicKey = verification.payload.identityPublicKey,
                        echoedSecret = verification.payload.inviteSecret,
                        ownPublicKey = keyPair.publicKey,
                    )
                    burn(row, burnedThisPass)
                }

                ResponseVerification.Garbage -> burn(row, burnedThisPass)
            }
        }
    }

    /**
     * Mark [row]'s invite `COMPROMISED` and drop anything this device stashed for it, so the row
     * and the stash agree that there is nothing left to complete — including a response an
     * earlier entry of the same relay answer had just stashed.
     */
    private fun burn(row: ContactInviteLocalStore.InviteRow, burnedThisPass: MutableSet<String>) {
        burnedThisPass += row.inviteId
        components.responseStash.remove(row.inviteId)
        inviteStore.updateStatus(row, ContactInvite.Status.COMPROMISED)
    }

    private suspend fun rejectQuietly(
        row: ContactInviteLocalStore.InviteRow,
        responderPublicKey: ByteArray,
        echoedSecret: ByteArray,
        ownPublicKey: ByteArray,
    ) {
        // Echoes back the secret the responder itself sent (we never saw the
        // real K_link) — a courtesy reject to an honest-but-broken client; an
        // attacker learns only that the invite is burned.
        try {
            components.relayClient.postAck(
                inviteId = row.inviteId,
                sealedAck = components.cryptoManager.sealToIdentity(
                    plaintext = InviteAckPayload(
                        status = InviteAckPayload.STATUS_REJECTED,
                        inviteSecret = echoedSecret,
                        identityPublicKey = ownPublicKey,
                    ).encode(),
                    recipientEncPublicKey = responderPublicKey,
                    info = INVITE_ACK_INFO,
                    aad = row.inviteId.hexToByteArray(),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Burning locally is the load-bearing part; the reject is best-effort.
        }
    }
}
