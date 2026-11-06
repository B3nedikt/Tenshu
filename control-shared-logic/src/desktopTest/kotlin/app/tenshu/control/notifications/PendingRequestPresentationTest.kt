package app.tenshu.control.notifications

import app.tenshu.error.ErrorCopyKey

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The verifiable half of NT-1. The iOS bridge row and the SwiftUI center cannot be compiled
 * anywhere but a Mac, so the decisions they depend on live in `commonMain` and are pinned
 * here: which variant maps to which kind, which field becomes the subject, which variant has
 * a SAS, and what the badge counts.
 */
class PendingRequestPresentationTest {

    private val grant = PendingRequest.MembershipGrant(
        grantorFingerprint = "1b4d6e0a",
        timestampMillis = 1_716_800_000_000L,
    )

    private val invite = PendingRequest.ContactInviteRedeemed(
        inviteId = "invite-1",
        claimedName = "Jo",
        claimedFingerprint = "9f2a7c1e",
        claimedSas = "482 913",
        timestampMillis = 1_717_000_000_000L,
    )

    @Test
    fun grant_carriesTheGrantorFingerprint_andNoVerificationCode() {
        val facts = pendingRequestFacts(grant)
        assertEquals(PendingRequestKind.MEMBERSHIP_GRANT, facts.kind)
        assertEquals("1b4d6e0a", facts.subject)
        // A grant's trust is the signed capability, not a read-aloud code. A card branches
        // on emptiness rather than rendering an empty code section.
        assertEquals("", facts.verificationCode)
        assertEquals("grant:1b4d6e0a", facts.id)
    }

    @Test
    fun invite_carriesTheClaimedName_andItsSas() {
        val facts = pendingRequestFacts(invite)
        assertEquals(PendingRequestKind.CONTACT_INVITE_REDEEMED, facts.kind)
        // The *claimed* name — quarantined until confirmed, which is why the SAS is beside it.
        assertEquals("Jo", facts.subject)
        assertEquals("482 913", facts.verificationCode)
        assertEquals("invite:invite-1", facts.id)
        // The RAW claim the code was rendered for — what accept hands back (T-CI-10).
        assertEquals("9f2a7c1e", facts.shownFingerprint)
        assertEquals("", pendingRequestFacts(grant).shownFingerprint)
    }

    @Test
    fun idsAreStable_soDismissalSurvivesARepoll() {
        // The dismissal marker is synced against this id (Phase 4). If it changed between
        // polls, a dismissed request would come back unread on the next sweep.
        assertEquals(pendingRequestFacts(grant).id, pendingRequestFacts(grant.copy()).id)
        assertEquals(pendingRequestFacts(invite).id, pendingRequestFacts(invite.copy()).id)
    }

    @Test
    fun badgeCountsUnseenOnly_notTheListLength() {
        val all = listOf(grant, invite)
        assertEquals(2, unreadRequestCount(all, emptySet()))
        assertEquals(1, unreadRequestCount(all, setOf(grant.id)))
        // Dismissed-but-unresolved: still listed in the center, no longer in the badge.
        assertEquals(0, unreadRequestCount(all, setOf(grant.id, invite.id)))
    }

    @Test
    fun mockRepository_dismissMarksSeen_withoutResolving() = runTest {
        val repo = MockPendingRequestsRepository()
        val target = repo.requests.value.first()

        repo.dismiss(target)

        assertTrue(repo.requests.value.any { it.id == target.id }, "a dismissed request stays listed")
        assertTrue(target.id in repo.seenIds.value)
        assertEquals(
            repo.requests.value.size - 1,
            repo.unread.first().size,
            "the badge drops it",
        )
    }

    @Test
    fun mockRepository_acceptResolves_andTakesTheSeenMarkerWithIt() = runTest {
        val repo = MockPendingRequestsRepository()
        val target = repo.requests.value.filterIsInstance<PendingRequest.MembershipGrant>().single()

        repo.dismiss(target)
        repo.acceptGrant(target)

        assertTrue(repo.requests.value.none { it.id == target.id }, "a resolved request leaves the feed")
        assertTrue(target.id !in repo.seenIds.value, "and does not leave a stale seen marker behind")
    }

    @Test
    fun mockRepository_failingMode_surfacesAMessage_andChangesNothing() = runTest {
        val repo = MockPendingRequestsRepository(failing = true)
        val before = repo.requests.value

        val result = repo.dismiss(before.first())

        assertTrue(result is RequestActionResult.Failure)
        assertEquals(ErrorCopyKey.UNEXPECTED, result.copyKey)
        assertEquals(before, repo.requests.value)
        assertTrue(repo.seenIds.value.isEmpty())
    }
}
