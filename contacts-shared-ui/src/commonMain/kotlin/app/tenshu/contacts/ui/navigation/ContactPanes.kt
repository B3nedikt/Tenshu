package app.tenshu.contacts.ui.navigation

/**
 * The back stack decides which contact is selected, at every width. On a wide window the list
 * and the selected contact share one screen (`AppListDetailLayout`), but the selection is still
 * the same `[…, Contacts, ContactDetail(id)]` tail a narrow window pushes — so resizing never
 * has to translate state between two models:
 *
 * - **Narrowing** with a contact selected renders the tail as it is: the detail screen, with
 *   back returning to the list.
 * - **Widening** from a detail screen folds the tail into the list ([paneStack]) and shows that
 *   contact beside it.
 *
 * Only the tail directly over [Destination.Contacts] folds. A detail pushed over anything else
 * stays a screen of its own.
 */
internal object ContactPanes {

    /** The contact the back stack selects for the list's detail pane, or `null` if none. */
    fun selectedPersonId(backStack: List<Destination>): String? {
        val top = backStack.lastOrNull() as? Destination.ContactDetail ?: return null
        return if (backStack.getOrNull(backStack.lastIndex - 1) == Destination.Contacts) top.personId else null
    }

    /**
     * The stack the nav host renders. Narrow: [backStack] unchanged. Wide: the selected
     * contact's detail is folded into the list entry, which draws it in its detail pane — so
     * the nav host's top is the list, and the rail stays up.
     */
    fun paneStack(backStack: List<Destination>, twoPane: Boolean): List<Destination> =
        if (twoPane && selectedPersonId(backStack) != null) backStack.dropLast(1) else backStack

    /**
     * Opening [personId] from the list. Narrow: a push, as before. Wide: the selection is
     * **replaced** rather than stacked, so clicking through ten contacts does not leave ten
     * entries for back to walk through on a later narrow window.
     */
    fun open(backStack: MutableList<Destination>, personId: String, twoPane: Boolean) {
        if (twoPane && selectedPersonId(backStack) != null) backStack.removeAt(backStack.lastIndex)
        val next = Destination.ContactDetail(personId)
        if (backStack.lastOrNull() != next) backStack.add(next)
    }

    /** Drops the selection, if the back stack holds one; anything else is left alone. */
    fun clearSelection(backStack: MutableList<Destination>) {
        if (selectedPersonId(backStack) != null) backStack.removeAt(backStack.lastIndex)
    }

    /**
     * The contact the detail pane shows: the selected one, else the first in the list.
     *
     * Showing the first contact rather than an empty pane is the desktop convention (macOS
     * Contacts, Outlook, Thunderbird all open on a person), and an empty half-window reads as
     * something that failed to load. The fallback is **display-only** — it is not written to
     * the back stack — so a window narrowed before the user picked anyone returns to the list,
     * not to a detail screen they never opened.
     *
     * A selection that the loaded list no longer holds (merged away, or deleted on another
     * device) falls back to the first contact too, rather than parking a "contact not found"
     * screen beside a list that plainly works. Before the list has [loaded] the selection is
     * trusted, so a cold start does not flash the first contact before the chosen one.
     */
    fun shownPersonId(selected: String?, listedIds: List<String>, loaded: Boolean): String? =
        selected?.takeIf { !loaded || it in listedIds } ?: listedIds.firstOrNull()
}
