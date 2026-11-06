package app.tenshu.control.notifications

import org.tenshu.contact.ContactInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingRequestTest {

    @Test
    fun a_redeemed_invite_becomes_an_actionable_request() {
        val request = contactInviteRequestOrNull(
            status = ContactInvite.Status.REDEEMED,
            inviteId = "i1",
            claimedName = "Alice",
            claimedFingerprint = "fp-alice",
            claimedSas = "123456",
            createdAtMillis = 100L,
        )
        assertEquals(
            PendingRequest.ContactInviteRedeemed("i1", "Alice", "fp-alice", "123456", 100L),
            request,
        )
        assertEquals("invite:i1", request!!.id)
    }

    @Test
    fun non_redeemed_invites_are_not_requests() {
        for (status in listOf(ContactInvite.Status.PENDING, ContactInvite.Status.COMPLETED, ContactInvite.Status.EXPIRED)) {
            assertNull(
                contactInviteRequestOrNull(status, "i1", "Alice", "fp", "123456", 100L),
                "status $status should not be a pending request",
            )
        }
    }

    @Test
    fun a_grant_becomes_an_actionable_request() {
        val request = membershipGrantRequest(grantorFingerprint = "fp9", discoveredAtMillis = 50L)
        assertEquals(PendingRequest.MembershipGrant("fp9", 50L), request)
        assertEquals("grant:fp9", request.id)
    }

    @Test
    fun aggregate_merges_newest_first() {
        val grants = listOf(PendingRequest.MembershipGrant("fp", 10L))
        val invites = listOf(
            PendingRequest.ContactInviteRedeemed("i-old", "A", "fpA", "111111", 5L),
            PendingRequest.ContactInviteRedeemed("i-new", "B", "fpB", "222222", 30L),
        )
        val merged = aggregatePendingRequests(grants, invites)
        assertEquals(listOf("invite:i-new", "grant:fp", "invite:i-old"), merged.map { it.id })
    }

    @Test
    fun aggregate_dedups_by_id() {
        val a = PendingRequest.MembershipGrant("fp", 10L)
        val b = PendingRequest.MembershipGrant("fp", 20L) // same id, different timestamp
        val merged = aggregatePendingRequests(listOf(a), listOf(b))
        assertEquals(1, merged.size)
        assertTrue(merged.single().id == "grant:fp")
    }
}
