package app.tenshu.control.ui.screens.services

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.PublisherAccount
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Services card after CE-4: **one** card, whose numbers always come from the synced
 * entitlement and whose actions depend on whether this device holds a commerce session.
 */
class ServicesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val oneGib = 1L * 1024 * 1024 * 1024

    private fun storage(
        used: Long = 34L * 1024 * 1024,
        limit: Long = oneGib,
        plan: SyncedPlanTier = SyncedPlanTier.Free,
    ) = SyncedStorage(usedBytes = used, limitBytes = limit, plan = plan)

    private fun email(address: String = "test@example.com") =
        PublisherAccount.IdentityMethod.Email(address)

    @Test
    fun emptyState_showsSignUpCta() {
        var signUpClicks = 0
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState.EMPTY,
                onSignUp = { signUpClicks += 1 },
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        composeTestRule.onNodeWithText("Tenshu Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not signed in on this device", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign in or create an account").performClick()
        assertEquals(1, signUpClicks)
    }

    @Test
    fun signedIn_showsSyncedPlanStorageAndIdentity() {
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = storage(), identity = email()),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        composeTestRule.onNodeWithText("Plan: Free").assertIsDisplayed()
        composeTestRule.onNodeWithText("34 MB of 1 GB used").assertIsDisplayed()
        composeTestRule.onNodeWithText("Verified with test@example.com").assertIsDisplayed()
    }

    @Test
    fun signingInDoesNotChangeTheFiguresOnScreen() {
        // ⚠⚠ The CE-4 regression as a rendering test. Before, a signed-in device rendered the
        // local account's hardcoded literals and the correct synced card was suppressed entirely —
        // so this exact storage, with and without an identity, produced different numbers.
        val paid = storage(used = 3L * oneGib, limit = 2L * 1024 * oneGib, plan = SyncedPlanTier.Premium)

        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = paid, identity = email()),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        composeTestRule.onNodeWithText("Plan: Premium").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 GB of 2 TB used").assertIsDisplayed()
    }

    @Test
    fun syncedOnly_showsPlanAndSignInToManage_withoutSignUpOrIdentity() {
        // 2nd device (D11): no local session, but the synced entitlement reports the real plan.
        var signInClicks = 0
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = storage(), identity = null),
                onSignUp = {},
                onSignIn = { signInClicks += 1 },
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        composeTestRule.onNodeWithText("Tenshu Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Plan: Free").assertIsDisplayed()
        composeTestRule.onNodeWithText("34 MB of 1 GB used").assertIsDisplayed()
        // Billing/identity actions stay behind the per-device sign-in; the figures never were.
        composeTestRule.onNodeWithText("Sign in or create an account").assertDoesNotExist()
        composeTestRule.onNodeWithText("Disconnect").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sign in to manage").performClick()
        assertEquals(1, signInClicks)
    }

    @Test
    fun syncedOnly_paidTier_namesTheTier() {
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(
                    storage = storage(used = 0L, limit = 2L * 1024 * oneGib, plan = SyncedPlanTier.Premium),
                    identity = null,
                ),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        composeTestRule.onNodeWithText("Plan: Premium").assertIsDisplayed()
    }

    @Test
    fun signedInBeforeTheEntitlementSyncs_saysSettingUpRatherThanShowingZero() {
        // The seconds after signup or a purchase. A "0 B of 0 B used" gauge here would read as
        // "you have no storage" at precisely the wrong moment.
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = null, identity = email()),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        // ⚠ Copy CHANGED (`entitlement-trust-anchor-distribution.md` P1c). This state is not only
        // reached during setup — an unverifiable entitlement or an unreachable relay lands here too
        // — so it must not promise progress. It also must not render a 0-byte gauge, which reads as
        // "you have no storage"; both halves are asserted.
        composeTestRule.onNodeWithText("storage details aren", substring = true, ignoreCase = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings → Errors", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Setting up your storage", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Verified with test@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign in or create an account").assertDoesNotExist()
        // Still actionable: the account exists even though its entitlement has not landed.
        composeTestRule.onNodeWithText("Get more storage").assertIsDisplayed()
    }

    @Test
    fun signedIn_managementCtasFireCallbacks() {
        var storageClicks = 0
        var identityClicks = 0
        var disconnectClicks = 0
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = storage(), identity = email("a@b.c")),
                onSignUp = {},
                onGetMoreStorage = { storageClicks += 1 },
                onChangeIdentityMethod = { identityClicks += 1 },
                onDisconnect = { disconnectClicks += 1 },
            )
        }

        composeTestRule.onNodeWithText("Get more storage").performClick()
        composeTestRule.onNodeWithText("Change identity method").performClick()
        composeTestRule.onNodeWithText("Disconnect").performClick()
        assertTrue(storageClicks == 1 && identityClicks == 1 && disconnectClicks == 1)
    }

    @Test
    fun manageSubscription_shownAndFires_andNoticeRenders_whenProvided() {
        var manageClicks = 0
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = storage(), identity = email("a@b.c")),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
                onManageSubscription = { manageClicks += 1 },
                portalNotice = "No paid subscription to manage yet.",
            )
        }

        composeTestRule.onNodeWithText("No paid subscription to manage yet.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manage subscription").performClick()
        assertEquals(1, manageClicks)
    }

    @Test
    fun manageSubscription_hidden_whenNotProvided() {
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = storage(), identity = email("a@b.c")),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
            )
        }

        composeTestRule.onNodeWithText("Manage subscription").assertDoesNotExist()
    }

    @Test
    fun deleteAccount_isOfferedToAnEmailSignedInDevice() {
        var deletes = 0
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(storage = storage(), identity = email()),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
                onDeleteAccount = { deletes += 1 },
            )
        }

        composeTestRule.onNodeWithText("Delete account").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun deleteAccount_isNotOfferedWithoutAnEmailAccount() {
        // A redeemed code has no account to delete, and a synced-only device has no session.
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = ServicesCardState(
                    storage = storage(),
                    identity = PublisherAccount.IdentityMethod.ReiKey(source = "code"),
                ),
                onSignUp = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
                onDeleteAccount = {},
            )
        }

        composeTestRule.onNodeWithText("Delete account").assertDoesNotExist()
    }
}
