package app.tenshu.contacts.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyDescendant
import app.tenshu.contacts.composeVmTest
import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.Contact
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import app.tenshu.contacts.ui.screens.list.ContactListScreen
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wide list-detail layout: the list and a contact side by side, the first one shown before
 * anyone is picked, and the row whose contact is beside it marked selected.
 */
@OptIn(ExperimentalTestApi::class)
class ContactListDetailTest {

    // ⚠ On the row's clickable node — the one a screen reader focuses — while M3's `ListItem`
    // merges the name into a node one level below it, hence `hasAnyDescendant`.
    private val isSelected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    @Test
    fun theFirstContactIsShownBesideTheListUntilAnotherIsPicked() = composeVmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(ContactListViewModel(repository))
        val people = repository.contactsState.value
        val first = people.first()
        val other = people.last()
        var selected by mutableStateOf<String?>(null)
        val opened = mutableListOf<String>()

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalWindowWidthSizeClass provides WindowWidthSizeClass.Large) {
                    ContactListScreen(
                        viewModel = vm,
                        onOpenContact = { opened += it; selected = it },
                        selectedPersonId = selected,
                        detailPane = { personId -> Text("pane:$personId") },
                    )
                }
            }
        }

        waitUntil { onAllNodesWithText("pane:${first.personId}").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("pane:${first.personId}").assertIsDisplayed()
        // The list is still there beside it, and the shown contact's row says so.
        onNode(isSelected and hasAnyDescendant(hasText(first.displayName))).assertIsDisplayed()

        onNodeWithText(other.displayName).performClick()
        waitUntil { onAllNodesWithText("pane:${other.personId}").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(other.personId), opened)
        onNode(isSelected and hasAnyDescendant(hasText(other.displayName))).assertIsDisplayed()
    }

    @Test
    fun withoutADetailPaneNoRowCarriesASelectionState() = composeVmTest { vms ->
        // The narrow layout: the same screen, unchanged — no highlight, a tap navigates. Not even
        // `Selected = false`: Android reads that out as "not selected" on every row.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactListViewModel(repository))
        val first = repository.contactsState.value.first()
        setContent { TenshuTheme { ContactListScreen(viewModel = vm, onOpenContact = {}) } }

        waitUntil { onAllNodesWithText(first.displayName).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            0,
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun crossingTheBreakpointKeepsTheListWhereItWas() = composeVmTest { vms ->
        // The narrow and wide branches build different trees; the list's scroll state is hoisted
        // above both, or a resize across 840 dp throws the user back to the top.
        val people = List(80) { Contact(personId = "p$it", displayName = "Person %02d".format(it)) }
        val vm = vms.own(ContactListViewModel(MockContactsRepository(initial = people)))
        var sizeClass by mutableStateOf(WindowWidthSizeClass.Compact)

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalWindowWidthSizeClass provides sizeClass) {
                    ContactListScreen(
                        viewModel = vm,
                        onOpenContact = {},
                        detailPane = if (sizeClass >= WindowWidthSizeClass.Expanded) {
                            { personId -> Text("pane:$personId") }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        waitUntil { onAllNodesWithText("Person 00").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasScrollToIndexAction()).performScrollToIndex(60)
        waitForIdle()
        onNodeWithText("Person 60").assertIsDisplayed()

        sizeClass = WindowWidthSizeClass.Large
        waitUntil { onAllNodesWithText("pane:p0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Person 60").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("Person 00").fetchSemanticsNodes().size)
    }
}
