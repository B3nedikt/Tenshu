package app.tenshu.control.ui.screens.notifications

import app.tenshu.error.ErrorCopyKey

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.ui.theme.TenshuTheme
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NotificationCenterScreenTest {

    private val grant = PendingRequest.MembershipGrant(grantorFingerprint = "fingerprintABCDEF", timestampMillis = 20L)
    private val invite = PendingRequest.ContactInviteRedeemed(
        inviteId = "i1",
        claimedName = "Alice",
        claimedFingerprint = "fp-alice",
        claimedSas = "482913",
        timestampMillis = 10L,
    )

    @Test
    fun shows_the_empty_state_when_there_are_no_requests() = runComposeUiTest {
        setContent {
            TenshuTheme {
                NotificationCenterContent(requests = emptyList(), onBack = {})
            }
        }
        onNodeWithText("You are all caught up").assertIsDisplayed()
    }

    @Test
    fun lists_grant_and_invite_requests() = runComposeUiTest {
        setContent {
            TenshuTheme {
                NotificationCenterContent(requests = listOf(grant, invite), onBack = {})
            }
        }
        onNodeWithText("Someone wants to share a folder with you").assertIsDisplayed()
        onNodeWithText("A contact accepted your invite").assertIsDisplayed()
    }

    @Test
    fun accept_on_a_grant_card_fires_the_accept_action() = runComposeUiTest {
        var accepted: PendingRequest.MembershipGrant? = null
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = grant,
                    actionState = RequestActionUiState(),
                    actions = PendingRequestActions(onAcceptGrant = { accepted = it }),
                )
            }
        }
        onNodeWithText("Accept").performClick()
        assertEquals(grant, accepted)
    }

    @Test
    fun confirm_on_an_invite_card_passes_the_petname_and_codes_match_flag() = runComposeUiTest {
        var confirmedPetname: String? = null
        var confirmedSas: Boolean? = null
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = invite,
                    actionState = RequestActionUiState(),
                    actions = PendingRequestActions(
                        onConfirmInvite = { _, petname, sas -> confirmedPetname = petname; confirmedSas = sas },
                    ),
                )
            }
        }
        // The §5.3 verification code surfaces so the user can compare it out-of-band.
        onNodeWithText("482913").assertIsDisplayed()
        // Petname defaults to the claimed name; tick "codes match" then confirm.
        onNodeWithText("The codes match").performClick()
        onNodeWithText("Confirm").performClick()
        assertEquals("Alice", confirmedPetname)
        assertEquals(true, confirmedSas)
    }

    @Test
    fun an_in_flight_action_disables_the_card_buttons() = runComposeUiTest {
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = grant,
                    actionState = RequestActionUiState(inProgress = true),
                    actions = PendingRequestActions(),
                )
            }
        }
        onNodeWithText("Accept").assertIsNotEnabled()
    }

    @Test
    fun a_failure_message_renders_on_the_card() = runComposeUiTest {
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = grant,
                    actionState = RequestActionUiState(error = ErrorCopyKey.UNEXPECTED),
                    actions = PendingRequestActions(),
                )
            }
        }
        // The card now renders the shared catalogue's sentence for the key, not a
        // proxy-authored message (`core-error-copy-catalogue.md` §5.0).
        onNodeWithText("Something went wrong. Please try again.").assertIsDisplayed()
    }

    @Test
    fun the_dismiss_affordance_fires_the_dismiss_action() = runComposeUiTest {
        var dismissed: PendingRequest? = null
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = grant,
                    actionState = RequestActionUiState(),
                    actions = PendingRequestActions(onDismiss = { dismissed = it }),
                )
            }
        }
        onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(grant, dismissed)
    }

    @Test
    fun an_already_seen_request_hides_the_dismiss_affordance_but_stays_listed() = runComposeUiTest {
        // Mark-seen semantics: a dismissed request still renders in the center (so it
        // can be acted on later) — it just loses the "×" since re-dismissing is a no-op.
        setContent {
            TenshuTheme {
                NotificationCenterContent(
                    requests = listOf(grant),
                    onBack = {},
                    seenIds = setOf(grant.id),
                )
            }
        }
        onNodeWithText("Someone wants to share a folder with you").assertIsDisplayed()
        onAllNodesWithContentDescription("Dismiss").assertCountEquals(0)
    }

    @Test
    fun pinned_requests_shows_at_most_the_two_newest() = runComposeUiTest {
        val older = PendingRequest.MembershipGrant("fp-old-fingerprint", timestampMillis = 1L)
        setContent {
            TenshuTheme {
                PinnedRequests(
                    requests = listOf(invite, grant, older),
                    actionState = emptyMap(),
                    actions = PendingRequestActions(),
                )
            }
        }
        onNodeWithText("Needs your attention").assertIsDisplayed()
        // Two visible; the third (oldest) grant card is not composed.
        onAllNodesWithText("fp-old-fingerprint").assertCountEquals(0)
    }

    @Test
    fun confirm_waits_for_the_claim_to_load() = runComposeUiTest {
        // The proxy refuses an empty shown fingerprint (T-CI-10), so a card whose claim has not
        // loaded must not offer an action that can only fail.
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = invite.copy(claimedFingerprint = "", claimedSas = ""),
                    actionState = RequestActionUiState(),
                    actions = PendingRequestActions(),
                )
            }
        }
        onNodeWithText("Confirm").assertIsNotEnabled()
    }

    @Test
    fun a_claim_that_moves_under_the_card_clears_the_codes_match_tick() = runComposeUiTest {
        // ⚠⚠ Threat model T-CI-10: a second inviter device's write can replace the claim by merge
        // while this card is open. The tick was made against the first code; confirming must not
        // carry it over to the second — the request passed back is the one now on screen, and it
        // goes out unticked.
        var shown by mutableStateOf(invite)
        var confirmed: Pair<PendingRequest.ContactInviteRedeemed, Boolean>? = null
        setContent {
            TenshuTheme {
                PendingRequestCard(
                    request = shown,
                    actionState = RequestActionUiState(),
                    actions = PendingRequestActions(
                        onConfirmInvite = { request, _, sas -> confirmed = request to sas },
                    ),
                )
            }
        }
        onNodeWithText("The codes match").performClick()

        shown = invite.copy(claimedFingerprint = "fp-mallory", claimedSas = "111222")
        waitForIdle()
        onNodeWithText("111222").assertIsDisplayed()
        onNodeWithText("Confirm").performClick()

        assertEquals("fp-mallory", confirmed?.first?.claimedFingerprint)
        assertEquals(false, confirmed?.second, "a tick made against the old code must not carry over")
    }
}
