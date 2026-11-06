package app.tenshu.home.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Home navigation destinations (docs/proposals/home-and-mia-apps.md §3). Custom KMP
 * navigation (`:ui-core` `KmpNavHost`) over a plain `mutableStateListOf` back stack.
 *
 * The four [TopLevel] destinations are the `AdaptiveNavScaffold` bar items. Detail
 * destinations (per-category device screens) arrive with M2; M1 keeps the shell flat.
 */
@Serializable
sealed interface Destination {

    /** A bottom-nav / rail destination. */
    @Serializable
    sealed interface TopLevel : Destination

    @Serializable
    data object Home : TopLevel

    @Serializable
    data object Automations : TopLevel

    @Serializable
    data object Activity : TopLevel

    @Serializable
    data object Settings : TopLevel

    /** Settings → Open-source licences (`docs/oss-licences.md`); pushed full-screen. */
    @Serializable
    data object Licences : Destination

    /** Create ([automationId] == null) or edit an automation; pushed full-screen, no nav chrome. */
    @Serializable
    data class AutomationEditor(val automationId: String?) : Destination

    /** The embedded Mia chat surface (D1); pushed full-screen from the Home tab's card. */
    @Serializable
    data object MiaChat : Destination
}
