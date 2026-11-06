package app.tenshu.drive.ui.contacts

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.drive.composeVmTest
import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.FakeContactsRepository
import app.tenshu.error.ErrorCopyKey
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.contact.ContactInvite
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What Drive's completion prompt puts on screen when the accept is refused — rendered through the
 * real [ContactPromptDialog], so it resolves the key exactly as a user's screen does.
 *
 * ⚠⚠ **What this pins:** the refusal renders as its catalogue copy, never as the error object.
 * A view model that stored `result.error.toString()` would put threat model T-CI-10's refusal on
 * screen as `Unknown(copyKey=ErrorCopyKey(value=error_invite_response_changed))` — the one failure
 * whose remedy ("decline this invite and send a new one") is the reason it is typed at all.
 */
@OptIn(ExperimentalTestApi::class)
class ContactPromptDialogErrorCopyTest {

    private val redeemed = ContactInviteModel(
        inviteId = "invite-1",
        status = ContactInvite.Status.REDEEMED,
        intentKind = "",
        intentLabel = "",
        createdAtMillis = 0L,
        expiresAtMillis = 0L,
        claimedName = "Bob",
        claimedFingerprint = "abcdef0123456789",
        claimedSas = "482913",
    )

    private fun refusedWith(key: ErrorCopyKey) = composeVmTest { vms ->
        val contacts = FakeContactsRepository(
            completeResult = RepositoryResult.Error(RepositoryError.Unknown(key)),
        )
        contacts.invites.value = listOf(redeemed)
        val vm = vms.own(ContactPromptViewModel(contacts, "invite-1"))

        setContent { TenshuTheme { ContactPromptDialog(vm, onDismiss = {}) } }
        waitForIdle()
        onNodeWithText("Save contact", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.uiState.value.errorCopyKey != null }
        waitForIdle()
        check(this)
    }

    // Set per test: what must be on screen once the refusal lands.
    private var check: androidx.compose.ui.test.ComposeUiTest.() -> Unit = {}

    private fun androidx.compose.ui.test.ComposeUiTest.assertNoErrorObjectOnScreen() {
        for (leak in listOf("ErrorCopyKey", "copyKey=", "Unknown(", "error_invite")) {
            assertTrue(
                onAllNodesWithText(leak, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty(),
                "the error object, or its key, reached the screen: \"$leak\"",
            )
        }
    }

    @Test
    fun aReplyThatIsNotTheOneShown_showsTheRemedy_notTheErrorObject() {
        check = {
            onNodeWithText("Decline this invite and send a new one", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
            assertNoErrorObjectOnScreen()
        }
        refusedWith(ErrorCopyKey.INVITE_RESPONSE_CHANGED)
    }

    @Test
    fun aConfirmationWithNoCodeShown_showsItsOwnCopy() {
        check = {
            onNodeWithText("There is no code to confirm for this invite yet", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
            assertNoErrorObjectOnScreen()
        }
        refusedWith(ErrorCopyKey.INVITE_CODE_NOT_SHOWN)
    }

    @Test
    fun anyOtherFailure_showsTheGenericSentence_neverTheErrorObject() {
        check = {
            onNodeWithText("Something went wrong", substring = true, useUnmergedTree = true).assertIsDisplayed()
            assertNoErrorObjectOnScreen()
        }
        refusedWith(ErrorCopyKey("error_generic"))
    }
}
