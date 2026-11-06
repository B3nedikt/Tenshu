package app.tenshu.timeline.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Timeline navigation destinations (docs/proposals/timeline-app.md §7 Ph6). Custom KMP
 * navigation (`:ui-core` `KmpNavHost`, not androidx Navigation) — the back stack is a plain
 * `mutableStateListOf(Home)` that screens push/pop.
 *
 * The [TopLevel] destinations are the `AdaptiveNavScaffold` chrome's items (bottom bar on a
 * phone, rail then permanent drawer as the window widens); everything else is pushed
 * full-screen with its own back bar and no chrome.
 *
 * ⚠ Home's three surfaces (feed / Places / Activities) are **still one destination**, not
 * three: the per-entry `ViewModelStore` `KmpNavHost` hands out means three destinations would
 * mean three `TimelineHomeViewModel`s, each re-collecting all five repository flows. The
 * selected surface is hoisted into the shell as plain Compose state instead — see `App`.
 */
@Serializable
sealed interface Destination {

    /** A destination reachable from the nav chrome, which stays visible while it is shown. */
    @Serializable
    sealed interface TopLevel : Destination

    @Serializable
    data object Home : TopLevel

    @Serializable
    data object Settings : TopLevel

    /** Settings → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination

    @Serializable
    data class TripDetail(val tripId: String) : Destination

    @Serializable
    data class MomentDetail(val momentId: String) : Destination

    @Serializable
    data class PlaceDetail(val placeId: String) : Destination

    /**
     * The full-screen, pan-and-zoom route map for a movement moment — pushed from the moment
     * detail's inline route hero.
     *
     * Carries only the id: a destination is `@Serializable` and a decoded polyline can be hundreds
     * of points, so the entry rebuilds the *same* `MomentDetailViewModel` (same Koin key) and reads
     * the route off its state, exactly as `MomentDetail` does.
     */
    @Serializable
    data class MomentRoute(val momentId: String) : Destination

    /** The full-screen route map for a trip — see [MomentRoute]. */
    @Serializable
    data class TripRoute(val tripId: String) : Destination
}
