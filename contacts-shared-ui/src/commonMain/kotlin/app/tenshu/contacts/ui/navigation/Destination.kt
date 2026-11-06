package app.tenshu.contacts.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Contacts navigation destinations (docs/proposals/contacts-app.md §6). Custom KMP
 * navigation (`:ui-core` `KmpNavHost`) over a plain `mutableStateListOf` back stack, the
 * shape every sibling app uses.
 *
 * Two top-level surfaces: the badged contact list (D3) and the own profile card (D6). A
 * tapped row pushes [ContactDetail].
 */
@Serializable
sealed interface Destination {

    /** A bottom-nav / rail destination. */
    @Serializable
    sealed interface TopLevel : Destination

    @Serializable
    data object Contacts : TopLevel

    @Serializable
    data object Profile : TopLevel

    @Serializable
    data class ContactDetail(val personId: String) : Destination

    /**
     * *Possible duplicates* (Ph9). Pushed from the list's banner rather than being a
     * top-level surface: it is empty for almost everyone almost always, and a permanent tab
     * for a screen that usually says "nothing here" is a tax on every other visit.
     */
    @Serializable
    data object Merge : Destination

    /** You → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination
}
