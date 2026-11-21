package app.tenshu.contacts.ui.navigation

import app.tenshu.contacts.ui.navigation.Destination.ContactDetail
import app.tenshu.contacts.ui.navigation.Destination.Contacts
import app.tenshu.contacts.ui.navigation.Destination.Merge
import app.tenshu.contacts.ui.navigation.Destination.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The list-detail selection is the back stack's `[Contacts, ContactDetail]` tail at every width,
 * so a resize never has to carry state from one model into another.
 */
class ContactPanesTest {

    @Test
    fun aDetailDirectlyOverTheListIsTheSelection() {
        assertEquals("a", ContactPanes.selectedPersonId(listOf(Contacts, ContactDetail("a"))))
        assertNull(ContactPanes.selectedPersonId(listOf(Contacts)))
        assertNull(ContactPanes.selectedPersonId(listOf(Contacts, Merge)))
        // Over anything but the list it is a screen of its own, not a pane.
        assertNull(ContactPanes.selectedPersonId(listOf(Profile, ContactDetail("a"))))
    }

    @Test
    fun wideFoldsTheSelectionIntoTheListAndNarrowLeavesTheStackAlone() {
        val stack = listOf(Contacts, ContactDetail("a"))
        assertEquals(listOf(Contacts), ContactPanes.paneStack(stack, twoPane = true))
        assertEquals(stack, ContactPanes.paneStack(stack, twoPane = false))
        // A pushed screen that is not the selection stays on top at any width.
        assertEquals(listOf(Contacts, Merge), ContactPanes.paneStack(listOf(Contacts, Merge), twoPane = true))
    }

    @Test
    fun narrowingWithAContactSelectedLandsOnItsDetailScreen() {
        // Selected while wide …
        val stack = mutableListOf<Destination>(Contacts)
        ContactPanes.open(stack, "a", twoPane = true)
        assertEquals(listOf(Contacts), ContactPanes.paneStack(stack, twoPane = true))
        // … and the very same stack, rendered narrow, is the detail screen with back to the list.
        assertEquals(listOf(Contacts, ContactDetail("a")), ContactPanes.paneStack(stack, twoPane = false))
    }

    @Test
    fun wideReplacesTheSelectionSoBackDoesNotWalkEveryContactClicked() {
        val stack = mutableListOf<Destination>(Contacts)
        ContactPanes.open(stack, "a", twoPane = true)
        ContactPanes.open(stack, "b", twoPane = true)
        ContactPanes.open(stack, "c", twoPane = true)
        assertEquals(listOf(Contacts, ContactDetail("c")), stack)
    }

    @Test
    fun narrowStillPushes() {
        val stack = mutableListOf<Destination>(Contacts)
        ContactPanes.open(stack, "a", twoPane = false)
        assertEquals(listOf(Contacts, ContactDetail("a")), stack)
        // The same contact twice is one entry, not two.
        ContactPanes.open(stack, "a", twoPane = true)
        assertEquals(listOf(Contacts, ContactDetail("a")), stack)
    }

    @Test
    fun clearingTouchesOnlyTheSelection() {
        val selected = mutableListOf(Contacts, ContactDetail("a"))
        ContactPanes.clearSelection(selected)
        assertEquals(listOf<Destination>(Contacts), selected)

        val merge = mutableListOf(Contacts, Merge)
        ContactPanes.clearSelection(merge)
        assertEquals(listOf(Contacts, Merge), merge)
    }

    @Test
    fun withNothingSelectedThePaneShowsTheFirstContact() {
        assertEquals("a", ContactPanes.shownPersonId(null, listOf("a", "b"), loaded = true))
        assertEquals("b", ContactPanes.shownPersonId("b", listOf("a", "b"), loaded = true))
        assertNull(ContactPanes.shownPersonId(null, emptyList(), loaded = true))
    }

    @Test
    fun aSelectionTheListNoLongerHoldsFallsBackOnceLoadedButNotBefore() {
        // Merged away or deleted elsewhere: not a "contact not found" pane beside a working list.
        assertEquals("a", ContactPanes.shownPersonId("gone", listOf("a"), loaded = true))
        // Cold start: the list has not answered yet, so the selection is trusted, not overridden.
        assertEquals("b", ContactPanes.shownPersonId("b", emptyList(), loaded = false))
    }
}
