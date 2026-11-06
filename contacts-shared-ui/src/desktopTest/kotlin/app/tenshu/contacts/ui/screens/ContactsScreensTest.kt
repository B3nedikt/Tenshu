package app.tenshu.contacts.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.ui.screens.detail.ContactDetailScreen
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import app.tenshu.contacts.ui.screens.invite.ConfirmContactDialog
import app.tenshu.contacts.ui.screens.invite.ConfirmContactViewModel
import app.tenshu.contacts.ui.screens.list.ContactListScreen
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import app.tenshu.contacts.ui.screens.merge.MergeScreen
import app.tenshu.contacts.ui.screens.merge.MergeViewModel
import app.tenshu.contacts.ui.screens.profile.ProfileScreen
import app.tenshu.contacts.ui.screens.profile.ProfileViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.tenshu.contacts.composeVmTest

/**
 * Compose-render coverage for the Ph1 screens. The interesting assertion is the Q2b one:
 * a contact whose keyring is mixed must render the *key changed* line, not a checkmark.
 */
@OptIn(ExperimentalTestApi::class)
class ContactsScreensTest {

    @Test
    fun theOneListHoldsConnectedAndImportOnlyContacts() = composeVmTest { vms ->
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil {
            onAllNodesWithText("Mira Okonkwo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // D3: a handshaked contact and a typed-in one sit in the same list.
        onNodeWithText("Mira Okonkwo", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Dad", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aChangedKeyIsCalledOutOnTheRow() = composeVmTest { vms ->
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        // Q2b: Sara's old verified key sits beside a new unverified one. The row must say so
        // — under the rule this replaced she rendered a plain checkmark.
        waitUntil {
            onAllNodesWithText("This contact is using a key you have not verified.", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun searchResultsLiveInTheExpandedSurface() = composeVmTest { vms ->
        // The §9.6 chrome change: results appear in `AppSearchBar`'s expanded surface and the
        // list behind stays whole, rather than the query silently shrinking the address book.
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil {
            onAllNodesWithText("Dad", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Target the field by its set-text action rather than the placeholder — the
        // placeholder is a sibling Text node with no input semantics.
        onNode(hasSetTextAction()).performTextInput("mira")

        waitUntil {
            onAllNodesWithText("Mira Okonkwo", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // ⚠ The expanded surface covers the screen, so what proves the list was not filtered
        // is the state behind it: `contacts` is the whole address book and `searchResults` is
        // the query's answer, two separate things rather than one narrowed one.
        assertEquals(listOf("Mira Okonkwo"), vm.uiState.value.searchResults.map { it.displayName })
        assertTrue(vm.uiState.value.contacts.any { it.displayName == "Dad" })

        // Leaving search brings the whole list back — including the query being dropped, so a
        // stale one cannot sit invisible behind a collapsed bar.
        vm.onSearchActiveChange(false)
        waitUntil {
            onAllNodesWithText("Dad", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("", vm.uiState.value.query)
    }

    @Test
    fun anUnmatchedQuerySaysSoWithoutClaimingTheListIsEmpty() = composeVmTest { vms ->
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil {
            onAllNodesWithText("Dad", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasSetTextAction()).performTextInput("zzzz")

        waitUntil {
            onAllNodesWithText("No matches", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Never "No contacts yet" — that is a different situation with different copy.
        assertEquals(
            0,
            onAllNodesWithText("No contacts yet", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theFabSheetHoldsBothWaysOfAcquiringAContact() = composeVmTest { vms ->
        // Manual add and import used to be two icon buttons in a top app bar. They belong
        // together under the primary action, which is what Drive does with its own two.
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil {
            onAllNodesWithText("Dad", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Add a contact").performClick()

        waitUntil {
            onAllNodesWithText("Import contacts", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Import contacts", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theFabSheetLeadsToTheAddDialog() = composeVmTest { vms ->
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil {
            onAllNodesWithText("Dad", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Add a contact").performClick()
        waitUntil {
            onAllNodesWithText("Import contacts", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The sheet row, not the FAB: the dialog's own title matches too, so target the row.
        onAllNodesWithText("Add a contact", useUnmergedTree = true)[0].performClick()

        waitUntil {
            onAllNodesWithText("Name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun detailRendersTheKeyringWithClaimedNamesQuarantined() = composeVmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-sara"))
        setContent { TenshuTheme { ContactDetailScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Sara Lindqvist", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Both keys are listed, in the pinned XXXXX-YYYYY-ZZZZZ display form.
        onNodeWithText("7E5F0-C39A1-BB24D", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("20A7C-C61D4-E93B0", useUnmergedTree = true).assertIsDisplayed()
        // Each key carries its own claimed name, rendered as a claim and never promoted to
        // the headline — two keys, two claims, both quoted.
        onAllNodesWithText("Introduced themselves as", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test
    fun detailSaysWhyAContactHasNoKeys() = composeVmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-dad"))
        setContent { TenshuTheme { ContactDetailScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("This contact has no Tenshu key.", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aWaitingConfirmationBannersAboveTheList() = composeVmTest { vms ->
        val repository = MockContactsRepository(
            initialConfirmations = listOf(
                InviteConfirmation(
                    inviteId = "invite-0",
                    claimedName = "Mira",
                    claimedFingerprint = "3ab19c7f2e08d641",
                    claimedSas = "418902",
                ),
            ),
        )
        var confirmed: String? = null
        setContent {
            TenshuTheme {
                ContactListScreen(
                    viewModel = vms.own(ContactListViewModel(repository)),
                    onOpenContact = {},
                    onConfirmInvite = { confirmed = it },
                )
            }
        }

        waitUntil {
            onAllNodesWithText("accepted your invitation", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Tap to save them as a contact", useUnmergedTree = true).performClick()
        check(confirmed == "invite-0") { "the banner must open the confirmation prompt" }
    }

    @Test
    fun theConfirmationPromptQuarantinesTheClaimAndGatesVerification() = composeVmTest { vms ->
        val repository = MockContactsRepository(
            initialConfirmations = listOf(
                InviteConfirmation(
                    inviteId = "invite-0",
                    claimedName = "Mira",
                    claimedFingerprint = "3ab19c7f2e08d641",
                    claimedSas = "418902",
                ),
            ),
        )
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))
        setContent { TenshuTheme { ConfirmContactDialog(viewModel = vm, onDismiss = {}) } }

        waitUntil {
            onAllNodesWithText("That is the name they gave", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The code is shown for comparison, and the box is NOT pre-ticked — saving a
        // contact is not the same act as vouching for their key (A24).
        onNodeWithText("418902", useUnmergedTree = true).assertIsDisplayed()
        check(!vm.uiState.value.sasConfirmed) { "the SAS check must start unticked" }
    }

    @Test
    fun theProfileCardEditsTheOwnName() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ProfileScreen(viewModel = vm) } }

        waitUntil {
            onAllNodesWithText("Kae", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Edit name", useUnmergedTree = true).performClick()
        onNode(hasSetTextAction()).performTextReplacement("Kae Sato")
        onNodeWithText("Save", useUnmergedTree = true).performClick()

        waitUntil {
            onAllNodesWithText("Kae Sato", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aContactWithUndecodablePhotoBytesStillRendersItsMonogram() = composeVmTest { vms ->
        // Ph8's fallback. A card can embed a format this platform has no codec for (Skiko
        // ships none for HEIC), and a contact whose picture cannot be read is still a
        // contact — so the row must show initials, never a broken-image glyph or nothing.
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil {
            onAllNodesWithText("Mira Okonkwo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Mira carries photo bytes that are not a decodable image (see ContactsMockData).
        onNodeWithText("MO", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theRemovePhotoActionOnlyAppearsWhenThereIsOne() = composeVmTest { vms ->
        // A permanent "Remove photo" under a monogram would read as a broken affordance —
        // and there is no set counterpart yet to pair it with.
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-dad"))
        setContent { TenshuTheme { ContactDetailScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Dad", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithText("Remove photo", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theDuplicatesScreenLeadsWithWhyEachPairIsSuggested() = composeVmTest { vms ->
        // ⚠ "Same name" and "same phone number" deserve very different amounts of trust.
        // Hiding which one fired would present the weakest suggestion with the confidence of
        // the strongest — and D7's whole point is that these are questions.
        val repository = MockContactsRepository(
            initial = listOf(
                Contact(
                    personId = "a",
                    displayName = "Mira",
                    details = ContactDetails(phones = listOf(ContactField("mobile", "+44 7700 900123"))),
                ),
                Contact(
                    personId = "b",
                    displayName = "Mira O",
                    details = ContactDetails(phones = listOf(ContactField("mobile", "07700 900123"))),
                ),
            ),
        )
        val vm = vms.own(MergeViewModel(repository))
        setContent { TenshuTheme { MergeScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Same phone number", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("\"Mira\" and \"Mira O\"", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theMergeConfirmationSaysItCannotBeUndone() = composeVmTest { vms ->
        // Said before, not discovered after: the rows that move carry no memory of where
        // they came from, so there is genuinely nothing to undo with.
        val repository = MockContactsRepository(
            initial = listOf(
                Contact(personId = "a", displayName = "Mira"),
                Contact(personId = "b", displayName = "Mira"),
            ),
        )
        val vm = vms.own(MergeViewModel(repository))
        setContent { TenshuTheme { MergeScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Same name", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Same name", substring = true, useUnmergedTree = true).performClick()

        waitUntil {
            onAllNodesWithText("This cannot be undone.", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aCleanAddressBookSaysSoRatherThanShowingAnEmptyList() = composeVmTest { vms ->
        val vm = vms.own(MergeViewModel(MockContactsRepository(initial = emptyList())))
        setContent { TenshuTheme { MergeScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("No duplicates found", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
