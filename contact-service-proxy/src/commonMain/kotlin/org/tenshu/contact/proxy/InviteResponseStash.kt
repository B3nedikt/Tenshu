package org.tenshu.contact.proxy

import org.tenshu.contact.common.InviteResponsePayload

/**
 * Device-local, in-memory cache of **verified** invite responses, keyed by
 * `inviteId` — filled by [InviteResponsePickup] on the sync cycle, read by
 * `CompleteContactHandshakeImplementation` so the completion prompt acts on
 * the already-unsealed payload without a relay round trip.
 *
 * Deliberately ephemeral: the relay keeps a posted response until it is
 * acked (`contact-invitation-handshake.md` §6 retention), so a process
 * restart simply re-fetches on the next cycle. Nothing here needs to
 * survive — and the invitee's key material shouldn't sit at rest twice.
 */
class InviteResponseStash {

    private val verified = mutableMapOf<String, InviteResponsePayload>()

    fun put(inviteId: String, payload: InviteResponsePayload) {
        verified[inviteId] = payload
    }

    fun get(inviteId: String): InviteResponsePayload? = verified[inviteId]

    fun remove(inviteId: String) {
        verified.remove(inviteId)
    }
}
