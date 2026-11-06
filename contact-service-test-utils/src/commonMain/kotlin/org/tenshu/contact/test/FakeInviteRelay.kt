package org.tenshu.contact.test

import org.tenshu.contact.common.InviteConflictException
import org.tenshu.contact.common.InviteGoneException
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.common.InviteUnknownException

/**
 * In-memory [InviteRelayClient] mimicking the relay's `/sync/invite/…`
 * single-slot state machine (`InviteRouting` + `InviteStore`) for in-process
 * round-trip tests: one shared instance plays the relay for both the inviter
 * and the invitee proxy. Single-owner shape — `pendingResponses()` returns
 * every not-yet-acked response (the routing test covers per-owner scoping).
 */
class FakeInviteRelay : InviteRelayClient {

    private class Mailbox(
        var expiresAtMillis: Long,
        var state: String = "OPEN",
        var sealedResponse: ByteArray? = null,
        var sealedAck: ByteArray? = null,
    )

    private val mailboxes = mutableMapOf<String, Mailbox>()

    fun recordCount(): Int = mailboxes.size

    override suspend fun registerInvite(inviteId: String, expiresAtMillis: Long): Long {
        if (mailboxes.containsKey(inviteId)) throw InviteConflictException("duplicate $inviteId")
        mailboxes[inviteId] = Mailbox(expiresAtMillis = expiresAtMillis)
        return expiresAtMillis
    }

    override suspend fun revokeInvite(inviteId: String) {
        mailboxes[inviteId]?.let {
            it.state = "REVOKED"
            it.sealedResponse = null
            it.sealedAck = null
        }
    }

    override suspend fun postResponse(inviteId: String, sealedResponse: ByteArray) {
        val mailbox = mailboxes[inviteId] ?: throw InviteUnknownException("unknown $inviteId")
        when (mailbox.state) {
            "REVOKED" -> throw InviteGoneException("revoked $inviteId")
            "OPEN" -> {
                mailbox.state = "RESPONDED"
                mailbox.sealedResponse = sealedResponse
            }

            else -> throw InviteConflictException("slot taken for $inviteId")
        }
    }

    override suspend fun pendingResponses(): List<InviteRelayClient.PendingResponse> =
        mailboxes.entries
            .filter { it.value.state == "RESPONDED" }
            .mapNotNull { (id, mailbox) ->
                mailbox.sealedResponse?.let { InviteRelayClient.PendingResponse(id, it) }
            }

    override suspend fun postAck(inviteId: String, sealedAck: ByteArray) {
        val mailbox = mailboxes[inviteId] ?: throw InviteUnknownException("unknown $inviteId")
        if (mailbox.state != "RESPONDED") throw InviteConflictException("no pending response")
        mailbox.state = "ACKED"
        mailbox.sealedAck = sealedAck
    }

    override suspend fun fetchAck(inviteId: String): InviteRelayClient.AckPoll {
        val mailbox = mailboxes[inviteId] ?: return InviteRelayClient.AckPoll.NotYet
        mailbox.sealedAck?.let { return InviteRelayClient.AckPoll.Found(it) }
        if (mailbox.state == "REVOKED") return InviteRelayClient.AckPoll.Gone
        return InviteRelayClient.AckPoll.NotYet
    }

    override suspend fun done(inviteId: String) {
        mailboxes.remove(inviteId)
    }
}
