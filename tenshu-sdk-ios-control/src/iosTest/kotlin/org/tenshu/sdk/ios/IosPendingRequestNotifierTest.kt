package org.tenshu.sdk.ios

import app.tenshu.control.notifications.MockPendingRequestsRepository
import app.tenshu.control.notifications.PendingRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NT-2b: pins the property of the notification copy that actually matters — **what is absent
 * from it**.
 *
 * ⚠⚠ A lock-screen notification is rendered before any authentication, so it is the one surface
 * where an unverified string an attacker chose would be shown to the user with the app's
 * authority. A redeemed contact invite carries a `claimedName` that is **quarantined until the
 * SAS is confirmed** — that quarantine is the entire point of the invite ceremony, and putting
 * the name in a banner would undo it. A membership grant carries the grantor's fingerprint,
 * which is not secret but is linkable.
 *
 * Android and the macOS tray both carry this constraint in comments. This turns it into a test,
 * because it is a rule that reads as an aesthetic choice ("keep the copy short") right up until
 * someone helpfully personalises it.
 *
 * ⚠ Not covered here: that the banner actually renders. That needs granted authorization, which
 * needs a human tap — the simulator has no API for it. Verified as far as the prompt (see the PR).
 */
class IosPendingRequestNotifierTest {

    private val notifier = IosPendingRequestNotifier()

    @Test
    fun titles_never_carry_request_specific_detail() {
        val samples = MockPendingRequestsRepository().requests.value
        assertTrue(samples.size >= 2, "sample should cover both leaves, was $samples")

        // Everything the sample requests carry that a helpful rewrite might reach for.
        val forbidden = buildList {
            samples.forEach { request ->
                when (request) {
                    is PendingRequest.ContactInviteRedeemed -> {
                        add(request.claimedName)
                        add(request.claimedFingerprint)
                        add(request.claimedSas)
                    }

                    is PendingRequest.MembershipGrant -> add(request.grantorFingerprint)
                    else -> Unit
                }
            }
        }.filter { it.isNotBlank() }

        samples.forEach { request ->
            val title = notifier.title(request)
            assertTrue(title.isNotBlank(), "every leaf needs a title")
            forbidden.forEach { secret ->
                assertFalse(
                    title.contains(secret, ignoreCase = true),
                    "notification title leaked request detail: '$secret' appeared in \"$title\"",
                )
            }
        }
    }

    @Test
    fun the_two_leaves_are_distinguishable() {
        // Generic must not collapse into identical: the user should be able to tell a folder
        // share from a contact invite without opening the app, which is the one bit of
        // information the kind legitimately conveys.
        val titles = MockPendingRequestsRepository().requests.value
            .map(notifier::title)
            .toSet()
        assertTrue(titles.size >= 2, "both kinds rendered the same title: $titles")
    }
}
