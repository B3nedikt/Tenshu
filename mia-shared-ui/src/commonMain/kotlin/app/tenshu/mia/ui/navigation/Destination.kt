package app.tenshu.mia.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Mia navigation destinations (docs/proposals/home-and-mia-apps.md §4). Chat is the app's
 * home; History and Settings complete the deliberately small three-tab shell.
 */
@Serializable
sealed interface Destination {

    /** A bottom-nav / rail destination. */
    @Serializable
    sealed interface TopLevel : Destination

    @Serializable
    data object Chat : TopLevel

    @Serializable
    data object History : TopLevel

    @Serializable
    data object Settings : TopLevel

    /** Settings → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination

    /** A past thread, read-only; pushed full-screen with its own back bar. */
    @Serializable
    data class ConversationDetail(val conversationId: String) : Destination
}
