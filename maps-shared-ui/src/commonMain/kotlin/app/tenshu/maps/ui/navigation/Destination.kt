package app.tenshu.maps.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Maps navigation destinations (Ph5/Ph6, docs/proposals/maps-app.md §6). Custom KMP
 * navigation (`:ui-core` `KmpNavHost`, not androidx Navigation) — the back stack is a plain
 * `mutableStateListOf(Places)` that screens push/pop. Places is the single top-level surface;
 * the four tabs live inside it (D1), so they are not destinations.
 */
@Serializable
sealed interface Destination {

    @Serializable
    data object Places : Destination

    @Serializable
    data class PlaceDetail(val placeId: String) : Destination

    /** The offline region picker (`maps-region-delivery.md` D5 — Maps owns the picker). */
    @Serializable
    data object Regions : Destination

    /** Regions → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination
}
