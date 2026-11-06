package app.tenshu.control.ui.screens.services

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The account-deletion surface (§9.7). Content-composable level; the flow's rules live in
 * [AccountDeletionViewModel] and are tested there.
 */
class AccountDeletionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(state: AccountDeletionUiState, onSendCode: () -> Unit = {}, onConfirm: () -> Unit = {}) {
        composeTestRule.setContent {
            AccountDeletionScreenContent(
                state = state,
                onSendCode = onSendCode,
                onCodeChange = {},
                onAcknowledgedChange = {},
                onConfirm = onConfirm,
                onCancel = {},
                onDone = {},
            )
        }
    }

    /** ⚠ What is deleted, what is kept, and that it is permanent — all before the first tap. */
    @Test
    fun theExplanationComesFirst() {
        var sends = 0
        show(AccountDeletionUiState(email = "ada@example.com"), onSendCode = { sends++ })

        composeTestRule.onNodeWithText("scheduled for permanent deletion after a 30-day grace period", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Kept: your invoices", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Not touched: anything on your own devices", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Apple's App Store", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Deleting your account is permanent and cannot be undone.").performScrollTo()
        composeTestRule.onNodeWithText("Delete my account").assertDoesNotExist()

        composeTestRule.onNodeWithText("Email me a code to confirm").performScrollTo().performClick()
        assertEquals(1, sends)
    }

    @Test
    fun theDestructiveButtonWaitsForCodeAndAcknowledgement() {
        show(AccountDeletionUiState(email = "ada@example.com", step = AccountDeletionStep.EnterCode, code = "123456"))

        composeTestRule.onNodeWithText("We've sent a code to ada@example.com", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Delete my account").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun withCodeAndAcknowledgement_itDeletes() {
        var confirms = 0
        show(
            AccountDeletionUiState(step = AccountDeletionStep.EnterCode, code = "123456", acknowledged = true),
            onConfirm = { confirms++ },
        )

        composeTestRule.onNodeWithText("Delete my account").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun aWrongCodeIsExplained() {
        show(AccountDeletionUiState(step = AccountDeletionStep.EnterCode, error = AccountDeletionError.InvalidCode))
        composeTestRule.onNodeWithText("That code didn't work", substring = true).performScrollTo()
    }

    @Test
    fun theDoneStateSaysWhatHappensNext() {
        show(AccountDeletionUiState(step = AccountDeletionStep.Deleted))
        composeTestRule.onNodeWithText("Your account has been deleted.").performScrollTo()
        composeTestRule.onNodeWithText("scheduled for deletion after a 30-day grace period", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Delete my account").assertDoesNotExist()
    }

    @Test
    fun aSignedOutDeviceIsToldTheDeletionMayHaveWorked() {
        show(AccountDeletionUiState(step = AccountDeletionStep.EnterCode, error = AccountDeletionError.SignedOut))
        composeTestRule.onNodeWithText("If you just deleted your account, the deletion went through.", substring = true)
            .performScrollTo()
    }
}
